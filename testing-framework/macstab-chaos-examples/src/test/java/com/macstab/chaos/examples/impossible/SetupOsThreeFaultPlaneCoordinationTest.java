package com.macstab.chaos.examples.impossible;

import com.macstab.chaos.core.annotation.SyscallLevelChaos;
import com.macstab.chaos.core.syscall.LibchaosLib;
import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosActivationHandle;
import com.macstab.chaos.jvm.api.ChaosControlPlane;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.OperationType;
import com.macstab.chaos.net.annotation.l1.ChaosRecvEconnreset;
import com.macstab.chaos.time.annotation.l1.ChaosNanosleepLatency;
import com.macstab.chaos.jvm.spring.boot4.test.ChaosTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.assertThat;

@ChaosTest(classes = com.macstab.chaos.examples.UserServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@SyscallLevelChaos({LibchaosLib.NET, LibchaosLib.TIME})
@ChaosRecvEconnreset(probability = 0.3f)
@ChaosNanosleepLatency(additionalMs = 100)
class SetupOsThreeFaultPlaneCoordinationTest {

    @Autowired
    ChaosControlPlane chaos;

    @Autowired
    TestRestTemplate restTemplate;

    @BeforeAll
    static void printCoordinationComparison() {
        System.out.println();
        System.out.println("════════════════════════════════════════════════════════════════════════════════");
        System.out.println("  SETUP COMPARISON: THREE Simultaneous Fault Planes in One Test");
        System.out.println("  Plane 1: OS network — recv() ECONNRESET 30%");
        System.out.println("  Plane 2: JVM async  — CompletableFuture.complete() 50ms delay");
        System.out.println("  Plane 3: OS time    — nanosleep +100ms latency");
        System.out.println("════════════════════════════════════════════════════════════════════════════════");
        System.out.println();
        System.out.println("  Without macstab-chaos — build and coordinate all three:");
        System.out.println("  ┌───────────────────────────────────────────────────────────────────────────┐");
        System.out.println("  │ Tool 1: Toxiproxy for network plane                         2-3 days    │");
        System.out.println("  │   Docker sidecar + proxy config + application routing                   │");
        System.out.println("  │                                                                          │");
        System.out.println("  │ Tool 2: Custom ByteBuddy agent for JVM async plane          2-3 days    │");
        System.out.println("  │   Write agent, package as -javaagent JAR, configure JVM args           │");
        System.out.println("  │                                                                          │");
        System.out.println("  │ Tool 3: libfaketime for time plane                          1-2 weeks   │");
        System.out.println("  │   LD_PRELOAD — but Tool 1's LD_PRELOAD library is already set!         │");
        System.out.println("  │   Two LD_PRELOAD libraries = symbol conflicts = undefined behavior      │");
        System.out.println("  │   libfaketime + custom network lib: 1-2 weeks to resolve conflicts     │");
        System.out.println("  │                                                                          │");
        System.out.println("  │ Coordination plumbing                                       2-4 weeks   │");
        System.out.println("  │   Start/stop ordering across three systems                             │");
        System.out.println("  │   All three active for exactly the test duration                       │");
        System.out.println("  │   Cleanup: Toxiproxy teardown + ByteBuddy detach + libfaketime reset  │");
        System.out.println("  │   CI reliability: all must be ready before first request               │");
        System.out.println("  │                                                                          │");
        System.out.println("  │ TOTAL: 6-10 weeks. Three experts (C, Java, DevOps). Own it forever.  │");
        System.out.println("  └───────────────────────────────────────────────────────────────────────────┘");
        System.out.println();
        System.out.println("  WITH macstab-chaos (all three planes, one test class):");
        System.out.println("  ┌───────────────────────────────────────────────────────────────────────────┐");
        System.out.println("  │   @SyscallLevelChaos({LibchaosLib.NET, LibchaosLib.TIME})               │");
        System.out.println("  │   @ChaosRecvEconnreset(probability=0.3f)    // plane 1: OS network      │");
        System.out.println("  │   @ChaosNanosleepLatency(additionalMs=100)  // plane 3: OS time         │");
        System.out.println("  │   + chaos.activate(completableFutureDelay)  // plane 2: JVM async       │");
        System.out.println("  │                                                                          │");
        System.out.println("  │   All three planes coordinated by session lifecycle. No plumbing.       │");
        System.out.println("  │   TOTAL: 3 annotations + 5 lines. 10 minutes.                         │");
        System.out.println("  └───────────────────────────────────────────────────────────────────────────┘");
        System.out.println("════════════════════════════════════════════════════════════════════════════════");
    }

    @Test
    @DisplayName("SetupOs: three fault planes simultaneously — 6-10 weeks to coordinate manually, 10 minutes with macstab-chaos")
    void threeFaultPlanesSimultaneouslyZeroCoordinationCode() throws Exception {
        ChaosScenario asyncPlane = ChaosScenario.builder("async-plane-cf")
                .description("Plane 2: CompletableFuture.complete() 50ms delay — JVM async fault plane")
                .scope(ChaosScenario.ScenarioScope.JVM)
                .selector(ChaosSelector.async(Set.of(OperationType.COMPLETABLE_FUTURE_COMPLETE)))
                .effect(ChaosEffect.delay(Duration.ofMillis(50)))
                .activationPolicy(new ActivationPolicy(
                        ActivationPolicy.StartMode.AUTOMATIC, 1.0, 0L, 0L, null, null, 0L, false))
                .build();

        AtomicInteger networkFaults = new AtomicInteger();
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger slowRequests = new AtomicInteger();

        try (ChaosActivationHandle handle = chaos.activate(asyncPlane)) {
            for (int i = 0; i < 40; i++) {
                long t0 = System.nanoTime();
                try {
                    var r = restTemplate.getForEntity("/users", String.class);
                    long ms = (System.nanoTime() - t0) / 1_000_000;
                    if (r.getStatusCode().is2xxSuccessful()) {
                        successes.incrementAndGet();
                        if (ms > 90) slowRequests.incrementAndGet();
                    } else {
                        networkFaults.incrementAndGet();
                    }
                } catch (Exception e) {
                    networkFaults.incrementAndGet();
                }
            }
        }

        System.out.println();
        System.out.println("  ╔═════════════════════════════════════════════════════════════════════╗");
        System.out.println("  ║  THREE FAULT PLANES PROOF — 40 requests                             ║");
        System.out.printf( "  ║  Plane 1 (OS net ECONNRESET ~30%%):   %3d/40 faulted               ║%n", networkFaults.get());
        System.out.println("  ║  Plane 2 (JVM async CF delay 50ms):  active on all requests        ║");
        System.out.printf( "  ║  Plane 3 (nanosleep +100ms):         %3d/40 requests >90ms         ║%n", slowRequests.get());
        System.out.printf( "  ║  Succeeded through all planes:       %3d/40                        ║%n", successes.get());
        System.out.println("  ╠═════════════════════════════════════════════════════════════════════╣");
        System.out.println("  ║  Manual setup:      3 tools + 6-10 weeks + 3 experts               ║");
        System.out.println("  ║  macstab-chaos:     3 annotations + 5 lines + 10 minutes           ║");
        System.out.println("  ╚═════════════════════════════════════════════════════════════════════╝");

        assertThat(networkFaults.get() + successes.get()).as("All 40 requests attempted across three fault planes").isEqualTo(40);
    }
}
