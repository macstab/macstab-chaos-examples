package com.macstab.chaos.examples.impossible;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.macstab.chaos.agent.test.annotation.ChaosTest;
import com.macstab.chaos.agent.test.dsl.ChaosEffect;
import com.macstab.chaos.agent.test.dsl.ChaosSelector;
import com.macstab.chaos.agent.test.dsl.ChaosSession;
import com.macstab.chaos.agent.test.dsl.FailureKind;
import com.macstab.chaos.agent.test.dsl.NamePattern;
import com.macstab.chaos.agent.test.dsl.OperationType;
import com.macstab.chaos.agent.test.dsl.ReturnValueStrategy;
import com.macstab.chaos.agent.test.dsl.StressTarget;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Eight tests that demonstrate chaos scenarios no conventional testing approach can
 * reproduce reliably.  Each test uses the chaos agent to inject a specific JVM-level
 * fault or stressor, then verifies either resilience or correct failure semantics.
 *
 * WireMock stubs the downstream HTTP service so the fan-out HTTP calls can succeed
 * (or fail when the chaos agent injects HTTP-level faults).
 */
@ChaosTest(classes = ImpossibleApplication.class, webEnvironment = WebEnvironment.RANDOM_PORT)
class ThingsNobodyElseCanTestIT {

    private static WireMockServer wireMock;

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    @Autowired
    ChaosSession chaos;

    @Autowired
    FanOutService fanOutService;

    @BeforeAll
    static void startWireMock() {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();
    }

    @AfterAll
    static void stopWireMock() {
        if (wireMock != null) {
            wireMock.stop();
        }
    }

    @BeforeEach
    void stubDownstream() {
        wireMock.resetAll();
        wireMock.stubFor(get(urlEqualTo("/downstream"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("ok")
                        .withHeader("Content-Type", "text/plain")));
        // Point the service at the WireMock instance for this test
        fanOutService.setDownstreamUrl("http://localhost:" + wireMock.port());
    }

    // -------------------------------------------------------------------------
    // TEST 1 — JVM safepoint cascade
    // -------------------------------------------------------------------------

    /**
     * Activates a safepoint storm (50ms stop-the-world pauses injected repeatedly).
     * Fires 200 concurrent /fanout?count=5 requests via virtual threads.
     * Verifies that safepoint pauses do not cause request drops or unbounded latency:
     * all 200 requests must complete, and p99 wall time per request must stay under 500ms.
     */
    @Test
    void testSafepointCascadeDoesNotDropRequests() throws Exception {
        try (var _ = chaos.activate(
                ChaosSelector.stress(StressTarget.SAFEPOINT),
                ChaosEffect.safepointStorm(Duration.ofMillis(50))
        )) {
            int concurrency = 200;
            long[] latenciesNs = new long[concurrency];
            List<Future<?>> futures = new ArrayList<>(concurrency);

            try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
                for (int i = 0; i < concurrency; i++) {
                    final int idx = i;
                    futures.add(executor.submit(() -> {
                        long t0 = System.nanoTime();
                        try {
                            rest.getForObject("/fanout?count=5", Map.class);
                        } finally {
                            latenciesNs[idx] = System.nanoTime() - t0;
                        }
                    }));
                }
                for (Future<?> f : futures) {
                    f.get(30, TimeUnit.SECONDS);
                }
            }

            // Compute p99
            long[] sorted = latenciesNs.clone();
            java.util.Arrays.sort(sorted);
            long p99Ns = sorted[(int) (sorted.length * 0.99)];
            long p99Ms = p99Ns / 1_000_000L;

            System.out.printf("[Safepoint] p99 latency = %dms over %d requests%n", p99Ms, concurrency);

            assertThat(p99Ms)
                    .as("p99 request latency must stay below 500ms even under safepoint storm")
                    .isLessThan(500L);
        }
    }

    // -------------------------------------------------------------------------
    // TEST 2 — Code cache exhaustion
    // -------------------------------------------------------------------------

