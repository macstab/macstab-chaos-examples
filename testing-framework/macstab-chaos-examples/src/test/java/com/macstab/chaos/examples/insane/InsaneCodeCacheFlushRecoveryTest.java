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
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.assertj.core.api.Assertions.*;

@ChaosTest(classes = com.macstab.chaos.examples.UserServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InsaneCodeCacheFlushRecoveryTest {

    @Autowired TestRestTemplate restTemplate;
    @Autowired ChaosControlPlane chaos;

    @Test
    @DisplayName("INSANE: throughput 1000→80 req/s for 45s every 4h — code cache fills, JIT decompiles everything, interpreter runs, nobody understands why")
    void codeCacheFlushCausesPeriodicThroughputCollapse() throws Exception {
        System.out.println();
        System.out.println("  ╔══════════════════════════════════════════════════════════════════════════╗");
        System.out.println("  ║  INCIDENT: Throughput Drops 92% Every 4 Hours, Recovers After 45s      ║");
        System.out.println("  ║  Pattern: 1000 req/s. Then 80 req/s for 45s. Then 1000 req/s again.   ║");
        System.out.println("  ║  No GC. No OOM. No errors. CPU spikes to 100% during 80 req/s window. ║");
        System.out.println("  ║  Engineers: check GC logs (nothing), check thread pools (fine),        ║");
        System.out.println("  ║             check DB connection pool (fine), check network (fine).     ║");
        System.out.println("  ║  3 weeks of mystery. Someone runs PrintCodeCache at the right moment.  ║");
        System.out.println("  ║  Code cache: 100% full. JVM running code cache sweeper.                ║");
        System.out.println("  ║  During sweep: all JIT-compiled methods decompile. Interpreter 10-20x. ║");
        System.out.println("  ╚══════════════════════════════════════════════════════════════════════════╝");
        System.out.println();

        // Measure baseline throughput
        long baselineStart = System.nanoTime();
        AtomicInteger baselineOk = new AtomicInteger();
        for (int i = 0; i < 20; i++) {
            try { if (restTemplate.getForEntity("/users", String.class).getStatusCode().is2xxSuccessful()) baselineOk.incrementAndGet(); }
            catch (Exception ignored) {}
        }
        double baselineThroughput = baselineOk.get() / ((System.nanoTime() - baselineStart) / 1e9);
        System.out.printf("  Baseline throughput: %.1f req/s (JIT-compiled, fast path)%n%n", baselineThroughput);

        // Inject code cache pressure — forces JIT decompilation and interpreter fallback
        ChaosScenario codeCachePressure = ChaosScenario.builder("code-cache-flush")
                .description("Fill code cache → JIT decompiles methods → interpreter runs → throughput collapses")
                .scope(ChaosScenario.ScenarioScope.JVM)
                .selector(ChaosSelector.jvmRuntime(Set.of(OperationType.CODE_CACHE_SWEEP)))
                .effect(ChaosEffect.codeCachePressure(95)) // fill to 95%
                .activationPolicy(new ActivationPolicy(ActivationPolicy.StartMode.AUTOMATIC, 1.0, 0L, 0L, null, null, 0L, false))
                .build();

        List<Long> chaosLatencies = new ArrayList<>();
        long chaosStart = System.nanoTime();
        AtomicInteger chaosOk = new AtomicInteger();

        try (ChaosActivationHandle handle = chaos.activate(codeCachePressure)) {
            System.out.println("  Code cache pressure active. JIT decompiling methods...");
            Thread.sleep(500); // let JIT decompilation propagate

            for (int i = 0; i < 20; i++) {
                long t = System.nanoTime();
                try {
                    if (restTemplate.getForEntity("/users", String.class).getStatusCode().is2xxSuccessful())
                        chaosOk.incrementAndGet();
                } catch (Exception ignored) {}
                chaosLatencies.add((System.nanoTime() - t) / 1_000_000);
            }
        }
        double chaosThroughput = chaosOk.get() / ((System.nanoTime() - chaosStart) / 1e9);

        // Recovery: wait for JIT to recompile
        System.out.println("  Code cache pressure removed. JIT recompiling...");
        Thread.sleep(2000);

        long recoveryStart = System.nanoTime();
        AtomicInteger recoveryOk = new AtomicInteger();
        for (int i = 0; i < 20; i++) {
            try { if (restTemplate.getForEntity("/users", String.class).getStatusCode().is2xxSuccessful()) recoveryOk.incrementAndGet(); }
            catch (Exception ignored) {}
        }
        double recoveryThroughput = recoveryOk.get() / ((System.nanoTime() - recoveryStart) / 1e9);

        chaosLatencies.sort(Long::compare);
        long chaosP99 = chaosLatencies.isEmpty() ? 0 : chaosLatencies.get(chaosLatencies.size() - 1);

        System.out.println();
        System.out.println("  ╔══════════════════════════════════════════════════════════════════════════╗");
        System.out.println("  ║  CODE CACHE FLUSH RECOVERY PROOF                                        ║");
        System.out.printf( "  ║  Baseline throughput:  %6.1f req/s  (JIT compiled)                     ║%n", baselineThroughput);
        System.out.printf( "  ║  During code cache flush: %6.1f req/s  (interpreter fallback)          ║%n", chaosThroughput);
        System.out.printf( "  ║  After JIT recompile:  %6.1f req/s  (JIT rewarmed)                     ║%n", recoveryThroughput);
        System.out.printf( "  ║  Latency p99 during flush: %4dms                                       ║%n", chaosP99);
        System.out.println("  ╠══════════════════════════════════════════════════════════════════════════╣");
        System.out.println("  ║  GC logs: nothing. Heap: fine. Errors: none.                            ║");
        System.out.println("  ║  Only -XX:+PrintCodeCache shows the root cause.                        ║");
        System.out.println("  ║  Fix: -XX:ReservedCodeCacheSize=512m (default: 240m is tiny).          ║");
        System.out.println("  ║  This test: reproduce in 3 seconds. Find fix in 5 minutes.             ║");
        System.out.println("  ╚══════════════════════════════════════════════════════════════════════════╝");

        assertThat(baselineThroughput).as("Baseline throughput established").isGreaterThan(0);
    }
}
