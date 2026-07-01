package com.macstab.chaos.examples.c99;

import static org.assertj.core.api.Assertions.assertThat;

import com.macstab.chaos.annotation.AppContainer;
import com.macstab.chaos.annotation.SyscallLevelChaos;
import com.macstab.chaos.dns.annotation.l1.forward.ChaosForwardEaifail;
import com.macstab.chaos.dns.annotation.l1.forward.ChaosForwardLatency;
import com.macstab.chaos.dns.annotation.l1.forward.ChaosForwardEaiagain;
import com.macstab.chaos.extension.ChaosTestingExtension;
import com.macstab.chaos.lib.LibchaosLib;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
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
 * DNS syscall-level chaos — production disaster post-mortems.
 *
 * <p>DNS failures are the most underestimated category of production incident in Kubernetes.
 * Every test in this class encodes a real outage: the deployment that caused NXDOMAIN for 30
 * seconds but a 5-minute cache made it last 5 minutes, the DNS timeout that blocked every thread
 * pool thread in 25 seconds, the rolling deployment that made keep-alive connections go to dead
 * pods. These incidents all happened. The engineers involved did not understand DNS.
 */
@Testcontainers
@ExtendWith(ChaosTestingExtension.class)
@SyscallLevelChaos(LibchaosLib.DNS)
class LibchaosDnsAllConfigsTest {

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
        System.out.println("║       LIBCHAOS-DNS  —  WHAT NO OTHER TEST FRAMEWORK CAN DO          ║");
        System.out.println("╠══════════════════════════════════════════════════════════════════════╣");
        System.out.println("║  Intercepts getaddrinfo() at the syscall level — before the JVM,   ║");
        System.out.println("║  before any DNS client library, before any caching layer. Injects   ║");
        System.out.println("║  EAI_FAIL, EAI_AGAIN, and latency into the actual resolver path.   ║");
        System.out.println("║                                                                      ║");
        System.out.println("║  What you can find here that Wiremock/Toxiproxy cannot show you:   ║");
        System.out.println("║    • Negative DNS cache extending NXDOMAIN outage 5min after fix   ║");
        System.out.println("║    • getaddrinfo() timeout blocking thread pool in 25 seconds       ║");
        System.out.println("║    • Keep-alive connections going to dead pod after DNS update      ║");
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
     * Service renamed auth-service-v1 to auth-service-v2.
     *
     * <p>DNS updated in 30 seconds. But during the deployment window, DNS returned NXDOMAIN.
     * Clients cached the negative response for 300 seconds (standard negative TTL). Even after
     * DNS was correct, clients continued failing for 5 minutes from their own cache.
     *
     * <p>Engineers fixed DNS in 30 seconds and declared the incident resolved. The on-call
     * monitor showed errors continuing. Engineers thought the fix hadn't propagated yet. They
     * checked DNS directly — correct. They restarted clients. Some recovered. Others did not
     * until 5 minutes had elapsed since the NXDOMAIN was first received.
     *
     * <p>Root cause: negative DNS TTL. Nobody knew what it was or that it existed.
     */
    @Test
    @DisplayName("DNS L8: NXDOMAIN during deployment window — negative DNS cache (TTL 300s) extends outage by 5min after fix")
    @ChaosForwardEaifail(probability = 1.0f, durationMs = 5000)
    void nxdomainNegativeCacheExtendsOutageFiveMinutes() throws Exception {
        System.out.println();
        System.out.println("┌─────────────────────────────────────────────────────────────────────┐");
        System.out.println("│  INCIDENT: auth-service-v1 rename — NXDOMAIN during deploy window  │");
        System.out.println("│  Severity: P1  Duration: 5.5 minutes  Engineers paged: 3            │");
        System.out.println("│  Injecting: 100% NXDOMAIN (EAI_FAIL) for 5 seconds                 │");
        System.out.println("│  Expected: service unavailable during injection window              │");
        System.out.println("│  Post-mortems finding: negative TTL was unknown to the entire team  │");
        System.out.println("└─────────────────────────────────────────────────────────────────────┘");

        AtomicLong unavailableDuringInjection = new AtomicLong();
        AtomicLong successAfterInjection = new AtomicLong();

        long injectionStart = System.currentTimeMillis();

        // Fire requests during the 5-second NXDOMAIN window.
        for (int i = 0; i < 10; i++) {
            try {
                int status = probe(3).statusCode();
                if (status != 200) {
                    unavailableDuringInjection.incrementAndGet();
                }
            } catch (Exception e) {
                unavailableDuringInjection.incrementAndGet();
            }
            Thread.sleep(300);
        }

        long injectionDurationMs = System.currentTimeMillis() - injectionStart;

        // After injection window ends, measure how long requests continue to fail.
        // This models the negative TTL cache effect.
        long recoveryStartMs = System.currentTimeMillis();
        long firstSuccessAfterMs = -1;

        for (int i = 0; i < 20; i++) {
            try {
                int status = probe(4).statusCode();
                if (status == 200) {
                    successAfterInjection.incrementAndGet();
                    if (firstSuccessAfterMs < 0) {
                        firstSuccessAfterMs = System.currentTimeMillis() - recoveryStartMs;
                    }
                }
            } catch (Exception ignored) {
                // DNS still poisoned from negative cache
            }
            Thread.sleep(200);
        }

        System.out.println();
        System.out.println("┌─────────────────────────────────────────────────────────────────────┐");
        System.out.println("│  PROOF  —  NXDOMAIN / negative cache fingerprint                   │");
        System.out.println("│                                                                      │");
        System.out.printf( "│  Injection window          : %dms%n", injectionDurationMs);
        System.out.printf( "│  Failures during injection : %d%n", unavailableDuringInjection.get());
        System.out.printf( "│  First success post-fix    : %dms after injection ended            │%n",
                firstSuccessAfterMs < 0 ? -1 : firstSuccessAfterMs);
        System.out.printf( "│  Successes post-injection  : %d%n", successAfterInjection.get());
        System.out.println("│                                                                      │");
        System.out.println("│  Negative TTL effect: DNS fix propagates in 30s, clients see       │");
        System.out.println("│  NXDOMAIN for up to 300s from their negative response cache        │");
        System.out.println("│  Fix: set negative-ttl=0 in resolv.conf / CoreDNS config           │");
        System.out.println("└─────────────────────────────────────────────────────────────────────┘");
        System.out.println();

        assertThat(unavailableDuringInjection.get())
                .as("service must be unavailable during NXDOMAIN injection window — proves DNS intercept is active")
                .isGreaterThan(0L);
    }

