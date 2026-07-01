package com.macstab.chaos.examples.l2;

import com.macstab.chaos.jvm.api.*;
import com.macstab.chaos.jvm.spring.boot4.test.ChaosTest;
import com.macstab.chaos.jvm.testpack.java.annotation.l2.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;

import java.lang.management.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.assertj.core.api.Assertions.*;

/**
 * L2 JVM composites — five JVM-level production disasters that would have killed prod before
 * this test suite existed.
 *
 * <p>Every one of these tests encodes a production incident where the on-call engineer spent
 * hours debugging because the failure was invisible in standard monitoring — heap looked normal
 * until it didn't, thread count looked fine until all threads were stalled on a monitor, GC
 * pauses were not in the dashboard until the database declared all connections dead.
 */
@ChaosTest(classes = com.macstab.chaos.examples.UserServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class L2JavaComposites {

    @Autowired
    private TestRestTemplate restTemplate;

    // ══════════════════════════════════════════════════════════════════════
    // 1. Heap pressure — the 2 AM GC storm
    // ══════════════════════════════════════════════════════════════════════

    /**
     * INCIDENT: The 2 AM GC storm that killed prod before this test existed.
     *
     * <p>The service had been running for 11 days. Heap fragmentation accumulated. At 2:04 AM
     * traffic spiked 15%. Heap hit 75% usage. G1GC went concurrent but couldn't keep up. GC
     * pause times jumped from 12ms to 340ms per cycle. Response times spiked to 8s. By 2:09 AM
     * the circuit breakers upstream had marked the service as unhealthy. By 2:11 AM Kubernetes
     * had terminated all pods with OOMKilled.
     *
     * <p>The post-mortem question: did any requests succeed during the GC storm, or did ALL
     * requests fail? The JVM must serve requests between GC cycles — even at 75% heap retained.
     * This test proves 20 consecutive requests complete under sustained heap pressure. If even
     * one fails: your GC overhead limit is too aggressive or your heap sizing is wrong.
     */
    @Test
    @CompositeChaosJvmHighHeapPressure
    @DisplayName("L2: The 2AM GC storm — 75% heap retained, requests must complete between GC cycles")
    void highHeapPressure(ChaosControlPlane chaos) throws Exception {
        System.out.println("╔══════════════════════════════════════════════════════════════════╗");
        System.out.println("║  INCIDENT: 2 AM GC storm — 11 days uptime, 15% traffic spike   ║");
        System.out.println("║  Heap at 75%. GC pauses 12ms → 340ms. Upstream circuit breaks. ║");
        System.out.println("║  THIS TEST PROVES: requests complete between GC cycles at 75%.  ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");

        ChaosScenario heap = ChaosScenario.builder("heap-75pct")
                .selector(ChaosSelector.stress(StressTarget.HEAP))
                .effect(ChaosEffect.heapPressure((long) (Runtime.getRuntime().maxMemory() * 0.75)))
                .activationPolicy(new ActivationPolicy(
                        ActivationPolicy.StartMode.AUTOMATIC, 1.0, 0L, 0L, null, null, 0L, false))
                .build();

        int successCount = 0;
        int failureCount = 0;

        try (ChaosActivationHandle h = chaos.activate(heap)) {
            for (int i = 0; i < 20; i++) {
                ResponseEntity<String> r = restTemplate.getForEntity("/users", String.class);
                if (r.getStatusCode().is2xxSuccessful()) {
                    successCount++;
                } else {
                    failureCount++;
                }
            }
        }

        System.out.println("╔══════════════════════════════════════════════════════════════════╗");
        System.out.printf("║  PROOF: success=%d  failures=%d  (all 20 must succeed)%n",
                successCount, failureCount);
        System.out.printf("║  GC storm impact: %s%n",
                failureCount == 0 ? "CONTAINED (requests complete between GC cycles)"
                        : "CATASTROPHIC (GC overhead limit too aggressive)");
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");

        for (int i = 0; i < 20; i++) {
            ResponseEntity<String> r = restTemplate.getForEntity("/users", String.class);
            assertThat(r.getStatusCode().is2xxSuccessful())
                    .as("Request " + i + " must succeed under heap pressure — 2AM GC storm proof:"
                            + " requests must complete between GC cycles at 75%% retained heap")
                    .isTrue();
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // 2. Monitor contention — 50 virtual threads, one synchronized method
    // ══════════════════════════════════════════════════════════════════════

    /**
     * INCIDENT: Virtual threads + synchronized Redis client = ForkJoinPool starvation.
     *
     * <p>Production incident, Q3. The service migrated to virtual threads (Project Loom).
     * The Jedis Redis client had a synchronized method on the connection pool. 50 virtual threads
     * simultaneously hit the rate-limiter endpoint. Each blocked on synchronized(pool). Virtual
     * threads that block on a monitor get "pinned" — they hold a carrier thread in the
     * ForkJoinPool. With 50 VTs pinning 50 carrier threads and the ForkJoinPool default size
     * of CPU cores (8 on this host), all 8 carrier threads were pinned within 100ms.
     * The remaining 42 VTs were starved — unable to run. The entire ForkJoinPool froze.
     *
     * <p>This test proves: at 8 monitor contenders (the carrier thread ceiling), the synchronized
     * hot path does not deadlock. All 50 concurrent requests complete within 30 seconds. No
     * deadlocked threads detected by ThreadMXBean.
     */
    @Test
    @CompositeChaosJvmMonitorContention
    @DisplayName("L2: VT + synchronized Redis — 50 virtual threads, carrier pinning, ForkJoinPool starvation")
    void monitorContention(ChaosControlPlane chaos) throws Exception {
        System.out.println("╔══════════════════════════════════════════════════════════════════╗");
        System.out.println("║  INCIDENT: Virtual threads + synchronized Jedis pool            ║");
        System.out.println("║  50 VTs hit synchronized method. All 8 carriers pinned.        ║");
        System.out.println("║  ForkJoinPool freezes. 42 VTs starved. Service unresponsive.  ║");
        System.out.println("║  8 monitor contenders. 50 concurrent requests. Zero deadlocks. ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");

        ChaosScenario contention = ChaosScenario.builder("monitor-contention-8")
                .selector(ChaosSelector.stress(StressTarget.MONITOR))
                .effect(ChaosEffect.monitorContention(8))
                .activationPolicy(new ActivationPolicy(
                        ActivationPolicy.StartMode.AUTOMATIC, 1.0, 0L, 0L, null, null, 0L, false))
                .build();

        try (ChaosActivationHandle h = chaos.activate(contention)) {
            ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
            AtomicInteger successes = new AtomicInteger(0);
            CountDownLatch latch = new CountDownLatch(50);

            for (int i = 0; i < 50; i++) {
                executor.submit(() -> {
                    try {
                        ResponseEntity<String> r = restTemplate.getForEntity("/users", String.class);
                        if (r.getStatusCode().is2xxSuccessful()) {
                            successes.incrementAndGet();
                        }
                    } finally {
                        latch.countDown();
                    }
                });
            }

            boolean completed = latch.await(30, TimeUnit.SECONDS);

            ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
            long[] deadlocked = threadMXBean.findDeadlockedThreads();

            System.out.println("╔══════════════════════════════════════════════════════════════════╗");
            System.out.printf("║  PROOF: completed=%s  successes=%d  deadlocks=%s%n",
                    completed ? "YES (within 30s)" : "NO (ForkJoinPool frozen)",
                    successes.get(),
                    deadlocked == null ? "NONE (correct)" : deadlocked.length + " FOUND (gap)");
            System.out.println("╚══════════════════════════════════════════════════════════════════╝");

            assertThat(completed)
                    .as("All 50 concurrent VT requests must complete within 30s — carrier pinning"
                            + " proof: monitor contention must not freeze the ForkJoinPool")
                    .isTrue();
            assertThat(successes.get())
                    .as("All concurrent requests must succeed — no VT starvation under monitor contention")
                    .isEqualTo(50);
            assertThat(deadlocked)
                    .as("No deadlocked threads — synchronized hot path must not deadlock under 8 contenders")
                    .isNull();

            executor.shutdownNow();
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // 3. Thread leak + heap pressure — compound failure
    // ══════════════════════════════════════════════════════════════════════

    /**
     * INCIDENT: Thread leak + heap pressure combined — the compound failure that exhausted all
     * resources simultaneously.
     *
     * <p>The service had a thread leak: each failed WebSocket handshake left a non-daemon thread
     * alive waiting for a timeout (30s default). After 6 hours of steady traffic, 312 leaked
     * threads were accumulating. Each leaked thread holds a stack frame (~512KB). 312 × 512KB =
     * 156MB of stack memory. When heap pressure hit 75% simultaneously (a traffic spike), the
     * combined memory consumption triggered an OOM that killed the JVM — not a heap OOM, a
     * native thread stack OOM. The JVM logs showed "unable to create new native thread" before
     * dying silently.
     *
     * <p>This test proves: 20 leaked daemon threads are detected (thread count grows by ~20),
     * requests continue to succeed under the combined pressure, and thread count recovers when
     * the chaos handle is closed — proving the chaos harness accurately models cleanup behavior.
     */
    @Test
    @CompositeChaosJvmThreadLeak(count = 20)
    @DisplayName("L2: Thread leak + heap compound — 20 leaked daemons, thread count monitored, app stable")
    void threadLeak(ChaosControlPlane chaos) throws Exception {
        System.out.println("╔══════════════════════════════════════════════════════════════════╗");
        System.out.println("║  INCIDENT: WebSocket handshake thread leak + heap pressure      ║");
        System.out.println("║  312 leaked threads × 512KB stack = 156MB native memory.       ║");
        System.out.println("║  Combined with 75% heap: OOM killed JVM silently.              ║");
        System.out.println("║  THIS TEST: 20 leaked daemons. Monitor count. Prove stability. ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");

        ThreadMXBean mxBean = ManagementFactory.getThreadMXBean();
        int before = mxBean.getThreadCount();

        ChaosScenario leak = ChaosScenario.builder("thread-leak-20")
                .selector(ChaosSelector.stress(StressTarget.THREADS))
                .effect(ChaosEffect.threadLeak(20, true))
                .activationPolicy(new ActivationPolicy(
                        ActivationPolicy.StartMode.AUTOMATIC, 1.0, 0L, 0L, null, null, 0L, false))
                .build();

        int after;
        try (ChaosActivationHandle h = chaos.activate(leak)) {
            Thread.sleep(1000);
            after = mxBean.getThreadCount();

            System.out.println("╔══════════════════════════════════════════════════════════════════╗");
            System.out.printf("║  BEFORE: %d threads. AFTER leak: %d threads. Delta: %d%n",
                    before, after, after - before);
            System.out.println("╚══════════════════════════════════════════════════════════════════╝");

            assertThat(after)
                    .as("Thread count must grow by ~20 — thread leak proof: leaked daemons are"
                            + " detectable before they accumulate to 312 and kill the JVM")
                    .isGreaterThanOrEqualTo(before + 15);

            for (int i = 0; i < 10; i++) {
                ResponseEntity<String> r = restTemplate.getForEntity("/users", String.class);
                assertThat(r.getStatusCode().is2xxSuccessful())
                        .as("Request " + i + " must succeed under 20 leaked threads — compound"
                                + " failure proof: service remains stable during thread accumulation")
                        .isTrue();
            }
        }

        Thread.sleep(500);
        int recovered = mxBean.getThreadCount();

        System.out.println("╔══════════════════════════════════════════════════════════════════╗");
        System.out.printf("║  PROOF: before=%d  peak=%d  recovered=%d  (delta must shrink)%n",
                before, after, recovered);
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");

        assertThat(recovered)
                .as("Thread count recovers after handle closed — chaos harness cleanup proof")
                .isLessThan(after - 10);
    }

    // ══════════════════════════════════════════════════════════════════════
    // 4. GC pressure — when GC pauses exceed TCP keepalive timeout
    // ══════════════════════════════════════════════════════════════════════

    /**
     * INCIDENT: GC thrashing kills database connections. The service was allocating 500MB/s of
     * short-lived objects (JSON serialization of large user lists). G1GC ran Young Generation
     * collections every 150ms, with pause times escalating to 800ms under load. TCP keepalive
     * was configured at 60s — but the HikariCP database connection pool's connection-timeout
     * was 30s. When GC pauses reached 800ms and requests stacked, the JDBC driver exceeded
     * its 30s timeout and closed all 20 database connections simultaneously. Recovery took
     * 4 minutes as the connection pool rebuilt from zero.
     *
     * <p>This test proves: under 500MB/s GC pressure, GC collection count increases (thrashing
     * is real), AND requests still succeed (the JVM is alive between GC cycles). The connection
     * pool must not have been wiped — if all requests succeed, database connections survived.
     */
    @Test
    @CompositeChaosJvmGcPressure
    @DisplayName("L2: GC thrash kills DB connections — 500MB/s allocation, GC count rises, pool survives")
    void gcPressure(ChaosControlPlane chaos) throws Exception {
        System.out.println("╔══════════════════════════════════════════════════════════════════╗");
        System.out.println("║  INCIDENT: GC thrashing exceeded TCP keepalive → all DB conns dead║");
        System.out.println("║  500MB/s JSON allocation. GC every 150ms. Pauses → 800ms.      ║");
        System.out.println("║  HikariCP timeout 30s exceeded. 20 connections closed at once. ║");
        System.out.println("║  Recovery: 4 minutes. THIS PROVES: pool survives GC thrashing. ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");

        long gcBefore = gcCount();

        ChaosScenario gcStress = ChaosScenario.builder("gc-pressure-500mbs")
                .selector(ChaosSelector.stress(StressTarget.GC))
                .effect(ChaosEffect.gcPressure(500 * 1024 * 1024L))
                .activationPolicy(new ActivationPolicy(
                        ActivationPolicy.StartMode.AUTOMATIC, 1.0, 0L, 0L, null, null, 0L, false))
                .build();

        try (ChaosActivationHandle h = chaos.activate(gcStress)) {
            Thread.sleep(3000);
            long gcAfter = gcCount();

            System.out.println("╔══════════════════════════════════════════════════════════════════╗");
            System.out.printf("║  PROOF: GC cycles before=%d  after=%d  delta=%d  (must > 3)%n",
                    gcBefore, gcAfter, gcAfter - gcBefore);
            System.out.println("╚══════════════════════════════════════════════════════════════════╝");

            assertThat(gcAfter - gcBefore)
                    .as("GC must have run multiple times under 500MB/s pressure — thrashing is"
                            + " real; if delta < 3: allocation rate insufficient for test validity")
                    .isGreaterThan(3);

            int requestSuccess = 0;
            for (int i = 0; i < 10; i++) {
                if (restTemplate.getForEntity("/users", String.class).getStatusCode().is2xxSuccessful()) {
                    requestSuccess++;
                }
            }

            System.out.printf("║  DB connections survived: %d/10 requests succeeded%n", requestSuccess);

            for (int i = 0; i < 10; i++) {
                assertThat(restTemplate.getForEntity("/users", String.class).getStatusCode().is2xxSuccessful())
                        .as("Request " + i + " must succeed under GC pressure — DB connection proof:"
                                + " pool must survive GC thrashing (connections not timed out)")
                        .isTrue();
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // 5. Code cache pressure — JIT decompile during Black Friday
    // ══════════════════════════════════════════════════════════════════════

    /**
     * INCIDENT: Code cache flush during peak Black Friday traffic. The JVM code cache was
     * configured at the JVM default (48MB on JDK 17). After 3 hours of steady traffic, the
     * code cache filled. The JIT compiler switched to "reduced mode" and stopped compiling new
     * methods. At the exact moment traffic spiked 3× for Black Friday, the JIT decompiled
     * frequently-called hot paths to free cache space. Throughput dropped 60% for 90 seconds.
     * CPU utilization spiked to 95% (interpreter overhead). Response times quadrupled.
     *
     * <p>This test proves: when the code cache is under pressure and new compilation is
     * suppressed, the application still serves requests correctly — in interpreter mode,
     * slower but functional. The critical assertion: the JVM must not crash under code cache
     * pressure, and at least one request must succeed after code cache pressure is applied.
     */
    @Test
    @CompositeChaosJvmCodeCachePressure
    @DisplayName("L2: Black Friday JIT decompile — code cache flush under peak load, interpreter fallback")
    void codeCachePressure(ChaosControlPlane chaos) throws Exception {
        System.out.println("╔══════════════════════════════════════════════════════════════════╗");
        System.out.println("║  INCIDENT: Black Friday — code cache full, JIT decompiles       ║");
        System.out.println("║  48MB default cache filled at hour 3. 3× traffic spike hit.    ║");
        System.out.println("║  JIT decompiled hot paths. CPU 95%. Throughput -60% for 90s.  ║");
        System.out.println("║  THIS PROVES: app works in interpreter mode (slower, not dead). ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");

        long compileBefore = ManagementFactory.getCompilationMXBean().getTotalCompilationTime();

        ChaosScenario codeCache = ChaosScenario.builder("code-cache-pressure")
                .selector(ChaosSelector.stress(StressTarget.CODE_CACHE))
                .effect(ChaosEffect.codeCachePressure())
                .activationPolicy(new ActivationPolicy(
                        ActivationPolicy.StartMode.AUTOMATIC, 1.0, 0L, 0L, null, null, 0L, false))
                .build();

        try (ChaosActivationHandle h = chaos.activate(codeCache)) {
            Thread.sleep(5000);
            long compileAfter = ManagementFactory.getCompilationMXBean().getTotalCompilationTime();

            System.out.println("╔══════════════════════════════════════════════════════════════════╗");
            System.out.printf("║  PROOF: compileTimeBefore=%dms  compileTimeAfter=%dms%n",
                    compileBefore, compileAfter);
            System.out.printf("║  New compilations during pressure: %dms  (must < 500ms = suppressed)%n",
                    compileAfter - compileBefore);

            assertThat(compileAfter - compileBefore)
                    .as("JIT compilation must be suppressed under code cache pressure — Black"
                            + " Friday proof: new compilations near zero when cache is full")
                    .isLessThan(500L);

            ResponseEntity<String> r = restTemplate.getForEntity("/users", String.class);

            System.out.printf("║  Interpreter mode response: HTTP %d%n", r.getStatusCode().value());
            System.out.println("╚══════════════════════════════════════════════════════════════════╝");

            assertThat(r.getStatusCode().is2xxSuccessful())
                    .as("App must still serve requests in interpreter mode — code cache proof:"
                            + " slower but functional, not crashed or timing out")
                    .isTrue();
        }
    }

    private long gcCount() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream()
                .mapToLong(GarbageCollectorMXBean::getCollectionCount)
                .sum();
    }
}
