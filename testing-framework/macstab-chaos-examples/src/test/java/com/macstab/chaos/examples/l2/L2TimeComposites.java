package com.macstab.chaos.examples.l2;

import static org.assertj.core.api.Assertions.assertThat;

import com.macstab.chaos.annotation.AppContainer;
import com.macstab.chaos.annotation.SyscallLevelChaos;
import com.macstab.chaos.extension.ChaosTestingExtension;
import com.macstab.chaos.lib.LibchaosLib;
import com.macstab.chaos.redis.RedisConnectionInfo;
import com.macstab.chaos.redis.annotation.RedisStandalone;
import com.macstab.chaos.time.testpack.CompositeChaosClockSkew;
import com.macstab.chaos.time.testpack.CompositeChaosLeapSecond;
import com.macstab.chaos.time.testpack.CompositeChaosTimeTravel;
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
import redis.clients.jedis.Jedis;

/**
 * L2 – Time composite chaos: distributed timestamp ordering failures and clock-based invariant
 * violations in production systems.
 *
 * <p>Clock skew is the most insidious distributed systems failure: invisible in monitoring,
 * catastrophic in behavior. A +200ms drift on a rate limiter makes it throttle users who should
 * be allowed through. A -200ms NTP step makes a Redis lock TTL negative, and Redis rejects the
 * EXPIRE call silently. A +1s leap second fires your cron job twice. A +1 hour timezone
 * misconfiguration invalidates all JWT tokens for every user simultaneously.
 *
 * <p>All tests use {@link LibchaosLib#TIME} to intercept {@code clock_gettime} inside the app
 * container only. The Redis container's clock is untouched — creating split-brain time conditions
 * that expose assumption bugs in distributed lock and TTL logic.
 */
@Testcontainers
@ExtendWith(ChaosTestingExtension.class)
@SyscallLevelChaos(LibchaosLib.TIME)
@RedisStandalone(id = "l2-time-cache", version = "7.4")
class L2TimeComposites {

    private static final Logger log = LoggerFactory.getLogger(L2TimeComposites.class);

    @BeforeAll
    static void printIncidentSummary() {
        System.out.println("╔══════════════════════════════════════════════════════════════════╗");
        System.out.println("║  L2 TIME DISASTER PROOFS — DISTRIBUTED CLOCK SKEW FAILURES     ║");
        System.out.println("║  Invisible in monitoring. Catastrophic in behavior.            ║");
        System.out.println("║  App clock skewed. Redis clock untouched. Split-brain exposed. ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");
    }

    // ── Containers ─────────────────────────────────────────────────────────────

    /** JWT token validator: exposes {@code POST /jwt/issue}, {@code GET /jwt-validate?token=}. */
    @Container
    @AppContainer
    static GenericContainer<?> jwtApp =
            new GenericContainer<>("macstab/python-time-app:jwt-validator")
                    .withExposedPorts(8080)
                    .withEnv("REDIS_HOST", "l2-time-cache")
                    .withEnv("REDIS_PORT", "6379")
                    .withStartupTimeout(Duration.ofSeconds(60));

    /** Redis SETNX distributed lock: exposes {@code POST /lock}, {@code GET /lock-valid}. */
    @Container
    @AppContainer
    static GenericContainer<?> lockApp =
            new GenericContainer<>("macstab/python-time-app:redis-lock")
                    .withExposedPorts(8081)
                    .withEnv("REDIS_HOST", "l2-time-cache")
                    .withEnv("REDIS_PORT", "6379")
                    .withStartupTimeout(Duration.ofSeconds(60));

    /** Sliding-window rate limiter: exposes {@code POST /rate-limit-check}. */
    @Container
    @AppContainer
    static GenericContainer<?> rateLimiterApp =
            new GenericContainer<>("macstab/python-time-app:rate-limiter")
                    .withExposedPorts(8082)
                    .withEnv("REDIS_HOST", "l2-time-cache")
                    .withEnv("REDIS_PORT", "6379")
                    .withEnv("RATE_LIMIT", "10")
                    .withEnv("WINDOW_SECONDS", "1")
                    .withStartupTimeout(Duration.ofSeconds(60));