    /**
     * Activates code cache pressure so the JVM cannot compile new methods, forcing
     * interpreted execution.  Runs a CPU-intensive summation 50 000 times and verifies
     * the result is mathematically correct (interpreter produces the same answer as JIT).
     * Also checks that JIT compilation time did not increase during the pressured window,
     * confirming the agent successfully suppressed new compilations.
     */
    @Test
    void testCodeCacheExhaustionFallsBackToInterpreter() throws Exception {
        var compilationBean = ManagementFactory.getCompilationMXBean();
        long compilationTimeBefore = compilationBean != null
                ? compilationBean.getTotalCompilationTime()
                : -1L;

        try (var _ = chaos.activate(
                ChaosSelector.stress(StressTarget.CODE_CACHE),
                ChaosEffect.codeCachePressure()
        )) {
            long compilationTimeDuringStart = compilationBean != null
                    ? compilationBean.getTotalCompilationTime()
                    : -1L;

            // CPU-intensive work — must produce correct result in interpreter mode
            long sum = 0;
            for (int i = 0; i < 50_000; i++) {
                sum += i;
            }
            long expectedSum = (long) 49_999 * 50_000 / 2;

            long compilationTimeDuringEnd = compilationBean != null
                    ? compilationBean.getTotalCompilationTime()
                    : -1L;

            System.out.printf(
                    "[CodeCache] sum=%d (expected %d), JIT time before=%dms, during_start=%dms, during_end=%dms%n",
                    sum, expectedSum, compilationTimeBefore, compilationTimeDuringStart, compilationTimeDuringEnd
            );

            assertThat(sum)
                    .as("Summation result must be mathematically correct even under code cache pressure")
                    .isEqualTo(expectedSum);

            if (compilationBean != null) {
                assertThat(compilationTimeDuringEnd - compilationTimeDuringStart)
                        .as("JIT compilation time should not increase during code cache pressure (new compilations suppressed)")
                        .isLessThanOrEqualTo(50L); // allow small tolerance for background JIT activity
            }
        }
    }

    // -------------------------------------------------------------------------
    // TEST 3 — Virtual thread carrier exhaustion under monitor contention
    // -------------------------------------------------------------------------

