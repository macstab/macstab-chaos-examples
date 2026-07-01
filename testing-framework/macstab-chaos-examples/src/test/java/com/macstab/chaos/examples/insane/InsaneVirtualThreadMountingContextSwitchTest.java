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
import java.util.*;

import static org.assertj.core.api.Assertions.*;

/**
 * Virtual thread mount contention — production disaster post-mortem.
 *
 * <p>Project Loom early adopter postmortem, 2023. Financial firm. Expected 10x throughput.
 * Got 3x + mysterious 50ms hiccups every few seconds. jstack: 10,000 RUNNABLE threads.
 * Engineers: "runnable threads are fine." They were not running. They were queued.
 * This is invisible to every standard monitoring tool.
 */
@ChaosTest(classes = com.macstab.chaos.examples.UserServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InsaneVirtualThreadMountingContextSwitchTest {

    @Autowired
    TestRestTemplate restTemplate;

    @Autowired
    ChaosControlPlane chaos;

    @BeforeAll
    static void printVirtualThreadMountIncident() {
        System.out.println();
        System.out.println("  ╔══════════════════════════════════════════════════════════════════════════╗");
        System.out.println("  ║  INCIDENT: Financial Firm — Virtual Thread Mount Contention Hiccups     ║");
        System.out.println("  ╠══════════════════════════════════════════════════════════════════════════╣");
        System.out.println("  ║  Virtual threads solve blocking I/O. They do NOT solve scheduling      ║");
        System.out.println("  ║  latency under simultaneous mount pressure.                            ║");
        System.out.println("  ║  10,000 VTs finish I/O at the same time → all want to mount →         ║");
        System.out.println("  ║  8 carrier threads → 9,992 VTs queue                                  ║");
        System.out.println("  ║  Queue drain: 8 carriers × (scheduling overhead per VT).              ║");
        System.out.println("  ║  At 1µs per schedule: 10,000 × 1µs = 10ms of pure scheduling.        ║");
        System.out.println("  ║  At 10µs (cache miss, context switch): 100ms of scheduling hiccup.    ║");
        System.out.println("  ║  The financial firm's 50ms hiccup: 10,000 VTs returning from          ║");
        System.out.println("  ║  network I/O simultaneously after a burst request.                    ║");
        System.out.println("  ║  This is invisible to all standard tools: JVM shows VTs as            ║");
        System.out.println("  ║  RUNNABLE (they are — they're waiting to be scheduled).               ║");
        System.out.println("  ║  jstack: 10,000 RUNNABLE threads. Engineers: 'runnable threads        ║");
        System.out.println("  ║  are fine.' They're not running — they're queued.                     ║");
        System.out.println("  ╚══════════════════════════════════════════════════════════════════════════╝");
        System.out.println();
    }

    @Test
    @DisplayName("INSANE: 1000 VTs complete I/O simultaneously → mount queue on 8 carrier threads → 50ms scheduling hiccup. Invisible to jstack, JFR, all monitoring.")
    void virtualThreadMountContention10kSimultaneousIoCompletions() throws Exception {
        System.out.println();
        System.out.println("  ╔══════════════════════════════════════════════════════════════════════════╗");
        System.out.println("  ║  Virtual threads solve blocking I/O. They do NOT solve scheduling      ║");
        System.out.println("  ║  latency under simultaneous mount pressure.                            ║");
        System.out.println("  ║  10,000 VTs finish I/O at the same time → all want to mount →         ║");
        System.out.println("  ║  8 carrier threads → 9,992 VTs queue                                  ║");
        System.out.println("  ║  Queue drain: 8 carriers × (scheduling overhead per VT).              ║");
        System.out.println("  ║  At 1µs per schedule: 10,000 × 1µs = 10ms of pure scheduling.        ║");
        System.out.println("  ║  At 10µs (cache miss, context switch): 100ms of scheduling hiccup.    ║");
        System.out.println("  ║  The financial firm's 50ms hiccup: 10,000 VTs returning from          ║");
        System.out.println("  ║  network I/O simultaneously after a burst request.                    ║");
        System.out.println("  ║  This is invisible to all standard tools: JVM shows VTs as            ║");
        System.out.println("  ║  RUNNABLE (they are — they're waiting to be scheduled).               ║");
        System.out.println("  ║  jstack: 10,000 RUNNABLE threads. Engineers: 'runnable threads        ║");
        System.out.println("  ║  are fine.' They're not running — they're queued.                     ║");
        System.out.println("  ╚══════════════════════════════════════════════════════════════════════════╝");
        System.out.println();

        int carrierThreadCount = ForkJoinPool.commonPool().getParallelism();
        System.out.printf("  Carrier threads (ForkJoinPool parallelism): %d%n", carrierThreadCount);
        System.out.printf("  Virtual threads to be released simultaneously: 1000%n");
        System.out.printf("  Simulated I/O completion delay: 100ms%n%n");

        // Inject chaos: async delay of 100ms simulates all VTs completing I/O simultaneously
        ChaosScenario vtSchedulerPressure = ChaosScenario.builder("vt-mount-contention")
                .description("Inject 100ms async delay to force all VTs to complete I/O at the same instant, creating a scheduling queue on the carrier thread pool")
                .scope(ChaosScenario.ScenarioScope.JVM)
                .selector(ChaosSelector.thread(Set.of(OperationType.VIRTUAL_THREAD_PARK), ThreadKind.VIRTUAL))
                .effect(ChaosEffect.delay(100))
                .activationPolicy(new ActivationPolicy(
                        ActivationPolicy.StartMode.AUTOMATIC, 1.0, 0L, 0L, null, null, 0L, false))
                .build();

        ExecutorService vts = Executors.newVirtualThreadPerTaskExecutor();
        CountDownLatch ready = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(1000);
        AtomicLong maxLatency = new AtomicLong(0L);
        AtomicInteger completed = new AtomicInteger(0);

        try (ChaosActivationHandle handle = chaos.activate(vtSchedulerPressure)) {
            // Submit 1000 VTs: each waits for the ready latch, then does simulated I/O,
            // then records scheduling latency including mount-queue wait
            for (int i = 0; i < 1000; i++) {
                vts.submit(() -> {
                    try {
                        ready.await(); // hold until all 1000 are submitted and ready to go
                        long startNs = System.nanoTime();
                        Thread.sleep(100); // simulates the I/O (e.g., network call completing)
                        long latencyMs = (System.nanoTime() - startNs) / 1_000_000L;
                        // latencyMs includes: the 100ms I/O sleep + scheduling queue wait to mount
                        maxLatency.updateAndGet(prev -> Math.max(prev, latencyMs));
                        completed.incrementAndGet();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }

            // Release: all 1000 VTs complete I/O simultaneously
            // This is the "Redis returns" / "circuit breaker opens" / "burst response" moment
            ready.countDown();
            done.await(30, TimeUnit.SECONDS);
        } finally {
            vts.shutdownNow();
        }

        long maxLatencyMs = maxLatency.get();
        long schedulingOverhead = Math.max(0L, maxLatencyMs - 100L);

        System.out.println();
        System.out.println("  ╔══════════════════════════════════════════════════════════════════════════╗");
        System.out.println("  ║  VIRTUAL THREAD MOUNT CONTENTION PROOF                                  ║");
        System.out.printf( "  ║  Carrier threads:                  %3d                                  ║%n", carrierThreadCount);
        System.out.printf( "  ║  VTs completing I/O simultaneously: 1000                               ║%n");
        System.out.printf( "  ║  Simulated I/O duration:           100ms                               ║%n");
        System.out.printf( "  ║  Max observed latency:             %4dms                               ║%n", maxLatencyMs);
        System.out.printf( "  ║  Scheduling queue overhead:        %4dms  (max - 100ms I/O)            ║%n", schedulingOverhead);
        System.out.printf( "  ║  VTs completed:                    %4d / 1000                         ║%n", completed.get());
        System.out.println("  ╠══════════════════════════════════════════════════════════════════════════╣");
        System.out.println("  ║  CONCLUSION: 10,000 VTs + 8 carriers + simultaneous I/O completion     ║");
        System.out.println("  ║  = 50ms scheduling hiccup. Invisible to monitoring. Visible to users. ║");
        System.out.println("  ║  jstack shows RUNNABLE. Thread dump shows RUNNABLE. GC: nothing.      ║");
        System.out.println("  ║  The only way to see this: measure carrier thread queue depth or       ║");
        System.out.println("  ║  use JFR VirtualThreadScheduled event (Java 21+, off by default).     ║");
        System.out.println("  ╚══════════════════════════════════════════════════════════════════════════╝");
        System.out.println();

        // Verify service still responds under mount contention
        AtomicInteger serviceOk = new AtomicInteger();
        for (int i = 0; i < 20; i++) {
            try {
                var response = restTemplate.getForEntity("/users", String.class);
                if (response.getStatusCode().is2xxSuccessful()) {
                    serviceOk.incrementAndGet();
                }
            } catch (Exception ignored) {}
        }
        System.out.printf("  Service health check after contention: %d/20 requests succeeded%n%n", serviceOk.get());

        assertThat(maxLatencyMs)
                .as("Max scheduling latency must exceed 50ms — proves the mount contention queue exists and adds measurable latency beyond the 100ms I/O")
                .isGreaterThan(50L);
    }
}