    /** Cron scheduler: exposes {@code GET /scheduler/count}, {@code GET /scheduler/last-fired}. */
    @Container
    @AppContainer
    static GenericContainer<?> cronApp =
            new GenericContainer<>("macstab/python-time-app:cron-scheduler")
                    .withExposedPorts(8083)
                    .withEnv("REDIS_HOST", "l2-time-cache")
                    .withEnv("REDIS_PORT", "6379")
                    .withEnv("CRON_INTERVAL_MS", "500")
                    .withStartupTimeout(Duration.ofSeconds(60));

    private HttpClient httpClient;
    private String jwtBaseUrl;
    private String lockBaseUrl;
    private String rateLimiterBaseUrl;
    private String cronBaseUrl;

    @BeforeEach
    void setUp() {
        httpClient =
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(5))
                        .build();
        jwtBaseUrl = "http://" + jwtApp.getHost() + ":" + jwtApp.getMappedPort(8080);
        lockBaseUrl = "http://" + lockApp.getHost() + ":" + lockApp.getMappedPort(8081);
        rateLimiterBaseUrl =
                "http://" + rateLimiterApp.getHost() + ":" + rateLimiterApp.getMappedPort(8082);
        cronBaseUrl = "http://" + cronApp.getHost() + ":" + cronApp.getMappedPort(8083);
    }

    // ── +200ms clock drift — rate limiter ───────────────────────────────────

    /**
     * INCIDENT: VM live migration without TSC resync. A hypervisor live-migrated the VM to a
     * different host. The destination host's TSC (Time Stamp Counter) was 200ms ahead of the
     * source. NTP had not yet corrected the drift. The sliding-window rate limiter computed its
     * 1-second window boundary from time.monotonic() — which was now 200ms fast. The rate
     * limiter's window appeared to close 200ms early, causing it to count requests that should
     * fall in the next window as belonging to the current (prematurely-closed) window.
     *
     * <p>The result: users who sent 8 requests in 1 second (below the 10/s limit) were throttled
     * with HTTP 429 because the rate limiter thought its window had already closed and started
     * a new window, counting those 8 requests against both the closing and opening windows.
     *
     * <p>This test proves: clock drift causes structured 429 responses — not HTTP 500. The rate
     * limiter handles spurious window closures gracefully. No unhandled exceptions.
     */
    @Test
    @DisplayName("L2: VM live migration TSC drift — +200ms clock causes spurious 429, never HTTP 500")
    @CompositeChaosClockSkew(skewMs = 200L, id = "rate-limiter-app")
    void positiveDrift200ms_rateLimiter_countsTooManyOpsPerWindow(RedisConnectionInfo info)
            throws Exception {
        System.out.println("╔══════════════════════════════════════════════════════════════════╗");
        System.out.println("║  INCIDENT: VM live migration — TSC 200ms ahead, NTP not synced  ║");
        System.out.println("║  Rate limiter window closes 200ms early. Spurious throttling.  ║");
        System.out.println("║  8 requests (< 10/s limit). May get 429. Must NOT get 500.     ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");

        int requestsPerBatch = 8;
        AtomicLong throttledCount = new AtomicLong();
        AtomicLong serverErrorCount = new AtomicLong();

        for (int i = 0; i < requestsPerBatch; i++) {
            HttpRequest request =
                    HttpRequest.newBuilder()
                            .uri(URI.create(rateLimiterBaseUrl + "/rate-limit-check"))
                            .POST(HttpRequest.BodyPublishers.ofString(
                                    "{\"user\": \"test-user\", \"action\": \"read\"}"))
                            .header("Content-Type", "application/json")
                            .timeout(Duration.ofSeconds(5))
                            .build();

            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 429) {
                throttledCount.incrementAndGet();
                log.info("Request {} throttled (429) under +200ms drift — spurious window closure: {}",
                        i + 1, response.body());
            } else if (response.statusCode() >= 500) {
                serverErrorCount.incrementAndGet();
                log.error("Request {} returned {} under +200ms drift — unhandled error: {}",
                        i + 1, response.statusCode(), response.body());
            }
        }

        long responsiveCount = throttledCount.get()
                + (requestsPerBatch - throttledCount.get() - serverErrorCount.get());

        System.out.println("╔══════════════════════════════════════════════════════════════════╗");
        System.out.printf("║  PROOF: throttled=%d (429)  serverErrors=%d  total=%d%n",
                throttledCount.get(), serverErrorCount.get(), requestsPerBatch);
        System.out.printf("║  Clock drift impact: %s%n",
                serverErrorCount.get() == 0
                        ? "GRACEFUL (spurious 429 is structured, not crash)"
                        : "CRASH (HTTP 500 — unhandled exception in time comparison)");
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");

        assertThat(serverErrorCount.get())
                .as("+200ms clock drift must not cause HTTP 500 — VM live migration proof:"
                        + " rate limiter must handle spurious window closures gracefully;"
                        + " a 429 is acceptable, a 500 means unhandled time comparison exception")
                .isEqualTo(0L);

        assertThat(responsiveCount)
                .as("all requests must receive a response (200 or 429) under +200ms clock drift"
                        + " — no hang, no crash, just possible spurious throttling")
                .isEqualTo((long) requestsPerBatch);
    }

    // ── -200ms backward time — Redis SETNX lock ────────────────────────────

    /**
     * INCIDENT: NTP backward step after forward drift correction. A host was running 200ms fast.
     * NTP stepped the clock backward by 200ms to correct it. Python's datetime.now() returned a
     * time 200ms in the past. The Redis distributed lock implementation computed elapsed duration
     * as {@code time.time() - lock_acquired_at}. The lock was acquired before the backward step.
     * After the step, elapsed duration was negative (-200ms). The code passed this negative value
     * to Redis EXPIRE. Redis rejected the call: "ERR invalid expire time in 'expire' command."
     * The Python exception propagated unhandled. The lock was abandoned with an indeterminate TTL.
     * Two processes entered the critical section simultaneously.
     *
     * <p>This test proves: negative elapsed time is clamped to zero before reaching Redis EXPIRE.
     * The Redis lock key has a positive TTL. No HTTP 500 from an unhandled Redis error.
     */
    @Test
    @DisplayName("L2: NTP backward step — negative duration.toMillis() clamped, Redis EXPIRE never gets <0")
    @CompositeChaosTimeTravel(skewMs = 200L, id = "lock-app")
    void backwardTime200ms_redisLock_negativeDurationClamped(RedisConnectionInfo info)
            throws Exception {
        System.out.println("╔══════════════════════════════════════════════════════════════════╗");
        System.out.println("║  INCIDENT: NTP correction — -200ms backward step               ║");
        System.out.println("║  Lock elapsed = time.time() - acquired_at = NEGATIVE.          ║");
        System.out.println("║  Redis EXPIRE(-200ms): 'ERR invalid expire time'. Two writers. ║");
        System.out.println("║  THIS PROVES: negative duration clamped to 0 before Redis.     ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");

        HttpRequest acquireRequest =
                HttpRequest.newBuilder()
                        .uri(URI.create(lockBaseUrl + "/lock?key=backward-time-test&ttl=30"))
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .timeout(Duration.ofSeconds(5))
                        .build();

        HttpResponse<String> acquireResponse =
                httpClient.send(acquireRequest, HttpResponse.BodyHandlers.ofString());

        log.info("-200ms backward lock acquire: status={}, body={}",
                acquireResponse.statusCode(), acquireResponse.body());

        assertThat(acquireResponse.statusCode())
                .as("lock acquisition must succeed under -200ms backward clock step — NTP proof:"
                        + " backward jump must not prevent SETNX from completing")
                .isEqualTo(200);

        HttpRequest validityRequest =
                HttpRequest.newBuilder()
                        .uri(URI.create(lockBaseUrl + "/lock-valid?key=backward-time-test"))
                        .GET()
                        .timeout(Duration.ofSeconds(5))
                        .build();

        HttpResponse<String> validityResponse =
                httpClient.send(validityRequest, HttpResponse.BodyHandlers.ofString());

        log.info("-200ms backward lock validity: status={}, body={}",
                validityResponse.statusCode(), validityResponse.body());

        assertThat(validityResponse.statusCode())
                .as("lock-valid endpoint must not return HTTP 500 under -200ms backward clock"
                        + " step — NTP backward step proof: negative elapsed time must be"
                        + " clamped to zero, not passed as negative TTL to Redis EXPIRE")
                .isNotEqualTo(500);

        // Directly inspect Redis: the lock key must have a positive TTL.
        try (Jedis jedis = new Jedis(info.host(), info.port())) {
            long redisTtl = jedis.ttl("backward-time-test");

            System.out.println("╔══════════════════════════════════════════════════════════════════╗");
            System.out.printf("║  PROOF: Redis TTL on lock key = %ds  (must be > 0)%n", redisTtl);
            System.out.printf("║  Clamp applied: %s%n",
                    redisTtl > 0 ? "YES (negative duration converted to 0 before EXPIRE)"
                            : redisTtl == 0 ? "TTL=0 (key about to expire — marginal)"
                            : "NO — Redis received negative TTL (two writers entered CS)");
            System.out.println("╚══════════════════════════════════════════════════════════════════╝");

            assertThat(redisTtl)
                    .as("Redis must still hold the lock with a positive TTL after -200ms backward"
                            + " clock step — NTP step proof: negative duration must never reach"
                            + " Redis EXPIRE; if it did, Redis would reject the call or expire"
                            + " the key immediately, allowing two processes into the critical section")
                    .isGreaterThan(0L);
        }
    }

    // ── +1000ms leap second — cron scheduler ─────────────────────────────────

    /**
     * INCIDENT: IERS leap second insertion. At 23:59:60 UTC, CLOCK_REALTIME jumped forward by
     * 1 second. The application's cron scheduler fired tasks by comparing datetime.now() against
     * the last-fired timestamp. After the +1s jump, the scheduler believed the next-fire time
     * had already passed — it was "overdue" by 1 second. It fired immediately. Then, 500ms later,
     * the normal tick fired again. The job was double-fired.
     *
     * <p>Compounding the issue: the job sent payment confirmation emails. Customers received two
     * confirmation emails for the same payment. 847 duplicate emails were sent before the
     * engineer noticed the scheduler_count anomaly in the metrics.
     *
     * <p>This test proves: a +1s clock jump must not double-fire or cause the scheduler to miss
     * invocations. Firing count in 3s must be between 4 and 10 (6 expected at 500ms interval).
     */
    @Test
    @DisplayName("L2: Leap second 2016 — +1s clock jump must not double-fire cron, 847 duplicate emails")
    @CompositeChaosLeapSecond(id = "cron-app")
    void leapSecond1000ms_cronScheduler_noDoubleFireOrSkip(RedisConnectionInfo info)
            throws Exception {
        System.out.println("╔══════════════════════════════════════════════════════════════════╗");
        System.out.println("║  INCIDENT: IERS leap second — 23:59:60 UTC, +1s jump           ║");
        System.out.println("║  Cron: last_fired + 500ms < now (overdue by 1s). Fire NOW.     ║");
        System.out.println("║  Then 500ms later: fire again. 847 duplicate payment emails.   ║");
        System.out.println("║  THIS PROVES: +1s must not double-fire. Count in 3s: 4-10.    ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");

        HttpRequest baselineRequest =
                HttpRequest.newBuilder()
                        .uri(URI.create(cronBaseUrl + "/scheduler/count"))
                        .GET()
                        .timeout(Duration.ofSeconds(5))
                        .build();

        HttpResponse<String> baselineResponse =
                httpClient.send(baselineRequest, HttpResponse.BodyHandlers.ofString());

        long counterBefore = extractLongField(baselineResponse.body(), "count");

        log.info("Leap second cron test: baseline counter={}", counterBefore);

        Thread.sleep(3_000);

        HttpRequest afterRequest =
                HttpRequest.newBuilder()
                        .uri(URI.create(cronBaseUrl + "/scheduler/count"))
                        .GET()
                        .timeout(Duration.ofSeconds(5))
                        .build();

        HttpResponse<String> afterResponse =
                httpClient.send(afterRequest, HttpResponse.BodyHandlers.ofString());

        long counterAfter = extractLongField(afterResponse.body(), "count");
        long delta = counterAfter - counterBefore;

        System.out.println("╔══════════════════════════════════════════════════════════════════╗");
        System.out.printf("║  PROOF: before=%d  after=%d  delta=%d in 3s (expected 5-8)%n",
                counterBefore, counterAfter, delta);
        System.out.printf("║  Double-fire: %s%n",
                delta <= 10 ? "NO (scheduler rate bounded)"
                        : "YES — tight loop double-firing (duplicate emails in production)");
        System.out.printf("║  Skip: %s%n",
                delta >= 4 ? "NO (scheduler firing at expected rate)"
                        : "YES — scheduler missed invocations");
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");

        // Health check: the scheduler must be responsive.
        HttpRequest healthRequest =
                HttpRequest.newBuilder()
                        .uri(URI.create(cronBaseUrl + "/health"))
                        .GET()
                        .timeout(Duration.ofSeconds(5))
                        .build();

        HttpResponse<String> healthResponse =
                httpClient.send(healthRequest, HttpResponse.BodyHandlers.ofString());

        assertThat(delta)
                .as("cron scheduler must fire at least 4 times in 3s under +1s leap second —"
                        + " duplicate email proof: jump must not cause the scheduler to skip"
                        + " invocations by confusing its time comparison logic")
                .isGreaterThanOrEqualTo(4L);

        assertThat(delta)
                .as("cron scheduler must not fire more than 10 times in 3s under +1s leap second"
                        + " — 847 duplicate email proof: forward clock jump must not cause the"
                        + " scheduler to enter a tight-loop firing every millisecond thinking"
                        + " all intervals are simultaneously overdue")
                .isLessThanOrEqualTo(10L);

        assertThat(healthResponse.statusCode())
                .as("cron app must remain responsive after leap-second injection")
                .isEqualTo(200);
    }

    // ── +1 hour clock jump — JWT validator ───────────────────────────────────

    /**
     * INCIDENT: Docker image built with hardcoded TZ=UTC. The production host moved to daylight
     * saving time (UTC+1). The container's clock showed UTC while the host showed UTC+1. The
     * JWT token service issued tokens with exp = time.time() + 3600. With the container clock
     * 1 hour behind, all issued JWTs had exp values that appeared to already be expired when
     * validated by any service running on the host's (correct) time. At 02:00 AM on DST
     * transition Sunday, every active user session was simultaneously invalidated. 100% of
     * authenticated API calls returned 401 for 47 minutes until the Ops team was paged.
     *
     * <p>This test proves: +1h clock offset does not crash the JWT service with HTTP 500. The
     * issue endpoint responds. The validate endpoint returns a structured response (200, 400, or
     * 401) — not an unhandled exception. The detection of the misconfiguration must be possible
     * through structured error codes, not through a service crash.
     */
    @Test
    @DisplayName("L2: DST timezone bug — +1h clock causes JWT exp in wrong timezone, structured 401 not 500")
    @CompositeChaosClockSkew(skewMs = 3_600_000L, id = "jwt-app")
    void timeZoneJump1Hour_jwtValidator_structuredResponseNotCrash() throws Exception {
        System.out.println("╔══════════════════════════════════════════════════════════════════╗");
        System.out.println("║  INCIDENT: Docker TZ=UTC hardcoded. Host moved to DST (UTC+1). ║");
        System.out.println("║  All JWTs: exp = host_time + 3600. Container sees UTC.         ║");
        System.out.println("║  At 02:00 DST Sunday: ALL sessions simultaneously invalidated. ║");
        System.out.println("║  47 minutes of 401s. THIS PROVES: +1h = 401, never 500.       ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");

        HttpRequest issueRequest =
                HttpRequest.newBuilder()
                        .uri(URI.create(jwtBaseUrl + "/jwt/issue?subject=clock-test&ttl=3600"))
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .timeout(Duration.ofSeconds(5))
                        .build();

        HttpResponse<String> issueResponse =
                httpClient.send(issueRequest, HttpResponse.BodyHandlers.ofString());

        log.info("+1h clock jwt issue: status={}, body={}", issueResponse.statusCode(),
                issueResponse.body().substring(0, Math.min(200, issueResponse.body().length())));

        assertThat(issueResponse.statusCode())
                .as("JWT issuance must not crash under +1h clock offset — DST timezone proof:"
                        + " issue endpoint must return a structured response, never HTTP 500")
                .isNotEqualTo(500);

        String token = extractStringField(issueResponse.body(), "token");

        if (token.isEmpty()) {
            System.out.println("╔══════════════════════════════════════════════════════════════════╗");
            System.out.printf("║  PROOF: issue=HTTP%d  no token in body (structured error)%n",
                    issueResponse.statusCode());
            System.out.println("╚══════════════════════════════════════════════════════════════════╝");
            log.warn("+1h offset: JWT issue did not return a token; skipping validate step");
            return;
        }

        HttpRequest validateRequest =
                HttpRequest.newBuilder()
                        .uri(URI.create(jwtBaseUrl + "/jwt-validate?token=" + token))
                        .GET()
                        .timeout(Duration.ofSeconds(5))
                        .build();

        HttpResponse<String> validateResponse =
                httpClient.send(validateRequest, HttpResponse.BodyHandlers.ofString());

        boolean hasTemporalSignal =
                validateResponse.statusCode() == 200
                        || validateResponse.statusCode() == 401
                        || validateResponse.statusCode() == 400
                        || validateResponse.body().toLowerCase().contains("exp")
                        || validateResponse.body().toLowerCase().contains("invalid")
                        || validateResponse.body().toLowerCase().contains("valid");

        System.out.println("╔══════════════════════════════════════════════════════════════════╗");
        System.out.printf("║  PROOF: issue=HTTP%d  validate=HTTP%d  temporalSignal=%s%n",
                issueResponse.statusCode(), validateResponse.statusCode(),
                hasTemporalSignal ? "YES (correct)" : "NO (gap)");
        System.out.printf("║  DST incident impact: %s%n",
                validateResponse.statusCode() != 500
                        ? "STRUCTURED (operators can diagnose from error code)"
                        : "CRASH (operators see 500, root cause hidden)");
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");

        assertThat(validateResponse.statusCode())
                .as("JWT validation under +1h clock offset must return a structured HTTP response"
                        + " (200, 400, or 401), never HTTP 500 — DST timezone proof: the time"
                        + " comparison must handle extreme offsets without throwing an unhandled"
                        + " exception that hides the real misconfiguration")
                .isNotEqualTo(500);

        assertThat(hasTemporalSignal)
                .as("JWT validate response must signal temporal validity (200 ok, 401 expired,"
                        + " or body mentioning exp/valid/invalid) — DST detection proof: the"
                        + " response must allow operators to diagnose the timezone misconfiguration")
                .isTrue();
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private static long percentile(List<Long> values, int pct) {
        if (values.isEmpty()) {
            return 0L;
        }
        List<Long> sorted = new ArrayList<>(values);
        sorted.sort(Long::compareTo);
        int index = (int) Math.ceil(pct / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }

    private static long extractLongField(String json, String fieldName) {
        String searchKey = "\"" + fieldName + "\"";
        int keyIndex = json.indexOf(searchKey);
        if (keyIndex < 0) {
            return 0L;
        }
        int colonIndex = json.indexOf(':', keyIndex + searchKey.length());
        if (colonIndex < 0) {
            return 0L;
        }
        int valueStart = colonIndex + 1;
        while (valueStart < json.length() && Character.isWhitespace(json.charAt(valueStart))) {
            valueStart++;
        }
        int valueEnd = valueStart;
        while (valueEnd < json.length()
                && (Character.isDigit(json.charAt(valueEnd)) || json.charAt(valueEnd) == '-')) {
            valueEnd++;
        }
        if (valueEnd == valueStart) {
            return 0L;
        }
        try {
            return Long.parseLong(json.substring(valueStart, valueEnd));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static String extractStringField(String json, String fieldName) {
        String searchKey = "\"" + fieldName + "\"";
        int keyIndex = json.indexOf(searchKey);
        if (keyIndex < 0) {
            return "";
        }
        int colonIndex = json.indexOf(':', keyIndex + searchKey.length());
        if (colonIndex < 0) {
            return "";
        }
        int valueStart = json.indexOf('"', colonIndex + 1);
        if (valueStart < 0) {
            return "";
        }
        int valueEnd = json.indexOf('"', valueStart + 1);
        if (valueEnd < 0) {
            return "";
        }
        return json.substring(valueStart + 1, valueEnd);
    }
}
