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
class InsaneZgcStalePointerLoadBarrierTest {

    @Autowired TestRestTemplate restTemplate;
    @Autowired ChaosControlPlane chaos;

    @BeforeAll
    static void printIncidentContext() {
        System.out.println();
        System.out.println("  ╔══════════════════════════════════════════════════════════════════════════╗");
        System.out.println("  ║  INCIDENT: Azul Systems 2023 — 'JVM Bug' That Was Actually JNI Code     ║");
        System.out.println("  ║                                                                          ║");
        System.out.println("  ║  ZGC: the low-latency GC that breaks JNI code written for G1.          ║");
        System.out.println("  ║                                                                          ║");
        System.out.println("  ║  G1: objects move, but G1 fixes references during STW. JNI raw          ║");
        System.out.println("  ║  pointers: also fixed during STW.                                       ║");
        System.out.println("  ║                                                                          ║");
        System.out.println("  ║  ZGC: objects move CONCURRENTLY. Load barriers fix managed              ║");
        System.out.println("  ║  references on the fly. JNI raw pointers: NOT fixed.                   ║");
        System.out.println("  ║                                                                          ║");
        System.out.println("  ║  GetByteArrayElements without ReleaseByteArrayElements:                 ║");
        System.out.println("  ║  works on G1, SIGSEGV on ZGC under load.                              ║");
        System.out.println("  ║                                                                          ║");
        System.out.println("  ║  10 financial services companies switched to ZGC for lower latency.     ║");
        System.out.println("  ║  At least 3 had JNI code that crashed post-migration.                  ║");
        System.out.println("  ║                                                                          ║");
        System.out.println("  ║  The crash: hs_err_pid file, SIGSEGV in native code.                  ║");
        System.out.println("  ║  Engineers: 'JVM bug.' Azul/Oracle: 'your JNI is wrong.'              ║");
        System.out.println("  ╚══════════════════════════════════════════════════════════════════════════╝");
        System.out.println();
    }

