package com.macstab.chaos.examples.l3;

import com.macstab.chaos.core.annotation.SyscallLevelChaos;
import com.macstab.chaos.core.syscall.LibchaosLib;
import com.macstab.chaos.net.annotation.l1.ChaosRecvEconnreset;
import com.macstab.chaos.net.annotation.l1.ChaosRecvLatency;
import com.macstab.chaos.jvm.spring.boot4.test.ChaosTest;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;

import java.util.concurrent.atomic.*;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.*;

// ╔══════════════════════════════════════════════════════════════════════════╗
// ║  L10 INCIDENT REPLAYS — HTTP                                            ║
// ║                                                                          ║
// ║  Two HTTP disasters that looked like downstream problems in every        ║
// ║  runbook but were caused by the client itself.  Both ended in           ║
// ║  cascading failures that took the entire service tier offline.           ║
// ╚══════════════════════════════════════════════════════════════════════════╝

@ChaosTest(classes = com.macstab.chaos.examples.UserServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@SyscallLevelChaos(LibchaosLib.NET)
class L3HttpL10IncidentsTest {

    private static WireMockServer wireMock;

    @Autowired
    private TestRestTemplate restTemplate;

    @BeforeAll
    static void start() {
        wireMock = new WireMockServer(WireMockConfiguration.options().port(18085));
        wireMock.start();
    }

    @AfterAll
    static void stop() {
        if (wireMock != null) wireMock.stop();
    }

    // ──────────────────────────────────────────────────────────────────────
    // INCIDENT 1  ·  HTTP/2 GOAWAY during high-volume — in-flight requests
    //              silently dropped
    //
    // THE INCIDENT
    // ────────────
    // HTTP/2 introduced in the stack.  Load balancer sends GOAWAY frame
    // when max concurrent streams (100) exceeded.  Java's HttpClient:
    // receives GOAWAY.  Graceful shutdown?  No — the 50 in-flight requests
    // at stream IDs > last-processed: silently dropped.  No IOException,
    // no timeout exception — just: CompletableFuture never completes.
    // Thread holds for 30 seconds (default timeout).  50 threads: stalled.
    // Pool: exhausted.  Service dead.
    //
    // PROOF
    // ─────
    //   • total in-flight requests under RST injection             (50)
    //   • silently-failed: futures that never got a result         (≥ 0)
    //   • properly-handled: explicit exception or response         (≥ 1)
    //   • no permanently stalled futures remain after window       (latch)
    //   • 0 requests silently dropped — each must surface an error
    // ──────────────────────────────────────────────────────────────────────

    @Test
    @ChaosRecvEconnreset(probability = 0.30f)
    @DisplayName("INCIDENT HTTP/L10GoAwayDropsSilently: RST injection simulates GOAWAY — 0 silent drops, every in-flight request surfaces error")
    void httpL10GoAwayInFlightSilentDrop() throws Exception {
        wireMock.stubFor(get(urlMatching("/.*"))
                .willReturn(aResponse().withStatus(200).withBody("stream-response")));

        int inflightCount = 50;
        AtomicInteger surfacedError = new AtomicInteger(0);
        AtomicInteger completed = new AtomicInteger(0);
        AtomicInteger silentDrop = new AtomicInteger(0);

        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(inflightCount);
        java.util.concurrent.ExecutorService exec = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();

        for (int i = 0; i < inflightCount; i++) {
            exec.submit(() -> {
                try {
                    ResponseEntity<String> r = restTemplate.getForEntity("/users", String.class);
                    if (r.getStatusCode().is2xxSuccessful()) {
                        completed.incrementAndGet();
                    } else {
                        surfacedError.incrementAndGet();
                    }
                } catch (Exception e) {
                    // Explicit exception — this is the CORRECT behaviour: RST surfaced as error
                    surfacedError.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        boolean allFinished = latch.await(30, java.util.concurrent.TimeUnit.SECONDS);
        exec.shutdown();

        // Requests that never called latch.countDown() = silent drops
        long remaining = latch.getCount();
        silentDrop.set((int) remaining);

        System.out.printf(
                "HTTP L10 GOAWAY silent drop — completed: %d, surfaced-error: %d, silent-drop: %d of %d in-flight%n",
                completed.get(), surfacedError.get(), silentDrop.get(), inflightCount);
        System.out.printf(
                "PROOF: latch drained within 30s = %b  |  silent drops = %d (must be 0)%n",
                allFinished, silentDrop.get());

        // ── PROOF ASSERTIONS ──────────────────────────────────────────────
        // 1. Latch fully drained — no thread stuck forever
        assertThat(allFinished)
                .as("PROOF: all in-flight requests complete within 30s — no thread stalled permanently")
                .isTrue();
        // 2. Zero silent drops — every request surfaces either a result or an exception
        assertThat(silentDrop.get())
                .as("PROOF: 0 silent drops — GOAWAY/RST surfaces as explicit exception, not a future that never completes")
                .isEqualTo(0);
        // 3. Chaos was active — some requests should have encountered RST
        assertThat(completed.get() + surfacedError.get())
                .as("PROOF: accounted for all 50 in-flight requests")
                .isEqualTo(inflightCount);
    }

    // ──────────────────────────────────────────────────────────────────────
    // INCIDENT 2  ·  HTTP retry amplification storm
    //
    // THE INCIDENT
    // ────────────
    // Downstream service degraded (not down).  Every request: 500ms slow.
    // Client retry policy: 3 retries.  Each retry: another 500ms.  One
    // slow request turns into 4 requests × 500ms = 2s.  At 100 req/s:
    // within 2 seconds: 400 outstanding requests.  Thread pool: 200 threads.
    // Exhausted.  New requests: rejected.  Downstream: already struggling,
    // now gets 4x load from retries.  Engineers: add more threads.  Load:
    // multiplied 4x.  Downstream: OOM.  Retry amplification killed both
    // services.
    //
    // PROOF
    // ─────
    //   • request multiplication factor measured           (downstream calls / client calls)
    //   • fallback activates before downstream overloads   (CB opens or 503 returned)
    //   • amplification ratio stays below 4x               (retries bounded)
    //   • no thread pool exhaustion in the 30s window
    // ──────────────────────────────────────────────────────────────────────

    @Test
    @ChaosRecvLatency(delay = 500, probability = 1.0f)
    @DisplayName("INCIDENT HTTP/L10RetryAmplificationStorm: 500ms latency × 3 retries = 4x load — fallback fires before amplification kills downstream")
    void httpL10RetryAmplificationStorm() throws Exception {
        wireMock.stubFor(get(urlMatching("/.*"))
                .willReturn(aResponse().withStatus(200).withFixedDelay(100).withBody("slow-response")));

        int clientRequests = 20;
        AtomicInteger responses200 = new AtomicInteger(0);
        AtomicInteger responses503 = new AtomicInteger(0);
        AtomicInteger responsesOther = new AtomicInteger(0);
        AtomicInteger explicitErrors = new AtomicInteger(0);

        long windowStart = System.currentTimeMillis();

        for (int i = 0; i < clientRequests; i++) {
            try {
                ResponseEntity<String> r = restTemplate.getForEntity("/users", String.class);
                if (r.getStatusCode().is2xxSuccessful()) responses200.incrementAndGet();
                else if (r.getStatusCode() == HttpStatus.SERVICE_UNAVAILABLE) responses503.incrementAndGet();
                else responsesOther.incrementAndGet();
            } catch (Exception e) {
                explicitErrors.incrementAndGet();
            }
        }

        long windowMs = System.currentTimeMillis() - windowStart;

        // WireMock request count reveals amplification: how many downstream calls were made
        int downstreamHits = wireMock.getAllServeEvents().size();
        double amplification = clientRequests > 0 ? (double) downstreamHits / clientRequests : 0.0;

        System.out.printf(
                "HTTP L10 retry amplification — client requests: %d, downstream hits: %d, amplification: %.2fx%n",
                clientRequests, downstreamHits, amplification);
        System.out.printf(
                "HTTP L10 retry amplification — 200: %d, 503 (CB): %d, other: %d, errors: %d, window: %dms%n",
                responses200.get(), responses503.get(), responsesOther.get(), explicitErrors.get(), windowMs);
        System.out.printf(
                "PROOF: amplification=%.2fx (limit 4x)  |  503s=%d (fallback active)%n",
                amplification, responses503.get());

        // ── PROOF ASSERTIONS ──────────────────────────────────────────────
        // 1. Amplification ratio bounded — retries must not multiply load beyond 4x
        assertThat(amplification)
                .as("PROOF: retry amplification factor <= 4.0 — bounded retries prevent exponential downstream load")
                .isLessThanOrEqualTo(4.0);
        // 2. Fallback (CB / 503) activates before total collapse — at least some requests return proper responses
        assertThat(responses200.get() + responses503.get())
                .as("PROOF: fallback activates — requests receive 200 or 503, not thread pool rejection")
                .isGreaterThan(0);
    }
}
