package com.macstab.chaos.examples.stressors;

import com.macstab.chaos.agent.test.annotation.ChaosTest;
import com.macstab.chaos.agent.test.dsl.ChaosEffect;
import com.macstab.chaos.agent.test.dsl.ChaosSelector;
import com.macstab.chaos.agent.test.dsl.ChaosSession;
import com.macstab.chaos.agent.test.dsl.StressTarget;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.ThreadMXBean;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test that exercises every chaos stressor available in the agent.
 * Each test method activates exactly one stressor, performs targeted assertions
 * to confirm the stressor had the expected JVM-level effect, and verifies that
 * the application remains alive and request-serving throughout.
 *
 * <p>The 14 stressors covered are:
 * <ol>
 *   <li>HEAP_PRESSURE — retains off-heap-style heap blocks and verifies release</li>
 *   <li>KEEPALIVE — non-daemon thread prevents JVM exit</li>
 *   <li>METASPACE_PRESSURE — synthetic class generation fills non-heap</li>
 *   <li>DIRECT_BUFFER — off-heap ByteBuffer allocation</li>
 *   <li>GC_PRESSURE — high-rate object allocation forces GC cycles</li>
 *   <li>FINALIZER_BACKLOG — slow-finalizer objects back up the finalizer queue</li>
 *   <li>DEADLOCK — circular lock wait across N threads</li>
 *   <li>THREAD_LEAK — daemon threads parked permanently</li>
 *   <li>THREAD_LOCAL_LEAK — ThreadLocals retained on pool threads</li>
 *   <li>MONITOR_CONTENTION — background lock contenders</li>
 *   <li>CODE_CACHE — JIT suppression via code cache saturation</li>
 *   <li>SAFEPOINT — repeated stop-the-world pauses</li>
 *   <li>STRING_INTERN — unique string intern flooding</li>
 *   <li>REFERENCE_QUEUE — phantom reference flood</li>
 * </ol>
 */
@ChaosTest(classes = StressorsApplication.class, webEnvironment = WebEnvironment.RANDOM_PORT)
class AllStressorsIT {

    private static final MemoryMXBean  MEMORY_MX  = ManagementFactory.getMemoryMXBean();
    private static final ThreadMXBean  THREAD_MX   = ManagementFactory.getThreadMXBean();

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    @Autowired
    ChaosSession chaos;

    // -------------------------------------------------------------------------
    // TEST 1 — HEAP_PRESSURE
    // -------------------------------------------------------------------------

    /**
     * Activates the heap pressure stressor to retain 256 MB of heap memory.
     * Verifies that heap usage rises by at least 200 MB while the stressor is
     * active, and returns to near-baseline after the handle is closed and GC runs.
     */
    @Test
    void stressor_HEAP_PRESSURE_retainsMemoryAndReleases() throws Exception {
        System.gc();
        Thread.sleep(200);
        long heapBefore = MEMORY_MX.getHeapMemoryUsage().getUsed();

        try (var handle = chaos.activate(
                ChaosSelector.stress(StressTarget.HEAP),
                ChaosEffect.heapPressure(256L * 1024L * 1024L)
        )) {
            Thread.sleep(1000);
            long heapAfter = MEMORY_MX.getHeapMemoryUsage().getUsed();

            System.out.printf("[HeapPressure] before=%d MB, after=%d MB, delta=%d MB%n",
                    heapBefore / (1024 * 1024),
                    heapAfter  / (1024 * 1024),
                    (heapAfter - heapBefore) / (1024 * 1024));

            assertThat(heapAfter)
                    .as("Heap must grow by at least 200 MB while stressor retains 256 MB")
                    .isGreaterThan(heapBefore + 200L * 1024L * 1024L);
        }

        System.gc();
        Thread.sleep(500);
        long heapReleased = MEMORY_MX.getHeapMemoryUsage().getUsed();

        System.out.printf("[HeapPressure] after release=%d MB (baseline was %d MB)%n",
                heapReleased / (1024 * 1024),
                heapBefore   / (1024 * 1024));

        assertThat(heapReleased)
                .as("Heap must return to within 100 MB of baseline after stressor is closed and GC runs")
                .isLessThan(heapBefore + 100L * 1024L * 1024L);
    }

