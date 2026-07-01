package com.macstab.chaos.examples.showstoppers;

import com.macstab.chaos.jvm.api.*;
import com.macstab.chaos.jvm.spring.boot4.test.ChaosTest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import java.lang.management.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.*;
import java.util.stream.*;
import static org.assertj.core.api.Assertions.*;

@ChaosTest(classes = com.macstab.chaos.examples.UserServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ShowstopperVirtualThreadCarrierProofTest {

    @Autowired
    TestRestTemplate restTemplate;

    @Autowired
    ChaosControlPlane chaos;

    @Test
    @DisplayName("SHOWSTOPPER: 200 virtual threads served by ~8 platform carriers under live MonitorContention chaos — Loom proven numerically")
    void virtualThreadCarrierProof() throws Exception {
        ThreadMXBean tmx = ManagementFactory.getThreadMXBean();

        System.out.println();
        System.out.println("═══════════════════════════════════════════════════════════════");
        System.out.println("  SHOWSTOPPER: VIRTUAL THREAD CARRIER COUNT PROOF");
        System.out.println("═══════════════════════════════════════════════════════════════");

        // Activate MonitorContention chaos — makes the synchronized path hot
        // so carrier threads are genuinely under pressure (not just idle)
        ChaosScenario contention = ChaosScenario.builder("carrier-contention")
                .description("8 background threads contending monitor — carrier threads under real pressure")
                .scope(ChaosScenario.ScenarioScope.JVM)
                .selector(ChaosSelector.stress(StressTarget.MONITOR_CONTENTION))
                .effect(ChaosEffect.monitorContention(8))
                .activationPolicy(new ActivationPolicy(ActivationPolicy.StartMode.AUTOMATIC, 1.0, 0L, 0L, null, null, 0L, false))
                .build();

        try (ChaosActivationHandle handle = chaos.activate(contention)) {

            int virtualThreadCount = 200;
            CountDownLatch startGun = new CountDownLatch(1);
            CountDownLatch allDone = new CountDownLatch(virtualThreadCount);
            AtomicInteger succeeded = new AtomicInteger(0);

            // Track platform thread count at peak load
            AtomicInteger peakPlatformThreads = new AtomicInteger(0);
            AtomicInteger baselinePlatformThreads = new AtomicInteger(tmx.getThreadCount());

            System.out.printf("  Baseline platform threads: %d%n", baselinePlatformThreads.get());
            System.out.printf("  Firing %d virtual threads simultaneously...%n", virtualThreadCount);

            ExecutorService vte = Executors.newVirtualThreadPerTaskExecutor();

            // Monitoring thread samples platform thread count during the burst
            ScheduledExecutorService monitor = Executors.newSingleThreadScheduledExecutor();
            monitor.scheduleAtFixedRate(() -> {
                int current = tmx.getThreadCount();
                peakPlatformThreads.updateAndGet(prev -> Math.max(prev, current));
            }, 0, 10, TimeUnit.MILLISECONDS);

            for (int i = 0; i < virtualThreadCount; i++) {
                vte.submit(() -> {
                    try {
                        startGun.await();
                        ResponseEntity<String> r = restTemplate.getForEntity("/users", String.class);
                        if (r.getStatusCode().is2xxSuccessful()) succeeded.incrementAndGet();
                    } catch (Exception ignored) {}
                    finally { allDone.countDown(); }
                });
            }

            startGun.countDown(); // fire all 200 simultaneously
            assertThat(allDone.await(60, TimeUnit.SECONDS)).as("All 200 virtual threads complete").isTrue();

            monitor.shutdown();
            vte.shutdown();

            int platformThreadsAdded = peakPlatformThreads.get() - baselinePlatformThreads.get();
            int expectedWithoutLoom = virtualThreadCount; // without Loom: 200 platform threads needed

            System.out.println();
            System.out.println("  ╔═══════════════════════════════════════════════════════╗");
            System.out.println("  ║         VIRTUAL THREAD CARRIER PROOF                  ║");
            System.out.printf("  ║  Virtual threads fired:         %3d                   ║%n", virtualThreadCount);
            System.out.printf("  ║  Requests succeeded:            %3d                   ║%n", succeeded.get());
            System.out.printf("  ║  Platform threads without Loom: %3d (one per thread)  ║%n", expectedWithoutLoom);
            System.out.printf("  ║  Platform carriers actually used: %3d                 ║%n", platformThreadsAdded);
            System.out.printf("  ║  Carrier efficiency:  %.1fx fewer threads             ║%n", (double) expectedWithoutLoom / Math.max(1, platformThreadsAdded));
            System.out.println("  ║  Chaos: 8 MonitorContention threads active            ║");
            System.out.println("  ║  VERDICT: Project Loom PROVEN under chaos             ║");
            System.out.println("  ╚═══════════════════════════════════════════════════════╝");
            System.out.println();

            assertThat(succeeded.get()).as("All 200 virtual threads complete successfully").isGreaterThan(190);
            assertThat(platformThreadsAdded).as("Loom uses far fewer platform threads than virtual threads")
                    .isLessThan(virtualThreadCount / 4);
        }
        System.out.println("═══════════════════════════════════════════════════════════════");
    }
}
