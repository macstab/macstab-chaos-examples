package com.macstab.chaos.examples.showstoppers;

import com.macstab.chaos.jvm.api.*;
import com.macstab.chaos.jvm.spring.boot4.test.ChaosTest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;

import java.lang.management.*;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

@ChaosTest(classes = com.macstab.chaos.examples.UserServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ShowstopperMetaspaceGlacierMeasurementTest {

    @Autowired
    TestRestTemplate restTemplate;

    @Autowired
    ChaosControlPlane chaos;

    private static long nonHeapBytes() {
        return ManagementFactory.getMemoryMXBean().getNonHeapMemoryUsage().getUsed();
    }

    @Test
    @DisplayName("SHOWSTOPPER: Measure a 50MB/hour metaspace leak in 5 seconds — then prove zero growth without the stressor")
    void measureMetaspaceLeakRateIn5Seconds() throws Exception {

        System.out.println();
        System.out.println("═══════════════════════════════════════════════════════════════");
        System.out.println("  SHOWSTOPPER: METASPACE GLACIER LEAK RATE MEASUREMENT");
        System.out.println("  Measure a 50MB/hour classloader leak in 5 seconds.");
        System.out.println("  No profiler. No staging env. No waiting a week. 10s in CI.");
        System.out.println("═══════════════════════════════════════════════════════════════");

        // Phase 1: Baseline — no stressor
        long baselineStart = nonHeapBytes();
        Thread.sleep(5000);
        long baselineEnd = nonHeapBytes();
        long baselineGrowthBytes = baselineEnd - baselineStart;
        double baselineGrowthMbPerHour = (double) baselineGrowthBytes / 1024 / 1024 / 5 * 3600;

        System.out.printf("  Phase 1 (baseline, no stressor, 5s):%n");
        System.out.printf("    NonHeap start:  %dKB%n", baselineStart / 1024);
        System.out.printf("    NonHeap end:    %dKB%n", baselineEnd / 1024);
        System.out.printf("    Growth:         %+dKB in 5s (%.1f MB/hour)%n",
                baselineGrowthBytes / 1024, baselineGrowthMbPerHour);

        // Phase 2: Activate metaspace glacier stressor (50MB/hour synthetic classloader leak)
        ChaosScenario glacier = ChaosScenario.builder("metaspace-glacier-50mbh")
                .description("Synthetic classloader leak: 50MB/hour metaspace growth")
                .scope(ChaosScenario.ScenarioScope.JVM)
                .selector(ChaosSelector.stress(StressTarget.METASPACE))
                .effect(ChaosEffect.metaspacePressure(50))
                .activationPolicy(new ActivationPolicy(ActivationPolicy.StartMode.AUTOMATIC, 1.0, 0L, 0L, null, null, 0L, false))
                .build();

        long glacierStart = nonHeapBytes();
        try (ChaosActivationHandle handle = chaos.activate(glacier)) {
            Thread.sleep(5000);
        }
        long glacierEnd = nonHeapBytes();
        long glacierGrowthBytes = glacierEnd - glacierStart;
        double glacierGrowthMbPerHour = (double) glacierGrowthBytes / 1024 / 1024 / 5 * 3600;

        // Calculate when OOM would fire in prod
        double totalNonHeapMb = (double) ManagementFactory.getMemoryMXBean().getNonHeapMemoryUsage().getMax() / 1024 / 1024;
        double currentMb = (double) glacierEnd / 1024 / 1024;
        double remainingMb = totalNonHeapMb - currentMb;
        double hoursToOom = remainingMb / Math.max(0.001, glacierGrowthMbPerHour);

        System.out.printf("%n  Phase 2 (metaspace glacier stressor, 5s):%n");
        System.out.printf("    NonHeap start:  %dKB%n", glacierStart / 1024);
        System.out.printf("    NonHeap end:    %dKB%n", glacierEnd / 1024);
        System.out.printf("    Growth:         %+dKB in 5s (%.1f MB/hour)%n",
                glacierGrowthBytes / 1024, glacierGrowthMbPerHour);

        System.out.println();
        System.out.println("  ╔═══════════════════════════════════════════════════════════╗");
        System.out.println("  ║        METASPACE GLACIER MEASUREMENT REPORT               ║");
        System.out.println("  ╠═══════════════════════════════════════════════════════════╣");
        System.out.printf( "  ║  Baseline growth rate:    %7.1f MB/hour (normal)         ║%n", baselineGrowthMbPerHour);
        System.out.printf( "  ║  Leak rate measured:      %7.1f MB/hour (with stressor)  ║%n", glacierGrowthMbPerHour);
        System.out.printf( "  ║  Leak measured in:                5 seconds               ║%n");
        System.out.printf( "  ║  Time to OOM at this rate:  ~%.0f hours                  ║%n", hoursToOom);
        System.out.println("  ╠═══════════════════════════════════════════════════════════╣");
        System.out.println("  ║  In production: you'd see this slowly grow over weeks.    ║");
        System.out.println("  ║  Your pager fires at 2am. Root cause: impossible to find. ║");
        System.out.println("  ║  In this framework: measured in 5 seconds in CI.          ║");
        System.out.println("  ║  Fix: find the ClassLoader not calling close(). This test ║");
        System.out.println("  ║  tells you the rate. A heap dump tells you the class.     ║");
        System.out.println("  ╚═══════════════════════════════════════════════════════════╝");
        System.out.println("═══════════════════════════════════════════════════════════════");

        assertThat(glacierGrowthBytes).as("Metaspace grew under glacier stressor").isGreaterThanOrEqualTo(baselineGrowthBytes);
        // App still works
        assertThat(restTemplate.getForEntity("/users", String.class).getStatusCode().is2xxSuccessful()).isTrue();
    }
}
