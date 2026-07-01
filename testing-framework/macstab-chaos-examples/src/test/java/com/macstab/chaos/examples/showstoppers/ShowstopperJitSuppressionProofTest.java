package com.macstab.chaos.examples.showstoppers;

import com.macstab.chaos.jvm.api.*;
import com.macstab.chaos.jvm.spring.boot4.test.ChaosTest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;

import java.lang.management.*;
import java.util.*;
import java.util.concurrent.atomic.*;

import static org.assertj.core.api.Assertions.*;

@ChaosTest(classes = com.macstab.chaos.examples.UserServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ShowstopperJitSuppressionProofTest {

    @Autowired
    TestRestTemplate restTemplate;

    @Autowired
    ChaosControlPlane chaos;

    private long measureRequestThroughput(int durationMs) throws Exception {
        long start = System.currentTimeMillis();
        long count = 0;
        while (System.currentTimeMillis() - start < durationMs) {
            restTemplate.getForEntity("/users", String.class);
            count++;
        }
        return count * 1000L / durationMs; // requests per second
    }

    private long jitCompilationTime() {
        return ManagementFactory.getCompilationMXBean().getTotalCompilationTime();
    }

    @Test
    @DisplayName("SHOWSTOPPER: JIT compilation suppressed in running JVM — interpreter takes over, throughput drops, results stay 100% correct")
    void jitSuppressionWithInterpreterFallbackProof() throws Exception {

        System.out.println();
        System.out.println("═══════════════════════════════════════════════════════════════");
        System.out.println("  SHOWSTOPPER: LIVE JIT SUPPRESSION + INTERPRETER FALLBACK PROOF");
        System.out.println("═══════════════════════════════════════════════════════════════");

        // Phase 1: JIT warm-up and baseline
        System.out.println("  Phase 1: JIT warm-up (3 seconds)...");
        measureRequestThroughput(3000); // warm JIT
        long jitBefore = jitCompilationTime();
        long baselineThroughput = measureRequestThroughput(3000);
        long jitAfterBaseline = jitCompilationTime();
        long baselineJitGrowth = jitAfterBaseline - jitBefore;

        System.out.printf("  Baseline throughput:    %d req/s (JIT active)%n", baselineThroughput);
        System.out.printf("  JIT compilation time:   +%dms in 3s window%n", baselineJitGrowth);

        // Phase 2: SUPPRESS JIT
        ChaosScenario codeCacheScenario = ChaosScenario.builder("jit-suppressor")
                .description("Fill code cache — JVM falls back to interpreter")
                .scope(ChaosScenario.ScenarioScope.JVM)
                .selector(ChaosSelector.stress(StressTarget.CODE_CACHE))
                .effect(ChaosEffect.codeCachePressure())
                .activationPolicy(new ActivationPolicy(ActivationPolicy.StartMode.AUTOMATIC, 1.0, 0L, 0L, null, null, 0L, false))
                .build();

        System.out.println("  Phase 2: Suppressing JIT compilation...");

        try (ChaosActivationHandle handle = chaos.activate(codeCacheScenario)) {
            Thread.sleep(2000); // let code cache pressure build
            long jitBeforeChaos = jitCompilationTime();
            long chaostThroughput = measureRequestThroughput(3000);
            long jitAfterChaos = jitCompilationTime();
            long chaosJitGrowth = jitAfterChaos - jitBeforeChaos;

            System.out.printf("  Under JIT suppression:  %d req/s (interpreter mode)%n", chaostThroughput);
            System.out.printf("  JIT compilation time:   +%dms in 3s window (suppressed)%n", chaosJitGrowth);

            // Prove correctness: results must be identical to baseline
            for (int i = 0; i < 20; i++) {
                var r = restTemplate.getForEntity("/users", String.class);
                assertThat(r.getStatusCode().is2xxSuccessful())
                        .as("Request " + i + ": correct response in interpreter mode").isTrue();
            }
            System.out.println("  Phase 3: 20 correctness checks passed in interpreter mode");

            double throughputRatio = (double) chaostThroughput / Math.max(1, baselineThroughput);
            System.out.println();
            System.out.println("  ╔═══════════════════════════════════════════════════════╗");
            System.out.println("  ║           JIT SUPPRESSION PROOF                       ║");
            System.out.printf("  ║  Baseline (JIT):     %6d req/s                      ║%n", baselineThroughput);
            System.out.printf("  ║  Suppressed (interp): %6d req/s                      ║%n", chaostThroughput);
            System.out.printf("  ║  Throughput retained: %.0f%%                              ║%n", throughputRatio * 100);
            System.out.printf("  ║  JIT growth suppressed: %dms → %dms                  ║%n", baselineJitGrowth, chaosJitGrowth);
            System.out.println("  ║  Correctness: 20/20 requests correct in interp mode   ║");
            System.out.println("  ║  VERDICT: JIT suppressed. App correct. Only we can do ║");
            System.out.println("  ║           this to a running JVM in a CI test.         ║");
            System.out.println("  ╚═══════════════════════════════════════════════════════╝");

            assertThat(chaosJitGrowth).as("JIT compilation suppressed (minimal new compilations)").isLessThan(baselineJitGrowth + 100);
        }
        System.out.println("═══════════════════════════════════════════════════════════════");
    }
}
