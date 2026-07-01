package com.macstab.chaos.examples.sla;

import static org.assertj.core.api.Assertions.assertThat;

import com.macstab.chaos.annotation.AppContainer;
import com.macstab.chaos.annotation.SyscallLevelChaos;
import com.macstab.chaos.annotation.composite.CompositeChaosConnectionDrop;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.wiremock.testcontainers.WireMockContainer;

/**
 * SLA proof under chaos.
 *
 * <p>This test is the final boss. It answers the question that every engineering director, SRE lead,
 * and enterprise customer eventually asks:
 *
 * <blockquote>
 * <em>"Can you prove that your service meets its SLA under realistic fault conditions – not just in
 * a perfect lab environment?"</em>
 * </blockquote>
 *
 * <p>The SLA commitments being proven:
 * <ul>
 *   <li><b>p99 latency &lt; 2 000 ms</b> under 30% packet loss
 *   <li><b>Error rate &lt; 5%</b> under 30% packet loss
 * </ul>
 *
 * <p>1 000 requests are fired concurrently using virtual threads (Project Loom) with 30%
 * {@code ECONNRESET} injected at the libchaos layer. After all requests complete, the p50, p95, and
 * p99 latencies are computed and a full SLA proof report is printed to standard output.
 */
@Testcontainers
@ExtendWith(ChaosTestingExtension.class)
@SyscallLevelChaos({LibchaosLib.NET, LibchaosLib.DNS})
@RedisStandalone(id = "sla-redis", version = "7.4")
class SlaProofTest {

    private static final Logger log = LoggerFactory.getLogger(SlaProofTest.class);

    private static final int TOTAL_REQUESTS = 1_000;
    private static final long SLA_P99_MS = 2_000L;
    private static final double SLA_MAX_ERROR_RATE = 5.0;

    // ── Containers ────────────────────────────────────────────────────────

    @Container
    @AppContainer
    static GenericContainer<?> app =
            new GenericContainer<>("macstab/user-service:latest")
                    .withExposedPorts(8080)
                    .withEnv("SPRING_REDIS_HOST", "sla-redis")
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

    private String appBaseUrl;

    @BeforeEach
    void setUp() {
        appBaseUrl = "http://" + app.getHost() + ":" + app.getMappedPort(8080);
    }

    // ── Test ─────────────────────────────────────────────────────────────

    /**
     * Fires 1 000 concurrent requests under 30% {@code ECONNRESET} chaos and proves the SLA.
     *
     * <p>Virtual threads (JDK 21 {@link Executors#newVirtualThreadPerTaskExecutor()}) allow 1 000
     * genuine concurrent requests without the overhead of 1 000 platform threads. Each virtual
     * thread makes a single HTTP GET and records:
     * <ul>
     *   <li>Response latency in milliseconds (measured with {@link System#nanoTime()}).
     *   <li>Whether the response was a success (HTTP 200 or 503) or an error (anything else).
     * </ul>
     *
     * <p>After all 1 000 requests complete, the latencies are sorted and the p50, p95, and p99
     * percentiles are computed. The SLA proof report is printed to stdout.
     */
    @Test
    @CompositeChaosConnectionDrop(toxicity = 0.30)
    void proveThatSlaHoldsUnder30PercentPacketLoss() throws Exception {
        HttpClient httpClient =
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(3))
                        .build();

        List<Long> latenciesMs =
                Collections.synchronizedList(new ArrayList<>(TOTAL_REQUESTS));
        AtomicLong errorCount = new AtomicLong();
        AtomicLong successCount = new AtomicLong();

