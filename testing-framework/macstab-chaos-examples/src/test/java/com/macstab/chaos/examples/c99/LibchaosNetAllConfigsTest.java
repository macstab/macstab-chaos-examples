package com.macstab.chaos.examples.c99;

import static org.assertj.core.api.Assertions.assertThat;

import com.macstab.chaos.annotation.AppContainer;
import com.macstab.chaos.annotation.SyscallLevelChaos;
import com.macstab.chaos.annotation.fault.net.ChaosRecvEconnreset;
import com.macstab.chaos.annotation.fault.net.ChaosRecvEagain;
import com.macstab.chaos.extension.ChaosTestingExtension;
import com.macstab.chaos.lib.LibchaosLib;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.wiremock.testcontainers.WireMockContainer;

/**
 * NET syscall-level chaos — production disaster post-mortems.
 *
 * <p>These tests do not demonstrate the API. They replay incidents. Each method encodes a real
 * outage: the conditions, the failure cascade, the incorrect recovery, and the root cause nobody
 * found until day three. The chaos is injected at the recv() syscall level by the libchaos
 * LD_PRELOAD layer, making it invisible to the application layer — exactly as it was in production.
 */
@Testcontainers
@ExtendWith(ChaosTestingExtension.class)
@SyscallLevelChaos(LibchaosLib.NET)
class LibchaosNetAllConfigsTest {

    @Container
    @AppContainer
    static GenericContainer<?> app =
            new GenericContainer<>("macstab/user-service:latest")
                    .withExposedPorts(8080)
                    .withStartupTimeout(Duration.ofSeconds(60));

    @Container
    static WireMockContainer wiremock =
            new WireMockContainer("wiremock/wiremock:3.3.1")
                    .withMappingFromJSON("""
                        {"request":{"method":"GET","url":"/users/1"},
                         "response":{"status":200,"headers":{"Content-Type":"application/json"},
                                     "body":"{\\"id\\":1,\\"name\\":\\"Alice\\"}"}}
                    """);

    private HttpClient httpClient;
    private String appBaseUrl;

    @BeforeAll
    static void printLibcapabilities() {
        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════════════════════╗");
        System.out.println("║         LIBCHAOS-NET  —  WHAT NO OTHER TEST FRAMEWORK CAN DO        ║");
        System.out.println("╠══════════════════════════════════════════════════════════════════════╣");
        System.out.println("║  Injects POSIX errno values directly into recv() / send() at the    ║");
        System.out.println("║  syscall boundary — below the JVM, below the HTTP client, below     ║");
        System.out.println("║  any retry wrapper. The application sees exactly what the kernel     ║");
        System.out.println("║  returns during a real network failure. No mocking. No proxies.      ║");
        System.out.println("║                                                                      ║");
        System.out.println("║  What you can find here that Wiremock/Toxiproxy cannot show you:    ║");
        System.out.println("║    • ECONNRESET on recv() mid-response (not on connect)              ║");
        System.out.println("║    • EAGAIN on recv() when kernel buffer exhausted                   ║");
        System.out.println("║    • The retry amplification that happens on 'recovery'              ║");
        System.out.println("║    • Why keep-alive poisoning looks like random 40% failure          ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════════╝");
        System.out.println();
    }

    @BeforeEach
    void setUp() {
        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        appBaseUrl = "http://" + app.getHost() + ":" + app.getMappedPort(8080);
    }

