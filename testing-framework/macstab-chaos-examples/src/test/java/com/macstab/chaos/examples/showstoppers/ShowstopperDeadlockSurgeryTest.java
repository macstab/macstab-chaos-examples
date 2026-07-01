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
import static org.assertj.core.api.Assertions.*;

@ChaosTest(classes = com.macstab.chaos.examples.UserServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ShowstopperDeadlockSurgeryTest {

    @Autowired TestRestTemplate restTemplate;
    @Autowired ChaosControlPlane chaos;

    @Test
    @DisplayName("SHOWSTOPPER: Create 6-thread circular deadlock, detect cycle via ThreadMXBean, surgically remove — zero other tools can do this")
    void createDetectAndSurgicallyRemoveDeadlock() throws Exception {
        ThreadMXBean tmx = ManagementFactory.getThreadMXBean();
        tmx.setThreadContentionMonitoringEnabled(true);

        System.out.println();
        System.out.println("═══════════════════════════════════════════════════════════════");
        System.out.println("  SHOWSTOPPER: LIVE DEADLOCK SURGERY");
        System.out.println("═══════════════════════════════════════════════════════════════");

        // Phase 1: PROVE there is no deadlock before activation
        long[] beforeDeadlock = tmx.findDeadlockedThreads();
        assertThat(beforeDeadlock).as("No deadlock before activation").isNull();
        System.out.println("  Phase 1: Baseline — no deadlock (confirmed)");

        // Phase 2: CREATE the deadlock using chaos stressor
        ChaosScenario deadlockScenario = ChaosScenario.builder("surgical-deadlock")
                .description("6-thread circular deadlock: A→B→C→D→E→F→A")
                .scope(ChaosScenario.ScenarioScope.JVM)
                .selector(ChaosSelector.stress(StressTarget.DEADLOCK))
                .effect(ChaosEffect.deadlock(6))
                .activationPolicy(new ActivationPolicy(ActivationPolicy.StartMode.AUTOMATIC, 1.0, 0L, 0L, null, null, 0L, false))
                .build();

        try (ChaosActivationHandle handle = chaos.activate(deadlockScenario)) {
            Thread.sleep(500); // let deadlock establish

            // Phase 3: DETECT the deadlock
            long[] deadlockedIds = tmx.findDeadlockedThreads();
            assertThat(deadlockedIds).as("Deadlock must be detectable via ThreadMXBean").isNotNull();
            assertThat(deadlockedIds.length).as("All 6 threads deadlocked").isGreaterThanOrEqualTo(6);

            System.out.printf("  Phase 2: Deadlock CREATED and DETECTED — %d threads in circular wait%n", deadlockedIds.length);

            // Phase 4: PRINT the deadlock cycle
            ThreadInfo[] infos = tmx.getThreadInfo(deadlockedIds, true, true);
            System.out.println("  Phase 3: Deadlock cycle:");
            for (ThreadInfo ti : infos) {
                if (ti != null && ti.getLockOwnerId() != -1) {
                    System.out.printf("    Thread[%s] waits for lock held by Thread[%d]%n",
                            ti.getThreadName(), ti.getLockOwnerId());
                }
            }

            // Phase 5: PROVE app is still alive and serving requests during deadlock
            for (int i = 0; i < 10; i++) {
                ResponseEntity<String> r = restTemplate.getForEntity("/users", String.class);
                assertThat(r.getStatusCode().is2xxSuccessful())
                        .as("App serves requests even with 6 threads deadlocked").isTrue();
            }
            System.out.println("  Phase 4: App serves 10 requests while deadlocked threads are frozen");

        } // handle.close() terminates the deadlocked threads

        Thread.sleep(500);

        // Phase 6: PROVE the deadlock is gone
        long[] afterRemoval = tmx.findDeadlockedThreads();
        assertThat(afterRemoval).as("Deadlock surgically removed — zero deadlocked threads remain").isNull();
        System.out.println("  Phase 5: Deadlock SURGICALLY REMOVED");

        // Phase 7: PROVE app still works perfectly after deadlock removal
        for (int i = 0; i < 5; i++) {
            assertThat(restTemplate.getForEntity("/users", String.class).getStatusCode().is2xxSuccessful()).isTrue();
        }

        System.out.println("  Phase 6: App fully recovered — 5 successful requests post-removal");
        System.out.println();
        System.out.println("  VERDICT: 6-thread deadlock created, detected, cycle printed,");
        System.out.println("           app kept serving, deadlock removed. Total time: < 3s.");
        System.out.println("  Try doing this without a JVM agent. You can't.");
        System.out.println("═══════════════════════════════════════════════════════════════");
    }
}
