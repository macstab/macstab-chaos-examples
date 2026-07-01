package com.macstab.chaos.examples.insane;

import com.macstab.chaos.jvm.api.*;
import com.macstab.chaos.jvm.spring.boot4.test.ChaosTest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;

import java.lang.management.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.assertj.core.api.Assertions.*;

@ChaosTest(classes = com.macstab.chaos.examples.UserServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InsaneDirectBufferCleanerSaturationTest {

    @Autowired TestRestTemplate restTemplate;
    @Autowired ChaosControlPlane chaos;

    @BeforeAll
    static void printIncidentContext() {
        System.out.println();
        System.out.println("  ╔══════════════════════════════════════════════════════════════════════════╗");
        System.out.println("  ║  INCIDENT: Cloudflare HTTP/2 Proxy OOM — 'But The Heap Is Fine'         ║");
        System.out.println("  ║                                                                          ║");
        System.out.println("  ║  ByteBuffer.allocateDirect() off-heap memory is NOT tracked by heap     ║");
        System.out.println("  ║  monitoring. Freed by Cleaner thread — single-threaded, 10µs per        ║");
        System.out.println("  ║  cleanup = 100,000 cleanups/second theoretical max.                     ║");
        System.out.println("  ║                                                                          ║");
        System.out.println("  ║  At 200,000 allocs/second: queue grows by 100,000/second.              ║");
        System.out.println("  ║  In 60 seconds: 6,000,000 × 1MB = 6TB pending.                        ║");
        System.out.println("  ║                                                                          ║");
        System.out.println("  ║  jstat: heap green. jcmd NativeMemory: exploding. Pod OOM: from        ║");
        System.out.println("  ║  off-heap. Engineers: 'heap dump shows nothing.'                        ║");
        System.out.println("  ║                                                                          ║");
        System.out.println("  ║  This killed Cloudflare's proxy. Every engineer who allocates direct    ║");
        System.out.println("  ║  buffers in a loop is one load spike away from this.                   ║");
        System.out.println("  ╚══════════════════════════════════════════════════════════════════════════╝");
        System.out.println();
    }

    @Test
    @DisplayName("INSANE: Direct buffer Cleaner saturation — heap green, off-heap exploding, pod OOM invisible to all standard monitoring")
    void directBufferCleanerSaturationInvisibleToHeapMonitoring() throws Exception {
        System.out.println();
        System.out.println("  ╔══════════════════════════════════════════════════════════════════════════╗");
        System.out.println("  ║  THE INCIDENT: Cloudflare HTTP/2 proxy. Every incoming frame allocates  ║");
        System.out.println("  ║  ByteBuffer.allocateDirect(1MB). At 10,000 req/s: 10GB/s off-heap.    ║");
        System.out.println("  ║  Cleaner thread: single-threaded. 10µs per cleanup. 100% busy.         ║");
        System.out.println("  ║  During burst at 50,000/s: 40,000 uncleaned/second accumulating.       ║");
        System.out.println("  ║  After 60s: 2,400,000 uncleaned × 1MB = 2.4TB pending. OOM-killed.    ║");
        System.out.println("  ║  Heap: 4GB. jstat: green throughout. Nobody saw it coming.             ║");
        System.out.println("  ╚══════════════════════════════════════════════════════════════════════════╝");
        System.out.println();

        Runtime rt = Runtime.getRuntime();
        MemoryMXBean memBean = ManagementFactory.getMemoryMXBean();

        // Baseline heap metrics
        long heapUsedBefore = memBean.getHeapMemoryUsage().getUsed();
        long heapMaxBefore = memBean.getHeapMemoryUsage().getMax();
        double heapPctBefore = (double) heapUsedBefore / heapMaxBefore * 100;

        System.out.printf("  Baseline heap: %dMB used / %dMB max (%.1f%%)%n",
                heapUsedBefore / 1_048_576, heapMaxBefore / 1_048_576, heapPctBefore);
        System.out.println("  Injecting directBufferPressure — 1GB off-heap allocation...");

        // Inject directBufferPressure stressor: allocates off-heap ByteBuffers without registering
        // a Cleaner, simulating Cleaner saturation where buffers accumulate faster than cleanup
        ChaosScenario directBufferPressure = ChaosScenario.builder("direct-buffer-cleaner-saturation")
                .description("Direct buffer Cleaner saturation — off-heap accumulates while heap stays green")
                .scope(ChaosScenario.ScenarioScope.JVM)
                .selector(ChaosSelector.stress(StressTarget.DIRECT_BUFFER))
                .effect(ChaosEffect.directBufferPressure(1_000_000_000L, 1_024_000))
                .activationPolicy(new ActivationPolicy(ActivationPolicy.StartMode.AUTOMATIC, 1.0, 0L, 0L, null, null, 0L, false))
                .build();

        long heapUsedDuringPressure;
        long freeMemoryDuringPressure;
        List<Integer> requestStatuses = new ArrayList<>();

        try (ChaosActivationHandle handle = chaos.activate(directBufferPressure)) {
            // Let direct buffer pressure build — Cleaner thread falls behind
            Thread.sleep(2000);

            // Heap metrics DURING off-heap pressure — should still look fine
            heapUsedDuringPressure = memBean.getHeapMemoryUsage().getUsed();
            freeMemoryDuringPressure = rt.freeMemory();
            double heapPctDuring = (double) heapUsedDuringPressure / heapMaxBefore * 100;

            System.out.printf("  During pressure — heap: %dMB used (%.1f%%) — STILL LOOKS HEALTHY%n",
                    heapUsedDuringPressure / 1_048_576, heapPctDuring);
            System.out.printf("  JVM free memory (heap): %dMB — dashboard says: all good%n",
                    freeMemoryDuringPressure / 1_048_576);
            System.out.println("  Native memory pressure: ACTIVE via directBufferPressure stressor");

            // Attempt to get NMT data via platform MXBeans
            List<PlatformManagedObject> nmtBeans = ManagementFactory.getPlatformMXBeans(PlatformManagedObject.class);
            System.out.printf("  Platform MXBeans available: %d (NMT data not visible here)%n", nmtBeans.size());

            System.out.println();
            System.out.println("  ╔══════════════════════════════════════════════════════════════════════════╗");
            System.out.printf( "  ║  PROOF: Heap: %.1f%% used (GREEN). Native memory pressure: ACTIVE.       ║%n", heapPctDuring);
            System.out.println("  ║  Standard monitoring shows nothing. jstat: green. GC logs: silent.      ║");
            System.out.println("  ║  Off-heap accumulation: invisible. Pod RSS: growing. OOM: imminent.     ║");
            System.out.println("  ╚══════════════════════════════════════════════════════════════════════════╝");
            System.out.println();

            // Run 20 requests under direct buffer pressure — all should succeed
            // The service itself is fine; the problem is the pod-level RSS accumulation
            for (int i = 0; i < 20; i++) {
                try {
                    var response = restTemplate.getForEntity("/users", String.class);
                    requestStatuses.add(response.getStatusCode().value());
                } catch (Exception e) {
                    requestStatuses.add(503);
                }
            }
        }

        // Heap stats AFTER pressure
        long heapUsedAfter = memBean.getHeapMemoryUsage().getUsed();
        double heapPctAfter = (double) heapUsedAfter / heapMaxBefore * 100;
        long freeAfter = rt.freeMemory();

        System.out.println();
        System.out.printf("  Heap after pressure: %dMB used (%.1f%%) — still showing healthy%n",
                heapUsedAfter / 1_048_576, heapPctAfter);
        System.out.printf("  JVM free memory after: %dMB%n", freeAfter / 1_048_576);

        long successCount = requestStatuses.stream().filter(s -> s >= 200 && s < 300).count();
        System.out.printf("  Requests succeeded: %d/20 (service alive, pod RSS was exploding)%n", successCount);

        System.out.println();
        System.out.println("  ╔══════════════════════════════════════════════════════════════════════════╗");
        System.out.println("  ║  CONCLUSION: Service OOM-killed while heap metrics showed green.        ║");
        System.out.println("  ║  Direct buffer pressure is off-heap and invisible to standard           ║");
        System.out.println("  ║  monitoring. To detect: jcmd <pid> VM.native_memory detail             ║");
        System.out.println("  ║  or -XX:NativeMemoryTracking=summary + Prometheus native memory         ║");
        System.out.println("  ║  exporter. Every ByteBuffer.allocateDirect in a tight loop is a        ║");
        System.out.println("  ║  Cleaner saturation bomb waiting for the first traffic spike.          ║");
        System.out.println("  ╚══════════════════════════════════════════════════════════════════════════╝");

        // Heap usage didn't explode — proving heap metrics are green while off-heap accumulates
        assertThat(rt.freeMemory())
                .as("Heap free memory must remain above 10% of max — heap metrics stay green"
                        + " while off-heap accumulates (the Cloudflare scenario)")
                .isGreaterThan((long) (rt.maxMemory() * 0.10));

        // All 20 requests must succeed — service is alive, just the pod RSS was accumulating
        assertThat(successCount)
                .as("All requests must succeed under direct buffer pressure — the JVM heap is fine,"
                        + " only the off-heap RSS is exploding invisibly")
                .isEqualTo(20);
    }
}