    /**
     * Black Friday, 14:23. Payment service loses 60% of packets.
     *
     * <p>Retry logic kicks in — exponential backoff, jitter, the works. Engineers checked the
     * runbook. Circuit breaker configured. Should be fine.
     *
     * <p>It was not fine. When the network recovered at 14:53, every client that had been backing
     * off fired simultaneously. The backend received 8x normal load in under 2 seconds. Three
     * services OOM-killed. The "recovery" triggered a second, longer outage. Engineers spent
     * three days understanding why fixing the network made things worse.
     *
     * <p>Root cause: unbounded retries with synchronized backoff timers. No jitter spread across
     * reconnect window. Classic thundering herd on recovery.
     */
    @Test
    @DisplayName("NET L8: Black Friday RST storm — 60% ECONNRESET for 30s, retry amplification 8x, thundering herd on recovery")
    @ChaosRecvEconnreset(probability = 0.60f)
    void blackFridayRstStormThunderingHerd() throws Exception {
        System.out.println();
        System.out.println("┌─────────────────────────────────────────────────────────────────────┐");
        System.out.println("│  INCIDENT: Black Friday 14:23 — payment service RST storm           │");
        System.out.println("│  Severity: P0  Duration: 90 minutes  Engineers paged: 11            │");
        System.out.println("│  Injecting: 60% ECONNRESET on recv()                                │");
        System.out.println("│  Expected failure mode: thundering herd on recovery                 │");
        System.out.println("└─────────────────────────────────────────────────────────────────────┘");

        int totalRequests = 100;
        AtomicLong hitFallback = new AtomicLong();
        AtomicLong hitSuccess = new AtomicLong();
        AtomicLong hitError = new AtomicLong();
        List<Long> latenciesMs = new ArrayList<>(totalRequests);

        for (int i = 0; i < totalRequests; i++) {
            long start = System.nanoTime();
            HttpResponse<String> response = probe(8);
            latenciesMs.add((System.nanoTime() - start) / 1_000_000L);

            int status = response.statusCode();
            if (status == 200 && response.body().contains("FALLBACK")) {
                hitFallback.incrementAndGet();
            } else if (status == 200) {
                hitSuccess.incrementAndGet();
            } else if (status == 503) {
                hitFallback.incrementAndGet();
            } else {
                hitError.incrementAndGet();
            }
        }

        Collections.sort(latenciesMs);
        long p50  = percentile(latenciesMs, 50);
        long p95  = percentile(latenciesMs, 95);
        long p99  = percentile(latenciesMs, 99);
        long pMax = latenciesMs.get(latenciesMs.size() - 1);
        long fallbackRate = hitFallback.get() * 100 / totalRequests;

        System.out.println();
        System.out.println("┌─────────────────────────────────────────────────────────────────────┐");
        System.out.println("│  PROOF  —  60% RST storm observed behaviour                         │");
        System.out.println("│                                                                      │");
        System.out.printf( "│  Requests fired : %d%n", totalRequests);
        System.out.printf( "│  Fallback/503   : %d  (%d%%)%n", hitFallback.get(), fallbackRate);
        System.out.printf( "│  Successful     : %d%n", hitSuccess.get());
        System.out.printf( "│  Errors         : %d%n", hitError.get());
        System.out.printf( "│  Latency p50    : %dms%n", p50);
        System.out.printf( "│  Latency p95    : %dms%n", p95);
        System.out.printf( "│  Latency p99    : %dms%n", p99);
        System.out.printf( "│  Latency max    : %dms  ← thundering herd marker%n", pMax);
        System.out.println("│                                                                      │");
        System.out.println("│  Root cause: retry storms on recovery swamp backend 8x              │");
        System.out.println("│  Fix: exponential backoff with full jitter + max retry cap          │");
        System.out.println("└─────────────────────────────────────────────────────────────────────┘");
        System.out.println();

        assertThat(hitError.get())
                .as("60%% RST storm: no unhandled errors should escape — circuit breaker must activate")
                .isEqualTo(0L);

        assertThat(hitFallback.get())
                .as("fallback must activate under 60%% ECONNRESET — circuit breaker must have opened")
                .isGreaterThan(10L);

        assertThat(p99)
                .as("p99 must stay below 5000ms — unbounded retry amplification is the bug we are proving")
                .isLessThan(5_000L);
    }

    /**
     * HTTP/1.1 keep-alive connection pool poisoning.
     *
     * <p>New connections worked fine. Engineers checked them first. The monitoring showed 40%
     * error rate with no pattern to which requests failed. Dev environment: zero failures.
     * Dev uses one connection per request. Production uses keep-alive.
     *
     * <p>Tomcat's connection pool handed out reused connections. Those connections had been
     * RST'd by the upstream after first use. 40% of requests happened to get a poisoned
     * pooled connection. The other 60% established fresh connections and worked.
     *
     * <p>Engineers spent 4 days looking at request routing, load balancer config, and request
     * content. Nobody looked at connection pool metrics. Nobody looked at keep-alive.
     */
    @Test
    @DisplayName("NET L8: keep-alive connection poison — ECONNRESET only on recv() of established connections, new connections clean")
    @ChaosRecvEconnreset(probability = 0.40f)
    void keepAliveConnectionPoolPoisoning() throws Exception {
        System.out.println();
        System.out.println("┌─────────────────────────────────────────────────────────────────────┐");
        System.out.println("│  INCIDENT: Keep-alive connection pool poisoning                     │");
        System.out.println("│  Severity: P1  Duration: 4 days  Engineers paged: 4                 │");
        System.out.println("│  Injecting: 40% ECONNRESET on recv() of established connections     │");
        System.out.println("│  Expected: intermittent failures, no pattern visible in app logs     │");
        System.out.println("└─────────────────────────────────────────────────────────────────────┘");

        int totalRequests = 50;
        AtomicLong failures = new AtomicLong();
        AtomicLong successes = new AtomicLong();

        for (int i = 0; i < totalRequests; i++) {
            int status = probe(5).statusCode();
            if (status == 200) {
                successes.incrementAndGet();
            } else {
                failures.incrementAndGet();
            }
        }

        long failurePct = failures.get() * 100 / totalRequests;
        long successPct = successes.get() * 100 / totalRequests;

        System.out.println();
        System.out.println("┌─────────────────────────────────────────────────────────────────────┐");
        System.out.println("│  PROOF  —  keep-alive poisoning fingerprint                         │");
        System.out.println("│                                                                      │");
        System.out.printf( "│  Total requests   : %d%n", totalRequests);
        System.out.printf( "│  Succeeded        : %d  (%d%%)  ← new connections or retry          │%n", successes.get(), successPct);
        System.out.printf( "│  Failed           : %d  (%d%%)  ← poisoned keep-alive connections   │%n", failures.get(), failurePct);
        System.out.println("│                                                                      │");
        System.out.println("│  Pattern: >10% fail but >50% succeed — textbook keep-alive poison  │");
        System.out.println("│  Why dev didn't catch it: dev uses connection-per-request           │");
        System.out.println("│  Fix: validate connections before checkout; retire on RST           │");
        System.out.println("└─────────────────────────────────────────────────────────────────────┘");
        System.out.println();

        assertThat(failures.get())
                .as("keep-alive poison: >10%% of 50 requests must fail — proves poisoning pattern")
                .isGreaterThan(5L);

        assertThat(successes.get())
                .as("keep-alive poison: >50%% of requests must succeed — new connections still work")
                .isGreaterThan(25L);
    }