        List<CompletableFuture<Void>> futures = new ArrayList<>(TOTAL_REQUESTS);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < TOTAL_REQUESTS; i++) {
                CompletableFuture<Void> future =
                        CompletableFuture.runAsync(
                                () -> {
                                    long start = System.nanoTime();
                                    try {
                                        HttpRequest request =
                                                HttpRequest.newBuilder()
                                                        .uri(
                                                                URI.create(
                                                                        appBaseUrl + "/users/1"))
                                                        .GET()
                                                        .timeout(Duration.ofSeconds(10))
                                                        .build();

                                        HttpResponse<String> response =
                                                httpClient.send(
                                                        request,
                                                        HttpResponse.BodyHandlers.ofString());

                                        long elapsedMs =
                                                (System.nanoTime() - start) / 1_000_000L;
                                        latenciesMs.add(elapsedMs);

                                        if (response.statusCode() == 200
                                                || response.statusCode() == 503) {
                                            successCount.incrementAndGet();
                                        } else {
                                            errorCount.incrementAndGet();
                                            log.warn(
                                                    "Unexpected HTTP {}: {}",
                                                    response.statusCode(),
                                                    response.body());
                                        }
                                    } catch (Exception e) {
                                        long elapsedMs =
                                                (System.nanoTime() - start) / 1_000_000L;
                                        latenciesMs.add(elapsedMs);
                                        errorCount.incrementAndGet();
                                        log.debug("Request failed: {}", e.getMessage());
                                    }
                                },
                                executor);

                futures.add(future);
            }

            // Wait for all requests to complete (max 120s for the full batch).
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(120, java.util.concurrent.TimeUnit.SECONDS);
        }

        // ── Compute percentiles ───────────────────────────────────────────
        List<Long> sorted = new ArrayList<>(latenciesMs);
        Collections.sort(sorted);

        long p50 = percentile(sorted, 50);
        long p95 = percentile(sorted, 95);
        long p99 = percentile(sorted, 99);
        long maxLatency = sorted.isEmpty() ? 0L : sorted.get(sorted.size() - 1);

        double errorRate = (double) errorCount.get() / TOTAL_REQUESTS * 100.0;
        boolean slaP99Met = p99 < SLA_P99_MS;
        boolean slaErrorRateMet = errorRate < SLA_MAX_ERROR_RATE;
        boolean slaOverallMet = slaP99Met && slaErrorRateMet;
        String verdict = slaOverallMet ? "SLA MET" : "SLA VIOLATED";

        // ── Print the SLA proof report ────────────────────────────────────
        printSlaReport(p50, p95, p99, maxLatency, errorRate, errorCount.get(), slaOverallMet);

        // ── Assert SLA ────────────────────────────────────────────────────
        assertThat(p99)
                .as(
                        "p99 latency must be below %d ms under 30%% ECONNRESET chaos;"
                                + " measured p99 = %d ms",
                        SLA_P99_MS, p99)
                .isLessThan(SLA_P99_MS);

        assertThat(errorRate)
                .as(
                        "error rate must be below %.1f%% under 30%% ECONNRESET chaos;"
                                + " measured error rate = %.2f%%",
                        SLA_MAX_ERROR_RATE, errorRate)
                .isLessThan(SLA_MAX_ERROR_RATE);
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private static long percentile(List<Long> sortedValues, int pct) {
        if (sortedValues.isEmpty()) {
            return 0L;
        }
        int index = (int) Math.ceil(pct / 100.0 * sortedValues.size()) - 1;
        return sortedValues.get(Math.max(0, Math.min(index, sortedValues.size() - 1)));
    }

    private static void printSlaReport(
            long p50,
            long p95,
            long p99,
            long max,
            double errorRate,
            long errorCount,
            boolean slaMet) {

        String verdictStr = slaMet ? "SLA MET" : "SLA VIOLATED";
        String verdictPad = " ".repeat(Math.max(0, 12 - verdictStr.length()));

        System.out.println();
        System.out.println("  ╔════════════════════════════════════════════════╗");
        System.out.println("  ║           SLA PROOF REPORT                       ║");
        System.out.println("  ║  Chaos: 30% ECONNRESET (CompositeChaosConnDrop)  ║");
        System.out.printf(
                "  ║  Requests: %-4d                                  ║%n",
                TOTAL_REQUESTS);
        System.out.printf(
                "  ║  p50: %4d ms   p95: %4d ms   p99: %4d ms       ║%n",
                p50, p95, p99);
        System.out.printf(
                "  ║  Max: %4d ms                                      ║%n",
                max);
        System.out.printf(
                "  ║  Errors: %d (%.2f%%)                             ║%n",
                errorCount, errorRate);
        System.out.printf(
                "  ║  VERDICT: %-12s%s                          ║%n",
                verdictStr, verdictPad);
        System.out.println("  ╚════════════════════════════════════════════════╝");
        System.out.println();

        // Also log for CI capture.
        log.info(
                "SLA PROOF: p50={}ms p95={}ms p99={}ms max={}ms errors={}({:.2f}%%) VERDICT={}",
                p50, p95, p99, max, errorCount, errorRate, verdictStr);
    }
}
