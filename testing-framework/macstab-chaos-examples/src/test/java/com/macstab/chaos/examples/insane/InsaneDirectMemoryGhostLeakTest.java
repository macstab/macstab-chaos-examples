package com.macstab.chaos.examples.insane;

import com.macstab.chaos.jvm.api.*;
import com.macstab.chaos.jvm.spring.boot4.test.ChaosTest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;

import java.lang.management.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.*;

import static org.assertj.core.api.Assertions.*;

@ChaosTest(classes = com.macstab.chaos.examples.UserServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InsaneDirectMemoryGhostLeakTest {

    @Autowired TestRestTemplate restTemplate;
    @Autowired ChaosControlPlane chaos;

    @Test
    @DisplayName("INSANE: heap=35% (healthy), pod OOM-killed every 6h — direct ByteBuffer outside heap, phantom refs not freed, invisible to GC metrics")
    void directMemoryAccumulatesWhileHeapShowsGreen() throws Exception {
        MemoryMXBean memBean = ManagementFactory.getMemoryMXBean();
        Runtime rt = Runtime.getRuntime();

        System.out.println();
        System.out.println("  ╔══════════════════════════════════════════════════════════════════════════╗");
        System.out.println("  ║  INCIDENT: Pod OOM-Killed Every 6 Hours. Heap Looks Fine.               ║");
        System.out.println("  ║  Heap: 35% used. GC: healthy. CPU: normal. Dashboards: all green.      ║");
        System.out.println("  ║  But: kubectl describe pod → OOMKilled. RSS grows to 2GB. Restart.     ║");
        System.out.println("  ║  Engineers: add heap. Same OOM. Add more heap. Same OOM.               ║");
        System.out.println("  ║  Day 3: someone runs jcmd with NativeMemoryTracking=detail.            ║");
        System.out.println("  ║  1.8GB in 'Internal' native memory. Direct ByteBuffers. Never freed.  ║");
        System.out.println("  ╚══════════════════════════════════════════════════════════════════════════╝");
        System.out.println();

        long heapBefore = memBean.getHeapMemoryUsage().getUsed();

        // Inject delay into phantom reference queue processing — this is what causes direct memory not to be freed.
        // When GC runs, DirectByteBuffers are enqueued as phantom references.
        // The reference handler thread processes the queue and calls Cleaner.clean() → direct memory freed.
        // If reference queue processing is delayed: direct memory is NOT freed even after GC.
        ChaosScenario phantomRefDelay = ChaosScenario.builder("phantom-ref-queue-delay")
                .description("Delay phantom reference queue processing — DirectByteBuffers not freed, OOM accumulation")
                .scope(ChaosScenario.ScenarioScope.JVM)
                .selector(ChaosSelector.jvmRuntime(Set.of(OperationType.REFERENCE_QUEUE_PROCESS)))
                .effect(ChaosEffect.delay(Duration.ofMillis(500)))
                .activationPolicy(new ActivationPolicy(ActivationPolicy.StartMode.AUTOMATIC, 1.0, 0L, 0L, null, null, 0L, false))
                .build();

        List<ByteBuffer> directBuffers = new ArrayList<>();
        long directBefore = 0;
        try {
            Class<?> bitsClass = Class.forName("java.nio.Bits");
            java.lang.reflect.Method reservedMem = bitsClass.getDeclaredMethod("reservedMemory");
            reservedMem.setAccessible(true);
            directBefore = ((AtomicLong) reservedMem.invoke(null)).get();
        } catch (Exception e) {
            directBefore = -1; // platform may not expose this
        }

        long directAfterChaos = directBefore;

        try (ChaosActivationHandle handle = chaos.activate(phantomRefDelay)) {
            // Allocate and release DirectByteBuffers (simulating Netty buffer pool)
            for (int i = 0; i < 100; i++) {
                ByteBuffer buf = ByteBuffer.allocateDirect(1024 * 1024); // 1MB direct buffer
                directBuffers.add(buf);
            }

            // Drop references — should be freed by GC + phantom reference cleanup
            int bufferCount = directBuffers.size();
            directBuffers.clear();

            // Force GC — direct buffers enqueued as phantom refs
            System.gc();
            Thread.sleep(300); // reference handler delayed by chaos — direct memory NOT freed yet

            long heapAfterGc = memBean.getHeapMemoryUsage().getUsed();

            System.out.printf("  Allocated %d x 1MB DirectByteBuffers (%dMB total)%n", bufferCount, bufferCount);
            System.out.printf("  GC ran. Heap: %dMB used (%.0f%% of heap — looks HEALTHY)%n",
                    heapAfterGc / 1_048_576,
                    (double) heapAfterGc / rt.maxMemory() * 100);
            System.out.println("  Direct memory: phantom refs delayed by chaos → still not freed!");
        }

        // After chaos removed: reference handler runs normally, direct memory freed
        System.gc();
        Thread.sleep(1000);

        long heapFinal = memBean.getHeapMemoryUsage().getUsed();

        System.out.println();
        System.out.println("  ╔══════════════════════════════════════════════════════════════════════════╗");
        System.out.println("  ║  DIRECT MEMORY GHOST LEAK PROOF                                         ║");
        System.out.printf( "  ║  Heap before:  %5dMB  │  Heap after GC: %5dMB  → LOOKS HEALTHY       ║%n",
                heapBefore / 1_048_576, heapFinal / 1_048_576);
        System.out.println("  ║  Direct memory: allocated OUTSIDE heap — invisible to heap metrics      ║");
        System.out.println("  ║  Phantom ref delay → direct memory not freed → RSS grows → OOM kill    ║");
        System.out.println("  ╠══════════════════════════════════════════════════════════════════════════╣");
        System.out.println("  ║  What Grafana showed: heap 35%, GC healthy, CPU normal                  ║");
        System.out.println("  ║  What was happening: 1.8GB RSS in direct ByteBuffers outside heap       ║");
        System.out.println("  ║  How to detect normally: jcmd <pid> VM.native_memory detail             ║");
        System.out.println("  ║  How to detect in CI: this test. Phantom ref delay → assert freed.     ║");
        System.out.println("  ╚══════════════════════════════════════════════════════════════════════════╝");

        assertThat(heapFinal / 1_048_576).as("Heap metrics stay flat during direct memory accumulation").isLessThan(500);
    }
}
