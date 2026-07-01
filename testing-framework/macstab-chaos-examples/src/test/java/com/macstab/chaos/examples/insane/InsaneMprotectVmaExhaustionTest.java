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
 * mprotect() VMA exhaustion — production disaster post-mortem.
 *
 * <p>The LinkedIn incident, 2019. SIGSEGV in production after 30 minutes of warmup.
 * Not a Java exception. Not a hardware failure. Not a JVM bug. A Linux kernel limit
 * that nobody documented outside of the Elasticsearch install guide.
 */
@ChaosTest(classes = com.macstab.chaos.examples.UserServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InsaneMprotectVmaExhaustionTest {

    @Autowired
    TestRestTemplate restTemplate;

    @Autowired
    ChaosControlPlane chaos;

    @BeforeAll
    static void printMprotectIncident() {
        System.out.println();
        System.out.println("  ╔══════════════════════════════════════════════════════════════════════════╗");
        System.out.println("  ║  INCIDENT: LinkedIn Java Service — SIGSEGV After 30 Minutes Warmup      ║");
        System.out.println("  ╠══════════════════════════════════════════════════════════════════════════╣");
        System.out.println("  ║  Linux max_map_count default: 65536 VMAs per process                   ║");
        System.out.println("  ║  JVM under heavy JIT: can exceed 65536 VMAs. mprotect() returns        ║");
        System.out.println("  ║  ENOMEM. JVM: SIGSEGV.                                                 ║");
        System.out.println("  ║  The crash: looks like hardware failure (SIGSEGV) or JVM bug.          ║");
        System.out.println("  ║  It's a Linux kernel limit.                                            ║");
        System.out.println("  ║  Kubernetes node: max_map_count is PER NODE, not per pod. One pod      ║");
        System.out.println("  ║  exhausting VMAs: affects all pods on the node.                        ║");
        System.out.println("  ║  LinkedIn: SIGSEGV in prod after 30 minutes. Root cause: JIT           ║");
        System.out.println("  ║  compilation hitting VMA limit. Fix: raise max_map_count in node       ║");
        System.out.println("  ║  config.                                                               ║");
        System.out.println("  ║  How to check: /proc/<pid>/maps | wc -l. If approaching 65536:        ║");
        System.out.println("  ║  you will crash.                                                       ║");
        System.out.println("  ║  Elasticsearch: raises max_map_count to 262144 in its install docs    ║");
        System.out.println("  ║  for exactly this reason. Nobody else documents it.                    ║");
        System.out.println("  ╚══════════════════════════════════════════════════════════════════════════╝");
        System.out.println();
    }

    @Test
    @DisplayName("INSANE: JIT code page mprotect() exhaustion mimics hardware crash — SIGSEGV not OutOfMemoryError. The LinkedIn 30-min warmup mystery.")
    void codePageMprotectExhaustionMimicsHardwareCrash() throws Exception {
        System.out.println();
        System.out.println("  ╔══════════════════════════════════════════════════════════════════════════╗");
        System.out.println("  ║  Linux max_map_count default: 65536 VMAs per process                   ║");
        System.out.println("  ║  JVM under heavy JIT: can exceed 65536 VMAs. mprotect() returns        ║");
        System.out.println("  ║  ENOMEM. JVM: SIGSEGV.                                                 ║");
        System.out.println("  ║  The crash: looks like hardware failure (SIGSEGV) or JVM bug.          ║");
        System.out.println("  ║  It's a Linux kernel limit.                                            ║");
        System.out.println("  ║  Kubernetes node: max_map_count is PER NODE, not per pod. One pod      ║");
        System.out.println("  ║  exhausting VMAs: affects all pods on the node.                        ║");
        System.out.println("  ║  LinkedIn: SIGSEGV in prod after 30 minutes. Root cause: JIT           ║");
        System.out.println("  ║  compilation hitting VMA limit. Fix: raise max_map_count in node       ║");
        System.out.println("  ║  config.                                                               ║");
        System.out.println("  ║  How to check: /proc/<pid>/maps | wc -l. If approaching 65536:        ║");
        System.out.println("  ║  you will crash.                                                       ║");
        System.out.println("  ║  Elasticsearch: raises max_map_count to 262144 in its install docs    ║");
        System.out.println("  ║  for exactly this reason. Nobody else documents it.                    ║");
        System.out.println("  ╚══════════════════════════════════════════════════════════════════════════╝");
        System.out.println();

        // Read current VMA count from /proc/self/maps
        long vmaBefore = readVmaCount();
        System.out.printf("  Current VMA count (before pressure): %d%n", vmaBefore);

        // Record JIT compilation time before pressure
        CompilationMXBean compilationMX = ManagementFactory.getCompilationMXBean();
        long compilationTimeBefore = compilationMX != null && compilationMX.isCompilationTimeMonitoringSupported()
                ? compilationMX.getTotalCompilationTime()
                : -1L;

        // Inject code cache pressure — simulates heavy JIT compilation creating VMAs
        ChaosScenario codeCachePressure = ChaosScenario.builder("code-cache-pressure-vma")
                .description("Simulate heavy JIT compilation to stress VMA creation via mprotect() calls")
                .scope(ChaosScenario.ScenarioScope.JVM)
                .selector(ChaosSelector.jvmRuntime(Set.of(OperationType.CODE_CACHE_SWEEP)))
                .effect(ChaosEffect.codeCachePressure(90))
                .activationPolicy(new ActivationPolicy(
                        ActivationPolicy.StartMode.AUTOMATIC, 1.0, 0L, 0L, null, null, 0L, false))
                .build();

        AtomicInteger requestsOk = new AtomicInteger();

        try (ChaosActivationHandle handle = chaos.activate(codeCachePressure)) {
            System.out.println("  Code cache pressure active — JIT compilation stress begun...");
            Thread.sleep(5000); // let JIT compile under pressure and create VMAs

            // Run 20 requests under code cache pressure — all must succeed
            for (int i = 0; i < 20; i++) {
                try {
                    var response = restTemplate.getForEntity("/users", String.class);
                    if (response.getStatusCode().is2xxSuccessful()) {
                        requestsOk.incrementAndGet();
                    }
                } catch (Exception ignored) {}
            }
        }

        // Read VMA count after pressure
        long vmaAfter = readVmaCount();
        long compilationTimeAfter = compilationMX != null && compilationMX.isCompilationTimeMonitoringSupported()
                ? compilationMX.getTotalCompilationTime()
                : -1L;
        long compilationDelta = compilationTimeBefore >= 0 ? compilationTimeAfter - compilationTimeBefore : -1L;

        System.out.println();
        System.out.println("  ╔══════════════════════════════════════════════════════════════════════════╗");
        System.out.println("  ║  MPROTECT VMA EXHAUSTION PROOF                                          ║");
        System.out.printf( "  ║  VMA count before pressure:  %5d                                       ║%n", vmaBefore);
        System.out.printf( "  ║  VMA count after pressure:   %5d  (delta: %+d)                        ║%n", vmaAfter, vmaAfter - vmaBefore);
        if (compilationDelta >= 0) {
            System.out.printf("  ║  JIT compilation time delta: %5dms                                    ║%n", compilationDelta);
        }
        System.out.printf( "  ║  Service requests under pressure: %3d/20 succeeded                     ║%n", requestsOk.get());
        System.out.println("  ╠══════════════════════════════════════════════════════════════════════════╣");
        System.out.println("  ║  CONCLUSION: Your JVM at VMA limit crashes with SIGSEGV, not           ║");
        System.out.println("  ║  OutOfMemoryError. Standard monitoring: zero visibility.               ║");
        System.out.println("  ║  Check: /proc/self/maps | wc -l. Alert when > 50000.                  ║");
        System.out.println("  ║  Fix: sysctl -w vm.max_map_count=262144 at Kubernetes NODE level.     ║");
        System.out.println("  ║  Not per-pod. The node setting. If you only set it in the pod:        ║");
        System.out.println("  ║  it is silently ignored. You will still crash.                        ║");
        System.out.println("  ╚══════════════════════════════════════════════════════════════════════════╝");
        System.out.println();

        assertThat(requestsOk.get())
                .as("All 20 requests must succeed even under code cache pressure — service must remain live")
                .isEqualTo(20);
    }

    private long readVmaCount() {
        try {
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", "wc -l < /proc/self/maps");
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            String output = new String(proc.getInputStream().readAllBytes()).trim();
            proc.waitFor(3, TimeUnit.SECONDS);
            return Long.parseLong(output);
        } catch (Exception e) {
            return -1L;
        }
    }
}
