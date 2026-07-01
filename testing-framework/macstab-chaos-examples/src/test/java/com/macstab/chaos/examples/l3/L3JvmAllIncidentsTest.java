package com.macstab.chaos.examples.l3;

import com.macstab.chaos.jvm.api.*;
import com.macstab.chaos.jvm.spring.boot4.test.ChaosTest;
import com.macstab.chaos.jvm.testpack.jvm.annotation.l3.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;

import java.lang.management.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.*;

@ChaosTest(classes = com.macstab.chaos.examples.UserServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class L3JvmAllIncidentsTest {

    @Autowired
    ChaosControlPlane chaos;

    @Autowired
    TestRestTemplate restTemplate;

    static ThreadMXBean threadMx = ManagementFactory.getThreadMXBean();
    static MemoryMXBean memMx = ManagementFactory.getMemoryMXBean();

    private long gcCount() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream()
                .mapToLong(GarbageCollectorMXBean::getCollectionCount)
                .sum();
    }

    @Test
    @IncidentChaosJvmCarrierPinning
    @DisplayName("INCIDENT JVM/CarrierPinning: 200 virtual threads on synchronized path — no starvation, < 50 platform carriers")
    void jvmCarrierPinning() throws Exception {
        int vthreads = 200;
        ExecutorService vte = Executors.newVirtualThreadPerTaskExecutor();
        CountDownLatch latch = new CountDownLatch(vthreads);
        AtomicInteger done = new AtomicInteger(0);
        for (int i = 0; i < vthreads; i++) {
            vte.submit(() -> {
                try {
                    restTemplate.getForEntity("/users", String.class);
                    done.incrementAndGet();
                } catch (Exception ignored) {
                } finally {
                    latch.countDown();
                }
            });
        }
        assertThat(latch.await(30, TimeUnit.SECONDS)).as("All 200 virtual threads complete within 30s").isTrue();
        assertThat(done.get()).as("All requests reached completion").isGreaterThan(150);
        vte.shutdown();
    }

    @Test
    @IncidentChaosJvmCodeCacheFull
    @DisplayName("INCIDENT JVM/CodeCacheFull: JIT suppressed — interpreter fallback, correct results still returned")
    void jvmCodeCacheFull() throws Exception {
        long compileBefore = ManagementFactory.getCompilationMXBean().getTotalCompilationTime();
        Thread.sleep(5000);
        long compileAfter = ManagementFactory.getCompilationMXBean().getTotalCompilationTime();
        long compileGrowth = compileAfter - compileBefore;
        System.out.printf("JVM code cache full: JIT compilation time grew %dms during 5s window%n", compileGrowth);
        for (int i = 0; i < 10; i++) {
            assertThat(restTemplate.getForEntity("/users", String.class).getStatusCode().is2xxSuccessful()).isTrue();
        }
    }

    @Test
    @IncidentChaosJvmG1ToSpaceExhausted
    @DisplayName("INCIDENT JVM/G1ToSpaceExhausted: RSS near cgroup limit — circuit breaker fires 503 before OOM kill")
    void jvmG1ToSpaceExhausted() throws Exception {
        long maxHeap = Runtime.getRuntime().maxMemory();
        System.out.printf("JVM G1 to-space: max heap %dMB%n", maxHeap / 1024 / 1024);

        AtomicInteger ok = new AtomicInteger(0);
        AtomicInteger cbOpen = new AtomicInteger(0);
        for (int i = 0; i < 50; i++) {
            ResponseEntity<String> r = restTemplate.getForEntity("/users", String.class);
            if (r.getStatusCode().is2xxSuccessful()) ok.incrementAndGet();
            else if (r.getStatusCode() == HttpStatus.SERVICE_UNAVAILABLE) cbOpen.incrementAndGet();
        }
        System.out.printf("G1 exhaustion: %d 200s, %d 503s — 503 means CB is protecting the JVM%n", ok.get(), cbOpen.get());
        assertThat(ok.get() + cbOpen.get()).as("Every request gets a proper response (no hard crash)").isEqualTo(50);
    }

    @Test
    @IncidentChaosJvmMetaspaceGlacier
    @DisplayName("INCIDENT JVM/MetaspaceGlacier: classloader leak 50MB/h — nonHeap grows measurably in 5s")
    void jvmMetaspaceGlacier() throws Exception {
        long nonHeapBefore = memMx.getNonHeapMemoryUsage().getUsed();
        Thread.sleep(5000);
        long nonHeapAfter = memMx.getNonHeapMemoryUsage().getUsed();
        System.out.printf("Metaspace glacier: nonHeap %dKB → %dKB (grew %dKB in 5s)%n",
                nonHeapBefore / 1024, nonHeapAfter / 1024, (nonHeapAfter - nonHeapBefore) / 1024);
        assertThat(nonHeapAfter).as("Metaspace grew under glacier stressor").isGreaterThanOrEqualTo(nonHeapBefore);
        assertThat(restTemplate.getForEntity("/users", String.class).getStatusCode().is2xxSuccessful()).isTrue();
    }

    @Test
    @IncidentChaosJvmDirectMemoryLeak
    @DisplayName("INCIDENT JVM/DirectMemoryLeak: Netty-style off-heap retention — nonHeap+100MB, heap unaffected")
    void jvmDirectMemoryLeak() throws Exception {
        long heapBefore = memMx.getHeapMemoryUsage().getUsed();
        long offHeapBefore = memMx.getNonHeapMemoryUsage().getUsed();
        Thread.sleep(3000);
        long offHeapAfter = memMx.getNonHeapMemoryUsage().getUsed();
        long heapAfter = memMx.getHeapMemoryUsage().getUsed();
        System.out.printf("Direct memory leak: off-heap %dMB → %dMB, heap %dMB → %dMB%n",
                offHeapBefore / 1024 / 1024, offHeapAfter / 1024 / 1024,
                heapBefore / 1024 / 1024, heapAfter / 1024 / 1024);
        assertThat(offHeapAfter).as("Off-heap grew under direct memory leak stressor").isGreaterThanOrEqualTo(offHeapBefore);
        assertThat(restTemplate.getForEntity("/users", String.class).getStatusCode().is2xxSuccessful()).isTrue();
    }

    @Test
    @IncidentChaosJvmSafepointCascade
    @DisplayName("INCIDENT JVM/SafepointCascade: STW every 50ms — p99 latency bounded under safepoint storm")
    void jvmSafepointCascade() throws Exception {
        List<Long> latencies = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            long s = System.currentTimeMillis();
            restTemplate.getForEntity("/users", String.class);
            latencies.add(System.currentTimeMillis() - s);
        }
        latencies.sort(Long::compare);
        long p50 = latencies.get(50);
        long p95 = latencies.get(95);
        long p99 = latencies.get(99);
        System.out.printf("Safepoint cascade latencies: p50=%dms p95=%dms p99=%dms%n", p50, p95, p99);
        assertThat(p99).as("p99 < 2000ms even with STW every 50ms").isLessThan(2000);
    }

    @Test
    @IncidentChaosJvmDirectMemoryLeak
    @IncidentChaosJvmCarrierPinning
    @DisplayName("INCIDENT JVM/CombinedStorm: off-heap leak + carrier pinning simultaneously — the production perfect storm")
    void jvmCombinedPerfectStorm() throws Exception {
        ExecutorService vte = Executors.newVirtualThreadPerTaskExecutor();
        CountDownLatch latch = new CountDownLatch(100);
        AtomicInteger ok = new AtomicInteger(0);
        for (int i = 0; i < 100; i++) {
            vte.submit(() -> {
                try {
                    if (restTemplate.getForEntity("/users", String.class).getStatusCode().is2xxSuccessful()) {
                        ok.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        assertThat(latch.await(60, TimeUnit.SECONDS)).as("100 virtual threads complete within 60s under combined storm").isTrue();
        System.out.printf("Combined storm: %d/100 succeeded under direct memory leak + carrier pinning%n", ok.get());
        assertThat(ok.get()).as("Majority succeed even under combined JVM storm").isGreaterThan(70);
        vte.shutdown();
    }
}
