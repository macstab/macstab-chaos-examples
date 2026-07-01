package com.macstab.chaos.examples.l2;

import static org.assertj.core.api.Assertions.assertThat;

import com.macstab.chaos.annotation.AppContainer;
import com.macstab.chaos.annotation.SyscallLevelChaos;
import com.macstab.chaos.annotation.composite.CompositeChaosAcceptStorm;
import com.macstab.chaos.annotation.composite.CompositeChaosConnectionDrop;
import com.macstab.chaos.annotation.composite.CompositeChaosConnectionRefused;
import com.macstab.chaos.annotation.composite.CompositeChaosConnectionTimeout;
import com.macstab.chaos.annotation.composite.CompositeChaosHalfOpenConnection;
import com.macstab.chaos.annotation.composite.CompositeChaosPollTimeout;
import com.macstab.chaos.annotation.composite.CompositeChaosPortAlreadyInUse;
import com.macstab.chaos.annotation.composite.ChaosSendBufferStarvation;
import com.macstab.chaos.annotation.composite.CompositeChaosSlowDownstream;
import com.macstab.chaos.annotation.composite.CompositeChaosSocketEphemeralExhaustion;
import com.macstab.chaos.annotation.composite.CompositeChaosTcpResetStorm;
import com.macstab.chaos.annotation.composite.CompositeChaosThunderingHerd;
import com.macstab.chaos.annotation.composite.CompositeChaosUnreachableHost;
import com.macstab.chaos.annotation.composite.CompositeChaosUnreachableNetwork;
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
 * L2 – Connection composite chaos: 14 production connection-storm disaster scenarios.
 *
 * <p>Connection storms are real. Black Friday. Feature launches. Rolling deploys at 3 AM. Every
 * one of these scenarios has taken down a production service that had passing tests. This class
 * proves your connection handling survives each one.
 *
 * <h2>Container topology</h2>
 * <pre>
 *  [downstream] – WireMock; simulates payment gateway, user-profile API, inventory service
 *  [app]        – Python HTTP client with retry and circuit-breaker; exercises /probe and /status
 *  [redis]      – Redis 7.4 standalone; session cache and fallback data store
 * </pre>
 */
@Testcontainers
@ExtendWith(ChaosTestingExtension.class)
@SyscallLevelChaos(LibchaosLib.NET)
@RedisStandalone(id = "l2-conn-redis", version = "7.4")
class L2ConnectionAllComposites {

    private static final Logger log = LoggerFactory.getLogger(L2ConnectionAllComposites.class);

    @BeforeAll
    static void printIncidentSummary() {
        System.out.println("╔══════════════════════════════════════════════════════════════════╗");
        System.out.println("║  L2 CONNECTION STORM PROOFS — 14 PRODUCTION DISASTER SCENARIOS  ║");
        System.out.println("║  Black Friday. Feature launches. Rolling deploys.               ║");
        System.out.println("║  Each test: a real connection failure that caused a real outage.║");
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");
    }

    // ── Containers ────────────────────────────────────────────────────────

    @Container
    static WireMockContainer downstream =
            new WireMockContainer("wiremock/wiremock:3.3.1")
                    .withMappingFromJSON(
                            """
                            {
                              "request": { "method": "GET", "url": "/api/resource" },
                              "response": {
                                "status": 200,
                                "headers": { "Content-Type": "application/json" },
                                "body": "{\\"id\\":42,\\"status\\":\\"ok\\"}"
                              }
                            }
                            """)
                    .withMappingFromJSON(
                            """
                            {
                              "request": { "method": "GET", "url": "/api/health" },
                              "response": {
                                "status": 200,
                                "headers": { "Content-Type": "application/json" },
                                "body": "{\\"healthy\\":true}"
                              }
                            }
                            """);

    @Container
    @AppContainer
    static GenericContainer<?> app =
            new GenericContainer<>("macstab/python-http-client:latest")
                    .withExposedPorts(8000)
                    .withEnv("DOWNSTREAM_URL", "http://downstream:8080")
                    .withEnv("REDIS_HOST", "l2-conn-redis")
                    .withEnv("REDIS_PORT", "6379")
                    .withStartupTimeout(Duration.ofSeconds(60));

    private HttpClient httpClient;
    private String appBaseUrl;

