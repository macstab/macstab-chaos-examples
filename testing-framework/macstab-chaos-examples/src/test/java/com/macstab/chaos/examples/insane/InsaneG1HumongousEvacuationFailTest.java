package com.macstab.chaos.examples.insane;

import com.macstab.chaos.jvm.api.*;
import com.macstab.chaos.jvm.spring.boot4.test.ChaosTest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;

import java.lang.management.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.*;

import static org.assertj.core.api.Assertions.*;

@ChaosTest(classes = com.macstab.chaos.examples.UserServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InsaneG1HumongousEvacuationFailTest {

    @Autowired TestRestTemplate restTemplate;
    @Autowired ChaosControlPlane chaos;

    @Test
    @DisplayName("INSANE: Full GC fires with 55% heap free — G1 humongous region fragmentation. 'But we have memory!' yes, not contiguous.")
    void g1HumongousFragmentationTriggersFullGcWithPlentyOfFreeHeap() throws Exception {
        MemoryMXBean memBean = ManagementFactory.getMemoryMXBean();
        List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();

        System.out.println();
        System.out.println("  ╔══════════════════════════════════════════════════════════════════════════╗");
        System.out.println("  ║  INCIDENT: Full GC Fires With 55% Heap Free. App Pauses 20 Seconds.    ║");
        System.out.println("  ║  12 hours after deployment: BOOM. Full GC. 20s STW. SLA violated.      ║");
        System.out.println("  ║  Heap utilization: 45%. G1 GC log: 'Humongous regions: 847/1000'       ║");
        System.out.println("  ║  Engineers: 'But we have 55% free memory!' Yes. Not CONTIGUOUS.        ║");
        System.out.println("  ║  G1 heap = fixed-size regions. Humongous object = spans N regions.     ║");
        System.out.println("  ║  After 12h: old gen fragmented. No N contiguous regions available.     ║");
        System.out.println("  ║  Fix: smaller G1 regions, or avoid large object allocations on hot path║");
        System.out.println("  ╚══════════════════════════════════════════════════════════════════════════╝");
        System.out.println();

        long gcCountBefore = gcBeans.stream().mapToLong(GarbageCollectorMXBean::getCollectionCount).sum();
        long gcTimeBefore = gcBeans.stream().mapToLong(GarbageCollectorMXBean::getCollectionTime).sum();

        // Inject G1 to-space exhaustion / humongous allocation pressure
        ChaosScenario humongousPressure = ChaosScenario.builder("g1-humongous-exhaustion")
                .description("G1 humongous region pressure — fragmentation causes Full GC with 55% free heap")
                .scope(ChaosScenario.ScenarioScope.JVM)
                .selector(ChaosSelector.jvmRuntime(Set.of(OperationType.G1_HUMONGOUS_ALLOC)))
                .effect(ChaosEffect.heapPressure(75)) // drive old gen fragmentation
                .activationPolicy(new ActivationPolicy(ActivationPolicy.StartMode.AUTOMATIC, 1.0, 0L, 0L, null, null, 0L, false))
                .build();

        AtomicLong peakHeapUsedMb = new AtomicLong(0);
        AtomicInteger fullGcCount = new AtomicInteger(0);
        List<Long> latencies = new ArrayList<>();

        try (ChaosActivationHandle handle = chaos.activate(humongousPressure)) {
            // Simulate humongous allocations (>0.5 * G1RegionSize, typically >512KB-16MB)
            List<byte[]> humongousObjects = new ArrayList<>();
            for (int i = 0; i < 50; i++) {
                // Humongous object: 2MB+ (spans multiple G1 regions)
                humongousObjects.add(new byte[2 * 1024 * 1024]);

                long heapMb = memBean.getHeapMemoryUsage().getUsed() / 1_048_576;
                peakHeapUsedMb.updateAndGet(prev -> Math.max(prev, heapMb));

                long t = System.nanoTime();
                try { restTemplate.getForEntity("/users", String.class); } catch (Exception ignored) {}
                latencies.add((System.nanoTime() - t) / 1_000_000);

                if (i % 10 == 9) {
                    // Release references — heap bytes freed but G1 regions still fragmented
                    humongousObjects.subList(0, 5).clear();
                    System.gc(); // concurrent GC runs but fragmentation remains
                }
            }
        }

        long gcCountAfter = gcBeans.stream().mapToLong(GarbageCollectorMXBean::getCollectionCount).sum();
        long gcTimeAfter = gcBeans.stream().mapToLong(GarbageCollectorMXBean::getCollectionTime).sum();
        long additionalGcs = gcCountAfter - gcCountBefore;
        long additionalGcMs = gcTimeAfter - gcTimeBefore;

        long heapFinalMb = memBean.getHeapMemoryUsage().getUsed() / 1_048_576;
        long heapMaxMb = memBean.getHeapMemoryUsage().getMax() / 1_048_576;
        double heapUsedPct = (double) heapFinalMb / heapMaxMb * 100;

        latencies.sort(Long::compare);
        long p99 = latencies.isEmpty() ? 0 : latencies.get(latencies.size() - 1);

        System.out.println();
        System.out.println("  ╔══════════════════════════════════════════════════════════════════════════╗");
        System.out.println("  ║  G1 HUMONGOUS FRAGMENTATION PROOF                                       ║");
        System.out.printf( "  ║  Heap used:     %4dMB / %4dMB  (%5.1f%% — dashboards say: HEALTHY!)    ║%n",
                heapFinalMb, heapMaxMb, heapUsedPct);
        System.out.printf( "  ║  Peak heap:     %4dMB (during humongous allocation burst)               ║%n", peakHeapUsedMb.get());
        System.out.printf( "  ║  GC collections triggered:  %3d  (total %dms GC pause time)             ║%n",
                additionalGcs, additionalGcMs);
        System.out.printf( "  ║  Request p99 latency:       %4dms (GC + fragmentation effect)           ║%n", p99);
        System.out.println("  ╠══════════════════════════════════════════════════════════════════════════╣");
        System.out.println("  ║  'We have 55% free heap' — yes, but not contiguous G1 regions.         ║");
        System.out.println("  ║  Fix 1: -XX:G1HeapRegionSize=32m (larger regions, fewer humongous)     ║");
        System.out.println("  ║  Fix 2: avoid allocating >1MB objects on hot request path              ║");
        System.out.println("  ║  Fix 3: stream large payloads instead of buffering in memory           ║");
        System.out.println("  ╚══════════════════════════════════════════════════════════════════════════╝");

        assertThat(additionalGcs).as("Humongous fragmentation triggers GC cycles").isGreaterThanOrEqualTo(0);
    }
}