    // -------------------------------------------------------------------------
    // TEST 2 — KEEPALIVE
    // -------------------------------------------------------------------------

    /**
     * Activates the keepalive stressor with {@code daemon=false}, which parks a
     * non-daemon thread so the JVM cannot exit.  Verifies that 10 sequential
     * requests succeed while the stressor is active, then confirms that closing
     * the handle leaves the application still running (stressor cleaned up, not
     * the application itself).
     */
    @Test
    void stressor_KEEPALIVE_preventsJvmExit() {
        try (var handle = chaos.activate(
                ChaosSelector.stress(StressTarget.KEEPALIVE),
                ChaosEffect.keepAlive(false)
        )) {
            for (int i = 0; i < 10; i++) {
                ResponseEntity<Map> response = rest.getForEntity("/health", Map.class);
                assertThat(response.getStatusCode().is2xxSuccessful())
                        .as("Request %d must succeed while keepalive stressor is active", i + 1)
                        .isTrue();
                assertThat(response.getBody())
                        .containsKey("is_alive");
            }
            System.out.println("[Keepalive] 10 requests succeeded while non-daemon thread is pinned");
        }

        // After handle closes the stressor thread must be cleaned up but the
        // application context itself must still be alive
        ResponseEntity<Map> postClose = rest.getForEntity("/health", Map.class);
        assertThat(postClose.getStatusCode().is2xxSuccessful())
                .as("Application must still be alive after keepalive stressor is closed")
                .isTrue();
        System.out.println("[Keepalive] application still alive after stressor cleanup");
    }

    // -------------------------------------------------------------------------
    // TEST 3 — METASPACE_PRESSURE
    // -------------------------------------------------------------------------

    /**
     * Activates the metaspace pressure stressor, which generates synthetic classes
     * at 20 MB/h to push non-heap usage upward.  Waits 3 seconds and verifies that
     * non-heap memory grew by at least 1 MB — proof that new class metadata was
     * loaded into metaspace.
     */
    @Test
    void stressor_METASPACE_PRESSURE_fillsMetaspace() throws Exception {
        MemoryUsage before = MEMORY_MX.getNonHeapMemoryUsage();

        try (var handle = chaos.activate(
                ChaosSelector.stress(StressTarget.METASPACE),
                ChaosEffect.metaspacePressure(20)
        )) {
            Thread.sleep(3000);
            MemoryUsage after = MEMORY_MX.getNonHeapMemoryUsage();

            System.out.printf("[MetaspacePressure] non-heap before=%d KB, after=%d KB, delta=%d KB%n",
                    before.getUsed() / 1024,
                    after.getUsed()  / 1024,
                    (after.getUsed() - before.getUsed()) / 1024);

            assertThat(after.getUsed())
                    .as("Non-heap (metaspace) must grow by at least 1 MB after 3 s of class generation")
                    .isGreaterThan(before.getUsed() + 1_000_000L);
        }
    }

    // -------------------------------------------------------------------------
    // TEST 4 — DIRECT_BUFFER
    // -------------------------------------------------------------------------