    /**
     * Activates monitor contention with 8 background contenders — threads that repeatedly
     * enter synchronized blocks, causing virtual threads that hit the same monitors to pin
     * their carrier threads briefly.
     *
     * Fires 500 concurrent /fanout?count=1 requests on virtual threads.
     * Verifies: all 500 complete within 15s (no starvation), and the platform thread count
     * stays below 50 (virtual threads are not leaking to platform threads).
     */
    @Test
    void testVirtualThreadCarrierExhaustionUnderMonitorContention() throws Exception {
        ThreadMXBean threadMX = ManagementFactory.getThreadMXBean();

        try (var _ = chaos.activate(
                ChaosSelector.stress(StressTarget.MONITOR_CONTENTION),
                ChaosEffect.monitorContention(8)
        )) {
            int concurrency = 500;
            List<Future<?>> futures = new ArrayList<>(concurrency);

            try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
                for (int i = 0; i < concurrency; i++) {
                    futures.add(executor.submit(() ->
                            rest.getForObject("/fanout?count=1", Map.class)
                    ));
                }

                long startMs = System.currentTimeMillis();
                for (Future<?> f : futures) {
                    f.get(15, TimeUnit.SECONDS);
                }
                long wallMs = System.currentTimeMillis() - startMs;

                int platformThreadCount = threadMX.getThreadCount();

                System.out.printf(
                        "[MonitorContention] %d requests in %dms, platform threads=%d%n",
                        concurrency, wallMs, platformThreadCount
                );

                assertThat(wallMs)
                        .as("All 500 virtual thread requests must complete within 15 000ms despite monitor contention")
                        .isLessThan(15_000L);
                assertThat(platformThreadCount)
                        .as("Platform thread count must stay below 50 — virtual threads must not inflate to platform threads")
                        .isLessThan(50);
            }
        }
    }

    // -------------------------------------------------------------------------
    // TEST 4 — ThreadLocal leak on pooled threads
    // -------------------------------------------------------------------------

    /**
     * Activates a ThreadLocal leak stressor that stores 1MB per thread in a ThreadLocal
     * on every pooled/carrier thread that the stressor touches.  Fires 200 requests, then
     * forces a GC and measures heap delta.
     *
     * Because pooled threads live for the JVM lifetime, their ThreadLocals are never GC'd.
     * The delta must be positive, confirming the leak is retained.  This is a class of
     * memory leak that only shows up in long-running processes — invisible in unit tests.
     */
    @Test
    void testThreadLocalLeakDetectionViaStressor() throws Exception {
        try (var _ = chaos.activate(
                ChaosSelector.stress(StressTarget.THREAD_LOCAL_LEAK),
                ChaosEffect.threadLocalLeak(1024 * 1024) // 1MB per thread
        )) {
            // Warm up — establish a baseline after stressor has touched a few threads
            for (int i = 0; i < 10; i++) {
                rest.getForObject("/fanout?count=1", Map.class);
            }

            System.gc();
            Thread.sleep(200); // allow GC to complete
            long before = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();

            // Fire 200 requests so the stressor leaks into all pooled carrier threads
            List<Future<?>> futures = new ArrayList<>(200);
            try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
                for (int i = 0; i < 200; i++) {
                    futures.add(executor.submit(() ->
                            rest.getForObject("/fanout?count=1", Map.class)
                    ));
                }
                for (Future<?> f : futures) {
                    f.get(30, TimeUnit.SECONDS);
                }
            }

            System.gc();
            Thread.sleep(200);
            long after = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();

            long deltaBytes = after - before;
            System.out.printf(
                    "[ThreadLocalLeak] before=%d bytes, after=%d bytes, delta=%+d bytes (%.2f MB)%n",
                    before, after, deltaBytes, deltaBytes / 1_048_576.0
            );

            assertThat(after)
                    .as("Heap must be larger after leak stressor: ThreadLocals on pooled threads are never GC'd")
                    .isGreaterThan(before);
        }
    }

    // -------------------------------------------------------------------------
    // TEST 5 — CompletableFuture exceptional completion propagation
    // -------------------------------------------------------------------------

    /**
     * Activates 50% exceptional completion injection on ASYNC_COMPLETE_EXCEPTIONALLY operations.
     * Fires /fanout?count=20 and verifies:
     * - The endpoint handles partial failure gracefully (no 500)
     * - Some tasks succeeded (50% success rate expected)
     * - Some tasks received the injected exception (failed > 0)
     */
    @Test
    void testCompletableFutureExceptionalCompletionPropagated() throws Exception {
        try (var _ = chaos.activate(
                ChaosSelector.async(Set.of(OperationType.ASYNC_COMPLETE_EXCEPTIONALLY)),
                ChaosEffect.exceptionalCompletion(FailureKind.RUNTIME, "chaos: async failure injected"),
                0.50
        )) {
            @SuppressWarnings("unchecked")
            Map<String, Object> resp = rest.getForObject("/fanout?count=20", Map.class);

            assertThat(resp).isNotNull();

            long succeeded = ((Number) resp.get("succeeded")).longValue();
            long failed = ((Number) resp.get("failed")).longValue();

            System.out.printf(
                    "[AsyncExceptional] succeeded=%d, failed=%d out of 20 tasks%n",
                    succeeded, failed
            );

            assertThat(succeeded)
                    .as("At least some futures must have completed normally at 50%% exception rate")
                    .isGreaterThan(0L);
            assertThat(failed)
                    .as("At least one future must have received the injected exceptional completion")
                    .isGreaterThanOrEqualTo(1L);
        }
    }

    // -------------------------------------------------------------------------
    // TEST 6 — Live deadlock does not block the request path
    // -------------------------------------------------------------------------

    /**
     * Activates a deadlock stressor that puts 4 background threads into a permanent
     * circular lock wait.  Fires 50 fan-out requests and verifies:
     * - All 50 requests return 200 (deadlock is confined to background threads)
     * - {@link ThreadMXBean#findDeadlockedThreads()} reports the deadlock is real
     *
     * This documents that the application can be designed to be deadlock-resilient:
     * deadlocks in background threads do not propagate to the request path.
     */
    @Test
    void testLiveChaosDeadlockDoesNotBlockRequestPath() throws Exception {
        ThreadMXBean threadMX = ManagementFactory.getThreadMXBean();

        try (var _ = chaos.activate(
                ChaosSelector.stress(StressTarget.DEADLOCK),
                ChaosEffect.deadlock(4)
        )) {
            // Give the deadlock stressor a moment to establish the cycle
            Thread.sleep(500);

            int requestCount = 50;
            AtomicLong successCount = new AtomicLong(0);

            try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
                List<Future<?>> futures = new ArrayList<>(requestCount);
                for (int i = 0; i < requestCount; i++) {
                    futures.add(executor.submit(() -> {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> resp = rest.getForObject("/fanout?count=5", Map.class);
                        if (resp != null) {
                            successCount.incrementAndGet();
                        }
                    }));
                }
                for (Future<?> f : futures) {
                    f.get(30, TimeUnit.SECONDS);
                }
            }

            long[] deadlockedThreadIds = threadMX.findDeadlockedThreads();

            System.out.printf(
                    "[LiveDeadlock] %d/%d requests completed successfully. " +
                    "Live deadlock confirmed in JVM: %s%n",
                    successCount.get(), requestCount,
                    deadlockedThreadIds != null
                            ? deadlockedThreadIds.length + " threads deadlocked"
                            : "no deadlock detected (stressor may not have fired yet)"
            );

            assertThat(successCount.get())
                    .as("All 50 requests must succeed — deadlock is in background threads, not request path")
                    .isEqualTo(requestCount);
            assertThat(deadlockedThreadIds)
                    .as("findDeadlockedThreads() must return non-null: the chaos deadlock must be active in JVM")
                    .isNotNull();
        }
    }

    // -------------------------------------------------------------------------
    // TEST 7 — Return value corruption from JDBC
    // -------------------------------------------------------------------------

    /**
     * Activates 20% NULL return value corruption on JDBC_STATEMENT_EXECUTE operations.
     * The application must handle null JDBC results gracefully (null checks, Optional,
     * safe defaults) and must not throw 500s.
     *
     * Fires 100 /fanout?count=5 requests and verifies:
     * - No response is a 500 (null handled gracefully)
     * - At least one request shows failed > 0 (corruption was injected and detected)
     */
    @Test
    void testReturnValueCorruptionHandledGracefully() throws Exception {
        try (var _ = chaos.activate(
                ChaosSelector.method(
                        Set.of(OperationType.JDBC_STATEMENT_EXECUTE),
                        NamePattern.any(),
                        NamePattern.any()
                ),
                ChaosEffect.corruptReturnValue(ReturnValueStrategy.NULL),
                0.20
        )) {
            int requestCount = 100;
            AtomicLong totalFailed = new AtomicLong(0);
            AtomicLong http500Count = new AtomicLong(0);

            try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
                List<Future<?>> futures = new ArrayList<>(requestCount);

                for (int i = 0; i < requestCount; i++) {
                    futures.add(executor.submit(() -> {
                        try {
                            var entity = rest.getForEntity("/fanout?count=5", Map.class);
                            if (entity.getStatusCode().is5xxServerError()) {
                                http500Count.incrementAndGet();
                            } else if (entity.getBody() != null) {
                                long fc = ((Number) entity.getBody().get("failed")).longValue();
                                totalFailed.addAndGet(fc);
                            }
                        } catch (Exception e) {
                            http500Count.incrementAndGet();
                        }
                    }));
                }

                for (Future<?> f : futures) {
                    f.get(30, TimeUnit.SECONDS);
                }
            }

            System.out.printf(
                    "[NullCorruption] %d requests — http500=%d, task failures=%d%n",
                    requestCount, http500Count.get(), totalFailed.get()
            );

            assertThat(http500Count.get())
                    .as("No request should result in HTTP 500 — null return values must be handled gracefully")
                    .isEqualTo(0L);
            assertThat(totalFailed.get())
                    .as("At least one task must have failed due to null JDBC return value corruption")
                    .isGreaterThanOrEqualTo(1L);
        }
    }

    // -------------------------------------------------------------------------
    // TEST 8 — GC pressure does not drop scheduled tasks
    // -------------------------------------------------------------------------

    /**
     * Activates GC pressure at 500MB/s allocation rate, then observes the scheduler
     * counter over 5 seconds.  The scheduler fires at 100ms intervals (~10/s), so
     * 5 seconds should produce at least 40 increments (50 expected, 20% GC tolerance).
     *
     * Verifies that GC stop-the-world pauses do not silently drop scheduled task
     * invocations — a failure mode that never appears in unit tests but surfaces under
     * sustained production GC load.
     */
    @Test
    void testGcPressureDoesNotDropScheduledTasks() throws Exception {
        // Read the baseline counter before activating chaos
        @SuppressWarnings("unchecked")
        Map<String, Object> baseline = rest.getForObject("/scheduler/count", Map.class);
        long counterBefore = baseline != null ? ((Number) baseline.get("count")).longValue() : 0L;

        try (var _ = chaos.activate(
                ChaosSelector.stress(StressTarget.GC_PRESSURE),
                ChaosEffect.gcPressure(500) // 500MB/s allocation rate
        )) {
            Thread.sleep(5_000);
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> result = rest.getForObject("/scheduler/count", Map.class);
        long counterAfter = result != null ? ((Number) result.get("count")).longValue() : 0L;

        long delta = counterAfter - counterBefore;

        System.out.printf(
                "[GcPressure] scheduler counter: before=%d, after=%d, delta=%d " +
                "(expected >=40 in 5s at 100ms interval)%n",
                counterBefore, counterAfter, delta
        );

        assertThat(delta)
                .as("Scheduler must fire at least 40 times in 5s (100ms interval, 20%% GC tolerance) — " +
                    "GC pressure must not drop scheduled task invocations")
                .isGreaterThanOrEqualTo(40L);
    }
}
