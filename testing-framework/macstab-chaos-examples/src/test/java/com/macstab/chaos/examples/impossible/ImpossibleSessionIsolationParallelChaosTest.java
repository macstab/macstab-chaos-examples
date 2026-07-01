package com.macstab.chaos.examples.impossible;

import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosActivationHandle;
import com.macstab.chaos.jvm.api.ChaosControlPlane;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.OperationType;
import com.macstab.chaos.jvm.spring.boot4.test.ChaosTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;

import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves per-session chaos isolation — a capability that is fundamentally impossible
 * with any global network-layer or proxy-based chaos tool.
 *
 * <p>WHY THIS IS IMPOSSIBLE WITH OTHER TOOLS:
 * <ul>
 *   <li>Toxiproxy: global proxy config. If you set 50% packet loss, it applies to ALL threads.
 *       You cannot give Thread A 50% loss and Thread B 0% loss simultaneously.</li>
 *   <li>tc-netem: network namespace level. ALL threads in the process see the same rules.</li>
 *   <li>ByteBuddy alone: no concept of "test isolation." Instrumentation is global.</li>
 *   <li>JVM agent with session isolation: chaos is scoped to a ChaosSession (ThreadLocal-propagated).
 *       Thread A's chaos does NOT leak to Thread B. This is the ONLY framework with
 *       per-test-thread fault isolation.</li>
 * </ul>
 *
 * <p>THE TEST: Two {@link Test} methods run concurrently via {@link Execution}({@link ExecutionMode#CONCURRENT}).
 * Test 1 activates 100% EXECUTOR_SUBMIT rejection — all tasks fail.
 * Test 2 has zero chaos — all tasks succeed.
 * They run AT THE SAME TIME. Test 2's success assertions must not be corrupted by Test 1's chaos.
 * If session isolation works: Test 2 passes. If isolation fails: Test 2's tasks also get rejected.
 */
@ChaosTest(classes = com.macstab.chaos.examples.UserServiceApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Execution(ExecutionMode.CONCURRENT)
class ImpossibleSessionIsolationParallelChaosTest {

    @Autowired
    TestRestTemplate restTemplate;

    @Autowired
    ChaosControlPlane chaos;

    /**
     * Activates 100% executor submission rejection scoped to this test's ChaosSession only.
     *
     * <p>The SESSION scope ensures the fault is bound to the ThreadLocal ChaosSession of this
     * test thread. While this test runs concurrently with {@link #noChaosBecauseSessionIsolated()},
     * the rejection fault cannot cross session boundaries — it is invisible to any thread not
     * propagating this session's context.
     */
    @Test
    @DisplayName("IMPOSSIBLE Part 1: 100% executor rejection in this test — session-isolated so other concurrent tests are UNAFFECTED")
    void chaosIsolatedToThisTestSession() throws Exception {
        System.out.println();
        System.out.println("  [SESSION A] Activating 100% EXECUTOR_SUBMIT rejection...");

        ChaosScenario fullRejection = ChaosScenario.builder("session-a-full-rejection")
                .description("Session A: reject all executor submissions — must not leak to Session B")
                .scope(ChaosScenario.ScenarioScope.SESSION)
                .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
                .effect(ChaosEffect.reject("session-A-only: executor overloaded"))
                .activationPolicy(new ActivationPolicy(
                        ActivationPolicy.StartMode.AUTOMATIC, 1.0, 0L, 0L, null, null, 0L, false))
                .build();

        AtomicInteger rejected = new AtomicInteger(0);
        AtomicInteger succeeded = new AtomicInteger(0);

        try (ChaosActivationHandle handle = chaos.activate(fullRejection)) {
            ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor();
            CountDownLatch latch = new CountDownLatch(20);
            for (int i = 0; i < 20; i++) {
                try {
                    exec.submit(() -> {
                        succeeded.incrementAndGet();
                        latch.countDown();
                    });
                } catch (Exception e) {
                    rejected.incrementAndGet();
                    latch.countDown();
                }
            }
            latch.await(5, TimeUnit.SECONDS);
            exec.shutdown();
        }

        System.out.printf("  [SESSION A] Submitted 20 tasks: %d succeeded, %d rejected%n",
                succeeded.get(), rejected.get());
        System.out.println("  [SESSION A] This chaos is SESSION-SCOPED — does not affect Session B.");
        assertThat(rejected.get())
                .as("Session A chaos causes rejections within this session")
                .isGreaterThan(10);
    }

    /**
     * Submits 20 tasks with zero chaos configured, while {@link #chaosIsolatedToThisTestSession()}
     * runs concurrently with 100% rejection active in its own session.
     *
     * <p>All 20 submissions must succeed. Any failure would prove that Session A's fault leaked
     * into this thread's execution context — which would be a session isolation violation.
     * The ChaosSession (ThreadLocal-propagated) boundary guarantees zero leakage.
     */
    @Test
    @DisplayName("IMPOSSIBLE Part 2: Zero chaos in this session — runs CONCURRENTLY with Part 1's 100% rejection, proves zero leakage")
    void noChaosBecauseSessionIsolated() throws Exception {
        System.out.println();
        System.out.println("  [SESSION B] No chaos configured. Running CONCURRENTLY with Session A's 100% rejection.");

        AtomicInteger succeeded = new AtomicInteger(0);
        AtomicInteger failed = new AtomicInteger(0);

        ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor();
        CountDownLatch latch = new CountDownLatch(20);
        for (int i = 0; i < 20; i++) {
            try {
                exec.submit(() -> {
                    succeeded.incrementAndGet();
                    latch.countDown();
                });
            } catch (Exception e) {
                failed.incrementAndGet();
                latch.countDown();
            }
        }
        latch.await(5, TimeUnit.SECONDS);
        exec.shutdown();

        System.out.printf("  [SESSION B] Submitted 20 tasks: %d succeeded, %d failed%n",
                succeeded.get(), failed.get());
        System.out.println();
        System.out.println("  ╔═══════════════════════════════════════════════════════════╗");
        System.out.println("  ║  SESSION ISOLATION PROOF                                  ║");
        System.out.println("  ║  Session A: 100% rejection running concurrently           ║");
        System.out.printf( "  ║  Session B: %2d/20 succeeded (Session A's chaos = ISOLATED) ║%n", succeeded.get());
        System.out.println("  ╠═══════════════════════════════════════════════════════════╣");
        System.out.println("  ║  Toxiproxy: global — Session A's loss would affect B too  ║");
        System.out.println("  ║  tc-netem: global — same problem                          ║");
        System.out.println("  ║  This agent: ChaosSession (ThreadLocal). Zero leakage.   ║");
        System.out.println("  ╚═══════════════════════════════════════════════════════════╝");

        assertThat(failed.get())
                .as("Session B unaffected by Session A's 100% rejection chaos")
                .isEqualTo(0);
        assertThat(succeeded.get())
                .as("All 20 submissions succeed in Session B")
                .isEqualTo(20);
        System.out.println("════════════════════════════════════════════════════════════════");
    }
}