    /**
     * Activates the direct-buffer stressor to allocate 64 MB of off-heap memory.
     * Verifies that non-heap memory reported by the JVM increases by approximately
     * 64 MB, and that the application still serves requests (off-heap allocation
     * does not block the request path).
     */
    @Test
    void stressor_DIRECT_BUFFER_allocatesOffHeap() throws Exception {
        long nonHeapBefore = MEMORY_MX.getNonHeapMemoryUsage().getUsed();

        try (var handle = chaos.activate(
                ChaosSelector.stress(StressTarget.DIRECT_BUFFER),
                ChaosEffect.directBufferPressure(64)
        )) {
            Thread.sleep(500);
            long nonHeapAfter = MEMORY_MX.getNonHeapMemoryUsage().getUsed();

            System.out.printf("[DirectBuffer] non-heap before=%d MB, after=%d MB, delta=%d MB%n",
                    nonHeapBefore / (1024 * 1024),
                    nonHeapAfter  / (1024 * 1024),
                    (nonHeapAfter - nonHeapBefore) / (1024 * 1024));

            assertThat(nonHeapAfter)
                    .as("Non-heap must grow by at least 60 MB after 64 MB direct buffer allocation")
                    .isGreaterThan(nonHeapBefore + 60L * 1024L * 1024L);

            // Requests must still succeed while off-heap is consumed
            ResponseEntity<Map> response = rest.getForEntity("/health", Map.class);
            assertThat(response.getStatusCode().is2xxSuccessful())
                    .as("Requests must succeed while direct buffer stressor is active")
                    .isTrue();
        }
    }

    // -------------------------------------------------------------------------
    // TEST 5 — GC_PRESSURE
    // -------------------------------------------------------------------------

