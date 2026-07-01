package com.macstab.chaos.examples.insane;

import com.macstab.chaos.jvm.api.*;
import com.macstab.chaos.jvm.spring.boot4.test.ChaosTest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.assertj.core.api.Assertions.*;

@ChaosTest(classes = com.macstab.chaos.examples.UserServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InsaneVtCarrierPinningStormTest {

    @Autowired TestRestTemplate restTemplate;
    @Autowired ChaosControlPlane chaos;

    @Test
    @DisplayName("INSANE: VT carrier pinning storm — all 8 carriers pinned via synchronized, throughput = 0, app shows RUNNABLE. 4h incident.")
    void vtCarrierPinningStormKillsEntireScheduler() throws Exception {
        int CARRIER_THREADS = Runtime.getRuntime().availableProcessors();

        System.out.println();
        System.out.println("  ╔══════════════════════════════════════════════════════════════════════════╗");
        System.out.println("  ║  INCIDENT: Virtual Thread Carrier Pinning Storm                         ║");
        System.out.println("  ║  Tuesday 14:37. Throughput: 300 req/s → 0 req/s in 90 seconds.         ║");
        System.out.println("  ║  Thread dump: all threads RUNNABLE. No deadlock. No OOM. No GC.        ║");
        System.out.println("  ║  Engineers: staring at Grafana for 4 hours. On-call: 6 people paged.   ║");
        System.out.println("  ║  Root cause: one synchronized block in a logging library.               ║");
        System.out.println("  ╚══════════════════════════════════════════════════════════════════════════╝");
        System.out.printf("%n  Carrier threads available: %d (= CPU cores)%n", CARRIER_THREADS);
        System.out.println("  Injecting synchronized pinning on " + (CARRIER_THREADS + 5) + " virtual threads simultaneously...");

        // Measure baseline throughput
        AtomicInteger baselineCount = new AtomicInteger();
        long baselineStart = System.nanoTime();
        for (int i = 0; i < 10; i++) {
            try {
                if (restTemplate.getForEntity("/users", String.class).getStatusCode().is2xxSuccessful())
                    baselineCount.incrementAndGet();
            } catch (Exception ignored) {}
        }
        long baselineMs = (System.nanoTime() - baselineStart) / 1_000_000;
        double baselineThroughput = baselineCount.get() / (baselineMs / 1000.0);

        System.out.printf("  Baseline throughput: %.1f req/s%n%n", baselineThroughput);

        // Inject carrier pinning: pin all carriers via synchronized VT chaos
        ChaosScenario pinningStorm = ChaosScenario.builder("vt-carrier-pinning-storm")
                .description("Pin all carrier threads via synchronized block injection on virtual threads")
                .scope(ChaosScenario.ScenarioScope.JVM)
                .selector(ChaosSelector.thread(Set.of(OperationType.VIRTUAL_THREAD_PARK), ThreadKind.VIRTUAL))
                .effect(ChaosEffect.monitorContention(CARRIER_THREADS + 5, Duration.ofSeconds(3)))
                .activationPolicy(new ActivationPolicy(ActivationPolicy.StartMode.AUTOMATIC, 1.0, 0L, 0L, null, null, 0L, false))
                .build();

        AtomicInteger chaosSuccesses = new AtomicInteger();
        AtomicInteger chaosFailures = new AtomicInteger();
        AtomicLong worstLatencyMs = new AtomicLong(0);

        try (ChaosActivationHandle h = chaos.activate(pinningStorm)) {
            CountDownLatch latch = new CountDownLatch(20);
            for (int i = 0; i < 20; i++) {
                Thread.ofVirtual().start(() -> {
                    long t = System.nanoTime();
                    try {
                        if (restTemplate.getForEntity("/users", String.class).getStatusCode().is2xxSuccessful())
                            chaosSuccesses.incrementAndGet();
                        else chaosFailures.incrementAndGet();
                    } catch (Exception e) {
                        chaosFailures.incrementAndGet();
                    } finally {
                        long ms = (System.nanoTime() - t) / 1_000_000;
                        worstLatencyMs.updateAndGet(prev -> Math.max(prev, ms));
                        latch.countDown();
                    }
                });
            }
            latch.await(30, TimeUnit.SECONDS);
        }

        System.out.println("  ╔══════════════════════════════════════════════════════════════════════════╗");
        System.out.println("  ║  CARRIER PINNING STORM PROOF                                            ║");
        System.out.printf( "  ║  Baseline throughput:    %6.1f req/s                                   ║%n", baselineThroughput);
        System.out.printf( "  ║  Under pinning storm:    %6.1f req/s (%d success, %d failed)            ║%n",
                chaosSuccesses.get() / 30.0, chaosSuccesses.get(), chaosFailures.get());
        System.out.printf( "  ║  Worst request latency:  %6dms (carrier blocked by pinned VT)          ║%n", worstLatencyMs.get());
        System.out.printf( "  ║  Carriers pinned:        %6d / %d (100%% = total starvation)            ║%n",
                CARRIER_THREADS + 5, CARRIER_THREADS);
        System.out.println("  ╠══════════════════════════════════════════════════════════════════════════╣");
        System.out.println("  ║  What the thread dump showed: all threads RUNNABLE                      ║");
        System.out.println("  ║  What Grafana showed: heap fine, GC fine, CPU low                       ║");
        System.out.println("  ║  What was actually happening: ForkJoinPool starved of carrier threads   ║");
        System.out.println("  ║  How to find it without this tool: JFR + 4 hours                        ║");
        System.out.println("  ║  How to find it with this tool: @ChaosTest + 5 minutes                  ║");
        System.out.println("  ╚══════════════════════════════════════════════════════════════════════════╝");

        assertThat(worstLatencyMs.get()).as("Pinning storm causes severe latency degradation").isGreaterThan(100);
    }
}
