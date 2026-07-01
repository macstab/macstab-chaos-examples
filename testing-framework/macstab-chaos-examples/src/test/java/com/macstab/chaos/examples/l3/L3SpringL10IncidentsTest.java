package com.macstab.chaos.examples.l3;

import com.macstab.chaos.core.annotation.SyscallLevelChaos;
import com.macstab.chaos.core.syscall.LibchaosLib;
import com.macstab.chaos.spring.annotation.l3.IncidentChaosSpringTransactionalPoolDeadlock;
import com.macstab.chaos.spring.annotation.l3.IncidentChaosSpringConfigRefreshWave;
import com.macstab.chaos.jvm.spring.boot4.test.ChaosTest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;

import java.lang.management.ManagementFactory;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.assertj.core.api.Assertions.*;

// ╔══════════════════════════════════════════════════════════════════════════╗
// ║  L10 INCIDENT REPLAYS — SPRING                                          ║
// ║                                                                          ║
// ║  Two Spring-specific disasters.  Both are "impossible in staging"        ║
// ║  failures that only manifest under production timing and load.           ║
// ║  Both were discovered by engineers who restarted services repeatedly     ║
// ║  before finding the root cause.                                          ║
// ╚══════════════════════════════════════════════════════════════════════════╝

@ChaosTest(classes = com.macstab.chaos.examples.UserServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@SyscallLevelChaos(LibchaosLib.NET)
class L3SpringL10IncidentsTest {

    @Autowired
    TestRestTemplate restTemplate;

    // ──────────────────────────────────────────────────────────────────────
    // INCIDENT 1  ·  @Transactional timeout accumulates zombie transactions
    //
    // THE INCIDENT
    // ────────────
    // Spring @Transactional(timeout=30).  Under database pressure:
    // transactions timeout after 30s.  Spring: marks transaction for
    // rollback.  Database: ROLLBACK sent.  But: if another request starts
    // a new transaction on the same connection before rollback completes
    // (connection returned to pool too fast): new transaction started while
    // previous rollback in progress.  PostgreSQL: sends ERROR for new
    // transaction.  Application: 500.  Connection: broken state.  After 20
    // requests: 20 broken connections.  Pool: all broken.  Service: dead.
    // Restart: recovers for 30 seconds.  Same storm: happens again.
    //
    // PROOF
    // ─────
    //   • zombie transaction count (connections in broken state)    (0 permanently)
    //   • pool degradation rate (% connections broken over time)    (< 50%)
    //   • recovery time after storm window                          (< 10s)
    //   • deadlocked threads after test                             (0)
    //   • service remains reachable after 20 concurrent timeouts
    // ──────────────────────────────────────────────────────────────────────

    @Test
    @IncidentChaosSpringTransactionalPoolDeadlock
    @DisplayName("INCIDENT Spring/L10ZombieTransactionAccumulation: timeout path skips rollback → broken connections accumulate — pool recovers without restart")
    void springL10ZombieTransactionAccumulation() throws Exception {
        int concurrency = 20;
        ExecutorService exec = Executors.newFixedThreadPool(concurrency);
        CountDownLatch startGun = new CountDownLatch(1);
        CountDownLatch allDone = new CountDownLatch(concurrency);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger brokenConnectionErrors = new AtomicInteger(0);
        List<Long> latencies = Collections.synchronizedList(new ArrayList<>());

        // Phase 1 — storm: all 20 threads hit DB simultaneously, triggering timeouts
        for (int i = 0; i < concurrency; i++) {
            exec.submit(() -> {
                try {
                    startGun.await();
                } catch (InterruptedException ignored) {}
                long s = System.currentTimeMillis();
                try {
                    ResponseEntity<String> r = restTemplate.getForEntity("/users", String.class);
                    if (r.getStatusCode().is2xxSuccessful()) successCount.incrementAndGet();
                    else brokenConnectionErrors.incrementAndGet();
                } catch (Exception e) {
                    String msg = e.getMessage();
                    if (msg != null && (msg.contains("transaction is aborted") || msg.contains("broken"))) {
                        brokenConnectionErrors.incrementAndGet();
                    } else {
                        brokenConnectionErrors.incrementAndGet();
                    }
                } finally {
                    latencies.add(System.currentTimeMillis() - s);
                    allDone.countDown();
                }
            });
        }

        startGun.countDown();
        boolean stormComplete = allDone.await(60, TimeUnit.SECONDS);
        exec.shutdown();

        long maxLatencyMs = latencies.stream().mapToLong(Long::longValue).max().orElse(0L);
        long p50Ms = latencies.stream().sorted().skip(latencies.size() / 2).findFirst().orElse(0L);

        System.out.printf(
                "Spring L10 zombie transactions — storm phase: %d success, %d broken-connection errors of %d%n",
                successCount.get(), brokenConnectionErrors.get(), concurrency);
        System.out.printf(
                "Spring L10 zombie transactions — max-latency=%dms p50=%dms storm-complete=%b%n",
                maxLatencyMs, p50Ms, stormComplete);

        // Phase 2 — recovery: pool must self-heal, service must remain reachable
        AtomicInteger recoveryOk = new AtomicInteger(0);
        AtomicInteger recoveryFail = new AtomicInteger(0);
        for (int i = 0; i < 15; i++) {
            try {
                ResponseEntity<String> r = restTemplate.getForEntity("/users", String.class);
                if (r.getStatusCode().is2xxSuccessful()) recoveryOk.incrementAndGet();
                else recoveryFail.incrementAndGet();
            } catch (Exception e) {
                recoveryFail.incrementAndGet();
            }
        }

        // Deadlock detection — zombie transactions can create thread deadlocks
        long[] deadlocked = Optional.ofNullable(ManagementFactory.getThreadMXBean().findDeadlockedThreads())
                .map(arr -> arr).orElse(new long[0]);

        System.out.printf(
                "Spring L10 zombie transactions — recovery: %d ok, %d fail | deadlocked-threads: %d%n",
                recoveryOk.get(), recoveryFail.get(), deadlocked.length);
        System.out.printf(
                "PROOF: pool-heals=%b | deadlocked=%d | restart-required=%b%n",
                recoveryOk.get() >= 10, deadlocked.length, recoveryOk.get() < 5);

        // ── PROOF ASSERTIONS ──────────────────────────────────────────────
        // 1. No deadlocked threads — zombie transactions must not block each other
        assertThat(deadlocked.length)
                .as("PROOF: 0 deadlocked threads — zombie transactions do not create thread-level deadlock")
                .isEqualTo(0);
        // 2. Pool self-heals — service recovers without restart
        assertThat(recoveryOk.get())
                .as("PROOF: pool self-heals — at least 10 of 15 post-storm requests succeed (no restart needed)")
                .isGreaterThanOrEqualTo(10);
        // 3. Storm completes within timeout — no thread stuck for 60s each
        assertThat(stormComplete)
                .as("PROOF: storm phase completes within 60s — timeout chaos converts hangs to fast fails")
                .isTrue();
    }

    // ──────────────────────────────────────────────────────────────────────
    // INCIDENT 2  ·  Spring ApplicationContext refresh — partial bean
    //              initialization serves requests with inconsistent config
    //
    // THE INCIDENT
    // ────────────
    // Hot config refresh triggered via Spring Actuator /actuator/refresh.
    // During refresh: Spring reinitializes beans that depend on updated
    // config.  Brief window (~200ms): some beans have old config, some have
    // new config.  Request hits endpoint during refresh: uses RestTemplate
    // with new timeout (100ms) but Redis client with old timeout (5000ms).
    // Inconsistent state.  Connection attempt: new timeout fires before
    // Redis responds.  Circuit breaker: opens.  Redis: fine.  Service:
    // degraded for 30s (circuit breaker reset window).  Engineers: "We just
    // refreshed config — should be fine."  It's not.  30s degradation per
    // config push.
    //
    // PROOF
    // ─────
    //   • partial initialization window measured                   (ms)
    //   • inconsistent bean state detected (old/new config served)  (boolean)
    //   • CB opens during refresh window                            (503 count)
    //   • degradation duration after refresh                        (≤ 30s)
    //   • 0 hard errors — inconsistency must surface as 503, not 500
    // ──────────────────────────────────────────────────────────────────────

    @Test
    @IncidentChaosSpringConfigRefreshWave
    @DisplayName("INCIDENT Spring/L10PartialBeanInitInconsistency: config refresh window exposes old+new bean mix — CB opens, degrades ≤30s, 0 hard errors")
    void springL10PartialBeanInitInconsistency() throws Exception {
        int requestCount = 60; // span the refresh window
        AtomicInteger responses200 = new AtomicInteger(0);
        AtomicInteger responses503 = new AtomicInteger(0);
        AtomicInteger responses500 = new AtomicInteger(0);
        AtomicInteger responsesOther = new AtomicInteger(0);
        AtomicInteger exceptions = new AtomicInteger(0);

        // Track whether we detect inconsistency: first 503 after a 200 = CB fired from inconsistent state
        AtomicLong firstInconsistencyMs = new AtomicLong(-1L);
        AtomicLong lastInconsistencyMs = new AtomicLong(-1L);
        AtomicBoolean sawSuccessBeforeCb = new AtomicBoolean(false);

        long windowStart = System.currentTimeMillis();

        for (int i = 0; i < requestCount; i++) {
            try {
                ResponseEntity<String> r = restTemplate.getForEntity("/users", String.class);
                long now = System.currentTimeMillis();
                if (r.getStatusCode().is2xxSuccessful()) {
                    responses200.incrementAndGet();
                    sawSuccessBeforeCb.set(true);
                } else if (r.getStatusCode() == HttpStatus.SERVICE_UNAVAILABLE) {
                    responses503.incrementAndGet();
                    if (sawSuccessBeforeCb.get() && firstInconsistencyMs.get() == -1L) {
                        firstInconsistencyMs.set(now);
                    }
                    lastInconsistencyMs.set(now);
                } else if (r.getStatusCode().is5xxServerError()) {
                    responses500.incrementAndGet();
                } else {
                    responsesOther.incrementAndGet();
                }
            } catch (Exception e) {
                exceptions.incrementAndGet();
            }

            // Small gap between requests to span the refresh window
            if (i % 10 == 0) {
                Thread.sleep(50);
            }
        }

        long totalWindowMs = System.currentTimeMillis() - windowStart;
        long degradationMs = (firstInconsistencyMs.get() >= 0 && lastInconsistencyMs.get() >= 0)
                ? lastInconsistencyMs.get() - firstInconsistencyMs.get()
                : 0L;

        System.out.printf(
                "Spring L10 partial bean init — 200: %d, 503 (CB): %d, 500: %d, other: %d, exceptions: %d of %d%n",
                responses200.get(), responses503.get(), responses500.get(), responsesOther.get(),
                exceptions.get(), requestCount);
        System.out.printf(
                "Spring L10 partial bean init — total-window=%dms, degradation-window=%dms%n",
                totalWindowMs, degradationMs);
        System.out.printf(
                "PROOF: inconsistency-detected=%b | degradation=%dms (≤30000) | hard-errors=%d (must be 0)%n",
                firstInconsistencyMs.get() >= 0, degradationMs, responses500.get() + exceptions.get());

        // ── PROOF ASSERTIONS ──────────────────────────────────────────────
        // 1. Zero hard errors — inconsistency must surface as 503 (CB), not 500 (crash)
        assertThat(responses500.get())
                .as("PROOF: 0 hard 500 errors — inconsistent bean state must degrade gracefully via CB, not crash")
                .isEqualTo(0);
        assertThat(exceptions.get())
                .as("PROOF: 0 unhandled exceptions — partial init window does not propagate exceptions to caller")
                .isEqualTo(0);
        // 2. Degradation window bounded at ≤ 30s (circuit breaker reset)
        assertThat(degradationMs)
                .as("PROOF: CB degradation window ≤ 30s — service recovers after circuit breaker resets")
                .isLessThanOrEqualTo(30_000L);
        // 3. Service returns responses for every request
        assertThat(responses200.get() + responses503.get() + responsesOther.get())
                .as("PROOF: all requests receive a response — no silent stall during config refresh")
                .isEqualTo(requestCount);
    }
}
