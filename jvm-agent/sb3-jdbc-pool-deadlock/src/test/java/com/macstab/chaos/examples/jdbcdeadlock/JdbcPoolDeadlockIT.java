package com.macstab.chaos.examples.jdbcdeadlock;

import com.macstab.chaos.agent.test.annotation.ChaosTest;
import com.macstab.chaos.agent.test.dsl.ChaosEffect;
import com.macstab.chaos.agent.test.dsl.ChaosSelector;
import com.macstab.chaos.agent.test.dsl.ChaosSession;
import com.macstab.chaos.agent.test.dsl.HeapPressureEffect;
import com.macstab.chaos.agent.test.dsl.OperationType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;

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
@ChaosTest(classes = JdbcPoolDeadlockApplication.class, webEnvironment = WebEnvironment.RANDOM_PORT)
class JdbcPoolDeadlockIT {

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    @Autowired
    ChaosSession chaos;

    // -------------------------------------------------------------------------
    // TEST 1 — Baseline: documents pool-size constraint
    // -------------------------------------------------------------------------

    /**
     * No chaos.  Fires 20 concurrent GET /trigger, each of which fires 10 nested
     * processOrder() calls.  With pool-size=5 and 10 concurrent outer calls per trigger,
     * only 5 outer transactions can hold connections simultaneously; the remaining 5 queue.
     * When the first 5 complete and release C1, the next 5 start and the inner REQUIRES_NEW
     * gets C2.  This test documents that sequential batching avoids deadlock at pool-size=5,
     * but 6+ simultaneous outer transactions with pool-size=5 will deadlock.
     */
    @Test
    void twentyConcurrentRequestsCompleteWithin5Seconds() throws Exception {
        int triggerCount = 20;
        AtomicInteger ok = new AtomicInteger(0);
        AtomicInteger failed = new AtomicInteger(0);

        long startMs = Instant.now().toEpochMilli();

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
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

            for (Future<?> f : futures) {
                f.get(5, TimeUnit.SECONDS);
            }
        }

        long totalMs = Instant.now().toEpochMilli() - startMs;

        System.out.printf(
                "[Baseline] %d triggers in %dms — ok=%d, failed=%d%n",
                triggerCount, totalMs, ok.get(), failed.get()
        );

        assertThat(totalMs)
                .as("All 20 concurrent /trigger requests must complete within 5 000ms")
                .isLessThan(5_000L);
        assertThat(ok.get())
                .as("All triggers should report zero failures in the no-chaos baseline")
                .isEqualTo(triggerCount);
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
    void jdbcFaultInjectionAt20PercentExposesDeadlockFasterThanTimeout() throws Exception {
        try (var _ = chaos.activate(
                ChaosSelector.operation(OperationType.JDBC_STATEMENT_EXECUTE),
                ChaosEffect.reject("chaos jdbc fault"),
                0.20
        )) {
            int triggerCount = 10;
            AtomicInteger totalFailed = new AtomicInteger(0);

            long startMs = Instant.now().toEpochMilli();

            try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
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

                for (Future<?> f : futures) {
                    f.get(3, TimeUnit.SECONDS);
                }
            }

            long totalMs = Instant.now().toEpochMilli() - startMs;

            System.out.printf(
                    "[JDBC 20%% fault] %d triggers in %dms — total inner failures=%d%n",
                    triggerCount, totalMs, totalFailed.get()
            );

            assertThat(totalMs)
                    .as("Fault-fail-fast must return all responses within 3 000ms, not wait for 30s pool timeout")
                    .isLessThan(3_000L);
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
    void heapPressureCombinedWithJdbcFaults() throws Exception {
        try (
            var _ = chaos.activate(HeapPressureEffect.retaining(256).megabytes());
            var __ = chaos.activate(
                    ChaosSelector.operation(OperationType.JDBC_STATEMENT_EXECUTE),
                    ChaosEffect.reject("chaos: combined fault"),
                    0.10
            )
        ) {
            int triggerCount = 5;
            AtomicInteger completed = new AtomicInteger(0);

            long startMs = Instant.now().toEpochMilli();

            try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
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
                    f.get(10, TimeUnit.SECONDS);
                }
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
