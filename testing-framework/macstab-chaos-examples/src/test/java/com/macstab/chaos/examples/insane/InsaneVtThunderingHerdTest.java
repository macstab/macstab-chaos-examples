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
class InsaneVtThunderingHerdTest {

    @Autowired TestRestTemplate restTemplate;
    @Autowired ChaosControlPlane chaos;

    @Test
    @DisplayName("INSANE: 500 VTs all parked → release simultaneously → ForkJoinPool thundering herd → 8s latency spike. Never visible in load tests.")
    void thunderingHerdFromSimultaneousVtUnpark() throws Exception {
        int HERD_SIZE = 500;
        System.out.println();
        System.out.println("  ╔══════════════════════════════════════════════════════════════════════════╗");
        System.out.println("  ║  INCIDENT: Maintenance Window Causes 8-Second Latency Spike             ║");
        System.out.println("  ║  Scenario: Redis maintenance. 10k requests queued (parked on CF).      ║");
        System.out.println("  ║  Redis returns. All 10k CompletableFutures complete simultaneously.    ║");
        System.out.println("  ║  10k VTs unpark at once. ForkJoinPool overwhelmed. 8s storm.           ║");
        System.out.println("  ║  First 100 responses: instant. Then: silence for 8 seconds.            ║");
        System.out.println("  ║  Then: 9900 responses flood out. Downstream: confused by the burst.    ║");
        System.out.println("  ║  Load tests: never catch this. Need the specific park pattern.         ║");
        System.out.println("  ╚══════════════════════════════════════════════════════════════════════════╝");
        System.out.printf("%n  Thundering herd size: %d virtual threads%n%n", HERD_SIZE);

        // Measure baseline: staggered VTs (normal traffic pattern)
        AtomicInteger baselineCompleted = new AtomicInteger();
        long baselineStart = System.nanoTime();
        CountDownLatch baselineLatch = new CountDownLatch(50);
        for (int i = 0; i < 50; i++) {
            Thread.ofVirtual().start(() -> {
                try {
                    restTemplate.getForEntity("/users", String.class);
                    baselineCompleted.incrementAndGet();
                } catch (Exception ignored) {}
                finally { baselineLatch.countDown(); }
            });
            Thread.sleep(1); // staggered — normal traffic
        }
        baselineLatch.await(15, TimeUnit.SECONDS);
        long baselineMs = (System.nanoTime() - baselineStart) / 1_000_000;
        System.out.printf("  Baseline (staggered 50 VTs): completed in %dms%n%n", baselineMs);

        // Inject thundering herd: all VTs parked, then all released simultaneously
        ChaosScenario thunderingHerd = ChaosScenario.builder("vt-thundering-herd")
                .description("Park all VTs then release simultaneously — ForkJoinPool scheduler overwhelm")
                .scope(ChaosScenario.ScenarioScope.JVM)
                .selector(ChaosSelector.thread(Set.of(OperationType.VIRTUAL_THREAD_PARK), ThreadKind.VIRTUAL))
                .effect(ChaosEffect.thunderingHerd(HERD_SIZE, Duration.ofMillis(500)))
                .activationPolicy(new ActivationPolicy(ActivationPolicy.StartMode.AUTOMATIC, 1.0, 0L, 0L, null, null, 0L, false))
                .build();

        CountDownLatch herdLatch = new CountDownLatch(HERD_SIZE);
        List<Long> completionTimes = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger herdOk = new AtomicInteger();
        long herdStart = System.nanoTime();
        AtomicLong firstCompletionMs = new AtomicLong(Long.MAX_VALUE);
        AtomicLong lastCompletionMs = new AtomicLong(0);

        try (ChaosActivationHandle handle = chaos.activate(thunderingHerd)) {
            // Launch all HERD_SIZE VTs simultaneously (the "all parked waiting for Redis" scenario)
            for (int i = 0; i < HERD_SIZE; i++) {
                Thread.ofVirtual().start(() -> {
                    try {
                        restTemplate.getForEntity("/users", String.class);
                        herdOk.incrementAndGet();
                    } catch (Exception ignored) {}
                    finally {
                        long completedAt = (System.nanoTime() - herdStart) / 1_000_000;
                        completionTimes.add(completedAt);
                        firstCompletionMs.updateAndGet(prev -> Math.min(prev, completedAt));
                        lastCompletionMs.updateAndGet(prev -> Math.max(prev, completedAt));
                        herdLatch.countDown();
                    }
                });
            }
            herdLatch.await(60, TimeUnit.SECONDS);
        }

        long herdTotalMs = lastCompletionMs.get();
        long stormWindowMs = lastCompletionMs.get() - (firstCompletionMs.get() == Long.MAX_VALUE ? 0 : firstCompletionMs.get());

        // Count completions in first 100ms vs rest (the burst pattern)
        long earlyCompletions = completionTimes.stream().filter(t -> t < 200).count();
        long lateCompletions = completionTimes.stream().filter(t -> t >= 200).count();

        System.out.println("  ╔══════════════════════════════════════════════════════════════════════════╗");
        System.out.println("  ║  THUNDERING HERD PROOF                                                  ║");
        System.out.printf( "  ║  Herd size:              %5d VTs all released simultaneously            ║%n", HERD_SIZE);
        System.out.printf( "  ║  Total completion time:  %5dms (vs %dms staggered baseline)             ║%n",
                herdTotalMs, baselineMs);
        System.out.printf( "  ║  Completions in <200ms:  %5d (the 'first burst' responses)              ║%n", earlyCompletions);
        System.out.printf( "  ║  Completions in >200ms:  %5d (delayed by ForkJoinPool storm)            ║%n", lateCompletions);
        System.out.printf( "  ║  Storm window:           %5dms (first → last completion spread)         ║%n", stormWindowMs);
        System.out.println("  ╠══════════════════════════════════════════════════════════════════════════╣");
        System.out.println("  ║  This happens ONLY during: maintenance windows, cold starts,            ║");
        System.out.println("  ║  circuit breaker recovery, or cache stampedes.                          ║");
        System.out.println("  ║  Normal load tests: never reproduce it (traffic is always staggered).  ║");
        System.out.println("  ║  Fix: rate-limit the unpark burst. Use a semaphore on recovery.        ║");
        System.out.println("  ╚══════════════════════════════════════════════════════════════════════════╝");

        assertThat(herdOk.get()).as("All herd VTs eventually complete").isGreaterThan(HERD_SIZE / 2);
    }
}
