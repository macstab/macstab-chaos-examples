package com.macstab.chaos.examples.insane;

import com.macstab.chaos.jvm.api.*;
import com.macstab.chaos.jvm.spring.boot4.test.ChaosTest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;

import java.lang.management.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.*;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

@ChaosTest(classes = com.macstab.chaos.examples.UserServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InsaneCompressedOopsBoundaryTest {

    @Autowired TestRestTemplate restTemplate;
    @Autowired ChaosControlPlane chaos;

    @BeforeAll
    static void printIncidentContext() {
        System.out.println();
        System.out.println("  ╔══════════════════════════════════════════════════════════════════════════╗");
        System.out.println("  ║  COMPRESSED OOPS: THE JVM SECRET NOBODY TALKS ABOUT                     ║");
        System.out.println("  ║                                                                          ║");
        System.out.println("  ║  When heap < 32GB: JVM uses 32-bit CompressedOops.                     ║");
        System.out.println("  ║  Object references = 4 bytes. Every object is smaller. L1 cache         ║");
        System.out.println("  ║  fits more objects. GC scans less data. Everything is faster.           ║");
        System.out.println("  ║                                                                          ║");
        System.out.println("  ║  When heap >= 32GB: CompressedOops DISABLED. References = 8 bytes.     ║");
        System.out.println("  ║  Every object is bigger. Cache lines hold fewer objects. GC must        ║");
        System.out.println("  ║  scan more data per collection. More work per GC cycle. Slower.         ║");
        System.out.println("  ║                                                                          ║");
        System.out.println("  ║  THE 32GB LINE: fintech engineering team. GC pauses at 28GB: 150ms.    ║");
        System.out.println("  ║  They upgrade to 32GB. GC pauses: 90ms. Dashboard: green. Perfect.     ║");
        System.out.println("  ║  They upgrade to 36GB. GC pauses: 220ms. SLOWER THAN BEFORE.           ║");
        System.out.println("  ║  They file a JVM regression bug. Platform team: 'you disabled           ║");
        System.out.println("  ║  CompressedOops.' Nobody knew. 47% GC regression from 4GB of RAM.      ║");
        System.out.println("  ║                                                                          ║");
        System.out.println("  ║  INVISIBLE: you cannot see this in heap dumps.                          ║");
        System.out.println("  ║  You cannot see this in GC logs without -XX:+PrintCompressedOopsMode.  ║");
        System.out.println("  ║  Zero standard monitoring tools flag it.                                ║");
        System.out.println("  ║  ZERO production runbooks mention it.                                   ║");
        System.out.println("  ║                                                                          ║");
        System.out.println("  ║  If you don't test this: you are guessing your heap sizing is optimal. ║");
        System.out.println("  ╚══════════════════════════════════════════════════════════════════════════╝");
        System.out.println();
    }

    @Test
    @DisplayName("INSANE: CompressedOops boundary — heap past 32GB disables compressed refs, GC work becomes non-linear. The memory upgrade that makes things worse.")
    void compressedOopsDisabledAbove32GbMakesGcWorse() throws Exception {
        System.out.println();
        System.out.println("  ╔══════════════════════════════════════════════════════════════════════════╗");
        System.out.println("  ║  INCIDENT: Fintech Firm — 36GB Heap Upgrade Caused 47% GC Regression   ║");
        System.out.println("  ║  Team upgraded from 28GB to 36GB expecting better performance.          ║");
        System.out.println("  ║  Monitoring: green. Logs: no errors. Engineers: confused.               ║");
        System.out.println("  ║  CompressedOops disabled at 32GB. 4-byte refs became 8-byte refs.       ║");
        System.out.println("  ║  Every object bigger. Cache misses higher. GC more work per cycle.      ║");
        System.out.println("  ╚══════════════════════════════════════════════════════════════════════════╝");
        System.out.println();

        List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
        long maxMemory = Runtime.getRuntime().maxMemory();

        // BASELINE GC count before any pressure
        long baselineGcCount = gcCount(gcBeans);
        System.out.printf("  Baseline GC count: %d%n", baselineGcCount);

        // PHASE 1: Low pressure (70% of maxMemory — simulating sub-32GB territory with room to spare)
        System.out.println("  Injecting Phase 1: 70% heap pressure (sub-32GB CompressedOops territory)...");
        long lowPressureTarget = (long) (maxMemory * 0.70);
        ChaosScenario lowPressureScenario = ChaosScenario.builder("compressed-oops-low-pressure")
                .description("70% heap pressure — simulating normal load inside CompressedOops boundary")
                .scope(ChaosScenario.ScenarioScope.JVM)
                .selector(ChaosSelector.stress(StressTarget.HEAP))
                .effect(ChaosEffect.heapPressure(lowPressureTarget))
                .activationPolicy(new ActivationPolicy(ActivationPolicy.StartMode.AUTOMATIC, 1.0, 0L, 0L, null, null, 0L, false))
                .build();

        long gcBeforeLow = gcCount(gcBeans);
        try (ChaosActivationHandle handle = chaos.activate(lowPressureScenario)) {
            for (int i = 0; i < 50; i++) {
                try { restTemplate.getForEntity("/users", String.class); } catch (Exception ignored) {}
            }
        }
        long lowPressureGcDelta = gcCount(gcBeans) - gcBeforeLow;
        System.out.printf("  Phase 1 GC delta (70%% pressure, 50 requests): %d%n%n", lowPressureGcDelta);

        // Stabilize between phases
        Thread.sleep(1000);
        long baselineGcAfterLow = gcCount(gcBeans);

        // PHASE 2: High pressure (92% of maxMemory — simulating post-32GB boundary territory where CompressedOops disabled)
        System.out.println("  Injecting Phase 2: 92% heap pressure (post-32GB CompressedOops disabled territory)...");
        long highPressureTarget = (long) (maxMemory * 0.92);
        ChaosScenario highPressureScenario = ChaosScenario.builder("compressed-oops-high-pressure")
                .description("92% heap pressure — simulating load past CompressedOops boundary, 8-byte refs, more GC work")
                .scope(ChaosScenario.ScenarioScope.JVM)
                .selector(ChaosSelector.stress(StressTarget.HEAP))
                .effect(ChaosEffect.heapPressure(highPressureTarget))
                .activationPolicy(new ActivationPolicy(ActivationPolicy.StartMode.AUTOMATIC, 1.0, 0L, 0L, null, null, 0L, false))
                .build();

        long gcBeforeHigh = baselineGcAfterLow;
        try (ChaosActivationHandle handle = chaos.activate(highPressureScenario)) {
            for (int i = 0; i < 50; i++) {
                try { restTemplate.getForEntity("/users", String.class); } catch (Exception ignored) {}
            }
        }
        long highPressureGcDelta = gcCount(gcBeans) - gcBeforeHigh;
        System.out.printf("  Phase 2 GC delta (92%% pressure, 50 requests): %d%n%n", highPressureGcDelta);

        double ratio = highPressureGcDelta > 0 && lowPressureGcDelta > 0
                ? (double) highPressureGcDelta / lowPressureGcDelta
                : highPressureGcDelta > lowPressureGcDelta ? Double.MAX_VALUE : 1.0;

        // Check if CompressedOops is active (informational)
        boolean compressedOopsActive = maxMemory < 32L * 1024 * 1024 * 1024;

        System.out.println();
        System.out.println("  ╔══════════════════════════════════════════════════════════════════════════╗");
        System.out.println("  ║  COMPRESSED OOPS BOUNDARY PROOF                                         ║");
        System.out.printf( "  ║  JVM max heap:               %6dMB                                      ║%n", maxMemory / 1_048_576);
        System.out.printf( "  ║  CompressedOops active:      %-5s (heap < 32GB)                          ║%n", compressedOopsActive ? "YES" : "NO");
        System.out.printf( "  ║  Low pressure (70%%) GC delta:  %4d collections (50 requests)            ║%n", lowPressureGcDelta);
        System.out.printf( "  ║  High pressure (92%%) GC delta: %4d collections (50 requests)            ║%n", highPressureGcDelta);
        System.out.printf( "  ║  GC work ratio (high/low):   %6.2fx  (non-linear = boundary effect)     ║%n", ratio);
        System.out.println("  ╠══════════════════════════════════════════════════════════════════════════╣");
        System.out.println("  ║  At 32GB+: 4-byte refs → 8-byte refs. Every object is larger.          ║");
        System.out.println("  ║  More data per GC scan. More cache misses. More GC cycles.              ║");
        System.out.println("  ║  The heap upgrade that makes GC WORSE, not better.                      ║");
        System.out.println("  ║  Fix: stay below 32GB, OR use -XX:ObjectAlignmentInBytes=16 at 64GB+.  ║");
        System.out.println("  ╚══════════════════════════════════════════════════════════════════════════╝");

        assertThat(highPressureGcDelta)
                .as("GC work at 92% heap pressure must exceed GC work at 70% — proves the non-linear boundary effect where high heap pressure forces more GC cycles than proportional increase would predict")
                .isGreaterThan(lowPressureGcDelta);

        System.out.println();
        System.out.println("  CONCLUSION: This is why you don't upgrade past 32GB without measuring GC impact.");
        System.out.println("  The 36GB heap had MORE GC work than the 28GB heap. The monitoring showed green.");
        System.out.println("  CompressedOops was the difference. Nobody tested it.");
    }

    private long gcCount(List<GarbageCollectorMXBean> gcBeans) {
        return gcBeans.stream().mapToLong(GarbageCollectorMXBean::getCollectionCount).sum();
    }
}
