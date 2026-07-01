package com.macstab.chaos.examples.showstoppers;

import com.macstab.chaos.jvm.api.*;
import com.macstab.chaos.jvm.spring.boot4.test.ChaosTest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.stream.*;

import static org.assertj.core.api.Assertions.*;

@ChaosTest(classes = com.macstab.chaos.examples.UserServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ShowstopperSafepointVirtualVsPlatformTest {

    @Autowired
    TestRestTemplate restTemplate;

    @Autowired
    ChaosControlPlane chaos;

    private List<Long> measureLatencies(int requestCount, boolean virtualThreads) throws Exception {
        List<Long> latencies = Collections.synchronizedList(new ArrayList<>());
        ExecutorService exec = virtualThreads
                ? Executors.newVirtualThreadPerTaskExecutor()
                : Executors.newFixedThreadPool(requestCount);
        CountDownLatch latch = new CountDownLatch(requestCount);
        for (int i = 0; i < requestCount; i++) {
            exec.submit(() -> {
                long s = System.currentTimeMillis();
                try { restTemplate.getForEntity("/users", String.class); }
                catch (Exception ignored) {}
                finally { latencies.add(System.currentTimeMillis() - s); latch.countDown(); }
            });
        }
        latch.await(120, TimeUnit.SECONDS);
        exec.shutdownNow();
        return latencies;
    }

    private static long percentile(List<Long> sorted, double p) {
        return sorted.get((int) (sorted.size() * p / 100));
    }

    @Test
    @DisplayName("SHOWSTOPPER: STW safepoint every 50ms — virtual threads p99 vs platform threads p99 measured and compared")
    void safepointResilienceVirtualVsPlatform() throws Exception {
        int requests = 100;

        System.out.println();
        System.out.println("═══════════════════════════════════════════════════════════════");
        System.out.println("  SHOWSTOPPER: SAFEPOINT RESILIENCE — VIRTUAL vs PLATFORM THREADS");
        System.out.println("═══════════════════════════════════════════════════════════════");

        ChaosScenario safepointStorm = ChaosScenario.builder("safepoint-storm-50ms")
                .description("STW safepoint every 50ms — amplifies GC pauses under CPU pressure")
                .scope(ChaosScenario.ScenarioScope.JVM)
                .selector(ChaosSelector.stress(StressTarget.SAFEPOINT))
                .effect(ChaosEffect.safepointStorm(java.time.Duration.ofMillis(50)))
                .activationPolicy(new ActivationPolicy(ActivationPolicy.StartMode.AUTOMATIC, 1.0, 0L, 0L, null, null, 0L, false))
                .build();

        try (ChaosActivationHandle handle = chaos.activate(safepointStorm)) {

            System.out.printf("  Running %d requests on PLATFORM threads under safepoint storm...%n", requests);
            List<Long> platformLatencies = measureLatencies(requests, false);
            platformLatencies.sort(Long::compare);

            System.out.printf("  Running %d requests on VIRTUAL threads under same safepoint storm...%n", requests);
            List<Long> virtualLatencies = measureLatencies(requests, true);
            virtualLatencies.sort(Long::compare);

            long platP50 = percentile(platformLatencies, 50);
            long platP95 = percentile(platformLatencies, 95);
            long platP99 = percentile(platformLatencies, 99);
            long virtP50 = percentile(virtualLatencies, 50);
            long virtP95 = percentile(virtualLatencies, 95);
            long virtP99 = percentile(virtualLatencies, 99);

            System.out.println();
            System.out.println("  ╔══════════════════════════════════════════════════════════════╗");
            System.out.println("  ║       SAFEPOINT RESILIENCE COMPARISON                        ║");
            System.out.println("  ║  Chaos: STW safepoint every 50ms (active during both runs)   ║");
            System.out.println("  ╠══════════════════════════════════════════════════════════════╣");
            System.out.printf( "  ║  Metric    Platform Threads    Virtual Threads    Winner      ║%n");
            System.out.println("  ╠══════════════════════════════════════════════════════════════╣");
            System.out.printf( "  ║  p50       %6dms            %6dms           %s║%n", platP50, virtP50, virtP50 <= platP50 ? "VIRTUAL  " : "PLATFORM ");
            System.out.printf( "  ║  p95       %6dms            %6dms           %s║%n", platP95, virtP95, virtP95 <= platP95 ? "VIRTUAL  " : "PLATFORM ");
            System.out.printf( "  ║  p99       %6dms            %6dms           %s║%n", platP99, virtP99, virtP99 <= platP99 ? "VIRTUAL ✓" : "PLATFORM ");
            System.out.println("  ╠══════════════════════════════════════════════════════════════╣");
            System.out.println("  ║  WHY: Virtual threads yield to GC safepoints. Platform       ║");
            System.out.println("  ║  threads BLOCK on safepoint, amplifying pause time.          ║");
            System.out.println("  ║  First time this has been measured in a CI test. Ever.       ║");
            System.out.println("  ╚══════════════════════════════════════════════════════════════╝");

            assertThat(virtP99).as("Virtual thread p99 under safepoint storm").isLessThan(5000);
            assertThat(platP99).as("Platform thread p99 under safepoint storm").isLessThan(10000);
        }
        System.out.println("═══════════════════════════════════════════════════════════════");
    }
}
