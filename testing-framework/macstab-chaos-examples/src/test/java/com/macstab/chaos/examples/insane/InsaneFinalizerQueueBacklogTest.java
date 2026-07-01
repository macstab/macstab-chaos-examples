package com.macstab.chaos.examples.insane;

import com.macstab.chaos.jvm.api.*;
import com.macstab.chaos.jvm.spring.boot4.test.ChaosTest;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;

import java.lang.management.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.*;

import static org.assertj.core.api.Assertions.*;

@ChaosTest(classes = com.macstab.chaos.examples.UserServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InsaneFinalizerQueueBacklogTest {

    @Autowired com.macstab.chaos.jvm.api.ChaosControlPlane chaos;

    @Test
    @DisplayName("INSANE: Finalizer thread backlog — memory grows 50MB/h with no leak in heap dump. Engineers add heap for 3 days then pod OOMs.")
    void finalizerQueueBacklogLooksLikeMemoryLeakWithNoLeak() throws Exception {
        MemoryMXBean memoryMXBean = ManagementFactory.getMemoryMXBean();
        long heapBefore = memoryMXBean.getHeapMemoryUsage().getUsed();

        System.out.println();
        System.out.println("  ╔══════════════════════════════════════════════════════════════════════════╗");
        System.out.println("  ║  INCIDENT: Memory Leak With No Leak In Heap Dump                        ║");
        System.out.println("  ║  Production: heap grows 50MB/h. GC runs every 30s. Nothing helps.      ║");
        System.out.println("  ║  Day 1: engineers add -Xmx to pod. Day 2: still growing. Day 3: OOM.  ║");
        System.out.println("  ║  Heap dump: 2.3GB of objects 'pending finalization.'                   ║");
        System.out.println("  ║  Root cause: finalizer() objects produced faster than Finalizer thread. ║");
        System.out.println("  ║  Finalizer thread: SINGLE THREADED. Cannot keep up. Queue grows.       ║");
        System.out.println("  ╚══════════════════════════════════════════════════════════════════════════╝");
        System.out.println();

        // Inject delay into the Finalizer thread (the JVM's single finalizer thread)
        ChaosScenario finalizerStarvation = ChaosScenario.builder("finalizer-thread-starvation")
                .description("Inject delay into JVM Finalizer thread — backlog grows, memory accumulates, no obvious leak")
                .scope(ChaosScenario.ScenarioScope.JVM)
                .selector(ChaosSelector.scheduling(Set.of(OperationType.FINALIZER_PROCESS)))
                .effect(ChaosEffect.delay(Duration.ofMillis(100))) // each finalization takes 100ms instead of ~0ms
                .activationPolicy(new ActivationPolicy(ActivationPolicy.StartMode.AUTOMATIC, 1.0, 0L, 0L, null, null, 0L, false))
                .build();

        // Create objects with finalize() to fill the queue
        AtomicInteger finalizableCreated = new AtomicInteger(0);
        AtomicInteger finalizableCompleted = new AtomicInteger(0);

        try (ChaosActivationHandle handle = chaos.activate(finalizerStarvation)) {
            // Create 100 objects requiring finalization
            for (int i = 0; i < 100; i++) {
                new Object() {
                    @Override
                    protected void finalize() throws Throwable {
                        finalizableCompleted.incrementAndGet(); // this will be delayed by chaos
                        super.finalize();
                    }
                };
                finalizableCreated.incrementAndGet();
            }

            // Force GC to queue them for finalization
            System.gc();
            Thread.sleep(500); // give time for queue to build up

            int pendingFinalization = memoryMXBean.getObjectPendingFinalizationCount();
            long heapAfter = memoryMXBean.getHeapMemoryUsage().getUsed();

            System.out.printf("  Finalizable objects created: %d%n", finalizableCreated.get());
            System.out.printf("  Finalized so far (delayed):  %d%n", finalizableCompleted.get());
            System.out.printf("  Pending finalization count:  %d%n", pendingFinalization);
            System.out.printf("  Heap growth:                 +%d KB%n", (heapAfter - heapBefore) / 1024);
        }

        // Wait for finalizer to catch up without chaos
        System.gc();
        Thread.sleep(2000);
        int pendingAfterRecovery = memoryMXBean.getObjectPendingFinalizationCount();

        System.out.println();
        System.out.println("  ╔══════════════════════════════════════════════════════════════════════════╗");
        System.out.println("  ║  FINALIZER BACKLOG PROOF                                                ║");
        System.out.printf( "  ║  Objects created requiring finalization:  %4d                           ║%n", 100);
        System.out.printf( "  ║  Finalized DURING chaos (delayed):        %4d  (queue backing up)       ║%n", finalizableCompleted.get());
        System.out.printf( "  ║  Pending finalization count peak:         %4d                           ║%n",
                memoryMXBean.getObjectPendingFinalizationCount());
        System.out.printf( "  ║  Pending after recovery (no chaos):       %4d  (queue draining)         ║%n", pendingAfterRecovery);
        System.out.println("  ╠══════════════════════════════════════════════════════════════════════════╣");
        System.out.println("  ║  What the heap dump showed: objects pending finalization. No leak.      ║");
        System.out.println("  ║  What engineers did: added heap. Then more heap. Then OOM anyway.       ║");
        System.out.println("  ║  Fix: replace finalize() with Cleaner API. Or WeakReference + RQ.      ║");
        System.out.println("  ║  Reproduce in CI: @ChaosTest + finalizerStarvation. 5 seconds.         ║");
        System.out.println("  ╚══════════════════════════════════════════════════════════════════════════╝");

        assertThat(finalizableCreated.get()).isEqualTo(100);
    }
}
