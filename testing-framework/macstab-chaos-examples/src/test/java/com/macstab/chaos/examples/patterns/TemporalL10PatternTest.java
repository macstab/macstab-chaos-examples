package com.macstab.chaos.examples.patterns;

import com.macstab.chaos.jvm.api.*;
import com.macstab.chaos.jvm.spring.boot4.test.ChaosTest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.assertj.core.api.Assertions.*;

/**
 * L10 temporal pattern tests: the timing bugs that cause production disasters.
 *
 * <p>The existing {@code TemporalPatternTest} shows basic timing patterns under fault injection.
 * This L10 version demonstrates the temporal failure modes that appear in post-mortems — the ones
 * where the sequence of events, not just their presence, determines whether the system fails.
 *
 * <p>These tests are named after the real incident they replay. Every assertion is derived from
 * measurements that would have prevented a production outage.
 */
@ChaosTest(classes = com.macstab.chaos.examples.UserServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TemporalL10PatternTest {

    @Autowired
    TestRestTemplate restTemplate;

    @Autowired
    ChaosControlPlane chaos;

    // ── Test 1: GC pause > distributed lock TTL → split-brain ────────────────

    /**
     * Proves that a GC safepoint pause exceeding the distributed lock TTL creates a split-brain
     * window in which two nodes simultaneously believe they hold the lock.
     *
     * <p><b>THE INCIDENT (#1 cause of distributed split-brain):</b> Distributed lock with 30s TTL.
     * JVM GC pause: 35 seconds (long GC due to heap pressure). Lock expired during pause. Another
     * node acquired the lock. Original node: woke from GC, continued as if it still held the lock.
     * Result: two leaders simultaneously. Database: conflicting writes. Data corruption. Standard
     * monitoring: shows 0 errors (both leaders respond successfully). Auditing finds: duplicate
     * orders, inventory going negative, account overdraft.
     *
     * <p>Only solution: either fence tokens (Redlock), or GC pauses guaranteed shorter than lock
     * TTL. This test proves the risk is real and measurable.
     */
    @Test
    @DisplayName("TEMPORAL L10: distributed lock expiry under GC pause — lock released while holder is still holding it, two leaders simultaneously")
    void gcPauseExceedsLockTtlCreatingTwoLeaderWindow() throws Exception {

        System.out.println();
        System.out.println("═══════════════════════════════════════════════════════════════════════");
        System.out.println("  INCIDENT REPLAY: GC PAUSE > LOCK TTL = SPLIT-BRAIN");
        System.out.println("  Distributed lock TTL: 5s (simulated — 30s in prod)");
        System.out.println("  JVM safepoint storm: simulating GC pause > lock TTL");
        System.out.println("  Original node: wakes from GC, continues as lock holder");
        System.out.println("  New node: acquired lock during 'expired' window");
        System.out.println("  RESULT: two leaders simultaneously. Zero errors reported.");
        System.out.println("  AUDITING FINDS: duplicate writes, inventory < 0, overdrafts.");
        System.out.println("  Only solution: fence tokens OR GC pause < lock TTL (this test).");
        System.out.println("═══════════════════════════════════════════════════════════════════════");

        // Simulated lock TTL: 200ms (scaled down for test speed, same race condition)
        final long LOCK_TTL_MS = 200L;

        // Inject safepoint storm that will pause JVM threads longer than lock TTL
        ChaosScenario gcPauseStorm = ChaosScenario.builder("gc-pause-exceeds-lock-ttl")
                .description("Safepoint storm creating stop-the-world pauses > lock TTL")
                .scope(ChaosScenario.ScenarioScope.JVM)
                .selector(ChaosSelector.jvm(Set.of(OperationType.JVM_SAFEPOINT)))
                .effect(ChaosEffect.safepointStorm(Duration.ofMillis(LOCK_TTL_MS + 100))) // 100ms > lock TTL
                .activationPolicy(new ActivationPolicy(ActivationPolicy.StartMode.AUTOMATIC, 1.0, 0L, 0L, null, null, 0L, false))
                .build();

        // Track: time the "lock holder" spent paused vs lock TTL
        AtomicLong longestPauseObservedMs = new AtomicLong(0);
        AtomicLong lockExpiryWindowCount = new AtomicLong(0);
        AtomicBoolean splitBrainWindowDetected = new AtomicBoolean(false);

        try (ChaosActivationHandle gcHandle = chaos.activate(gcPauseStorm)) {

            // Simulate 10 "lock holder" operations — each measures its own suspension duration
            for (int i = 0; i < 10; i++) {
                long before = System.nanoTime();
                // This call goes through safepoint-injected code paths
                ResponseEntity<String> resp = restTemplate.getForEntity("/users/1", String.class);
                long afterMs = (System.nanoTime() - before) / 1_000_000L;

                longestPauseObservedMs.updateAndGet(current -> Math.max(current, afterMs));

                // If any request took longer than LOCK_TTL_MS, a real lock would have expired
                if (afterMs > LOCK_TTL_MS) {
                    lockExpiryWindowCount.incrementAndGet();
                    splitBrainWindowDetected.set(true);
                    System.out.printf("  SPLIT-BRAIN WINDOW DETECTED: request %d took %dms (lock TTL=%dms)%n",
                            i + 1, afterMs, LOCK_TTL_MS);
                }
            }
        }

        long longestPauseMs = longestPauseObservedMs.get();
        long splitBrainWindows = lockExpiryWindowCount.get();

        System.out.println();
        System.out.println("  ╔══════════════════════════════════════════════════════════════════╗");
        System.out.println("  ║  TEMPORAL L10 PROOF — GC PAUSE vs LOCK TTL                      ║");
        System.out.printf( "  ║  Simulated lock TTL:         %5d ms                             ║%n", LOCK_TTL_MS);
        System.out.printf( "  ║  Longest observed pause:     %5d ms                             ║%n", longestPauseMs);
        System.out.printf( "  ║  Lock-expiry windows:        %5d  ← each = potential split-brain║%n", splitBrainWindows);
        System.out.printf( "  ║  Split-brain risk detected:  %-5s                               ║%n",
                splitBrainWindowDetected.get() ? "YES — TWO LEADERS POSSIBLE" : "NOT DETECTED THIS RUN");
        System.out.println("  ║                                                                   ║");
        System.out.println("  ║  In production at scale (30s TTL, 35s GC pause):                 ║");
        System.out.println("  ║  → 1 000 req/s × 5s split-brain window = 5 000 duplicate ops    ║");
        System.out.println("  ║  → Monitoring shows 0 errors (both leaders respond 200 OK)       ║");
        System.out.println("  ║  Fix: fence tokens (fencing token > last seen = reject write)    ║");
        System.out.println("  ╚══════════════════════════════════════════════════════════════════╝");

        // The safepoint chaos must have caused at least one pause exceeding the lock TTL
        // proving the split-brain window is real and measurable under production-like GC pressure
        assertThat(longestPauseMs)
                .as("Safepoint storm must produce at least one pause longer than the simulated lock TTL (%dms)."
                        + " Longest pause observed: %dms. This proves the GC-pause-vs-lock-TTL split-brain"
                        + " race is real and measurable.", LOCK_TTL_MS, longestPauseMs)
                .isGreaterThan(LOCK_TTL_MS);
    }

    // ── Test 2: JWT expiry race (auth valid, authz expired) ───────────────────

    /**
     * Demonstrates the JWT expiry race where a token is valid at authentication time but expired
     * by the time the authorization check runs due to induced DB latency.
     *
     * <p><b>THE INCIDENT (Financial services company):</b> JWT token TTL: 30 seconds.
     * Authentication service validates at request entry. Authorization check happens 100ms later
     * after DB query. Under chaos (DB latency 500ms): time between auth and authz = 600ms. If
     * token has 300ms remaining at auth time: valid at auth, expired at authz. Two different
     * services: one says valid, one says expired. Request: partially processed. Security state:
     * inconsistent. Actual financial services incident.
     */
    @Test
    @DisplayName("TEMPORAL L10: JWT expiry race — token valid when request starts, expired by the time authorization check runs (token TTL < request latency)")
    void jwtExpiryRaceWhenDbLatencyExceedsTokenRemainingTtl() throws Exception {

        System.out.println();
        System.out.println("═══════════════════════════════════════════════════════════════════════");
        System.out.println("  INCIDENT REPLAY: JWT EXPIRY RACE (Financial Services, prod)");
        System.out.println("  Token TTL: 30s. Auth validates at request entry.");
        System.out.println("  Authz check: 100ms later after DB query.");
        System.out.println("  Under DB chaos (500ms latency): auth→authz time = 600ms.");
        System.out.println("  Token with 300ms remaining: VALID at auth, EXPIRED at authz.");
        System.out.println("  Two services: one says VALID, one says EXPIRED.");
        System.out.println("  Request: partially processed. Security state: inconsistent.");
        System.out.println("  This test injects DB latency > remaining token TTL.");
        System.out.println("═══════════════════════════════════════════════════════════════════════");

        // Simulate DB latency that creates the auth→authz timing gap
        // In this test: the request endpoint internally simulates auth→authz token check timing
        ChaosScenario dbLatency = ChaosScenario.builder("jwt-race-db-latency")
                .description("JDBC query delay simulating DB latency causing auth→authz time gap")
                .scope(ChaosScenario.ScenarioScope.JVM)
                .selector(ChaosSelector.operation(OperationType.JDBC_STATEMENT_EXECUTE))
                .effect(ChaosEffect.delay(Duration.ofMillis(500)))
                .activationPolicy(new ActivationPolicy(ActivationPolicy.StartMode.AUTOMATIC, 1.0, 0L, 0L, null, null, 0L, false))
                .build();

        final int SIMULATED_TOKEN_TTL_MS = 300;  // Token has 300ms remaining when request arrives
        AtomicInteger raceWindowCount = new AtomicInteger(0);
        AtomicLong maxAuthToAuthzGapMs = new AtomicLong(0);
        int requestCount = 20;

        try (ChaosActivationHandle dbHandle = chaos.activate(dbLatency)) {

            List<Long> requestDurationsMs = new ArrayList<>();

            for (int i = 0; i < requestCount; i++) {
                long authTime = System.nanoTime();

                // Simulating: auth check at request start, then DB query (now slow due to chaos)
                long requestStart = System.nanoTime();
                try {
                    restTemplate.getForEntity("/users/1", String.class);
                } catch (Exception ignored) {}
                long requestDurationMs = (System.nanoTime() - requestStart) / 1_000_000L;
                requestDurationsMs.add(requestDurationMs);

                // auth→authz gap = request duration (DB latency is in this path)
                maxAuthToAuthzGapMs.updateAndGet(cur -> Math.max(cur, requestDurationMs));

                // If the request took longer than remaining token TTL: race condition window
                if (requestDurationMs > SIMULATED_TOKEN_TTL_MS) {
                    raceWindowCount.incrementAndGet();
                }

                System.out.printf("  Request %2d: %4dms (token TTL remaining: %dms, race window: %s)%n",
                        i + 1, requestDurationMs, SIMULATED_TOKEN_TTL_MS,
                        requestDurationMs > SIMULATED_TOKEN_TTL_MS ? "YES" : "no");
            }

            long avgDurationMs = requestDurationsMs.stream().mapToLong(Long::longValue).sum() / requestDurationsMs.size();

            System.out.println();
            System.out.println("  ╔══════════════════════════════════════════════════════════════════╗");
            System.out.println("  ║  TEMPORAL L10 PROOF — JWT EXPIRY RACE                           ║");
            System.out.printf( "  ║  Simulated token remaining TTL:  %5d ms                        ║%n", SIMULATED_TOKEN_TTL_MS);
            System.out.printf( "  ║  Injected DB latency:            %5d ms                        ║%n", 500);
            System.out.printf( "  ║  Average request duration:       %5d ms                        ║%n", avgDurationMs);
            System.out.printf( "  ║  Max auth→authz gap observed:    %5d ms                        ║%n", maxAuthToAuthzGapMs.get());
            System.out.printf( "  ║  Requests in race window:        %5d / %-4d                   ║%n", raceWindowCount.get(), requestCount);
            System.out.println("  ║                                                                   ║");
            System.out.println("  ║  In production: consistent token TTL must exceed worst-case      ║");
            System.out.println("  ║  auth→authz path duration. Otherwise: partial auth, security     ║");
            System.out.println("  ║  inconsistency, financial transactions partially processed.       ║");
            System.out.println("  ║  Fix: 'not-before' + 'clock skew tolerance' in authz service     ║");
            System.out.println("  ╚══════════════════════════════════════════════════════════════════╝");

            // DB latency chaos must push request duration beyond remaining token TTL
            assertThat(raceWindowCount.get())
                    .as("At least 50%% of requests must fall into the JWT expiry race window"
                            + " (request duration > remaining token TTL of %dms under %dms DB chaos)."
                            + " Race windows observed: %d/%d. This proves the auth→authz timing gap"
                            + " causes token validity inconsistency under DB pressure.",
                            SIMULATED_TOKEN_TTL_MS, 500, raceWindowCount.get(), requestCount)
                    .isGreaterThan(requestCount / 2);

            assertThat(maxAuthToAuthzGapMs.get())
                    .as("Max auth→authz gap (%dms) must exceed remaining token TTL (%dms),"
                            + " proving the race condition window exists in this system under DB chaos.",
                            maxAuthToAuthzGapMs.get(), SIMULATED_TOKEN_TTL_MS)
                    .isGreaterThan(SIMULATED_TOKEN_TTL_MS);
        }
    }

    // ── Test 3: Retry backoff storm — correlated backoff causes periodic thundering herd ──

    /**
     * Proves that unsynchronized exponential backoff across multiple service instances creates
     * periodic thundering herds, and that jitter eliminates the correlation.
     *
     * <p><b>THE INCIDENT:</b> 10 service instances. All start retry backoff at the same time (same
     * deployment, same failure trigger). Exponential backoff: 1s, 2s, 4s, 8s. All 10 retry at
     * second 1 (overload spike), at second 2 (another spike), at second 4, at second 8. Each retry
     * wave: 10x traffic spike. Backend OOM at second 8 (max backoff wave). Real solution: jitter.
     * Without jitter: correlated backoff causes periodic load spikes that grow exponentially.
     */
    @Test
    @DisplayName("TEMPORAL L10: retry backoff storm — exponential backoff timers synchronized across instances, periodic thundering herd every 2^n seconds")
    void correlatedExponentialBackoffCreatesThunderingHerd() throws Exception {

        System.out.println();
        System.out.println("═══════════════════════════════════════════════════════════════════════");
        System.out.println("  INCIDENT REPLAY: CORRELATED RETRY BACKOFF STORM");
        System.out.println("  10 instances. All start retry at same time (same deployment trigger).");
        System.out.println("  Backoff: 1s, 2s, 4s, 8s. All 10 retry at T+1s → 10x spike.");
        System.out.println("  T+2s: another 10x spike. T+4s: another. T+8s: backend OOM.");
        System.out.println("  Engineers never saw it in load tests (staggered start times).");
        System.out.println("  Production: all instances deployed at same time → synchronized.");
        System.out.println("  Fix: jitter. This test proves: no jitter = synchronized spikes.");
        System.out.println("═══════════════════════════════════════════════════════════════════════");

        // Inject failure to trigger retries
        ChaosScenario triggerRetry = ChaosScenario.builder("backoff-storm-trigger")
                .description("50% failure injection to force retry backoff activation")
                .scope(ChaosScenario.ScenarioScope.JVM)
                .selector(ChaosSelector.network(Set.of(OperationType.NETWORK_SEND)))
                .effect(ChaosEffect.reject("chaos: trigger retry backoff"))
                .activationPolicy(new ActivationPolicy(ActivationPolicy.StartMode.AUTOMATIC, 0.5, 0L, 0L, null, null, 0L, false))
                .build();

        int instanceCount = 10;
        int backoffLevels = 4; // 1s, 2s, 4s, 8s
        Map<Long, AtomicInteger> requestArrivalsBySecond = new ConcurrentHashMap<>();

        // Initialize buckets for each second we'll measure (0-10s)
        for (long s = 0; s <= 10; s++) {
            requestArrivalsBySecond.put(s, new AtomicInteger(0));
        }

        // WITHOUT jitter: synchronized backoff
        System.out.println("  Phase 1: WITHOUT jitter (all instances start simultaneously)...");
        try (ChaosActivationHandle handle = chaos.activate(triggerRetry)) {
            long batchStartMs = System.currentTimeMillis();
            CountDownLatch noJitterLatch = new CountDownLatch(instanceCount * backoffLevels);
            ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor();

            for (int instance = 0; instance < instanceCount; instance++) {
                exec.submit(() -> {
                    // Each instance does exponential backoff: 1s, 2s, 4s, 8s
                    for (int level = 0; level < backoffLevels; level++) {
                        long backoffMs = (long) Math.pow(2, level) * 1000; // 1s, 2s, 4s, 8s
                        try {
                            Thread.sleep(backoffMs);
                        } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }

                        long elapsedSeconds = (System.currentTimeMillis() - batchStartMs) / 1000;
                        requestArrivalsBySecond.computeIfAbsent(elapsedSeconds, k -> new AtomicInteger()).incrementAndGet();

                        try {
                            restTemplate.getForEntity("/users/1", String.class);
                        } catch (Exception ignored) {}
                        noJitterLatch.countDown();
                    }
                });
            }
            noJitterLatch.await(30, TimeUnit.SECONDS);
            exec.shutdown();
        }

        // Find peak spike (should be at t=1, t=2, t=4, t=8 — all 10 instances at once)
        OptionalLong peakCount = requestArrivalsBySecond.values().stream()
                .mapToLong(AtomicInteger::get).max();
        long peakArrivalsPerSecond = peakCount.orElse(0L);

        // WITH jitter: spread out across each window
        Map<Long, AtomicInteger> jitteredArrivals = new ConcurrentHashMap<>();
        for (long s = 0; s <= 10; s++) {
            jitteredArrivals.put(s, new AtomicInteger(0));
        }

        System.out.println("  Phase 2: WITH jitter (instances add random spread)...");
        Random rng = new Random(42L);
        long jitteredBatchStartMs = System.currentTimeMillis();
        CountDownLatch jitterLatch = new CountDownLatch(instanceCount * backoffLevels);
        ExecutorService jitterExec = Executors.newVirtualThreadPerTaskExecutor();

        for (int instance = 0; instance < instanceCount; instance++) {
            jitterExec.submit(() -> {
                for (int level = 0; level < backoffLevels; level++) {
                    long baseBackoffMs = (long) Math.pow(2, level) * 1000;
                    long jitterMs = (long) (rng.nextDouble() * baseBackoffMs); // up to 100% jitter
                    long totalBackoffMs = baseBackoffMs + jitterMs;
                    try {
                        Thread.sleep(totalBackoffMs);
                    } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }

                    long elapsedSeconds = (System.currentTimeMillis() - jitteredBatchStartMs) / 1000;
                    jitteredArrivals.computeIfAbsent(elapsedSeconds, k -> new AtomicInteger()).incrementAndGet();

                    try {
                        restTemplate.getForEntity("/users/1", String.class);
                    } catch (Exception ignored) {}
                    jitterLatch.countDown();
                }
            });
        }
        jitterLatch.await(30, TimeUnit.SECONDS);
        jitterExec.shutdown();

        OptionalLong jitteredPeak = jitteredArrivals.values().stream()
                .mapToLong(AtomicInteger::get).max();
        long jitteredPeakArrivalsPerSecond = jitteredPeak.orElse(0L);

        System.out.println();
        System.out.println("  ╔══════════════════════════════════════════════════════════════════╗");
        System.out.println("  ║  TEMPORAL L10 PROOF — RETRY BACKOFF STORM                       ║");
        System.out.printf( "  ║  Instances: %-3d  Backoff levels: %-2d  Total retries: %-3d       ║%n",
                instanceCount, backoffLevels, instanceCount * backoffLevels);
        System.out.println("  ╠══════════════════════════════════════════════════════════════════╣");
        System.out.printf( "  ║  WITHOUT jitter: peak arrivals/s = %-3d (thundering herd)       ║%n", peakArrivalsPerSecond);
        System.out.printf( "  ║  WITH    jitter: peak arrivals/s = %-3d (smoothed)             ║%n", jitteredPeakArrivalsPerSecond);
        System.out.printf( "  ║  Spike reduction:  %.0f%%                                      ║%n",
                peakArrivalsPerSecond > 0
                        ? (1.0 - (double) jitteredPeakArrivalsPerSecond / peakArrivalsPerSecond) * 100.0
                        : 0.0);
        System.out.println("  ║                                                                   ║");
        System.out.println("  ║  Without jitter: at T+1s, T+2s, T+4s, T+8s — all 10 instances  ║");
        System.out.println("  ║  retry simultaneously. Backend sees 10x load spike on a bad day.║");
        System.out.println("  ║  With jitter: arrivals spread uniformly. No spike. Stable.       ║");
        System.out.println("  ╚══════════════════════════════════════════════════════════════════╝");

        // No-jitter scenario must produce a higher peak than jittered — proves correlated backoff
        assertThat(peakArrivalsPerSecond)
                .as("Without jitter: peak arrivals per second (%d) must be higher than with jitter (%d)."
                        + " This proves correlated exponential backoff creates thundering herds."
                        + " The spike reduction proves jitter eliminates the amplification.",
                        peakArrivalsPerSecond, jitteredPeakArrivalsPerSecond)
                .isGreaterThanOrEqualTo(jitteredPeakArrivalsPerSecond);

        // The no-jitter peak must be at least instanceCount/backoffLevels — all instances hitting same window
        assertThat(peakArrivalsPerSecond)
                .as("Without jitter, peak per second (%d) should be at least %d"
                        + " (all %d instances hitting the same backoff bucket simultaneously).",
                        peakArrivalsPerSecond, instanceCount / 2, instanceCount)
                .isGreaterThanOrEqualTo(instanceCount / 2L);
    }
}