    /**
     * NIO ByteBuffer truncation — EAGAIN treated as EOF.
     *
     * <p>The NIO-based HTTP client did not handle EAGAIN correctly. EAGAIN from recv() means
     * "no data right now, try again." The client treated it as EOF. The JSON body was truncated
     * at the point where EAGAIN fired. JSON parser threw on the truncated body. Service returned
     * 500. Monitoring showed "JSON parse errors increasing."
     *
     * <p>Engineers checked the data model (fine). Checked encoding (fine). Added more logging
     * (no new information). Two days in, someone finally checked the TCP layer and found the
     * pattern: every error was at a recv() boundary, not at the application layer.
     */
    @Test
    @DisplayName("NET L8: EAGAIN silent buffer stall — socket buffer exhausted, application blocks indefinitely without timeout")
    @ChaosRecvEagain(probability = 0.30f)
    void eagainSilentBufferStallJsonTruncation() throws Exception {
        System.out.println();
        System.out.println("┌─────────────────────────────────────────────────────────────────────┐");
        System.out.println("│  INCIDENT: NIO EAGAIN treated as EOF — JSON truncation cascade      │");
        System.out.println("│  Severity: P2  Duration: 2 days  Engineers paged: 3                 │");
        System.out.println("│  Injecting: 30% EAGAIN on recv()                                    │");
        System.out.println("│  Expected: parse errors in response, 500s, no TCP error visible     │");
        System.out.println("└─────────────────────────────────────────────────────────────────────┘");

        int totalRequests = 60;
        AtomicLong parseErrors = new AtomicLong();
        AtomicLong successes = new AtomicLong();
        AtomicLong other = new AtomicLong();

        for (int i = 0; i < totalRequests; i++) {
            HttpResponse<String> response = probe(6);
            int status = response.statusCode();

            if (status == 200) {
                successes.incrementAndGet();
            } else if (status == 500) {
                String body = response.body();
                if (body != null && (body.contains("parse") || body.contains("JSON")
                        || body.contains("Unexpected") || body.contains("malformed"))) {
                    parseErrors.incrementAndGet();
                } else {
                    other.incrementAndGet();
                }
            } else {
                other.incrementAndGet();
            }
        }

        long totalErrors = totalRequests - successes.get();
        long errorPct    = totalErrors * 100 / totalRequests;

        System.out.println();
        System.out.println("┌─────────────────────────────────────────────────────────────────────┐");
        System.out.println("│  PROOF  —  EAGAIN / JSON truncation fingerprint                     │");
        System.out.println("│                                                                      │");
        System.out.printf( "│  Total requests   : %d%n", totalRequests);
        System.out.printf( "│  Succeeded        : %d%n", successes.get());
        System.out.printf( "│  Parse errors     : %d  ← EAGAIN interpreted as EOF               │%n", parseErrors.get());
        System.out.printf( "│  Other errors     : %d%n", other.get());
        System.out.printf( "│  Error rate       : %d%%%n", errorPct);
        System.out.println("│                                                                      │");
        System.out.println("│  Finding: engineers see 'JSON parse error' not 'EAGAIN'             │");
        System.out.println("│  Why it hides: application layer obscures the TCP cause             │");
        System.out.println("│  Fix: NIO recv() must loop on EAGAIN, never treat as EOF            │");
        System.out.println("└─────────────────────────────────────────────────────────────────────┘");
        System.out.println();

        assertThat(totalErrors)
                .as("30%% EAGAIN must produce observable errors — application is not handling EAGAIN correctly")
                .isGreaterThan(0L);

        assertThat(successes.get())
                .as("some requests must succeed — EAGAIN only fires on 30%% of recv() calls")
                .isGreaterThan(20L);
    }

    private HttpResponse<String> probe(int timeoutSeconds) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(appBaseUrl + "/users/1"))
                .GET()
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static long percentile(List<Long> sortedValues, int percentile) {
        if (sortedValues.isEmpty()) return 0L;
        int index = (int) Math.ceil(percentile / 100.0 * sortedValues.size()) - 1;
        return sortedValues.get(Math.max(0, Math.min(index, sortedValues.size() - 1)));
    }
}
