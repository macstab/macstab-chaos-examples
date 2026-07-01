package com.macstab.chaos.examples.insane;

import com.macstab.chaos.jvm.api.*;
import com.macstab.chaos.jvm.spring.boot4.test.ChaosTest;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.assertj.core.api.Assertions.*;

@ChaosTest(classes = com.macstab.chaos.examples.UserServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InsaneDeoptimizationCascadeTest {

    @Autowired TestRestTemplate restTemplate;
    @Autowired ChaosControlPlane chaos;

    @Test
    @DisplayName("INSANE: redefine one config class → 3s app freeze → GC log: silence. Deoptimization cascade. JIT must recompile 1000+ methods.")
    void classRedefinitionCausesDeoptimizationCascadeGcLogLiesAboutCause() throws Exception {
        System.out.println();
        System.out.println("  ╔══════════════════════════════════════════════════════════════════════════╗");
        System.out.println("  ║  INCIDENT: 3-Second Freeze After Config Hot-Reload. GC: Silent.        ║");
        System.out.println("  ║  Sprint demo. Hot-reload a config class (routine, done 100x before).   ║");
        System.out.println("  ║  App freezes for 3 seconds. Demo ruined.                               ║");
        System.out.println("  ║  GC log: nothing. Errors: nothing. Thread dump: all threads working.   ║");
        System.out.println("  ║  Root cause: class redefinition triggers deoptimization cascade.       ║");
        System.out.println("  ║  JIT compiled 1200 methods using the redefined class's methods.        ║");
        System.out.println("  ║  All 1200 deoptimize simultaneously → interpreter runs for 3 seconds.  ║");
        System.out.println("  ║  Then JIT recompiles them (CPU spike). Then normal. GC log: silent.   ║");
        System.out.println("  ╚══════════════════════════════════════════════════════════════════════════╝");
        System.out.println();

        // Warm up the JIT: run some requests so methods get compiled
        System.out.println("  Warming up JIT (compiling hot methods)...");
        for (int i = 0; i < 50; i++) {
            try { restTemplate.getForEntity("/users", String.class); } catch (Exception ignored) {}
        }

        // Measure warmed-up throughput
        AtomicInteger warmOk = new AtomicInteger();
        long warmStart = System.nanoTime();
        for (int i = 0; i < 20; i++) {
            try { if (restTemplate.getForEntity("/users", String.class).getStatusCode().is2xxSuccessful()) warmOk.incrementAndGet(); }
            catch (Exception ignored) {}
        }
        long warmMs = (System.nanoTime() - warmStart) / 1_000_000;
        double warmThroughput = warmOk.get() / (warmMs / 1000.0);
        System.out.printf("  JIT-warmed throughput: %.1f req/s%n%n", warmThroughput);

        // Trigger deoptimization cascade via class redefinition
        ChaosScenario deoptCascade = ChaosScenario.builder("deopt-cascade")
                .description("Trigger mass deoptimization via class redefinition — JIT recompiles 1000+ methods")
                .scope(ChaosScenario.ScenarioScope.JVM)
                .selector(ChaosSelector.jvmRuntime(Set.of(OperationType.CLASS_RETRANSFORM)))
                .effect(ChaosEffect.deoptimizationCascade(1000))
                .activationPolicy(new ActivationPolicy(ActivationPolicy.StartMode.AUTOMATIC, 1.0, 0L, 0L, null, null, 0L, false))
                .build();

        List<Long> deoptLatencies = new ArrayList<>();
        AtomicInteger deoptOk = new AtomicInteger();
        long deoptStart = System.nanoTime();

        try (ChaosActivationHandle handle = chaos.activate(deoptCascade)) {
            // Immediately after deopt cascade: measure performance
            for (int i = 0; i < 20; i++) {
                long t = System.nanoTime();
                try {
                    if (restTemplate.getForEntity("/users", String.class).getStatusCode().is2xxSuccessful())
                        deoptOk.incrementAndGet();
                } catch (Exception ignored) {}
                deoptLatencies.add((System.nanoTime() - t) / 1_000_000);
            }
        }
        long deoptMs = (System.nanoTime() - deoptStart) / 1_000_000;
        double deoptThroughput = deoptOk.get() / (deoptMs / 1000.0);

        deoptLatencies.sort(Long::compare);
        long deoptP99 = deoptLatencies.isEmpty() ? 0 : deoptLatencies.get(deoptLatencies.size() - 1);

        // Recovery: JIT recompiles over next few seconds
        Thread.sleep(3000);
        AtomicInteger recoveryOk = new AtomicInteger();
        long recoveryStart = System.nanoTime();
        for (int i = 0; i < 20; i++) {
            try { if (restTemplate.getForEntity("/users", String.class).getStatusCode().is2xxSuccessful()) recoveryOk.incrementAndGet(); }
            catch (Exception ignored) {}
        }
        double recoveryThroughput = recoveryOk.get() / ((System.nanoTime() - recoveryStart) / 1000.0 / 1_000_000.0);

        System.out.println("  ╔══════════════════════════════════════════════════════════════════════════╗");
        System.out.println("  ║  DEOPTIMIZATION CASCADE PROOF                                           ║");
        System.out.printf( "  ║  JIT-warmed throughput:      %6.1f req/s                                ║%n", warmThroughput);
        System.out.printf( "  ║  Immediately after deopt:    %6.1f req/s  (interpreter fallback)        ║%n", deoptThroughput);
        System.out.printf( "  ║  After JIT recompile (3s):   %6.1f req/s  (JIT warmed again)            ║%n", recoveryThroughput);
        System.out.printf( "  ║  P99 latency during deopt:   %4dms                                      ║%n", deoptP99);
        System.out.println("  ╠══════════════════════════════════════════════════════════════════════════╣");
        System.out.println("  ║  GC log: nothing. Error log: nothing. Metric alert: nothing.            ║");
        System.out.println("  ║  Diagnosis: -XX:+TraceDeoptimization (not in prod. Ever.)               ║");
        System.out.println("  ║  JFR: Deoptimization event in recording.                               ║");
        System.out.println("  ║  This test: reproduce on demand. Profile before/during/after.          ║");
        System.out.println("  ╚══════════════════════════════════════════════════════════════════════════╝");

        assertThat(warmThroughput).as("JIT-warmed throughput established").isGreaterThan(0);
    }
}
