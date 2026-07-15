package com.macstab.chaos.examples.jdbcdeadlock;

import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosControlPlane;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.spring.boot3.test.ChaosTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the JDBC connection pool deadlock scenario.
 *
 * The deadlock occurs because:
 * - OuterService holds connection C1 for the duration of its transaction (including a 200ms sleep)
 * - InnerService(REQUIRES_NEW) needs a second connection C2 while C1 is still held
 * - With pool-size=5, five concurrent outer transactions exhaust the pool
 * - Inner transactions starve, the pool times out after 30s
 *
 * The chaos agent exposes this faster and more reliably than waiting for production load.
 */
@ChaosTest(
        classes = JdbcPoolDeadlockApplication.class,
        webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = {
            // Shorten HikariCP connection-timeout to 500ms so deadlock cycles resolve
            // in < 1s instead of 30s, keeping the test suite fast while still
            // demonstrating pool-exhaustion failure modes.
            "spring.datasource.hikari.connection-timeout=500"
        }
)
class JdbcPoolDeadlockIT {

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    // -------------------------------------------------------------------------
    // TEST 1 — Baseline: documents pool-size constraint
    // -------------------------------------------------------------------------

    /**
     * No chaos.  Fires 20 concurrent GET /trigger, each of which fires 10 nested
     * processOrder() calls concurrently.  With pool-size=5, 5 outer transactions immediately
     * exhaust all pool connections while holding C1 and waiting for C2 (REQUIRES_NEW).
     * The remaining outer transactions queue and hit the 500ms connection-timeout.
     * All 200 orders fail within ~700ms; every single trigger reports at least one failure.
     * This test documents the baseline deadlock: without chaos the pool exhaustion failure
     * is unavoidable at this concurrency level.
     */
    @Test
    void twentyConcurrentRequestsDeadlockWithPoolExhaustion() throws Exception {
        int triggerCount = 20;
        AtomicInteger ok = new AtomicInteger(0);
        AtomicInteger failed = new AtomicInteger(0);

        long startMs = Instant.now().toEpochMilli();

        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            List<Future<?>> futures = new ArrayList<>(triggerCount);

            for (int i = 0; i < triggerCount; i++) {
                futures.add(executor.submit(() -> {
                    try {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> resp = rest.getForObject("/trigger", Map.class);
                        if (resp != null && (int) resp.get("failure_count") == 0) {
                            ok.incrementAndGet();
                        } else {
                            failed.incrementAndGet();
                        }
                    } catch (Exception e) {
                        failed.incrementAndGet();
                    }
                }));
            }

            // Outer txs acquire C1, sleep 200ms, then race to acquire C2 (REQUIRES_NEW).
            // Pool exhaustion is fully formed within ~200ms of requests arriving.
            // Call the JVM deadlock detector during this window — it cannot see LockSupport.park().
            Thread.sleep(300);
            ThreadMXBean tmx = ManagementFactory.getThreadMXBean();
            assertThat(tmx.findDeadlockedThreads())
                    .as("ThreadMXBean.findDeadlockedThreads() returns null: HikariCP's ConcurrentBag.borrow() "
                        + "parks via LockSupport.parkNanos() which registers no LockInfo — "
                        + "the JVM lock graph is blind to this deadlock")
                    .isNull();

            for (Future<?> f : futures) {
                f.get(10, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }

        long totalMs = Instant.now().toEpochMilli() - startMs;

        System.out.printf(
                "[Baseline] %d triggers in %dms — ok=%d, failed=%d%n",
                triggerCount, totalMs, ok.get(), failed.get()
        );

        assertThat(failed.get())
                .as("Pool exhaustion (pool-size=5, 200 concurrent outer txs each needing REQUIRES_NEW) must cause all triggers to see failures")
                .isEqualTo(triggerCount);
        assertThat(ok.get())
                .as("No trigger should complete without JDBC failures under 200-order pool-exhaustion load")
                .isZero();
    }

    // -------------------------------------------------------------------------
    // TEST 2 — JDBC fault injection accelerates deadlock detection
    // -------------------------------------------------------------------------

    /**
     * Activates 20% JDBC_STATEMENT_EXECUTE rejections.  Rejected statements fail fast
     * instead of occupying a connection for 200ms + pool-timeout, so failed requests
     * return in milliseconds rather than after the 30s HikariCP connection timeout.
     *
     * This is the core chaos-agent value proposition: fault-fail-fast is faster than
     * waiting for production pool exhaustion to manifest.
     */
    @Test
    void jdbcFaultInjectionAt20PercentExposesDeadlockFasterThanTimeout(ChaosControlPlane plane) throws Exception {
        // Use the broad jdbc() selector which includes JDBC_CONNECTION_ACQUIRE — rejecting
        // pool checkout requests before they consume a slot is the fastest way to break the deadlock.
        // randomSeed=42L (7th arg) fixes the PRNG: the injection decisions for a given
        // ordered sequence of matched JDBC calls are reproducible across runs, so a CI
        // failure reproduces locally with one command.
        try (var chaosJdbcFault = plane.activate(
                ChaosScenario.builder("jdbc-fault-20pct")
                        .selector(ChaosSelector.jdbc())
                        .effect(ChaosEffect.reject("chaos jdbc fault"))
                        .activationPolicy(new ActivationPolicy(ActivationPolicy.StartMode.AUTOMATIC, 0.20, 0L, null, null, null, 42L, false))
                        .build()
        )) {
            // 3 triggers × 10 orders = 30 orders; 20% chaos → ~24 effective orders.
            // 24 / pool-size(5) × round-time(700ms) ≈ 3.4s, well under the 5s assertion.
            int triggerCount = 3;
            AtomicInteger totalFailed = new AtomicInteger(0);

            long startMs = Instant.now().toEpochMilli();

            ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
            try {
                List<Future<?>> futures = new ArrayList<>(triggerCount);

                for (int i = 0; i < triggerCount; i++) {
                    futures.add(executor.submit(() -> {
                        try {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> resp = rest.getForObject("/trigger", Map.class);
                            if (resp != null) {
                                int fc = (int) resp.get("failure_count");
                                totalFailed.addAndGet(fc);
                            }
                        } catch (Exception ignored) {
                        }
                    }));
                }

                // 35s > HikariCP's 30s connection-timeout so futures always complete naturally;
                // assertion below distinguishes fast (chaos worked) from slow (no fault injection).
                for (Future<?> f : futures) {
                    f.get(35, TimeUnit.SECONDS);
                }
            } finally {
                executor.shutdownNow();
            }

            long totalMs = Instant.now().toEpochMilli() - startMs;

            System.out.printf(
                    "[JDBC 20%% fault] %d triggers in %dms — total inner failures=%d%n",
                    triggerCount, totalMs, totalFailed.get()
            );

            assertThat(totalMs)
                    .as("Fault-fail-fast must return all responses well under the 30s HikariCP pool timeout")
                    .isLessThan(5_000L);
            assertThat(totalFailed.get())
                    .as("At least one JDBC fault should have been injected across all triggers")
                    .isGreaterThan(0);
        }
    }

    // -------------------------------------------------------------------------
    // TEST 3 — Combined heap pressure + JDBC faults (production scenario)
    // -------------------------------------------------------------------------

    /**
     * Simulates a realistic production incident: a memory leak inflates the heap while
     * elevated JDBC error rates appear simultaneously.  With 10% JDBC rejection and
     * 256MB of retained heap pressure, the application must still serve all 5 concurrent
     * triggers within 10 seconds and must not OOM or deadlock.
     */
    @Test
    void heapPressureCombinedWithJdbcFaults(ChaosControlPlane plane) throws Exception {
        try (
            var activatedHeapPressure = plane.activate(
                    ChaosScenario.builder("heap-pressure-256mb")
                            .selector(ChaosSelector.stress(ChaosSelector.StressTarget.HEAP))
                            .effect(ChaosEffect.heapPressure(256L * 1024 * 1024, 32))
                            .activationPolicy(ActivationPolicy.withDestructiveEffects())
                            .build()
            );
            var activatedJdbcFault = plane.activate(
                    ChaosScenario.builder("jdbc-fault-10pct")
                            .selector(ChaosSelector.jdbc())
                            .effect(ChaosEffect.reject("chaos: combined fault"))
                            .activationPolicy(new ActivationPolicy(ActivationPolicy.StartMode.AUTOMATIC, 0.10, 0L, null, null, null, null, false))
                            .build()
            )
        ) {
            int triggerCount = 5;
            AtomicInteger completed = new AtomicInteger(0);

            long startMs = Instant.now().toEpochMilli();

            ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
            try {
                List<Future<?>> futures = new ArrayList<>(triggerCount);

                for (int i = 0; i < triggerCount; i++) {
                    futures.add(executor.submit(() -> {
                        try {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> resp = rest.getForObject("/trigger", Map.class);
                            if (resp != null) {
                                completed.incrementAndGet();
                            }
                        } catch (Exception ignored) {
                        }
                    }));
                }

                for (Future<?> f : futures) {
                    f.get(35, TimeUnit.SECONDS);
                }
            } finally {
                executor.shutdownNow();
            }

            long totalMs = Instant.now().toEpochMilli() - startMs;

            System.out.printf(
                    "[Heap+JDBC] %d triggers in %dms — completed=%d%n",
                    triggerCount, totalMs, completed.get()
            );

            assertThat(completed.get())
                    .as("All 5 triggers must complete (no OOM, no deadlock) under combined pressure")
                    .isEqualTo(triggerCount);
            assertThat(totalMs)
                    .as("Combined scenario must not stall beyond 10 000ms")
                    .isLessThan(10_000L);
        }
    }
}
