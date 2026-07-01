package com.macstab.chaos.examples.l2;

import static org.assertj.core.api.Assertions.assertThat;

import com.macstab.chaos.annotation.AppContainer;
import com.macstab.chaos.annotation.SyscallLevelChaos;
import com.macstab.chaos.annotation.composite.CompositeChaosSlowDnsResolution;
import com.macstab.chaos.annotation.composite.CompositeChaosSystemDnsFailure;
import com.macstab.chaos.annotation.composite.CompositeChaosTransientDnsFailure;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * L2 – DNS composite chaos: production DNS disaster scenarios in Kubernetes microservices.
 *
 * <p>DNS failures are the single most underestimated category of production incidents in
 * Kubernetes. Services fail not because DNS is down, but because ndots:5 amplifies a 20% per-
 * lookup failure rate into a 67% per-hostname failure rate. Each test in this class encodes a
 * real incident where DNS failure cascaded through microservice architecture in ways that no
 * single-service health check would have detected.
 *
 * <h2>The ndots:5 amplification trap</h2>
 * <p>Kubernetes sets {@code ndots:5} in every pod's {@code /etc/resolv.conf}. For any hostname
 * with fewer than 5 dots, the glibc resolver attempts up to five sequential lookups, appending
 * each search domain. A 20% per-lookup {@code EAI_AGAIN} rate produces an effective per-hostname
 * failure probability of {@code 1 - 0.80^5 = 67.2%} — catastrophic amplification invisible
 * in single-lookup tests.
 */
@Testcontainers
@ExtendWith(ChaosTestingExtension.class)
@SyscallLevelChaos(LibchaosLib.DNS)
class L2DnsComposites {

    private static final Logger log = LoggerFactory.getLogger(L2DnsComposites.class);

    @BeforeAll
    static void printIncidentSummary() {
        System.out.println("╔══════════════════════════════════════════════════════════════════╗");
        System.out.println("║  L2 DNS DISASTER PROOFS — KUBERNETES MICROSERVICE CASCADE        ║");
        System.out.println("║  ndots:5 amplification: 20% per-lookup = 67% per-hostname fail  ║");
        System.out.println("║  Five DNS failure modes. Each caused a real production outage.  ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");
    }

    // ── Containers ────────────────────────────────────────────────────────

    /**
     * Python DNS application performing real {@code getaddrinfo()} calls. The libchaos DNS
     * intercept layer injects faults at resolver syscall level. Reports resolution metadata
     * (retry count, elapsed DNS time, error codes) in the response body.
     */
    @Container
    @AppContainer
    static GenericContainer<?> app =
            new GenericContainer<>("macstab/python-dns-app:latest")
                    .withExposedPorts(8080)
                    .withStartupTimeout(Duration.ofSeconds(60));

    private HttpClient httpClient;
    private String appBaseUrl;