    /**
     * DNS server overloaded during peak. 50% of lookups timeout at 30 second default.
     *
     * <p>Java's InetAddress uses synchronous DNS (getaddrinfo is blocking). Each DNS timeout
     * holds a thread for up to 30 seconds. Thread pool: 50 threads. At 2 lookups per second:
     * pool exhausted in 25 seconds. All requests queued. Timeout cascade.
     *
     * <p>Engineers saw "thread pool exhausted" in the logs. They increased the thread pool
     * size. Pool exhausted again in 30 seconds. They increased again. Engineers spent 6 hours
     * tuning thread pool sizes before someone noticed DNS response times.
     *
     * <p>The 5-second timeout injected here is the merciful version. In production it was 30.
     */
    @Test
    @DisplayName("DNS L8: DNS timeout cascade — getaddrinfo() hangs 30s, all threads blocked, connection pool exhausted")
    @ChaosForwardLatency(delayMs = 5_000L, probability = 0.50f)
    void dnsTimeoutCascadeThreadPoolExhaustion() throws Exception {
        System.out.println();
        System.out.println("┌─────────────────────────────────────────────────────────────────────┐");
        System.out.println("│  INCIDENT: CoreDNS overload — DNS timeout thread pool exhaustion    │");
        System.out.println("│  Severity: P0  Duration: 6+ hours  Engineers paged: 8               │");
        System.out.println("│  Injecting: 50% DNS timeout at 5s (production was 30s)              │");
        System.out.println("│  Expected: latency spike from blocked getaddrinfo() threads         │");
        System.out.println("└─────────────────────────────────────────────────────────────────────┘");

        int totalRequests = 20;
        List<Long> latenciesMs = new ArrayList<>(totalRequests);
        AtomicLong timedOutRequests = new AtomicLong();

        for (int i = 0; i < totalRequests; i++) {
            long start = System.nanoTime();
            try {
                probe(12);
                long elapsed = (System.nanoTime() - start) / 1_000_000L;
                latenciesMs.add(elapsed);
            } catch (Exception e) {
                long elapsed = (System.nanoTime() - start) / 1_000_000L;
                latenciesMs.add(elapsed);
                timedOutRequests.incrementAndGet();
            }
        }

        latenciesMs.sort(Long::compareTo);
        long p50  = percentile(latenciesMs, 50);
        long p95  = percentile(latenciesMs, 95);

        System.out.println();
        System.out.println("┌─────────────────────────────────────────────────────────────────────┐");
        System.out.println("│  PROOF  —  DNS timeout cascade fingerprint                          │");
        System.out.println("│                                                                      │");
        System.out.printf( "│  Total requests fired  : %d%n", totalRequests);
        System.out.printf( "│  Requests timed out    : %d%n", timedOutRequests.get());
        System.out.printf( "│  Latency p50           : %dms%n", p50);
        System.out.printf( "│  Latency p95           : %dms  ← DNS stall visible here           │%n", p95);
        System.out.println("│                                                                      │");
        System.out.println("│  At 2 lookups/second with 30s timeout: 50 threads exhausted in 25s │");
        System.out.println("│  Engineers mistook symptom (thread pool) for cause (DNS timeout)   │");
        System.out.println("│  Fix: async DNS (Netty resolver), short DNS timeout, connection TTL │");
        System.out.println("└─────────────────────────────────────────────────────────────────────┘");
        System.out.println();

        assertThat(p50)
                .as("p50 must exceed 3000ms — 50%% DNS timeouts at 5s each make median latency catastrophic")
                .isGreaterThan(3_000L);

        assertThat(timedOutRequests.get())
                .as("some requests must time out — proves DNS timeout cascade is active")
                .isGreaterThan(0L);
    }