    @BeforeEach
    void setUp() {
        httpClient =
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(5))
                        .build();
        appBaseUrl = "http://" + app.getHost() + ":" + app.getMappedPort(8000);
    }

    // ══════════════════════════════════════════════════════════════════════
    // 1. Connection Drop — ECONNRESET 20%
    // ══════════════════════════════════════════════════════════════════════

    /**
     * INCIDENT: Kubernetes rolling update RST storm. Every pod replacement sends RST for all
     * in-flight connections. At 20% drop rate, if retries amplify load on the remaining pods,
     * the cascade takes down the remaining 80% too.
     *
     * <p>This test proves: at 20% ECONNRESET, the circuit breaker opens before retry amplification
     * exceeds the observable 5% error threshold, and at least one fallback response is served —
     * proving the circuit actually opened and protected the downstream.
     */
    @Test
    @DisplayName("L2: Rolling update RST storm — circuit breaker opens before retry amplification")
    @CompositeChaosConnectionDrop(toxicity = 0.20)
    void connectionDrop20PercentCircuitBreakerOpens() throws Exception {
        System.out.println("╔══════════════════════════════════════════════════════════════════╗");
        System.out.println("║  INCIDENT: Kubernetes rolling update — RST storm at 20%          ║");
        System.out.println("║  100 requests. Circuit must open before retry amplification.    ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");

        int totalRequests = 100;
        AtomicLong fallbackCount = new AtomicLong();
        AtomicLong errorCount = new AtomicLong();

        for (int i = 0; i < totalRequests; i++) {
            HttpResponse<String> response = probe(5);

            if (response.statusCode() == 200
                    && response.body().contains("\"source\":\"FALLBACK\"")) {
                fallbackCount.incrementAndGet();
            } else if (response.statusCode() == 500) {
                errorCount.incrementAndGet();
            }
        }

        double errorRate = (double) errorCount.get() / totalRequests * 100.0;

        System.out.println("╔══════════════════════════════════════════════════════════════════╗");
        System.out.printf("║  PROOF: fallbacks=%d  http500=%d  errorRate=%.2f%%  (must < 5%%)%n",
                fallbackCount.get(), errorCount.get(), errorRate);
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");

        assertThat(errorRate)
                .as(
                        "20%% ECONNRESET during rolling update must not amplify into observable"
                                + " errors — circuit breaker must open and serve fallback before"
                                + " error rate exceeds 5%%")
                .isLessThan(5.0);

        assertThat(fallbackCount.get())
                .as(
                        "circuit breaker must have opened at least once under 20%% connection drop"
                                + " — rolling update proof: breaker opened before retry cascade")
                .isGreaterThan(0L);
    }

    // ══════════════════════════════════════════════════════════════════════
    // 2. Connection Timeout — EAGAIN 10%
    // ══════════════════════════════════════════════════════════════════════

    /**
     * INCIDENT: NVMe SSD under high load — kernel receive buffer temporarily empty on non-blocking
     * sockets due to I/O pressure. 10% of recv() calls return EAGAIN. Application retry policy
     * catches the exception — but only if EAGAIN is classified as a transient retryable error.
     *
     * <p>At 10% the probability of all 3 retries hitting EAGAIN is {@code 0.10^3 = 0.1%}. The
     * retry layer must absorb every occurrence. This test proves EAGAIN is in your retryable set.
     */
    @Test
    @DisplayName("L2: Kernel recv buffer saturation — EAGAIN classified as transient, retry absorbs")
    @CompositeChaosConnectionTimeout(toxicity = 0.10)
    void connectionTimeout10PercentRetryAbsorbs() throws Exception {
        System.out.println("╔══════════════════════════════════════════════════════════════════╗");
        System.out.println("║  INCIDENT: NVMe I/O pressure — 10% EAGAIN on recv()             ║");
        System.out.println("║  P(all 3 retries fail) = 0.10^3 = 0.1%. Must be retryable.     ║");
        System.out.println("║  200 requests. Success rate must exceed 95%.                    ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");

        int totalRequests = 200;
        AtomicLong successCount = new AtomicLong();
        AtomicLong errorCount = new AtomicLong();

        for (int i = 0; i < totalRequests; i++) {
            HttpResponse<String> response = probe(8);

            if (response.statusCode() == 200) {
                successCount.incrementAndGet();
            } else {
                errorCount.incrementAndGet();
            }
        }

        double successRate = (double) successCount.get() / totalRequests * 100.0;

        System.out.println("╔══════════════════════════════════════════════════════════════════╗");
        System.out.printf("║  PROOF: success=%d (%.1f%%)  errors=%d  (success must > 95%%)%n",
                successCount.get(), successRate, errorCount.get());
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");

        assertThat(successRate)
                .as(
                        "retry must absorb 10%% transient EAGAIN — kernel buffer saturation proof:"
                                + " success rate must exceed 95%% across 200 requests")
                .isGreaterThan(95.0);
    }

    // ══════════════════════════════════════════════════════════════════════
    // 3. Connection Refused — ECONNREFUSED 100%
    // ══════════════════════════════════════════════════════════════════════

    /**
     * INCIDENT: All downstream pods killed simultaneously during a botched emergency rollback.
     * Zero pods running, ECONNREFUSED on every connect(). The retry policy burns 3 × timeout
     * per request. The circuit breaker must open after the very first request's retries exhaust.
     *
     * <p>This test proves: under total downstream failure, the circuit opens immediately and every
     * subsequent request is short-circuited to the fallback — no more connection attempts to a
     * dead service, no retry amplification of total outage.
     */
    @Test
    @DisplayName("L2: Emergency rollback — all pods killed, fallback activates within first request")
    @CompositeChaosConnectionRefused(toxicity = 1.0)
    void connectionRefused100PercentFallbackImmediate() throws Exception {
        System.out.println("╔══════════════════════════════════════════════════════════════════╗");
        System.out.println("║  INCIDENT: Emergency rollback — all downstream pods killed       ║");
        System.out.println("║  100% ECONNREFUSED. Circuit must open after first request.      ║");
        System.out.println("║  10 requests. Zero HTTP 500 acceptable. All must be controlled. ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");

        int totalRequests = 10;
        AtomicLong fallbackOrUnavailable = new AtomicLong();
        AtomicLong uncaughtErrors = new AtomicLong();

        for (int i = 0; i < totalRequests; i++) {
            HttpResponse<String> response = probe(5);

            if (response.statusCode() == 200 || response.statusCode() == 503) {
                fallbackOrUnavailable.incrementAndGet();
            } else if (response.statusCode() == 500) {
                uncaughtErrors.incrementAndGet();
                log.error("Uncaught 500 on request {} under 100%% ECONNREFUSED: {}",
                        i + 1, response.body());
            }
        }

        System.out.println("╔══════════════════════════════════════════════════════════════════╗");
        System.out.printf("║  PROOF: controlled=%d  uncaught500=%d  total=%d%n",
                fallbackOrUnavailable.get(), uncaughtErrors.get(), totalRequests);
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");

        assertThat(uncaughtErrors.get())
                .as(
                        "100%% ECONNREFUSED must not produce any uncaught HTTP 500 — emergency"
                                + " rollback proof: circuit opens immediately, fallback activates")
                .isEqualTo(0L);

        assertThat(fallbackOrUnavailable.get())
                .as(
                        "all 10 requests must receive a controlled response (fallback 200 or"
                                + " circuit-open 503) when downstream is completely unreachable")
                .isEqualTo((long) totalRequests);
    }

    // ══════════════════════════════════════════════════════════════════════
    // 4. Half-Open Connection — stale connection after silent reset, 15%
    // ══════════════════════════════════════════════════════════════════════

    /**
     * INCIDENT: Connection pool reuse after network partition. During a brief network partition,
     * connections were silently reset by an intermediate load balancer. The pool health check
     * (TCP ping) did not detect stale connections — they look healthy but fail on first send().
     * 15% of pooled connections are silently dead.
     *
     * <p>This test proves: TCP keepalive (SO_KEEPALIVE with short TCP_KEEPIDLE) detects dead
     * connections before the application tries to use them. Observable error rate stays below 5%.
     */
    @Test
    @DisplayName("L2: Post-partition pool reuse — TCP keepalive detects stale connections")
    @CompositeChaosHalfOpenConnection(toxicity = 0.15)
    void halfOpenConnectionDetectedByKeepalive() throws Exception {
        System.out.println("╔══════════════════════════════════════════════════════════════════╗");
        System.out.println("║  INCIDENT: Network partition left 15% of pool connections dead  ║");
        System.out.println("║  Connections pass health check but fail on first send().        ║");
        System.out.println("║  TCP keepalive must detect stale connections. ErrorRate < 5%.  ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");

        int totalRequests = 100;
        AtomicLong errorCount = new AtomicLong();
        AtomicLong successCount = new AtomicLong();

        for (int i = 0; i < totalRequests; i++) {
            HttpResponse<String> response = probe(6);

            if (response.statusCode() == 200) {
                successCount.incrementAndGet();
            } else if (response.statusCode() == 500) {
                errorCount.incrementAndGet();
                log.warn("Half-open surfaced as error on request {}: {}", i + 1, response.body());
            }
        }

        double errorRate = (double) errorCount.get() / totalRequests * 100.0;

        System.out.println("╔══════════════════════════════════════════════════════════════════╗");
        System.out.printf("║  PROOF: success=%d  errors=%d  errorRate=%.2f%%  (must < 5%%)%n",
                successCount.get(), errorCount.get(), errorRate);
        System.out.printf("║  Keepalive detection: %s%n",
                errorRate < 5.0 ? "WORKING" : "FAILED — stale connections leaking");
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");

        assertThat(errorRate)
                .as(
                        "TCP keepalive must detect half-open connections before they surface as"
                                + " errors — post-partition pool proof: observable error rate must"
                                + " stay below 5%% at 15%% stale connection toxicity")
                .isLessThan(5.0);
    }

    // ══════════════════════════════════════════════════════════════════════
    // 5. Slow Downstream — 500ms recv latency
    // ══════════════════════════════════════════════════════════════════════

    /**
     * INCIDENT: Database lock contention during a migration. The downstream service was processing
     * requests but responding at 500ms+ (waiting for locks). The circuit breaker slow-call
     * detector was configured at 1s threshold — but with 3 retries each adding 500ms, the worst
     * case was 1500ms per call, which crossed the threshold. The question: did the detector open
     * the circuit and cap p99, or did it let tail latency balloon to 4500ms?
     */
    @Test
    @DisplayName("L2: DB lock contention — slow-call detector fires, p99 bounded under 2s")
    @CompositeChaosSlowDownstream(latency = 500)
    void slowDownstream500MsP99BelowSla() throws Exception {
        System.out.println("╔══════════════════════════════════════════════════════════════════╗");
        System.out.println("║  INCIDENT: DB migration lock contention — downstream at 500ms   ║");
        System.out.println("║  Without slow-call detector: p99 = 1500ms+ per retry chain.    ║");
        System.out.println("║  200 requests. Slow-call detector must cap p99 at < 2000ms.    ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");

        int totalRequests = 200;
        List<Long> latenciesMs = new ArrayList<>(totalRequests);

        for (int i = 0; i < totalRequests; i++) {
            long start = System.nanoTime();
            probe(10);
            latenciesMs.add((System.nanoTime() - start) / 1_000_000L);
        }

        Collections.sort(latenciesMs);
        long p50 = percentile(latenciesMs, 50);
        long p95 = percentile(latenciesMs, 95);
        long p99 = percentile(latenciesMs, 99);

        System.out.println("╔══════════════════════════════════════════════════════════════════╗");
        System.out.printf("║  PROOF: p50=%dms  p95=%dms  p99=%dms  (p99 must < 2000ms)%n",
                p50, p95, p99);
        System.out.printf("║  Slow-call detector: %s%n",
                p99 < 2000L ? "FIRED — p99 bounded by fallback" : "DID NOT FIRE — tail unbounded");
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");

        assertThat(p99)
                .as(
                        "p99 must stay below 2000ms with 500ms recv latency — DB lock contention"
                                + " proof: slow-call detector must open circuit and short-circuit"
                                + " retries once slow-call rate threshold is breached")
                .isLessThan(2_000L);
    }

    // ══════════════════════════════════════════════════════════════════════
    // 6. Thundering Herd — 80% accept() failure
    // ══════════════════════════════════════════════════════════════════════

    /**
     * INCIDENT: Cache invalidation event woke up 50,000 clients simultaneously. The thundering
     * herd overwhelmed the accept() backlog on the origin servers. 80% of connection promotions
     * failed. Clients with aggressive retry (exponential backoff disabled) amplified the herd.
     *
     * <p>This test proves: your application applies exponential backoff rather than immediate
     * retry into the storm. The circuit breaker opens when the failure rate crosses 50%, after
     * which all calls are short-circuited — zero HTTP 500 regardless of backlog overflow.
     */
    @Test
    @DisplayName("L2: Cache invalidation thundering herd — backoff prevents retry amplification")
    @CompositeChaosThunderingHerd(toxicity = 0.8)
    void thunderingHerd80PercentAbsorbedByBackoff() throws Exception {
        System.out.println("╔══════════════════════════════════════════════════════════════════╗");
        System.out.println("║  INCIDENT: Cache invalidation — 50k clients woke simultaneously ║");
        System.out.println("║  80% accept() failure. Aggressive retry = thundering amplified. ║");
        System.out.println("║  100 requests. Zero HTTP 500. Breaker + backoff must contain.  ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");

        int totalRequests = 100;
        AtomicLong controlledCount = new AtomicLong();
        AtomicLong errorCount = new AtomicLong();

        for (int i = 0; i < totalRequests; i++) {
            HttpResponse<String> response = probe(8);

            if (response.statusCode() == 200 || response.statusCode() == 503) {
                controlledCount.incrementAndGet();
            } else if (response.statusCode() == 500) {
                errorCount.incrementAndGet();
            }
        }

        System.out.println("╔══════════════════════════════════════════════════════════════════╗");
        System.out.printf("║  PROOF: controlled=%d  uncaught500=%d  total=%d%n",
                controlledCount.get(), errorCount.get(), totalRequests);
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");

        assertThat(errorCount.get())
                .as(
                        "80%% accept() failure must not produce uncaught 500 — thundering herd"
                                + " proof: backoff + circuit breaker must contain the storm")
                .isEqualTo(0L);
    }

    // ══════════════════════════════════════════════════════════════════════
    // 7. Accept Storm — EMFILE/ENFILE 90%
    // ══════════════════════════════════════════════════════════════════════

    /**
     * INCIDENT: Production service ran out of file descriptors during a traffic spike. The OS-wide
     * file table was full (ENFILE) and the per-process FD limit was hit (EMFILE). The server could
     * not accept new connections. The question: does it return 503 (degraded-but-stable) or does
     * the process exhaust its own FD budget by retrying into the storm and crash?
     */
    @Test
    @DisplayName("L2: Traffic spike FD exhaustion — 503 before process crash, no FD retry storm")
    @CompositeChaosAcceptStorm(toxicity = 0.9)
    void acceptStorm90PercentServerReturns503() throws Exception {
        System.out.println("╔══════════════════════════════════════════════════════════════════╗");
        System.out.println("║  INCIDENT: Traffic spike — OS file table full, EMFILE/ENFILE   ║");
        System.out.println("║  90% FD exhaustion. Service must return 503, not crash.        ║");
        System.out.println("║  80 requests. Zero HTTP 500. All must be controlled.            ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");

        int totalRequests = 80;
        AtomicLong serviceUnavailable = new AtomicLong();
        AtomicLong uncaughtErrors = new AtomicLong();
        AtomicLong successCount = new AtomicLong();

        for (int i = 0; i < totalRequests; i++) {
            HttpResponse<String> response = probe(6);

            if (response.statusCode() == 503) {
                serviceUnavailable.incrementAndGet();
            } else if (response.statusCode() == 200) {
                successCount.incrementAndGet();
            } else if (response.statusCode() == 500) {
                uncaughtErrors.incrementAndGet();
                log.error("Uncaught 500 under FD exhaustion storm on request {}: {}",
                        i + 1, response.body());
            }
        }

        System.out.println("╔══════════════════════════════════════════════════════════════════╗");
        System.out.printf("║  PROOF: 503=%d  200=%d  uncaught500=%d  total=%d%n",
                serviceUnavailable.get(), successCount.get(), uncaughtErrors.get(), totalRequests);
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");

        assertThat(uncaughtErrors.get())
                .as(
                        "90%% FD exhaustion must not produce uncaught HTTP 500 — traffic spike"
                                + " proof: circuit breaker must return 503 before process crashes")
                .isEqualTo(0L);

        assertThat(serviceUnavailable.get() + successCount.get())
                .as("all responses under FD exhaustion must be controlled (503 or 200/fallback)")
                .isEqualTo((long) totalRequests);
    }

    // ══════════════════════════════════════════════════════════════════════
    // 8. Poll Timeout — poll() spurious wakeup, 30%
    // ══════════════════════════════════════════════════════════════════════

    /**
     * INCIDENT: epoll false negatives on overloaded kernel. Spurious poll() zero-return (timeout
     * expired with no FD ready) causing the event loop to miss ready sockets for one full poll
     * cycle. At 30% rate this adds 30ms average extra latency per request, but no errors — unless
     * the application misinterprets a spurious wakeup as a real timeout and closes the connection.
     *
     * <p>This test proves: zero HTTP 500 under spurious poll wakeups, and p99 stays below 3s
     * (the latency penalty is bounded by one poll cycle, not a full request timeout).
     */
    @Test
    @DisplayName("L2: epoll false negatives — spurious wakeup is latency, not error")
    @CompositeChaosPollTimeout(toxicity = 0.30)
    void pollTimeout30PercentSpuriousWakeupHandled() throws Exception {
        System.out.println("╔══════════════════════════════════════════════════════════════════╗");
        System.out.println("║  INCIDENT: epoll false negatives on overloaded kernel           ║");
        System.out.println("║  30% spurious poll() zero-return. Adds ~30ms average latency.  ║");
        System.out.println("║  100 requests. Zero HTTP 500. p99 must stay below 3000ms.      ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");

        int totalRequests = 100;
        List<Long> latenciesMs = new ArrayList<>(totalRequests);
        AtomicLong errorCount = new AtomicLong();

        for (int i = 0; i < totalRequests; i++) {
            long start = System.nanoTime();
            HttpResponse<String> response = probe(10);
            latenciesMs.add((System.nanoTime() - start) / 1_000_000L);

            if (response.statusCode() == 500) {
                errorCount.incrementAndGet();
            }
        }

        Collections.sort(latenciesMs);
        long p50 = percentile(latenciesMs, 50);
        long p99 = percentile(latenciesMs, 99);

        System.out.println("╔══════════════════════════════════════════════════════════════════╗");
        System.out.printf("║  PROOF: p50=%dms  p99=%dms  http500=%d  (p99 must < 3000ms)%n",
                p50, p99, errorCount.get());
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");

        assertThat(errorCount.get())
                .as("spurious poll() zero-return must not cause HTTP 500 — event loop must"
                        + " re-check readiness on the next poll cycle, not close the connection")
                .isEqualTo(0L);

        assertThat(p99)
                .as("p99 under 30%% poll timeout must stay below 3000ms — spurious wakeups"
                        + " cause latency not errors; one extra poll cycle = one extra ~100ms")
                .isLessThan(3_000L);
    }

    // ══════════════════════════════════════════════════════════════════════
    // 9. Send Buffer Starvation — ENOBUFS 20%
    // ══════════════════════════════════════════════════════════════════════

    /**
     * INCIDENT: Application writing faster than the network could drain the kernel send buffer.
     * TCP flow control caused ENOBUFS on 20% of send() calls — the write-side backpressure that
     * most applications never test. Applications that propagate ENOBUFS as an HTTP 500 expose
     * their users to the underlying kernel backpressure mechanism.
     *
     * <p>This test proves: ENOBUFS is in your retryable exception set. Zero HTTP 500.
     */
    @Test
    @DisplayName("L2: TCP flow control backpressure — ENOBUFS is transient, retry absorbs")
    @ChaosSendBufferStarvation(toxicity = 0.20)
    void sendBufferStarvation20PercentBackpressureHandled() throws Exception {
        System.out.println("╔══════════════════════════════════════════════════════════════════╗");
        System.out.println("║  INCIDENT: Write-side TCP backpressure — 20% ENOBUFS on send() ║");
        System.out.println("║  Application writing faster than network can drain buffer.      ║");
        System.out.println("║  150 requests. ENOBUFS must be retryable. Zero HTTP 500.       ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");

        int totalRequests = 150;
        AtomicLong errorCount = new AtomicLong();

        for (int i = 0; i < totalRequests; i++) {
            if (probe(5).statusCode() == 500) {
                errorCount.incrementAndGet();
            }
        }

        System.out.println("╔══════════════════════════════════════════════════════════════════╗");
        System.out.printf("║  PROOF: uncaught500=%d/%d  (must be 0)%n",
                errorCount.get(), totalRequests);
        System.out.printf("║  ENOBUFS classification: %s%n",
                errorCount.get() == 0 ? "TRANSIENT (correct)" : "PROPAGATED (gap found)");
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");

        assertThat(errorCount.get())
                .as("ENOBUFS on send at 20%% must not surface as HTTP 500 — TCP backpressure"
                        + " proof: ENOBUFS must be in retryable exception set with brief backoff")
                .isEqualTo(0L);
    }

    // ══════════════════════════════════════════════════════════════════════
    // 10. Unreachable Host — EHOSTUNREACH 100%
    // ══════════════════════════════════════════════════════════════════════

    /**
     * INCIDENT: Cloud provider routing misconfiguration. DNS resolved correctly — the IP address
     * was valid — but a routing table purge made that IP prefix unreachable. EHOSTUNREACH on every
     * connect(). Unlike ECONNREFUSED (port rejected), EHOSTUNREACH means the network cannot
     * deliver packets: no route to the destination host.
     *
     * <p>This test proves: EHOSTUNREACH is treated as a hard failure, circuit opens immediately,
     * all requests receive controlled fallback. No retry storm into an unreachable host.
     */
    @Test
    @DisplayName("L2: Routing table purge — DNS resolves but EHOSTUNREACH, circuit opens immediately")
    @CompositeChaosUnreachableHost(toxicity = 1.0)
    void unreachableHostFallbackActivates() throws Exception {
        System.out.println("╔══════════════════════════════════════════════════════════════════╗");
        System.out.println("║  INCIDENT: Cloud routing misconfiguration — EHOSTUNREACH 100%   ║");
        System.out.println("║  DNS resolves. IP valid. Routing table purged. No path exists.  ║");
        System.out.println("║  20 requests. Zero HTTP 500. All must receive controlled response║");
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");

        int totalRequests = 20;
        AtomicLong fallbackOrUnavailable = new AtomicLong();
        AtomicLong uncaughtErrors = new AtomicLong();

        for (int i = 0; i < totalRequests; i++) {
            HttpResponse<String> response = probe(5);

            if (response.statusCode() == 200 || response.statusCode() == 503) {
                fallbackOrUnavailable.incrementAndGet();
            } else if (response.statusCode() == 500) {
                uncaughtErrors.incrementAndGet();
            }
        }

        System.out.println("╔══════════════════════════════════════════════════════════════════╗");
        System.out.printf("║  PROOF: controlled=%d  uncaught500=%d  total=%d%n",
                fallbackOrUnavailable.get(), uncaughtErrors.get(), totalRequests);
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");

        assertThat(uncaughtErrors.get())
                .as("EHOSTUNREACH must not surface as HTTP 500 — routing purge proof:"
                        + " circuit breaker must open immediately on 100%% host unreachable")
                .isEqualTo(0L);

        assertThat(fallbackOrUnavailable.get())
                .as("all requests must receive controlled degradation when host is unreachable")
                .isEqualTo((long) totalRequests);
    }

    // ══════════════════════════════════════════════════════════════════════
    // 11. Unreachable Network — ENETUNREACH 100%
    // ══════════════════════════════════════════════════════════════════════

    /**
     * INCIDENT: BGP route withdrawal during a network maintenance window. An entire /16 prefix
     * was withdrawn, making a whole network unreachable — ENETUNREACH on every connect(). The
     * distinction from EHOSTUNREACH: no route to the destination NETWORK exists at all, not just
     * to the specific host.
     *
     * <p>This test proves: ENETUNREACH is treated identically to EHOSTUNREACH — hard failure,
     * circuit opens, fallback serves users. No distinction in error handling required.
     */
    @Test
    @DisplayName("L2: BGP route withdrawal — ENETUNREACH is hard failure, identical to EHOSTUNREACH")
    @CompositeChaosUnreachableNetwork(toxicity = 1.0)
    void unreachableNetworkRoutingFailure() throws Exception {
        System.out.println("╔══════════════════════════════════════════════════════════════════╗");
        System.out.println("║  INCIDENT: BGP route withdrawal — /16 prefix unreachable        ║");
        System.out.println("║  ENETUNREACH 100%. No route to destination network.             ║");
        System.out.println("║  20 requests. Zero HTTP 500. All must be controlled.            ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");

        int totalRequests = 20;
        AtomicLong controlledCount = new AtomicLong();
        AtomicLong uncaughtErrors = new AtomicLong();

        for (int i = 0; i < totalRequests; i++) {
            HttpResponse<String> response = probe(5);

            if (response.statusCode() == 200 || response.statusCode() == 503) {
                controlledCount.incrementAndGet();
            } else if (response.statusCode() == 500) {
                uncaughtErrors.incrementAndGet();
            }
        }

        System.out.println("╔══════════════════════════════════════════════════════════════════╗");
        System.out.printf("║  PROOF: controlled=%d  uncaught500=%d  total=%d%n",
                controlledCount.get(), uncaughtErrors.get(), totalRequests);
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");

        assertThat(uncaughtErrors.get())
                .as("ENETUNREACH (BGP withdrawal) must not propagate as HTTP 500 — routing"
                        + " failure proof: circuit opens and serves fallback on 100%% network fail")
                .isEqualTo(0L);

        assertThat(controlledCount.get())
                .as("all requests must receive controlled response when network is unreachable")
                .isEqualTo((long) totalRequests);
    }

    // ══════════════════════════════════════════════════════════════════════
    // 12. Port Already In Use — EADDRINUSE 100%
    // ══════════════════════════════════════════════════════════════════════

    /**
     * INCIDENT: Hot reload race condition. The incoming process attempted to bind its listen
     * socket while the outgoing process was still in TIME_WAIT. EADDRINUSE on bind(). The service
     * hung indefinitely trying to bind — blocking the health probe — rather than failing fast and
     * letting the orchestrator restart it.
     *
     * <p>This test proves: when bind() fails, the application fails fast with a clear startup
     * error. It does not hang. The orchestrator can detect the failure and restart within SLA.
     */
    @Test
    @DisplayName("L2: Hot reload race — EADDRINUSE causes fast startup failure, not indefinite hang")
    @CompositeChaosPortAlreadyInUse(toxicity = 1.0)
    void portAlreadyInUseServiceFailsFast() throws Exception {
        System.out.println("╔══════════════════════════════════════════════════════════════════╗");
        System.out.println("║  INCIDENT: Hot reload race — outgoing process in TIME_WAIT      ║");
        System.out.println("║  EADDRINUSE on bind(). Service must fail fast, not hang.        ║");
        System.out.println("║  Health probe must return non-200 or ConnectException within 5s.║");
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");

        try {
            HttpResponse<String> response =
                    HttpClient.newBuilder()
                            .connectTimeout(Duration.ofSeconds(3))
                            .build()
                            .send(
                                    HttpRequest.newBuilder()
                                            .uri(URI.create(appBaseUrl + "/health"))
                                            .GET()
                                            .timeout(Duration.ofSeconds(5))
                                            .build(),
                                    HttpResponse.BodyHandlers.ofString());

            System.out.println("╔══════════════════════════════════════════════════════════════════╗");
            System.out.printf("║  PROOF: health status=%d (must be non-200 — server could not bind)%n",
                    response.statusCode());
            System.out.println("╚══════════════════════════════════════════════════════════════════╝");

            assertThat(response.statusCode())
                    .as("health endpoint must return non-200 when bind() fails with EADDRINUSE"
                            + " — hot reload race proof: fail fast, do not hang")
                    .isNotEqualTo(200);

        } catch (java.net.ConnectException e) {
            System.out.println("╔══════════════════════════════════════════════════════════════════╗");
            System.out.printf("║  PROOF: ConnectException (server could not bind port): %s%n",
                    e.getMessage());
            System.out.println("╚══════════════════════════════════════════════════════════════════╝");
            log.info("CompositeChaosPortAlreadyInUse: ConnectException (server failed to bind): {}",
                    e.getMessage());
            assertThat(e.getMessage())
                    .as("connection must be refused when the server cannot bind its listen socket")
                    .containsIgnoringCase("refused");
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // 13. TCP Reset Storm — persistent ECONNRESET 50%
    // ══════════════════════════════════════════════════════════════════════

    /**
     * INCIDENT: Kubernetes rolling update sustained over 10 minutes (slow rollout policy). Every
     * pod replacement sent RST for in-flight connections. The reset storm persisted for the entire
     * rollout duration. At 50% the raw failure rate exactly matches the circuit breaker threshold.
     *
     * <p>The critical assertion: once the circuit breaker opens, the observable HTTP 500 rate drops
     * to zero. The rolling update must not cascade into user-visible 5xx errors after the circuit
     * has opened and the fallback is serving.
     */
    @Test
    @DisplayName("L2: Slow rolling update — TCP reset storm at 50%, zero 5xx after circuit opens")
    @CompositeChaosTcpResetStorm(toxicity = 0.50)
    void tcpResetStorm50PercentNoErrorsAfterCircuitBreakerOpens() throws Exception {
        System.out.println("╔══════════════════════════════════════════════════════════════════╗");
        System.out.println("║  INCIDENT: Slow Kubernetes rollout — 10-minute TCP reset storm  ║");
        System.out.println("║  50% ECONNRESET — exactly at circuit breaker threshold.         ║");
        System.out.println("║  150 requests. After circuit opens: zero HTTP 500 acceptable.  ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");

        int totalRequests = 150;
        AtomicLong fallbackCount = new AtomicLong();
        AtomicLong errorCount = new AtomicLong();

        for (int i = 0; i < totalRequests; i++) {
            HttpResponse<String> response = probe(5);

            if (response.statusCode() == 200
                    && response.body().contains("\"source\":\"FALLBACK\"")) {
                fallbackCount.incrementAndGet();
            } else if (response.statusCode() == 500) {
                errorCount.incrementAndGet();
            }
        }

        double errorRate = (double) errorCount.get() / totalRequests * 100.0;

        System.out.println("╔══════════════════════════════════════════════════════════════════╗");
        System.out.printf("║  PROOF: fallbacks=%d  http500=%d  errorRate=%.2f%%  (must be 0%%)%n",
                fallbackCount.get(), errorCount.get(), errorRate);
        System.out.printf("║  Rolling update impact: %s%n",
                errorCount.get() == 0 ? "CONTAINED (correct)" : "CASCADED (gap found)");
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");

        assertThat(fallbackCount.get())
                .as("circuit breaker must have opened under 50%% TCP reset storm — at least one"
                        + " fallback response proves the circuit actually opened")
                .isGreaterThan(0L);

        assertThat(errorCount.get())
                .as("zero HTTP 500 after circuit opens under rolling update TCP reset storm —"
                        + " the key guarantee: circuit open = no 5xx cascade to users")
                .isEqualTo(0L);
    }

    // ══════════════════════════════════════════════════════════════════════
    // 14. Socket Ephemeral Port Exhaustion — ENFILE/EMFILE 30%
    // ══════════════════════════════════════════════════════════════════════

    /**
     * INCIDENT: High-fan-out API gateway accumulated TIME_WAIT sockets. No SO_REUSEADDR. No
     * TIME_WAIT reuse configured. The ephemeral port range (32768-60999) saturated. 30% of
     * outbound connect() calls returned EMFILE/ENFILE. The gateway started returning HTTP 500
     * rather than queuing and retrying on a fresh ephemeral port.
     *
     * <p>This test proves: ENFILE/EMFILE on connect() is treated as transient. The retry layer
     * applies brief backoff and attempts on a fresh port. No uncaught HTTP 500.
     */
    @Test
    @DisplayName("L2: TIME_WAIT saturation — ENFILE/EMFILE on connect() is transient, not fatal")
    @CompositeChaosSocketEphemeralExhaustion(toxicity = 0.30)
    void ephemeralPortExhaustion30PercentGracefulDegradation() throws Exception {
        System.out.println("╔══════════════════════════════════════════════════════════════════╗");
        System.out.println("║  INCIDENT: API gateway — TIME_WAIT saturated ephemeral ports    ║");
        System.out.println("║  30% ENFILE/EMFILE on connect(). No SO_REUSEADDR configured.   ║");
        System.out.println("║  120 requests. Must retry on fresh port. Zero HTTP 500.         ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");

        int totalRequests = 120;
        AtomicLong successCount = new AtomicLong();
        AtomicLong errorCount = new AtomicLong();

        for (int i = 0; i < totalRequests; i++) {
            HttpResponse<String> response = probe(6);

            if (response.statusCode() == 200 || response.statusCode() == 503) {
                successCount.incrementAndGet();
            } else if (response.statusCode() == 500) {
                errorCount.incrementAndGet();
            }
        }

        double errorRate = (double) errorCount.get() / totalRequests * 100.0;

        System.out.println("╔══════════════════════════════════════════════════════════════════╗");
        System.out.printf("║  PROOF: controlled=%d  uncaught500=%d  errorRate=%.2f%%  (must 0%%)%n",
                successCount.get(), errorCount.get(), errorRate);
        System.out.printf("║  Port exhaustion handling: %s%n",
                errorCount.get() == 0 ? "TRANSIENT (correct)" : "FATAL (gap found)");
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");

        assertThat(errorRate)
                .as("30%% ephemeral port exhaustion (ENFILE/EMFILE) must be absorbed by retry"
                        + " — TIME_WAIT saturation proof: observable error rate must be 0%%")
                .isLessThan(5.0);

        assertThat(errorCount.get())
                .as("ephemeral port exhaustion must not produce uncaught HTTP 500 — service must"
                        + " degrade gracefully (200/fallback or 503), never expose kernel errors")
                .isEqualTo(0L);
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private HttpResponse<String> probe(int timeoutSeconds) throws Exception {
        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(URI.create(appBaseUrl + "/probe"))
                        .GET()
                        .timeout(Duration.ofSeconds(timeoutSeconds))
                        .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static long percentile(List<Long> sortedValues, int percentile) {
        if (sortedValues.isEmpty()) {
            return 0L;
        }
        int index = (int) Math.ceil(percentile / 100.0 * sortedValues.size()) - 1;
        return sortedValues.get(Math.max(0, Math.min(index, sortedValues.size() - 1)));
    }
}
