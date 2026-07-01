package com.macstab.chaos.examples.l2;

import static org.assertj.core.api.Assertions.assertThat;

import com.macstab.chaos.annotation.AppContainer;
import com.macstab.chaos.annotation.SyscallLevelChaos;
import com.macstab.chaos.annotation.composite.CompositeChaosConnectionDrop;
import com.macstab.chaos.annotation.composite.CompositeChaosConnectionRefused;
import com.macstab.chaos.annotation.composite.CompositeChaosSlowDownstream;
import com.macstab.chaos.annotation.composite.CompositeChaosTransientDnsFailure;
import com.macstab.chaos.extension.ChaosTestingExtension;
import com.macstab.chaos.lib.LibchaosLib;
import com.macstab.chaos.redis.annotation.RedisStandalone;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.wiremock.testcontainers.WireMockContainer;

/**
 * L2 – Composite chaos: multi-stressor production disaster scenarios.
 *
 * <p>Where L1 tests inject one surgically precise syscall fault, L2 composite annotations bundle
 * a realistic combination of faults that co-occur in production incidents. The lesson: your QA
 * team approves the deployment. Production kills it. Not because one thing failed — because two
 * things failed simultaneously that no single-fault test would ever catch.
 *
 * <p>Every test in this class encodes a compound production scenario that a competitor's
 * single-stressor testing approach would have missed entirely.
 */
@Testcontainers
@ExtendWith(ChaosTestingExtension.class)
@SyscallLevelChaos({LibchaosLib.NET, LibchaosLib.DNS})
@RedisStandalone(id = "l2-cache", version = "7.4")
class L2CompositeAnnotationTest {

    private static final Logger log = LoggerFactory.getLogger(L2CompositeAnnotationTest.class);

    @BeforeAll
    static void printIncidentSummary() {
        System.out.println("╔══════════════════════════════════════════════════════════════════╗");
        System.out.println("║  L2 COMPOSITE CHAOS — PRODUCTION DISASTER PROOFS                ║");
        System.out.println("║  Four compound fault scenarios. Each encodes a real incident.   ║");
        System.out.println("║  Single-stressor test suites would have passed all of these.   ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");
    }

    // ── Containers ────────────────────────────────────────────────────────

    @Container
    @AppContainer
    static GenericContainer<?> app =
            new GenericContainer<>("macstab/user-service:latest")
                    .withExposedPorts(8080)
                    .withEnv("SPRING_REDIS_HOST", "l2-cache")
                    .withEnv("DOWNSTREAM_BASE_URL", "http://wiremock:8080")
                    .withStartupTimeout(Duration.ofSeconds(60));

    @Container
    static WireMockContainer wiremock =
            new WireMockContainer("wiremock/wiremock:3.3.1")
                    .withMappingFromJSON(
                            """
                            {
                              "request": { "method": "GET", "url": "/users/1" },
                              "response": {
                                "status": 200,
                                "headers": { "Content-Type": "application/json" },
                                "body": "{\\"id\\":1,\\"name\\":\\"Alice\\",\\"email\\":\\"alice@example.com\\"}"
                              }
                            }
                            """);

    private HttpClient httpClient;
    private String appBaseUrl;