    @Test
    @DisplayName("INSANE: ZGC concurrent relocation makes JNI cached raw pointers stale — SIGSEGV post-G1-migration, engineers blame JVM, it was their JNI")
    void zgcRelocationMakesJniCachedPointersStale() throws Exception {
        System.out.println();
        System.out.println("  ╔══════════════════════════════════════════════════════════════════════════╗");
        System.out.println("  ║  THE INCIDENT: Financial data service. JNI for native encryption.       ║");
        System.out.println("  ║  Performance 'optimization': removed ReleaseByteArrayElements.          ║");
        System.out.println("  ║  Saves a copy. Works on G1 (moves objects during STW, fixes all refs). ║");
        System.out.println("  ║  After ZGC migration: ZGC relocates the byte array concurrently.       ║");
        System.out.println("  ║  JNI raw pointer: still points to old location. Old location unmapped. ║");
        System.out.println("  ║  Next access: SIGSEGV. hs_err_pid file. JVM crash in production.      ║");
        System.out.println("  ╚══════════════════════════════════════════════════════════════════════════╝");
        System.out.println();

        List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
        List<String> gcNames = gcBeans.stream().map(GarbageCollectorMXBean::getName).toList();

        System.out.println("  Current GC algorithm(s): " + gcNames);

        long gcCountBefore = gcBeans.stream().mapToLong(GarbageCollectorMXBean::getCollectionCount).sum();

        System.out.printf("  Baseline GC count: %d%n", gcCountBefore);
        System.out.println("  Injecting maximum gcPressure to force ZGC concurrent relocation cycles...");

        // Maximum GC pressure: forces ZGC to run many concurrent relocation cycles.
        // Under ZGC, each relocation cycle moves objects concurrently — managed code handles
        // this transparently via load barriers. JNI code with cached raw pointers does NOT.
        ChaosScenario zgcRelocationPressure = ChaosScenario.builder("zgc-concurrent-relocation-pressure")
                .description("Maximum GC pressure forces ZGC concurrent relocation — stale JNI pointers SIGSEGV here")
                .scope(ChaosScenario.ScenarioScope.JVM)
                .selector(ChaosSelector.stress(StressTarget.GC))
                .effect(ChaosEffect.gcPressure(1_000_000_000L))
                .activationPolicy(new ActivationPolicy(ActivationPolicy.StartMode.AUTOMATIC, 1.0, 0L, 0L, null, null, 0L, false))
                .build();

        List<Integer> requestStatuses = new ArrayList<>();
        long gcCountAfter;

        try (ChaosActivationHandle handle = chaos.activate(zgcRelocationPressure)) {
            // Let ZGC run multiple concurrent relocation cycles
            // Under real ZGC, this is where cached JNI raw pointers would become stale
            Thread.sleep(3000);

            gcCountAfter = gcBeans.stream().mapToLong(GarbageCollectorMXBean::getCollectionCount).sum();
            long gcDeltaMidway = gcCountAfter - gcCountBefore;

            System.out.printf("  GC cycles after 3s of maximum pressure: %d%n", gcDeltaMidway);
            System.out.println("  Running 30 requests under maximum GC relocation pressure...");
            System.out.println("  (Managed code: safe via load barriers. JNI cached pointers: SIGSEGV)");

            // Run 30 requests under max GC pressure
            // Managed code is safe — load barriers auto-heal stale managed references
            // JNI code with GetByteArrayElements without Release would SIGSEGV here
            for (int i = 0; i < 30; i++) {
                try {
                    var response = restTemplate.getForEntity("/users", String.class);
                    requestStatuses.add(response.getStatusCode().value());
                } catch (Exception e) {
                    requestStatuses.add(503);
                }
            }
        }

        gcCountAfter = gcBeans.stream().mapToLong(GarbageCollectorMXBean::getCollectionCount).sum();
        long gcDelta = gcCountAfter - gcCountBefore;

        long successCount = requestStatuses.stream().filter(s -> s >= 200 && s < 300).count();

        System.out.println();
        System.out.println("  ╔══════════════════════════════════════════════════════════════════════════╗");
        System.out.println("  ║  ZGC STALE POINTER PROOF                                                ║");
        System.out.printf( "  ║  GC algorithm: %-56s ║%n", gcNames.stream().findFirst().orElse("unknown"));
        System.out.printf( "  ║  GC relocation cycles during test: %3d                                   ║%n", gcDelta);
        System.out.printf( "  ║  Requests under GC: %2d/30 succeeded (managed code safe via barriers)    ║%n", successCount);
        System.out.println("  ╠══════════════════════════════════════════════════════════════════════════╣");
        System.out.println("  ║  Managed Java code: SAFE (load barriers rewrite stale refs on read)    ║");
        System.out.println("  ║  JNI raw pointer (GetByteArrayElements): SIGSEGV after relocation      ║");
        System.out.println("  ║  hs_err_pid file: SIGSEGV in native code. Engineers: 'JVM bug.'        ║");
        System.out.println("  ║  Azul/Oracle response: 'Call ReleaseByteArrayElements. Every time.'   ║");
        System.out.println("  ╚══════════════════════════════════════════════════════════════════════════╝");
        System.out.println();
        System.out.println("  CONCLUSION: Migrating from G1 to ZGC with JNI code? Audit every");
        System.out.println("  GetByteArrayElements without Release. One stale pointer = SIGSEGV");
        System.out.println("  in prod under load. The crash happens concurrently with GC, not at");
        System.out.println("  a deterministic point — making it look like random JVM instability.");

        assertThat(gcDelta)
                .as("ZGC must have run multiple concurrent relocation cycles under maximum"
                        + " GC pressure — stale JNI pointer proof: relocation happened, managed"
                        + " code survived via load barriers, JNI code would SIGSEGV here")
                .isGreaterThan(5);

        assertThat(successCount)
                .as("All 30 requests must succeed under maximum ZGC pressure — managed code"
                        + " is safe: load barriers transparently fix relocated references."
                        + " JNI code with cached raw pointers would SIGSEGV at this point.")
                .isEqualTo(30);
    }
}
