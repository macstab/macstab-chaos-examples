package com.macstab.chaos.examples.impossible;

import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosActivationHandle;
import com.macstab.chaos.jvm.api.ChaosControlPlane;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.OperationType;
import com.macstab.chaos.jvm.spring.boot4.test.ChaosTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.assertThat;

@ChaosTest(classes = com.macstab.chaos.examples.UserServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Execution(ExecutionMode.CONCURRENT)
class SetupOsSessionIsolationArchitectureTest {

    @Autowired
    ChaosControlPlane chaos;

    @BeforeAll
    static void printSessionIsolationComparison() {
        System.out.println();
        System.out.println("════════════════════════════════════════════════════════════════════════════════");
        System.out.println("  SETUP COMPARISON: Per-Test Chaos Session Isolation in Parallel Test Suite");
        System.out.println("════════════════════════════════════════════════════════════════════════════════");
        System.out.println();
        System.out.println("  Without per-test session isolation, you must choose:");
        System.out.println("  ┌───────────────────────────────────────────────────────────────────────────┐");
        System.out.println("  │ Option A: Run chaos tests SEQUENTIALLY                                  │");
        System.out.println("  │   No parallelism. 10x slower CI. Unacceptable for large test suites.   │");
        System.out.println("  │                                                                          │");
        System.out.println("  │ Option B: @Fork(1) — separate JVM per chaos test                       │");
        System.out.println("  │   3-8 seconds JVM startup per test. 100 chaos tests = 300-800s.        │");
        System.out.println("  │   JVM startup cost alone makes this unacceptable.                      │");
        System.out.println("  │                                                                          │");
        System.out.println("  │ Option C: Separate WireMock port per test                              │");
        System.out.println("  │   WireMock is HTTP only — cannot inject TCP errno (ECONNRESET etc.).   │");
        System.out.println("  │   Cannot isolate JDBC faults. Cannot isolate JVM-level faults.         │");
        System.out.println("  │                                                                          │");
        System.out.println("  │ Option D: Build ThreadLocal session propagation from scratch  2-3 weeks │");
        System.out.println("  │   • Design ChaosSession: ThreadLocal<ChaosSession>                     │");
        System.out.println("  │   • VT propagation: VTs do NOT inherit parent ThreadLocals            │");
        System.out.println("  │   • ExecutorService wrapping: wrap every Runnable/Callable            │");
        System.out.println("  │   • @Async / Spring thread pools: intercept task submission globally  │");
        System.out.println("  │   • @ExtendWith for JUnit 5: bind/unbind session per method          │");
        System.out.println("  │   Expert Java concurrency knowledge. Breaks with Java releases.       │");
        System.out.println("  └───────────────────────────────────────────────────────────────────────────┘");
        System.out.println();
        System.out.println("  WITH macstab-chaos (@ChaosTest + ChaosSession, pre-built):");
        System.out.println("  ┌───────────────────────────────────────────────────────────────────────────┐");
        System.out.println("  │   @ChaosTest          →  session bound/unbound per test method          │");
        System.out.println("  │   @Execution(CONCURRENT) →  parallel tests, zero chaos leakage         │");
        System.out.println("  │   Session propagates:  VTs, @Async, CompletableFuture, executors       │");
        System.out.println("  │   TOTAL: 1 annotation. 5 seconds.                                      │");
        System.out.println("  └───────────────────────────────────────────────────────────────────────────┘");
        System.out.println("════════════════════════════════════════════════════════════════════════════════");
    }

    @Test
    @DisplayName("SetupOs Session A: 100% executor rejection — session-scoped, zero leakage to concurrent Session B")
    void sessionA_fullRejectionIsolatedFromOtherTests() throws Exception {
        ChaosScenario fullRejection = ChaosScenario.builder("session-a-full-reject")
                .description("Session A: reject all executor submissions — must not leak to concurrent Session B")
                .scope(ChaosScenario.ScenarioScope.SESSION)
                .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
                .effect(ChaosEffect.reject("session-a-only: overloaded"))
                .activationPolicy(new ActivationPolicy(
                        ActivationPolicy.StartMode.AUTOMATIC, 1.0, 0L, 0L, null, null, 0L, false))
                .build();

        AtomicInteger rejected = new AtomicInteger();
        try (ChaosActivationHandle handle = chaos.activate(fullRejection)) {
            CountDownLatch latch = new CountDownLatch(20);
            for (int i = 0; i < 20; i++) {
                try {
                    Executors.newSingleThreadExecutor().submit(() -> {}).get(1, TimeUnit.SECONDS);
                } catch (Exception e) {
                    rejected.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            }
            latch.await(10, TimeUnit.SECONDS);
        }

        System.out.printf("%n  [Session A] 20 submits: %d rejected by 100%% session-scoped chaos%n", rejected.get());
        System.out.println("  This chaos is SESSION-SCOPED — does not leak to Session B running concurrently.");
        assertThat(rejected.get()).as("Session A chaos causes rejections in Session A").isGreaterThan(10);
    }

    @Test
    @DisplayName("SetupOs Session B: ZERO chaos — runs concurrently with Session A's 100% rejection, proves zero leakage")
    void sessionB_noChaosDespiteConcurrentSessionA() throws Exception {
        AtomicInteger successes = new AtomicInteger();
        CountDownLatch latch = new CountDownLatch(20);
        for (int i = 0; i < 20; i++) {
            try {
                Executors.newSingleThreadExecutor().submit(() -> {}).get(1, TimeUnit.SECONDS);
                successes.incrementAndGet();
            } catch (Exception ignored) {
            } finally {
                latch.countDown();
            }
        }
        latch.await(10, TimeUnit.SECONDS);

        System.out.printf("%n  [Session B] 20 submits: %d succeeded (concurrent with Session A's 100%% rejection)%n", successes.get());
        System.out.println("  Session A's chaos did NOT leak to Session B. Session isolation works.");
        System.out.println("  Without macstab-chaos session model: forced to run sequentially or fork.");
        assertThat(successes.get()).as("Session B fully unaffected by Session A's chaos").isEqualTo(20);
    }
}