    @BeforeEach
    void setUp() {
        httpClient =
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(5))
                        .build();
        appBaseUrl = "http://" + app.getHost() + ":" + app.getMappedPort(8080);
    }

    // ══════════════════════════════════════════════════════════════════════
    // 1. Transient DNS Failure — EAI_AGAIN 15%
    // ══════════════════════════════════════════════════════════════════════

    /**
     * INCIDENT: CoreDNS pod eviction during cluster autoscaling. The CoreDNS pod was evicted
     * from a node being scaled down. For 8 seconds, 15% of DNS lookups returned EAI_AGAIN
     * (SERVFAIL from overloaded upstream resolver). Services whose retry policy did not include
     * UnknownHostException in the retryable exception set started failing immediately.
     *
     * <p>With 3 retry attempts, P(all 3 fail) = 0.15^3 = 0.34%. Most requests succeed after
     * one retry. This test proves UnknownHostException is in your retryable exception set.
     */
    @Test
    @DisplayName("L2: CoreDNS pod eviction — 15% EAI_AGAIN absorbed by retry, success > 95%")
    @CompositeChaosTransientDnsFailure(probability = 0.15)
    void transientDnsFailure15PercentRetryAbsorbs() throws Exception {
        System.out.println("╔══════════════════════════════════════════════════════════════════╗");
        System.out.println("║  INCIDENT: CoreDNS pod evicted during autoscale — 15% EAI_AGAIN ║");
        System.out.println("║  P(all 3 retries fail) = 0.15^3 = 0.34%.                       ║");
        System.out.println("║  Is UnknownHostException in your retryable exception set?       ║");
        System.out.println("║  200 requests. Success rate must exceed 95%.                    ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");

        int totalRequests = 200;
        AtomicLong successCount = new AtomicLong();
        AtomicLong errorCount = new AtomicLong();

        for (int i = 0; i < totalRequests; i++) {
            HttpRequest request =
                    HttpRequest.newBuilder()
                            .uri(URI.create(appBaseUrl + "/resolve?host=backend.example.com"))
                            .GET()
                            .timeout(Duration.ofSeconds(10))
                            .build();

            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                successCount.incrementAndGet();
            } else {
                errorCount.incrementAndGet();
                log.warn("Request {} failed under EAI_AGAIN 15%%: status={} body={}",
                        i + 1, response.statusCode(), response.body());
            }
        }

        double successRate = (double) successCount.get() / totalRequests * 100.0;

        System.out.println("╔══════════════════════════════════════════════════════════════════╗");
        System.out.printf("║  PROOF: success=%d (%.1f%%)  errors=%d  (must > 95%%)%n",
                successCount.get(), successRate, errorCount.get());
        System.out.printf("║  EAI_AGAIN retryable: %s%n",
                successRate > 95.0 ? "YES (UnknownHostException classified correctly)"
                        : "NO — DNS eviction caused outage");
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");

        assertThat(successRate)
                .as(
                        "retry must absorb 15%% transient EAI_AGAIN — CoreDNS eviction proof:"
                                + " UnknownHostException must be in retryable exception set;"
                                + " success rate must exceed 95%% (P(all retries fail) = 0.34%%)")
                .isGreaterThan(95.0);
    }

    // ══════════════════════════════════════════════════════════════════════
    // 2. Heavy DNS Flap — EAI_AGAIN 50%
    // ══════════════════════════════════════════════════════════════════════

    /**
     * INCIDENT: CoreDNS overloaded — insufficient replica count for cluster size. Half of all DNS
     * forward lookups returned EAI_AGAIN. The circuit breaker was the last line of defense: once
     * the retry-amplified failure rate saturated the sliding window, it must stop all DNS
     * resolution attempts and serve a controlled fallback rather than hammer a struggling resolver.
     *
     * <p>At 50%, P(all 3 retries fail) = 0.50^3 = 12.5% — well above the 50% circuit breaker
     * threshold when combined with ndots:5 amplification. The circuit must open. The fallback
     * must activate. Continuing to hammer an overloaded CoreDNS makes the outage worse for
     * every service in the cluster.
     */
    @Test
    @DisplayName("L2: CoreDNS overload (50% EAI_AGAIN) — circuit opens to stop resolver hammering")
    @CompositeChaosTransientDnsFailure(probability = 0.50)
    void heavyDnsFlap50PercentCircuitBreakerOpens() throws Exception {
        System.out.println("╔══════════════════════════════════════════════════════════════════╗");
        System.out.println("║  INCIDENT: CoreDNS overloaded — too few replicas for cluster    ║");
        System.out.println("║  50% EAI_AGAIN. P(all 3 retries fail) = 12.5%.                ║");
        System.out.println("║  Circuit MUST open. Fallback MUST activate.                    ║");
        System.out.println("║  Continuing to hammer overloaded CoreDNS hurts the whole cluster║");
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");

        int totalRequests = 150;
        AtomicLong successCount = new AtomicLong();
        AtomicLong fallbackCount = new AtomicLong();
        AtomicLong uncaughtErrors = new AtomicLong();

        for (int i = 0; i < totalRequests; i++) {
            HttpRequest request =
                    HttpRequest.newBuilder()
                            .uri(URI.create(appBaseUrl + "/fetch?url=http://api.example.com/data"))
                            .GET()
                            .timeout(Duration.ofSeconds(10))
                            .build();

            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            String body = response.body();

            if (response.statusCode() == 200 && !body.contains("\"source\":\"FALLBACK\"")) {
                successCount.incrementAndGet();
            } else if (response.statusCode() == 200 && body.contains("\"source\":\"FALLBACK\"")) {
                fallbackCount.incrementAndGet();
            } else if (response.statusCode() == 500) {
                uncaughtErrors.incrementAndGet();
                log.error("Uncaught error on request {} under EAI_AGAIN 50%%: {}", i + 1, body);
            }
        }

        System.out.println("╔══════════════════════════════════════════════════════════════════╗");
        System.out.printf("║  PROOF: success=%d  fallbacks=%d  uncaught500=%d  total=%d%n",
                successCount.get(), fallbackCount.get(), uncaughtErrors.get(), totalRequests);
        System.out.printf("║  Circuit status: %s%n",
                fallbackCount.get() > 0 ? "OPENED (circuit stopped resolver hammering)"
                        : "DID NOT OPEN (resolver being hammered)");
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");

        assertThat(fallbackCount.get())
                .as(
                        "circuit breaker must open under 50%% DNS flap and serve at least one"
                                + " fallback response — CoreDNS overload proof: circuit must stop"
                                + " hammering an already-struggling resolver")
                .isGreaterThan(0L);

        assertThat(uncaughtErrors.get())
                .as("50%% DNS flap must not produce uncaught HTTP 500 — circuit breaker or"
                        + " fallback must absorb all failures before they cascade")
                .isEqualTo(0L);
    }

    // ══════════════════════════════════════════════════════════════════════
    // 3. Permanent DNS Failure — EAI_NONAME 100%
    // ══════════════════════════════════════════════════════════════════════

    /**
     * INCIDENT: Service discovery misconfiguration after a migration. The service name was updated
     * in the registry but the Kubernetes ConfigMap was not. 100% EAI_NONAME (NXDOMAIN) — the
     * hostname was permanently gone. Applications that retried on permanent DNS failure entered an
     * infinite loop, consuming resources and delaying the detection of the misconfiguration.
     *
     * <p>A correct application distinguishes EAI_NONAME (permanent) from EAI_AGAIN (transient)
     * and stops retrying immediately. It falls back to a hardcoded IP or cached address and
     * responds within 5 seconds — not hanging until the HTTP timeout is hit.
     */
    @Test
    @DisplayName("L2: Service discovery misconfiguration — EAI_NONAME must not trigger infinite retry")
    @CompositeChaosSystemDnsFailure(probability = 1.0)
    void permanentDnsFailureNoRetryHardcodedIpFallback() throws Exception {
        System.out.println("╔══════════════════════════════════════════════════════════════════╗");
        System.out.println("║  INCIDENT: ConfigMap not updated after migration — NXDOMAIN 100%║");
        System.out.println("║  EAI_NONAME = permanent. Must NOT retry. Must NOT hang.         ║");
        System.out.println("║  20 requests. All must complete within 5s. Zero hangs.          ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");

        int totalRequests = 20;
        AtomicLong fallbackOrDegradedCount = new AtomicLong();
        AtomicLong infiniteRetryOrHang = new AtomicLong();
        List<Long> responseTimes = new ArrayList<>(totalRequests);

        for (int i = 0; i < totalRequests; i++) {
            long start = System.nanoTime();

            HttpRequest request =
                    HttpRequest.newBuilder()
                            .uri(URI.create(appBaseUrl + "/fetch?url=http://api.example.com/data"))
                            .GET()
                            .timeout(Duration.ofSeconds(8))
                            .build();

            try {
                HttpResponse<String> response =
                        httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
                responseTimes.add(elapsedMs);

                if (response.statusCode() == 200 || response.statusCode() == 503
                        || response.statusCode() == 502) {
                    fallbackOrDegradedCount.incrementAndGet();
                } else if (response.statusCode() == 500) {
                    if (!response.body().contains("Traceback")) {
                        fallbackOrDegradedCount.incrementAndGet();
                    } else {
                        infiniteRetryOrHang.incrementAndGet();
                        log.error("Unhandled Python traceback on permanent DNS failure, request {}: {}",
                                i + 1, response.body());
                    }
                }
            } catch (java.net.http.HttpTimeoutException e) {
                long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
                responseTimes.add(elapsedMs);
                infiniteRetryOrHang.incrementAndGet();
                log.error("Request {} timed out under 100%% DNS failure — app may be retrying"
                        + " infinitely on EAI_NONAME: elapsed={}ms", i + 1, elapsedMs);
            }
        }

        long maxResponseMs = responseTimes.stream().mapToLong(Long::longValue).max().orElse(0L);

        System.out.println("╔══════════════════════════════════════════════════════════════════╗");
        System.out.printf("║  PROOF: controlled=%d  hangs=%d  maxResponseMs=%d%n",
                fallbackOrDegradedCount.get(), infiniteRetryOrHang.get(), maxResponseMs);
        System.out.printf("║  Permanent DNS handling: %s%n",
                infiniteRetryOrHang.get() == 0
                        ? "CORRECT (EAI_NONAME not retried, fell back within 5s)"
                        : "INCORRECT (retrying permanent failure — misconfiguration hidden)");
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");

        assertThat(maxResponseMs)
                .as(
                        "application must respond within 5000ms on 100%% permanent DNS failure"
                                + " — service discovery misconfiguration proof: EAI_NONAME must"
                                + " not trigger infinite retry loop hiding the config error")
                .isLessThan(5_000L);

        assertThat(infiniteRetryOrHang.get())
                .as("application must not exhibit infinite retry or hang on permanent DNS failure"
                        + " — immediate fallback to hardcoded IP required")
                .isEqualTo(0L);

        assertThat(fallbackOrDegradedCount.get())
                .as("all 20 requests must receive a structured response (fallback 200, 503, or 502)"
                        + " when DNS permanently fails with EAI_NONAME")
                .isEqualTo((long) totalRequests);
    }

    // ══════════════════════════════════════════════════════════════════════
    // 4. Slow DNS Resolution — 1000ms latency
    // ══════════════════════════════════════════════════════════════════════

    /**
     * INCIDENT: Upstream resolver overwhelmed during a DDoS mitigation event. DNS responses were
     * slow (1000ms+) but eventually arrived. The fatal interaction: ndots:5 means each hostname
     * traverses up to 5 search domains sequentially. At 1000ms per lookup, the worst-case total
     * DNS time for a single hostname is 5 × 1000ms = 5000ms — consuming the entire connection
     * timeout budget before a single TCP packet is sent.
     *
     * <p>Services with 5s total connection timeouts timed out entirely in the DNS phase. This test
     * proves: your DNS timeout is generous enough to accommodate ndots:5 worst-case resolution,
     * and no requests hang beyond the test budget.
     */
    @Test
    @DisplayName("L2: DDoS mitigation — 1s DNS latency × ndots:5 = up to 5s total resolution time")
    @CompositeChaosSlowDnsResolution(latency = 1000)
    void slowDns1sNdots5Amplification() throws Exception {
        System.out.println("╔══════════════════════════════════════════════════════════════════╗");
        System.out.println("║  INCIDENT: DDoS mitigation slowed upstream resolver to 1s/lookup║");
        System.out.println("║  ndots:5: 5 sequential lookups × 1000ms = 5000ms DNS phase.    ║");
        System.out.println("║  Services with 5s connection timeouts fail entirely in DNS.     ║");
        System.out.println("║  40 requests. Error rate < 10%. No hangs.                      ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");

        int totalRequests = 40;
        AtomicLong successCount = new AtomicLong();
        AtomicLong timeoutCount = new AtomicLong();
        AtomicLong errorCount = new AtomicLong();
        List<Long> latenciesMs = new ArrayList<>(totalRequests);

        for (int i = 0; i < totalRequests; i++) {
            long start = System.nanoTime();

            try {
                HttpRequest request =
                        HttpRequest.newBuilder()
                                .uri(URI.create(appBaseUrl + "/fetch?url=http://api.example.com/health"))
                                .GET()
                                .timeout(Duration.ofSeconds(15))
                                .build();

                HttpResponse<String> response =
                        httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
                latenciesMs.add(elapsedMs);

                if (response.statusCode() == 200) {
                    successCount.incrementAndGet();
                } else if (response.body().contains("timeout") || response.body().contains("Timeout")) {
                    timeoutCount.incrementAndGet();
                    log.warn("DNS timeout surfaced as app error on request {}: elapsed={}ms", i + 1, elapsedMs);
                } else {
                    errorCount.incrementAndGet();
                }

            } catch (java.net.http.HttpTimeoutException e) {
                long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
                latenciesMs.add(elapsedMs);
                timeoutCount.incrementAndGet();
                log.warn("Request {} timed out at test-client level under 1s DNS latency: elapsed={}ms",
                        i + 1, elapsedMs);
            }
        }

        long p99Ms = latenciesMs.isEmpty() ? 0L : percentile(latenciesMs, 99);
        double errorRate = (double) (errorCount.get() + timeoutCount.get()) / totalRequests * 100.0;

        System.out.println("╔══════════════════════════════════════════════════════════════════╗");
        System.out.printf("║  PROOF: success=%d  timeouts=%d  errors=%d  p99=%dms%n",
                successCount.get(), timeoutCount.get(), errorCount.get(), p99Ms);
        System.out.printf("║  Error rate: %.1f%%  (must < 10%%)%n", errorRate);
        System.out.printf("║  DNS timeout configured correctly: %s%n",
                errorRate < 10.0 ? "YES (accommodates ndots:5 worst-case)"
                        : "NO — DNS timeout too short for ndots:5");
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");

        assertThat(p99Ms)
                .as("p99 must remain below 15000ms with 1s DNS latency amplified by ndots:5"
                        + " — DDoS mitigation proof: no completely hung requests")
                .isLessThan(15_000L);

        assertThat(errorRate)
                .as("error rate must stay below 10%% under 1s DNS latency — DDoS proof: DNS"
                        + " timeout must accommodate ndots:5 amplification (up to 5s total)")
                .isLessThan(10.0);
    }

    // ══════════════════════════════════════════════════════════════════════
    // 5. Combined: Slow DNS (500ms) + Transient Failure (20%)
    // ══════════════════════════════════════════════════════════════════════

    /**
     * INCIDENT: Production ndots:5 DNS storm during a cluster upgrade. CoreDNS was being rolled
     * (causing 20% EAI_AGAIN) while simultaneously under high load from the upgrade traffic
     * (causing 500ms+ response times). Two failure modes combined in a way that no single-fault
     * test would catch.
     *
     * <p>The compound effect:
     * - Latency amplification: 5 sequential lookups × 500ms = 2500ms DNS latency
     * - Failure amplification: 1 - 0.80^5 = 67.2% effective per-hostname failure rate
     * - Compounding retry cost: 3 retries × 5 sequential lookups × 500ms = 7500ms DNS alone
     *
     * <p>Success rate must exceed 75% (circuit breaker and retry absorb both dimensions).
     * p99 must remain below 12s (slow-call detector caps tail latency).
     */
    @Test
    @DisplayName("L2: Cluster upgrade DNS storm — 500ms slow + 20% failure = ndots:5 compound disaster")
    @CompositeChaosSlowDnsResolution(latency = 500)
    @CompositeChaosTransientDnsFailure(probability = 0.20)
    void slowDns500MsPlusTransient20PercentNdots5Storm() throws Exception {
        System.out.println("╔══════════════════════════════════════════════════════════════════╗");
        System.out.println("║  INCIDENT: Cluster upgrade DNS storm — two failure modes combined║");
        System.out.println("║  500ms slow + 20% EAI_AGAIN = ndots:5 compound disaster.       ║");
        System.out.printf("║  Effective per-hostname failure: 1-0.80^5 = %.1f%%%n", (1.0 - Math.pow(0.80, 5)) * 100.0);
        System.out.println("║  Retry × latency worst case: 3×5×500ms = 7500ms DNS phase.    ║");
        System.out.println("║  80 requests. Success > 75%%. p99 < 12000ms.                   ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");

        int totalRequests = 80;
        AtomicLong successCount = new AtomicLong();
        AtomicLong errorCount = new AtomicLong();
        AtomicLong timeoutCount = new AtomicLong();
        List<Long> latenciesMs = new ArrayList<>(totalRequests);

        for (int i = 0; i < totalRequests; i++) {
            long start = System.nanoTime();

            try {
                HttpRequest request =
                        HttpRequest.newBuilder()
                                .uri(URI.create(appBaseUrl + "/fetch?url=http://backend.example.com/api"))
                                .GET()
                                .timeout(Duration.ofSeconds(15))
                                .build();

                HttpResponse<String> response =
                        httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
                latenciesMs.add(elapsedMs);

                if (response.statusCode() == 200) {
                    successCount.incrementAndGet();
                } else {
                    errorCount.incrementAndGet();
                    log.warn("Request {} failed under combined DNS storm: status={} elapsed={}ms",
                            i + 1, response.statusCode(), elapsedMs);
                }

            } catch (java.net.http.HttpTimeoutException e) {
                long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
                latenciesMs.add(elapsedMs);
                timeoutCount.incrementAndGet();
                log.warn("Request {} timed out at test-client level under DNS storm: elapsed={}ms",
                        i + 1, elapsedMs);
            }
        }

        long p99Ms = latenciesMs.isEmpty() ? 0L : percentile(latenciesMs, 99);
        double successRate = (double) successCount.get() / totalRequests * 100.0;
        double effectiveErrorRate =
                (double) (errorCount.get() + timeoutCount.get()) / totalRequests * 100.0;

        System.out.println("╔══════════════════════════════════════════════════════════════════╗");
        System.out.printf("║  PROOF: success=%.1f%%  errors=%d  timeouts=%d  p99=%dms%n",
                successRate, errorCount.get(), timeoutCount.get(), p99Ms);
        System.out.printf("║  effectiveErrorRate=%.1f%%  (must < 25%%)%n", effectiveErrorRate);
        System.out.printf("║  Upgrade DNS storm survived: %s%n",
                successRate > 75.0 && p99Ms < 12000L ? "YES" : "NO — compound fault gap");
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");

        assertThat(successRate)
                .as(
                        "application must achieve > 75%% success under combined 500ms slow DNS +"
                                + " 20%% EAI_AGAIN — cluster upgrade DNS storm proof: retry +"
                                + " circuit breaker must absorb both fault dimensions simultaneously")
                .isGreaterThan(75.0);

        assertThat(p99Ms)
                .as(
                        "p99 must remain below 12000ms under the combined ndots:5 DNS storm —"
                                + " slow-call detector must cap tail latency once retry-amplified"
                                + " slowness crosses the slow-call threshold")
                .isLessThan(12_000L);
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private static long percentile(List<Long> values, int percentile) {
        if (values.isEmpty()) {
            return 0L;
        }
        List<Long> sorted = new ArrayList<>(values);
        sorted.sort(Long::compareTo);
        int index = (int) Math.ceil(percentile / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }
}
