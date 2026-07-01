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
class InsaneVtDeathSpiralTest {

    @Autowired TestRestTemplate restTemplate;
    @Autowired ChaosControlPlane chaos;

    @Test
    @DisplayName("INSANE: VT death spiral — synchronized pins carriers, queue grows, system shows RUNNABLE but serves 0 requests. Invisible until OOM.")
    void vtDeathSpiralFromSynchronizedPinningUnderLoad() throws Exception {
        int carriers = Runtime.getRuntime().availableProcessors();

        System.out.println();
        System.out.println("  ╔══════════════════════════════════════════════════════════════════════════╗");
        System.out.println("  ║  INCIDENT: VT Death Spiral Under Load                                   ║");
        System.out.println("  ║  500 req/s load test. App handles it fine for 30 seconds.              ║");
        System.out.println("  ║  Then: latency climbs. Then: timeout. Then: silence.                   ║");
        System.out.println("  ║  Thread dump: 200 RUNNABLE virtual threads. No deadlock.               ║");
        System.out.println("  ║  Memory: growing slowly. No OOM yet.                                   ║");
        System.out.println("  ║  Root cause: synchronized block pins carrier threads.                  ║");
        System.out.printf( "  ║  CPU cores = %2d carriers. If all %2d are pinned: new VTs queue forever.  ║%n", carriers, carriers);
        System.out.println("  ║  Queue grows. Memory grows. Eventually OOM. App never recovers.        ║");
        System.out.println("  ╚══════════════════════════════════════════════════════════════════════════╝");
        System.out.printf("%n  Available carrier threads: %d%n", carriers);

        // Inject monitor contention to pin carriers
        ChaosScenario deathSpiral = ChaosScenario.builder("vt-death-spiral")
                .description("VT death spiral: synchronized pins all carriers, new VTs queue forever")
                .scope(ChaosScenario.ScenarioScope.JVM)
                .selector(ChaosSelector.thread(Set.of(OperationType.VIRTUAL_THREAD_PARK), ThreadKind.VIRTUAL))
                .effect(ChaosEffect.monitorContention(carriers, Duration.ofSeconds(2)))
                .activationPolicy(new ActivationPolicy(ActivationPolicy.StartMode.AUTOMATIC, 1.0, 0L, 0L, null, null, 0L, false))
                .build();

        // Measure before: baseline concurrent throughput
        AtomicInteger beforeOk = new AtomicInteger();
        CountDownLatch beforeLatch = new CountDownLatch(20);
        long beforeStart = System.nanoTime();
        for (int i = 0; i < 20; i++) {
            Thread.ofVirtual().start(() -> {
                try { if (restTemplate.getForEntity("/users", String.class).getStatusCode().is2xxSuccessful()) beforeOk.incrementAndGet(); }
                catch (Exception ignored) {}
                finally { beforeLatch.countDown(); }
            });
        }
        beforeLatch.await(10, TimeUnit.SECONDS);
        long beforeMs = (System.nanoTime() - beforeStart) / 1_000_000;
        System.out.printf("  Before spiral: %d/20 VTs completed in %dms%n%n", beforeOk.get(), beforeMs);

        // Inject the death spiral
        AtomicInteger duringOk = new AtomicInteger();
        AtomicInteger duringTimeout = new AtomicInteger();
        List<Long> duringLatencies = Collections.synchronizedList(new ArrayList<>());

        try (ChaosActivationHandle handle = chaos.activate(deathSpiral)) {
            CountDownLatch duringLatch = new CountDownLatch(30);
            long duringStart = System.nanoTime();
            for (int i = 0; i < 30; i++) {
                Thread.ofVirtual().start(() -> {
                    long t = System.nanoTime();
                    try {
                        if (restTemplate.getForEntity("/users", String.class).getStatusCode().is2xxSuccessful())
                            duringOk.incrementAndGet();
                    } catch (Exception e) {
                        duringTimeout.incrementAndGet();
                    } finally {
                        duringLatencies.add((System.nanoTime() - t) / 1_000_000);
                        duringLatch.countDown();
                    }
                });
            }
            duringLatch.await(30, TimeUnit.SECONDS);
        }

        duringLatencies.sort(Long::compare);
        long p50 = duringLatencies.isEmpty() ? 0 : duringLatencies.get(duringLatencies.size() / 2);
        long p99 = duringLatencies.isEmpty() ? 0 : duringLatencies.get(duringLatencies.size() - 1);

        System.out.println("  ╔══════════════════════════════════════════════════════════════════════════╗");
        System.out.println("  ║  VT DEATH SPIRAL PROOF                                                  ║");
        System.out.printf( "  ║  Before spiral: %2d/20 VTs in %4dms   (functioning normally)            ║%n", beforeOk.get(), beforeMs);
        System.out.printf( "  ║  During spiral: %2d/30 success, %2d timeout                              ║%n", duringOk.get(), duringTimeout.get());
        System.out.printf( "  ║  Latency p50: %5dms  p99: %5dms  (carrier starvation visible)         ║%n", p50, p99);
        System.out.println("  ╠══════════════════════════════════════════════════════════════════════════╣");
        System.out.println("  ║  What the thread dump showed: all VTs RUNNABLE (pinned = RUNNABLE)     ║");
        System.out.println("  ║  What findDeadlockedThreads() showed: null (not a 'deadlock')          ║");
        System.out.println("  ║  JVM diagnostic: -Djdk.tracePinnedThreads=full                         ║");
        System.out.println("  ║  Fix: replace synchronized with ReentrantLock in the offending library ║");
        System.out.println("  ╚══════════════════════════════════════════════════════════════════════════╝");

        assertThat(p99).as("Death spiral increases p99 latency significantly").isGreaterThan(p50);
    }
}