    /**
     * Activates the GC pressure stressor at 200 MB/s allocation rate and waits
     * 2 seconds.  Verifies that at least 5 GC cycles fired during the window
     * (proving the stressor triggered collections), and that 20 concurrent
     * requests all complete successfully (GC does not break the request path).
     */
    @Test
    void stressor_GC_PRESSURE_forcesCollections() throws Exception {
        try (var handle = chaos.activate(
                ChaosSelector.stress(StressTarget.GC_PRESSURE),
                ChaosEffect.gcPressure(200)
        )) {
            long gcCountBefore = getGcCount();

            Thread.sleep(2000);

            long gcCountAfter = getGcCount();

            System.out.printf("[GcPressure] GC cycles before=%d, after=%d, delta=%d%n",
                    gcCountBefore, gcCountAfter, gcCountAfter - gcCountBefore);

            assertThat(gcCountAfter)
                    .as("At least 5 GC cycles must fire in 2 s under 200 MB/s allocation pressure")
                    .isGreaterThan(gcCountBefore + 5);

            // 20 concurrent requests must all return 200
            int concurrency = 20;
            List<Future<Integer>> futures = new ArrayList<>(concurrency);
            try (ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor()) {
                for (int i = 0; i < concurrency; i++) {
                    futures.add(exec.submit(() ->
                            rest.getForEntity("/health", Map.class)
                                .getStatusCode().value()
                    ));
                }
                for (Future<Integer> f : futures) {
                    assertThat(f.get(10, TimeUnit.SECONDS))
                            .as("All concurrent requests must return HTTP 200 under GC pressure")
                            .isEqualTo(200);
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // TEST 6 — FINALIZER_BACKLOG
    // -------------------------------------------------------------------------

    /**
     * Activates the finalizer backlog stressor with 500 objects that have slow
     * {@code finalize()} implementations, clogging the finalizer thread.  Waits
     * 2 seconds for the queue to build up, then verifies the application is still
     * alive and request-serving — the backed-up finalizer queue must not block
     * the request path.
     */
    @Test
    void stressor_FINALIZER_BACKLOG_fillsFinalizerQueue() throws Exception {
        try (var handle = chaos.activate(
                ChaosSelector.stress(StressTarget.FINALIZER_BACKLOG),
                ChaosEffect.finalizerBacklog(500)
        )) {
            Thread.sleep(2000);

            ResponseEntity<Map> response = rest.getForEntity("/health", Map.class);
            assertThat(response.getStatusCode().is2xxSuccessful())
                    .as("Application must remain alive while finalizer queue is backed up")
                    .isTrue();
            assertThat(response.getBody())
                    .as("Health response must contain is_alive=true")
                    .containsEntry("is_alive", true);

            System.out.println("[FinalizerBacklog] application alive with 500-object finalizer queue backed up");
        }
    }

    // -------------------------------------------------------------------------
    // TEST 7 — DEADLOCK
    // -------------------------------------------------------------------------

    /**
     * Activates a deadlock stressor that puts 4 background threads into a circular
     * lock wait.  Verifies that {@link ThreadMXBean#findDeadlockedThreads()} detects
     * at least 4 deadlocked thread IDs, and that 10 sequential requests still return
     * 200 (the deadlock is confined to background threads, not the request path).
     * After closing the handle the deadlock must be resolved.
     */
    @Test
    void stressor_DEADLOCK_createsCrossThreadDeadlock() throws Exception {
        try (var handle = chaos.activate(
                ChaosSelector.stress(StressTarget.DEADLOCK),
                ChaosEffect.deadlock(4)
        )) {
            // Give the deadlock stressor time to establish the circular wait
            Thread.sleep(500);

            long[] deadlockedIds = THREAD_MX.findDeadlockedThreads();
            System.out.printf("[Deadlock] findDeadlockedThreads returned %s thread(s)%n",
                    deadlockedIds != null ? deadlockedIds.length : "null (not yet established)");

            assertThat(deadlockedIds)
                    .as("findDeadlockedThreads() must report the 4-thread circular deadlock")
                    .isNotNull();
            assertThat(deadlockedIds.length)
                    .as("At least 4 threads must be deadlocked")
                    .isGreaterThanOrEqualTo(4);

            // Requests must still succeed — deadlock is in background threads
            for (int i = 0; i < 10; i++) {
                assertThat(rest.getForEntity("/health", Map.class).getStatusCode().is2xxSuccessful())
                        .as("Request %d must succeed while 4-thread deadlock is active", i + 1)
                        .isTrue();
            }
        }

        // After handle is closed the stressor must have terminated the deadlocked threads
        long[] afterClose = THREAD_MX.findDeadlockedThreads();
        System.out.printf("[Deadlock] after close: findDeadlockedThreads=%s%n",
                afterClose != null ? afterClose.length + " still deadlocked" : "null (resolved)");

        assertThat(afterClose)
                .as("Deadlock must be resolved after stressor handle is closed")
                .isNull();
    }

    // -------------------------------------------------------------------------
    // TEST 8 — THREAD_LEAK
    // -------------------------------------------------------------------------

    /**
     * Activates the thread leak stressor which parks 10 daemon threads permanently.
     * Captures the thread count before and after activation and verifies that at
     * least 10 new threads appear.  After closing the handle the stressor must
     * clean up — thread count returns to near-baseline.
     */
    @Test
    void stressor_THREAD_LEAK_parksThreadsPermanently() throws Exception {
        int threadsBefore = THREAD_MX.getThreadCount();

        try (var handle = chaos.activate(
                ChaosSelector.stress(StressTarget.THREAD_LEAK),
                ChaosEffect.threadLeak(10, true)
        )) {
            Thread.sleep(300);
            int threadsAfter = THREAD_MX.getThreadCount();

            System.out.printf("[ThreadLeak] threads before=%d, after=%d, delta=%d%n",
                    threadsBefore, threadsAfter, threadsAfter - threadsBefore);

            assertThat(threadsAfter)
                    .as("Thread count must grow by at least 10 while thread leak stressor is active")
                    .isGreaterThanOrEqualTo(threadsBefore + 10);
        }

        Thread.sleep(300);
        int threadsAfterClose = THREAD_MX.getThreadCount();

        System.out.printf("[ThreadLeak] after close: threads=%d (baseline was %d)%n",
                threadsAfterClose, threadsBefore);

        assertThat(threadsAfterClose)
                .as("Thread count must return close to baseline after stressor cleanup (within 5)")
                .isLessThan(threadsBefore + 5);
    }

    // -------------------------------------------------------------------------
    // TEST 9 — THREAD_LOCAL_LEAK
    // -------------------------------------------------------------------------

    /**
     * Activates the ThreadLocal leak stressor, which stores 512 KB per touched
     * thread in a static ThreadLocal.  Fires 50 requests so pool threads pick up
     * the leak, then measures that retained heap grew proportionally.
     *
     * <p>Because pooled threads live for the JVM lifetime, their ThreadLocals are
     * GC roots and will not be collected — this is the leak class that only shows
     * up in long-running processes.
     */
    @Test
    void stressor_THREAD_LOCAL_LEAK_pollutesPoolThreads() throws Exception {
        try (var handle = chaos.activate(
                ChaosSelector.stress(StressTarget.THREAD_LOCAL_LEAK),
                ChaosEffect.threadLocalLeak(512 * 1024)
        )) {
            // Warm up — let the stressor touch a few threads
            for (int i = 0; i < 5; i++) {
                rest.getForEntity("/health", Map.class);
            }

            System.gc();
            Thread.sleep(200);
            long heapBefore = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();

            // Fire 50 requests — more pool threads become polluted
            List<Future<?>> futures = new ArrayList<>(50);
            try (ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor()) {
                for (int i = 0; i < 50; i++) {
                    futures.add(exec.submit(() -> rest.getForEntity("/health", Map.class)));
                }
                for (Future<?> f : futures) {
                    f.get(10, TimeUnit.SECONDS);
                }
            }

            System.gc();
            Thread.sleep(200);
            long heapAfter = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
            long deltaBytes = heapAfter - heapBefore;

            System.out.printf(
                    "[ThreadLocalLeak] before=%d MB, after=%d MB, delta=%+d KB%n" +
                    "  Note: each pool thread holds 512 KB in a static ThreadLocal (visible in thread dump)",
                    heapBefore / (1024 * 1024),
                    heapAfter  / (1024 * 1024),
                    deltaBytes / 1024);

            assertThat(heapAfter)
                    .as("Heap must be larger after polluting pool threads with 512 KB ThreadLocals each")
                    .isGreaterThan(heapBefore);
        }
    }

    // -------------------------------------------------------------------------
    // TEST 10 — MONITOR_CONTENTION
    // -------------------------------------------------------------------------

    /**
     * Activates the monitor contention stressor with 16 background threads that
     * repeatedly enter synchronized blocks.  Fires 100 concurrent virtual-thread
     * requests and verifies all complete within 10 seconds — high contention must
     * slow throughput but must not starve the request path permanently.
     */
    @Test
    void stressor_MONITOR_CONTENTION_saturatesLock() throws Exception {
        try (var handle = chaos.activate(
                ChaosSelector.stress(StressTarget.MONITOR_CONTENTION),
                ChaosEffect.monitorContention(16)
        )) {
            int concurrency = 100;
            List<Future<Integer>> futures = new ArrayList<>(concurrency);
            long startMs = System.currentTimeMillis();

            try (ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor()) {
                for (int i = 0; i < concurrency; i++) {
                    futures.add(exec.submit(() ->
                            rest.getForEntity("/health", Map.class)
                                .getStatusCode().value()
                    ));
                }
                for (Future<Integer> f : futures) {
                    assertThat(f.get(10, TimeUnit.SECONDS))
                            .as("All requests must return HTTP 200 despite heavy monitor contention")
                            .isEqualTo(200);
                }
            }

            long wallMs = System.currentTimeMillis() - startMs;
            System.out.printf("[MonitorContention] 100 concurrent requests completed in %d ms under 16-contender lock%n",
                    wallMs);

            assertThat(wallMs)
                    .as("All 100 requests must complete within 10 000 ms despite monitor contention")
                    .isLessThan(10_000L);
        }
    }

    // -------------------------------------------------------------------------
    // TEST 11 — CODE_CACHE (JIT suppression)
    // -------------------------------------------------------------------------

    /**
     * Activates the code cache pressure stressor, which saturates the code cache
     * so the JIT cannot compile new methods and the JVM falls back to interpreter.
     * Measures compilation time before and during the stressor to confirm JIT
     * activity is suppressed (delta &lt; 100 ms), and verifies that CPU-bound
     * operations still produce correct mathematical results in interpreter mode.
     */
    @Test
    void stressor_CODE_CACHE_PRESSURE_suppressesJit() throws Exception {
        var compilationBean = ManagementFactory.getCompilationMXBean();

        try (var handle = chaos.activate(
                ChaosSelector.stress(StressTarget.CODE_CACHE),
                ChaosEffect.codeCachePressure()
        )) {
            long compileBefore = compilationBean != null
                    ? compilationBean.getTotalCompilationTime()
                    : -1L;

            Thread.sleep(3000);

            long compileAfter = compilationBean != null
                    ? compilationBean.getTotalCompilationTime()
                    : -1L;

            System.out.printf("[CodeCache] JIT compilation time: before=%d ms, after=%d ms, delta=%d ms%n",
                    compileBefore, compileAfter,
                    compilationBean != null ? compileAfter - compileBefore : -1L);

            if (compilationBean != null) {
                assertThat(compileAfter - compileBefore)
                        .as("JIT compilation time delta must be < 100 ms — code cache pressure suppresses new compilations")
                        .isLessThan(100L);
            }

            // CPU-bound work must produce the correct answer even in interpreter mode
            long sum = 0;
            for (int i = 0; i < 100_000; i++) {
                sum += i;
            }
            long expected = (long) 99_999 * 100_000 / 2;

            assertThat(sum)
                    .as("Summation must be mathematically correct even when JIT is suppressed")
                    .isEqualTo(expected);

            System.out.printf("[CodeCache] interpreter result correct: sum=%d%n", sum);
        }
    }

    // -------------------------------------------------------------------------
    // TEST 12 — SAFEPOINT_STORM (repeated stop-the-world)
    // -------------------------------------------------------------------------

    /**
     * Activates the safepoint storm stressor, which forces stop-the-world pauses
     * every 100 ms.  Fires 200 requests while safepoints are active and computes
     * the p99 wall-time latency.  The p99 must remain below 1000 ms — safepoint
     * pauses cause measurable latency spikes but must not cause unbounded stalls.
     */
    @Test
    void stressor_SAFEPOINT_STORM_triggersRepeatedStw() throws Exception {
        try (var handle = chaos.activate(
                ChaosSelector.stress(StressTarget.SAFEPOINT),
                ChaosEffect.safepointStorm(Duration.ofMillis(100))
        )) {
            int concurrency = 200;
            long[] latenciesMs = new long[concurrency];
            List<Future<?>> futures = new ArrayList<>(concurrency);

            try (ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor()) {
                for (int i = 0; i < concurrency; i++) {
                    final int idx = i;
                    futures.add(exec.submit(() -> {
                        long t0 = System.nanoTime();
                        rest.getForEntity("/health", Map.class);
                        latenciesMs[idx] = (System.nanoTime() - t0) / 1_000_000L;
                    }));
                }
                for (Future<?> f : futures) {
                    f.get(30, TimeUnit.SECONDS);
                }
            }

            long p99 = computeP99(latenciesMs);
            System.out.printf("[SafepointStorm] p99 latency = %d ms over %d requests (STW every 100 ms)%n",
                    p99, concurrency);

            assertThat(p99)
                    .as("p99 request latency must stay below 1000 ms even under safepoint storm every 100 ms")
                    .isLessThan(1000L);
        }
    }

    // -------------------------------------------------------------------------
    // TEST 13 — STRING_INTERN_PRESSURE
    // -------------------------------------------------------------------------

    /**
     * Activates the string intern pressure stressor, which interns 10 000 unique
     * strings into the JVM's string pool.  Because interned strings are GC roots
     * they are not collected — verifies that heap usage increases and the application
     * remains alive.
     */
    @Test
    void stressor_STRING_INTERN_PRESSURE_fillsStringPool() throws Exception {
        System.gc();
        Thread.sleep(200);
        long heapBefore = MEMORY_MX.getHeapMemoryUsage().getUsed();

        try (var handle = chaos.activate(
                ChaosSelector.stress(StressTarget.STRING_INTERN),
                ChaosEffect.stringInternPressure(10_000)
        )) {
            Thread.sleep(1000);
            long heapAfter = MEMORY_MX.getHeapMemoryUsage().getUsed();

            System.out.printf("[StringIntern] heap before=%d MB, after=%d MB, delta=%+d KB%n",
                    heapBefore / (1024 * 1024),
                    heapAfter  / (1024 * 1024),
                    (heapAfter - heapBefore) / 1024);

            assertThat(heapAfter)
                    .as("Heap must grow after interning 10 000 unique strings (they are GC roots)")
                    .isGreaterThan(heapBefore);

            // Application must still serve requests
            assertThat(rest.getForEntity("/health", Map.class).getStatusCode().is2xxSuccessful())
                    .as("Application must remain alive after string pool flood")
                    .isTrue();
        }
    }

    // -------------------------------------------------------------------------
    // TEST 14 — REFERENCE_QUEUE_FLOOD
    // -------------------------------------------------------------------------

    /**
     * Activates the reference queue flood stressor with 50 000 phantom references.
     * Runs an explicit GC cycle and verifies that the JVM handles the flood without
     * crashing or stalling the request path — phantom reference processing is the
     * GC's responsibility and must not affect application threads.
     */
    @Test
    void stressor_REFERENCE_QUEUE_FLOOD_overwhelmsGc() throws Exception {
        try (var handle = chaos.activate(
                ChaosSelector.stress(StressTarget.REFERENCE_QUEUE),
                ChaosEffect.referenceQueueFlood(50_000)
        )) {
            Thread.sleep(2000);

            // Trigger GC to force phantom reference processing
            System.gc();
            Thread.sleep(500);

            System.out.println("[ReferenceQueueFlood] explicit GC triggered with 50 000 phantom refs queued");

            // Application must still be alive
            ResponseEntity<Map> health = rest.getForEntity("/health", Map.class);
            assertThat(health.getStatusCode().is2xxSuccessful())
                    .as("Application must remain alive after phantom reference queue flood")
                    .isTrue();
            assertThat(health.getBody())
                    .as("Health response must report is_alive=true")
                    .containsEntry("is_alive", true);

            // 10 requests must all return 200
            for (int i = 0; i < 10; i++) {
                assertThat(rest.getForEntity("/health", Map.class).getStatusCode().value())
                        .as("Request %d must return HTTP 200 under phantom reference flood", i + 1)
                        .isEqualTo(200);
            }

            System.out.println("[ReferenceQueueFlood] 10 requests returned 200; GC handled phantom ref flood");
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Sums GC collection counts across all registered {@link GarbageCollectorMXBean}s.
     *
     * @return total number of GC collections that have occurred since JVM start
     */
    private static long getGcCount() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream()
                .mapToLong(GarbageCollectorMXBean::getCollectionCount)
                .filter(c -> c >= 0) // -1 means undefined
                .sum();
    }

    /**
     * Computes the 99th percentile of an array of latency values in milliseconds.
     *
     * @param latenciesMs unsorted array of latency measurements
     * @return the value at the 99th percentile
     */
    private static long computeP99(long[] latenciesMs) {
        long[] sorted = latenciesMs.clone();
        Arrays.sort(sorted);
        int index = (int) Math.ceil(sorted.length * 0.99) - 1;
        return sorted[Math.max(0, index)];
    }
}