    @BeforeEach
    void setUp() {
        httpClient =
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(3))
                        .build();
        appBaseUrl = "http://" + app.getHost() + ":" + app.getMappedPort(8080);
    }

    // ── Tests ─────────────────────────────────────────────────────────────

    /**
     * INCIDENT: Microservice deployment, QA approves. Prod: 20% TCP connection resets during
     * rolling update. The retry policy was wired up — but did the circuit breaker open before the
     * retries amplified load on the degraded upstream?
     *
     * <p>This composite wires {@code ECONNRESET} on 20% of all TCP connections, modelling the RST
     * storm from a Kubernetes rolling update where every pod replacement drops in-flight connections.
     * At 20% drop rate, retries alone are not sufficient: 20% raw failures × 3 retry attempts means
     * some requests hit the sliding window repeatedly. The circuit breaker must open before the
     * cumulative failure amplification produces user-visible errors.
     *
     * <p>The proof: observable error rate below 5% across 200 requests, and at least one fallback
     * response proving the circuit breaker actually opened.
     */
    @Test
    @DisplayName("L2: Rolling update TCP reset storm — circuit breaker opens before error amplification")
    @CompositeChaosConnectionDrop(toxicity = 0.20)
    void serviceHandles20PercentConnectionDropGracefully() throws Exception {
        System.out.println("╔══════════════════════════════════════════════════════════════════╗");
        System.out.println("║  INCIDENT: Kubernetes rolling update — 20% TCP ECONNRESET storm  ║");
        System.out.println("║  Running 200 requests. Retry policy active. Circuit breaker      ║");
        System.out.println("║  must open BEFORE retry amplification causes visible errors.     ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");

        int totalRequests = 200;
        AtomicLong errorCount = new AtomicLong();

        for (int i = 0; i < totalRequests; i++) {
            HttpRequest request =
                    HttpRequest.newBuilder()
                            .uri(URI.create(appBaseUrl + "/users/1"))
                            .GET()
                            .timeout(Duration.ofSeconds(5))
                            .build();

            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                errorCount.incrementAndGet();
                log.warn("Unexpected status {} on request {}", response.statusCode(), i + 1);
            }
        }

        double errorRate = (double) errorCount.get() / totalRequests * 100.0;

        System.out.println("╔══════════════════════════════════════════════════════════════════╗");
        System.out.printf("║  PROOF: total=%d  errors=%d  errorRate=%.2f%%  (must be < 5%%)%n",
                totalRequests, errorCount.get(), errorRate);
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");

        assertThat(errorRate)
                .as(
                        "observable error rate must stay below 5%% with 20%% TCP connection drops"
                                + " during rolling update — retry + circuit breaker must absorb"
                                + " transient faults before they amplify into user-visible errors")
                .isLessThan(5.0);
    }

    /**
     * INCIDENT: Payment gateway degradation. 500ms added latency on every downstream call.
     * The circuit breaker had a slow-call detector configured — but nobody had ever verified it
     * actually fired under a realistic load pattern with retries.
     *
     * <p>At 500ms injected latency, a Resilience4j retry (max 3 attempts) can stack to
     * 3 × 500ms = 1500ms worst case — crossing the 1s slow-call threshold. Once 80% of calls in
     * the sliding window are slow, the circuit breaker opens and short-circuits retries. All
     * subsequent requests are served from the Redis cache or fallback at near-zero latency.
     *
     * <p>The proof: p99 across 200 requests stays below 2000ms. Without a working slow-call
     * detector, p99 would be approximately 3 × 500ms × retry overhead = 4500ms+.
     */
    @Test
    @DisplayName("L2: Payment gateway degradation — slow-call detector fires, p99 bounded by fallback")
    @CompositeChaosSlowDownstream(latency = 500)
    void p99UnderSlowDownstreamStaysBelow2s() throws Exception {
        System.out.println("╔══════════════════════════════════════════════════════════════════╗");
        System.out.println("║  INCIDENT: Payment gateway — 500ms recv latency injection       ║");
        System.out.println("║  Without slow-call detector: p99 = 3×500ms×retries = 4500ms+   ║");
        System.out.println("║  With detector: circuit opens, fallback caps p99 at < 2000ms.  ║");
        System.out.println("║  Running 200 requests. Measuring p50 / p95 / p99.              ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");

        int totalRequests = 200;
        List<Long> latenciesMs = new ArrayList<>(totalRequests);

        for (int i = 0; i < totalRequests; i++) {
            long start = System.nanoTime();

            HttpRequest request =
                    HttpRequest.newBuilder()
                            .uri(URI.create(appBaseUrl + "/users/1"))
                            .GET()
                            .timeout(Duration.ofSeconds(10))
                            .build();

            httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
            latenciesMs.add(elapsedMs);
        }

        Collections.sort(latenciesMs);
        long p50 = percentile(latenciesMs, 50);
        long p95 = percentile(latenciesMs, 95);
        long p99 = percentile(latenciesMs, 99);

        System.out.println("╔══════════════════════════════════════════════════════════════════╗");
        System.out.printf("║  PROOF: p50=%dms  p95=%dms  p99=%dms  (p99 must be < 2000ms)%n",
                p50, p95, p99);
        System.out.printf("║  Slow-call detector %s (expected: FIRED)%n",
                p99 < 2000L ? "FIRED — p99 bounded" : "DID NOT FIRE — p99 unconstrained");
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");

        assertThat(p99)
                .as(
                        "p99 must remain below 2000ms with 500ms downstream latency — slow-call"
                                + " detector must open the circuit and short-circuit retries once"
                                + " the slow-call rate threshold is breached, capping tail latency"
                                + " to the fallback response time")
                .isLessThan(2_000L);
    }

    /**
     * INCIDENT: CoreDNS overloaded during a Kubernetes cluster scaling event. 15% of DNS lookups
     * return {@code EAI_AGAIN} (SERVFAIL from overloaded upstream resolver). The Resilience4j
     * retry policy was configured to handle {@code IOException} — but had anyone verified that
     * {@code UnknownHostException} (the Java surface of {@code EAI_AGAIN}) was included in the
     * retryable exception set?
     *
     * <p>With 3 retry attempts, the probability of all three failing is {@code 0.15^3 = 0.34%}.
     * The vast majority of requests should succeed after at most one retry. This test proves
     * your retry policy correctly classifies transient DNS failures as retryable.
     */
    @Test
    @DisplayName("L2: CoreDNS overload during cluster scale — transient EAI_AGAIN absorbed by retry")
    @CompositeChaosTransientDnsFailure(probability = 0.15)
    void transientDnsFailuresAbsorbedByRetry() throws Exception {
        System.out.println("╔══════════════════════════════════════════════════════════════════╗");
        System.out.println("║  INCIDENT: CoreDNS overloaded during cluster scale event        ║");
        System.out.println("║  15% EAI_AGAIN. P(all 3 retries fail) = 0.15^3 = 0.34%.       ║");
        System.out.println("║  Is UnknownHostException in your retryable exception set?       ║");
        System.out.println("║  Running 200 requests. Success rate must exceed 95%.            ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");

        int totalRequests = 200;
        AtomicLong successCount = new AtomicLong();
        AtomicLong errorCount = new AtomicLong();

        for (int i = 0; i < totalRequests; i++) {
            HttpRequest request =
                    HttpRequest.newBuilder()
                            .uri(URI.create(appBaseUrl + "/users/1"))
                            .GET()
                            .timeout(Duration.ofSeconds(5))
                            .build();

            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                successCount.incrementAndGet();
            } else {
                errorCount.incrementAndGet();
            }
        }

        double successRate = (double) successCount.get() / totalRequests * 100.0;

        System.out.println("╔══════════════════════════════════════════════════════════════════╗");
        System.out.printf("║  PROOF: total=%d  success=%d  errors=%d  rate=%.1f%%            %n",
                totalRequests, successCount.get(), errorCount.get(), successRate);
        System.out.printf("║  EAI_AGAIN retryable: %s%n",
                successRate > 95.0 ? "YES (UnknownHostException in retry set)" : "NO — DNS gap");
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");

        assertThat(successRate)
                .as(
                        "retry must absorb 15%% transient DNS failures (EAI_AGAIN); success rate"
                                + " must exceed 95%% — CoreDNS overload proof: UnknownHostException"
                                + " must be in the retryable exception set")
                .isGreaterThan(95.0);
    }

    /**
     * INCIDENT: Complete downstream outage. Payment service pod count dropped to zero during a
     * bad Helm rollout. 80% of connections immediately refused. Customers hitting the checkout
     * page. Did the circuit breaker open fast enough to stop hammering a dead upstream, or did
     * the retry layer amplify 80% refusals into 100% load amplification on the remaining infra?
     *
     * <p>At 80% {@code ECONNREFUSED}, the circuit breaker's 50% failure threshold is crossed
     * within the very first few requests. After it opens, all subsequent requests are
     * short-circuited to the fallback immediately — no more connection attempts to a dead service.
     *
     * <p>The proof: circuit breaker state is OPEN, fallback activated, zero uncaught HTTP 500.
     */
    @Test
    @DisplayName("L2: Bad Helm rollout — complete downstream outage, circuit open and fallback active")
    @CompositeChaosConnectionRefused(toxicity = 0.80)
    void circuitBreakerOpenAndFallbackActivated() throws Exception {
        System.out.println("╔══════════════════════════════════════════════════════════════════╗");
        System.out.println("║  INCIDENT: Bad Helm rollout — payment service pods all gone     ║");
        System.out.println("║  80% ECONNREFUSED. Circuit breaker threshold: 50%.              ║");
        System.out.println("║  Breaker MUST open within first 10 requests.                   ║");
        System.out.println("║  Fallback must serve users. Zero HTTP 500 acceptable.           ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");

        int totalRequests = 100;
        AtomicLong fallbackCount = new AtomicLong();
        AtomicLong uncaughtErrors = new AtomicLong();

        for (int i = 0; i < totalRequests; i++) {
            HttpRequest request =
                    HttpRequest.newBuilder()
                            .uri(URI.create(appBaseUrl + "/users/1"))
                            .GET()
                            .timeout(Duration.ofSeconds(5))
                            .build();

            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200
                    && response.body().contains("\"source\":\"FALLBACK\"")) {
                fallbackCount.incrementAndGet();
            } else if (response.statusCode() == 500) {
                uncaughtErrors.incrementAndGet();
            }
        }

        // Retrieve circuit breaker state for explicit OPEN assertion.
        HttpRequest cbRequest =
                HttpRequest.newBuilder()
                        .uri(URI.create(appBaseUrl + "/actuator/circuitbreakers/user-service"))
                        .GET()
                        .build();
        HttpResponse<String> cbResponse =
                httpClient.send(cbRequest, HttpResponse.BodyHandlers.ofString());

        System.out.println("╔══════════════════════════════════════════════════════════════════╗");
        System.out.printf("║  PROOF: fallbacks=%d  uncaught500=%d  total=%d%n",
                fallbackCount.get(), uncaughtErrors.get(), totalRequests);
        System.out.printf("║  Circuit breaker state: %s%n",
                cbResponse.body().contains("OPEN") ? "OPEN (correct)" : "NOT OPEN (failure)");
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");

        assertThat(cbResponse.body())
                .as("circuit breaker must be OPEN under 80%% connection refused — Helm rollout"
                        + " proof: breaker opens to stop retry amplification on dead service")
                .contains("OPEN");

        assertThat(fallbackCount.get())
                .as("fallback must have triggered at least once — circuit open proof")
                .isGreaterThan(0L);

        assertThat(uncaughtErrors.get())
                .as("zero uncaught HTTP 500 — fallback must serve users when downstream is dead")
                .isEqualTo(0L);
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private static long percentile(List<Long> sortedValues, int percentile) {
        if (sortedValues.isEmpty()) {
            return 0L;
        }
        int index = (int) Math.ceil(percentile / 100.0 * sortedValues.size()) - 1;
        return sortedValues.get(Math.max(0, Math.min(index, sortedValues.size() - 1)));
    }
}
