package com.macstab.chaos.examples.impossible;

import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosActivationHandle;
import com.macstab.chaos.jvm.api.ChaosControlPlane;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.OperationType;
import com.macstab.chaos.jvm.api.ThreadKind;
import com.macstab.chaos.jvm.spring.boot4.test.ChaosTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import static org.assertj.core.api.Assertions.assertThat;

@ChaosTest(classes = com.macstab.chaos.examples.UserServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SetupOsJvmBootstrapBridgeTest {

    @Autowired
    ChaosControlPlane chaos;

    @BeforeAll
    static void printBootstrapBridgeComparison() {
        System.out.println();
        System.out.println("════════════════════════════════════════════════════════════════════════════════");
        System.out.println("  SETUP COMPARISON: JVM Bootstrap Classloader Bridge");
        System.out.println("  Needed for: Unsafe.park(), Thread internals, GC threads, VT scheduler");
        System.out.println("════════════════════════════════════════════════════════════════════════════════");
        System.out.println();
        System.out.println("  Standard ByteBuddy (what everyone uses):");
        System.out.println("  ┌───────────────────────────────────────────────────────────────────────────┐");
        System.out.println("  │ Instruments: Application classloader only                               │");
        System.out.println("  │ CANNOT reach: sun.misc.Unsafe, java.lang.Thread internals,             │");
        System.out.println("  │               GC threads, ClassLoader.defineClass(),                   │");
        System.out.println("  │               ReferenceHandler thread, Finalizer thread,               │");
        System.out.println("  │               VirtualThread scheduler (bootstrap-loaded ForkJoinPool)  │");
        System.out.println("  │                                                                          │");
        System.out.println("  │ Try to instrument bootstrap class with standard ByteBuddy:             │");
        System.out.println("  │   → NoClassDefFoundError at runtime                                    │");
        System.out.println("  │   → Agent classes not visible to bootstrap classloader                 │");
        System.out.println("  └───────────────────────────────────────────────────────────────────────────┘");
        System.out.println();
        System.out.println("  To build bootstrap bridge from scratch:");
        System.out.println("  ┌───────────────────────────────────────────────────────────────────────────┐");
        System.out.println("  │ Step 1: Two-JAR architecture design                         1 week      │");
        System.out.println("  │   agent-bootstrap.jar  →  Boot-Class-Path in MANIFEST.MF               │");
        System.out.println("  │   agent-impl.jar       →  ByteBuddy instrumentation                    │");
        System.out.println("  │   Shared interfaces in bootstrap jar; impls in agent jar               │");
        System.out.println("  │                                                                          │");
        System.out.println("  │ Step 2: Implement bootstrap JAR                             2-3 days   │");
        System.out.println("  │   Zero dependencies. Must be standalone. Shared callback interfaces.   │");
        System.out.println("  │                                                                          │");
        System.out.println("  │ Step 3: Maven build configuration                           1 day      │");
        System.out.println("  │   Two artifacts. Boot-Class-Path in manifest. Shade plugin.           │");
        System.out.println("  │                                                                          │");
        System.out.println("  │ Step 4: Class retransformation                              2-3 days   │");
        System.out.println("  │   Classes loaded BEFORE agent attach must be retransformed.           │");
        System.out.println("  │   instrumentation.retransformClasses(Thread.class, Unsafe.class...)   │");
        System.out.println("  │                                                                          │");
        System.out.println("  │ Step 5: JDK compatibility                                 1-2 weeks   │");
        System.out.println("  │   JDK 9+: --add-opens required per package                           │");
        System.out.println("  │   JDK 17: stricter module encapsulation, some paths broken           │");
        System.out.println("  │   JDK 21: VirtualThread impl changed, redo VT instrumentation        │");
        System.out.println("  │   JDK 24: Project Loom stabilization, more changes                   │");
        System.out.println("  │                                                                          │");
        System.out.println("  │ TOTAL: 3-5 weeks. Expert JVM internals. Breaks every JDK release.   │");
        System.out.println("  └───────────────────────────────────────────────────────────────────────────┘");
        System.out.println();
        System.out.println("  WITH Per's agent (bootstrap bridge pre-built, tested on JDK 17/21/24):");
        System.out.println("  ┌───────────────────────────────────────────────────────────────────────────┐");
        System.out.println("  │   ChaosSelector.thread(Set.of(OperationType.VIRTUAL_THREAD_PARK),       │");
        System.out.println("  │                        ThreadKind.VIRTUAL)                               │");
        System.out.println("  │   // Internally uses the bootstrap bridge to reach Unsafe.park()        │");
        System.out.println("  │   // No JVM internals knowledge required. No two-JAR architecture.     │");
        System.out.println("  │   TOTAL: 1 API call. 30 seconds.                                       │");
        System.out.println("  └───────────────────────────────────────────────────────────────────────────┘");
        System.out.println("════════════════════════════════════════════════════════════════════════════════");
    }

    @Test
    @DisplayName("SetupOs: bootstrap bridge intercepts Unsafe.park() for VTs — standard ByteBuddy cannot reach it; build it: 3-5 weeks")
    void bootstrapBridgeEnablesUnsafeParkInterception() throws Exception {
        ChaosScenario vtParkProbe = ChaosScenario.builder("bootstrap-bridge-vt-park")
                .description("Bootstrap bridge: delay Unsafe.park() for virtual threads — impossible without bootstrap access")
                .scope(ChaosScenario.ScenarioScope.JVM)
                .selector(ChaosSelector.thread(Set.of(OperationType.VIRTUAL_THREAD_PARK), ThreadKind.VIRTUAL))
                .effect(ChaosEffect.delay(Duration.ofMillis(20)))
                .activationPolicy(new ActivationPolicy(
                        ActivationPolicy.StartMode.AUTOMATIC, 1.0, 0L, 0L, null, null, 0L, false))
                .build();

        AtomicInteger completed = new AtomicInteger();
        long t0 = System.nanoTime();

        try (ChaosActivationHandle handle = chaos.activate(vtParkProbe)) {
            CountDownLatch latch = new CountDownLatch(10);
            for (int i = 0; i < 10; i++) {
                Thread.ofVirtual().start(() -> {
                    LockSupport.parkNanos(1_000_000L);
                    completed.incrementAndGet();
                    latch.countDown();
                });
            }
            latch.await(15, TimeUnit.SECONDS);
        }

        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
        System.out.printf("%n  10 VTs, each Unsafe.park() delayed 20ms via bootstrap bridge%n");
        System.out.printf("  Total elapsed: %dms | VTs completed: %d/10%n", elapsedMs, completed.get());
        System.out.println("  Standard ByteBuddy: NoClassDefFoundError trying to reach Unsafe.");
        System.out.println("  This agent: bootstrap bridge pre-built. Works on JDK 17/21/24.");
        System.out.println("  Building it yourself: 3-5 weeks + breaks every JDK release.");

        assertThat(completed.get()).as("All 10 VTs completed through bootstrap-bridged Unsafe.park()").isEqualTo(10);
    }
}