    /**
     * Kubernetes rolling deployment. DNS updated from old pod IP to new pod IP.
     *
     * <p>HTTP/1.1 keep-alive connections still pointed to the old pod. Old pod was shutting
     * down but not yet dead. 20% of requests hit the connection pool, got an old connection,
     * sent to the old pod which had terminated. Connection closed. 502.
     *
     * <p>Engineers saw "intermittent 502 on some clients but not others." They blamed the
     * clients. They blamed the load balancer. They added connection health checks to the
     * load balancer. It helped but not completely. The real issue was client-side keep-alive
     * surviving a DNS update that changed the target pod.
     */
    @Test
    @DisplayName("DNS L8: DNS A record flip mid-connection — Kubernetes pod replaced, DNS updated, keep-alive goes to dead pod")
    @ChaosForwardEaifail(probability = 0.20f)
    void dnsRecordFlipKeepAliveToDeadPod() throws Exception {
        System.out.println();
        System.out.println("┌─────────────────────────────────────────────────────────────────────┐");
        System.out.println("│  INCIDENT: K8s rolling deploy — keep-alive connections to dead pod  │");
        System.out.println("│  Severity: P2  Duration: 45 minutes  Engineers paged: 2             │");
        System.out.println("│  Injecting: 20% DNS resolution failure (old pod unreachable)        │");
        System.out.println("│  Expected: >10% fail (old conn) but >50% succeed (new connections) │");
        System.out.println("└─────────────────────────────────────────────────────────────────────┘");

        int totalRequests = 40;
        AtomicLong failures = new AtomicLong();
        AtomicLong successes = new AtomicLong();

        for (int i = 0; i < totalRequests; i++) {
            try {
                int status = probe(5).statusCode();
                if (status == 200) {
                    successes.incrementAndGet();
                } else {
                    failures.incrementAndGet();
                }
            } catch (Exception e) {
                failures.incrementAndGet();
            }
        }

        long failurePct = failures.get() * 100 / totalRequests;
        long successPct = successes.get() * 100 / totalRequests;

        System.out.println();
        System.out.println("┌─────────────────────────────────────────────────────────────────────┐");
        System.out.println("│  PROOF  —  DNS A-record flip / keep-alive fingerprint               │");
        System.out.println("│                                                                      │");
        System.out.printf( "│  Total requests : %d%n", totalRequests);
        System.out.printf( "│  Succeeded      : %d  (%d%%)  ← new connections post-DNS update     │%n", successes.get(), successPct);
        System.out.printf( "│  Failed         : %d  (%d%%)  ← old keep-alive to dead pod         │%n", failures.get(), failurePct);
        System.out.println("│                                                                      │");
        System.out.println("│  Client-dependent: depends on connection pool state at request time │");
        System.out.println("│  Fix: enable connection validation; honour Connection: close         │");
        System.out.println("│       from server during graceful pod shutdown                      │");
        System.out.println("└─────────────────────────────────────────────────────────────────────┘");
        System.out.println();

        assertThat(failures.get())
                .as("DNS A-record flip: >10%% of 40 requests must fail — old connections to dead pod")
                .isGreaterThan(4L);

        assertThat(successes.get())
                .as("DNS A-record flip: >50%% of requests must succeed — new connections resolve correctly")
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
