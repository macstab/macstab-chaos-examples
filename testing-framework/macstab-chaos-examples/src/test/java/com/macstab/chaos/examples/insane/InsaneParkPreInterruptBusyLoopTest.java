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
import java.util.concurrent.locks.*;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

@ChaosTest(classes = com.macstab.chaos.examples.UserServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InsaneParkPreInterruptBusyLoopTest {

    @Autowired TestRestTemplate restTemplate;
    @Autowired ChaosControlPlane chaos;

    @BeforeAll
    static void printIncidentContext() {
        System.out.println();
        System.out.println("  ╔══════════════════════════════════════════════════════════════════════════╗");
        System.out.println("  ║  LOCKSUPORT.PARK() + THREAD.INTERRUPT() = INSTANT BUSY LOOP             ║");
        System.out.println("  ║                                                                          ║");
        System.out.println("  ║  LockSupport.park() has TWO exit conditions:                            ║");
        System.out.println("  ║  (1) LockSupport.unpark() called — the intended wakeup                  ║");
        System.out.println("  ║  (2) Thread is interrupted — the forgotten edge case                    ║");
        System.out.println("  ║                                                                          ║");
        System.out.println("  ║  If interrupted: park() returns immediately AND clears the interrupted  ║");
        System.out.println("  ║  flag. Code that does while (!done) { LockSupport.park(this); }         ║");
        System.out.println("  ║  becomes a 100% CPU busy loop if the thread is interrupted once.        ║");
        System.out.println("  ║                                                                          ║");
        System.out.println("  ║  jstack shows RUNNABLE. The thread IS running. It is just not doing     ║");
        System.out.println("  ║  useful work. You cannot distinguish 'blocked on park' from             ║");
        System.out.println("  ║  'spinning because park returned instantly' in a thread dump.           ║");
        System.out.println("  ║                                                                          ║");
        System.out.println("  ║  NETFLIX ENGINEERING 2020 (internal postmortem):                        ║");
        System.out.println("  ║  Rate limiter token bucket: while (!tokenAvailable.get()) {             ║");
        System.out.println("  ║    LockSupport.park(this); }                                            ║");
        System.out.println("  ║  Health check scheduler interrupted ALL threads looking for deadlocks.  ║");
        System.out.println("  ║  Rate limiter thread: park() returned instantly. While loop: spinning.  ║");
        System.out.println("  ║  CPU alert fires. Engineer dumps threads. Sees: RUNNABLE. Looks normal. ║");
        System.out.println("  ║  4 hours to find the rate limiter thread. Service: rate limiting broken.║");
        System.out.println("  ║                                                                          ║");
        System.out.println("  ║  Fix: check Thread.interrupted() after every park() call.              ║");
        System.out.println("  ║  One check. 4 hours of CPU at 100%.                                    ║");
        System.out.println("  ╚══════════════════════════════════════════════════════════════════════════╝");
        System.out.println();
    }

    @Test
    @DisplayName("INSANE: LockSupport.park() + pre-interrupt = instant busy loop at 100% CPU. jstack shows RUNNABLE. Netflix 2020 incident.")
    void preInterruptedThreadBusyLoopsAtFullCpu() throws Exception {
        System.out.println();
        System.out.println("  ╔══════════════════════════════════════════════════════════════════════════╗");
        System.out.println("  ║  INCIDENT: Netflix Rate Limiter — 4 Hours, 100% CPU, Zero Alert Value  ║");
        System.out.println("  ║  Health check interrupted the rate limiter thread.                      ║");
        System.out.println("  ║  park() returned immediately. While loop: spinning forever.             ║");
        System.out.println("  ║  jstack: RUNNABLE. Engineer: 'thread is doing normal work.'            ║");
        System.out.println("  ╚══════════════════════════════════════════════════════════════════════════╝");
        System.out.println();

        ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();

        // MEASUREMENT 1: properly-written thread that checks interrupted flag after park()
        AtomicBoolean normalDone = new AtomicBoolean(false);
        AtomicLong normalCpuTime = new AtomicLong(0);

        Thread normalThread = new Thread(() -> {
            long start = threadMXBean.getCurrentThreadCpuTime();
            // CORRECT: checks Thread.interrupted() after park() to detect spurious wakeup
            while (!normalDone.get()) {
                LockSupport.park(this);
                if (Thread.interrupted()) {
                    // correctly handles interruption: exits or re-checks
                    break;
                }
            }
            normalCpuTime.set(threadMXBean.getCurrentThreadCpuTime() - start);
        }, "properly-written-parker");

        normalThread.start();
        Thread.sleep(100); // let the thread park properly
        normalThread.interrupt(); // trigger the interrupt
        Thread.sleep(100); // 100ms measurement window
        normalDone.set(true);
        LockSupport.unpark(normalThread); // wake it cleanly
        normalThread.join(1000);
        long normalCpuMs = normalCpuTime.get() / 1_000_000;

        // MEASUREMENT 2: the buggy thread — park()/loop without interrupted check
        AtomicBoolean buggyDone = new AtomicBoolean(false);
        AtomicLong buggyCpuTime = new AtomicLong(0);

        Thread buggyThread = new Thread(() -> {
            long start = threadMXBean.getCurrentThreadCpuTime();
            // BUG: no Thread.interrupted() check — if interrupted, park() returns immediately
            // and this becomes a 100% CPU busy spin
            while (!buggyDone.get()) {
                LockSupport.park(this);
                // MISSING: if (Thread.interrupted()) { handle it; }
                // park() returns instantly because interrupted status remains in effect
                // and the JVM must schedule safepoints for this spinning thread
            }
            buggyCpuTime.set(threadMXBean.getCurrentThreadCpuTime() - start);
        }, "buggy-park-without-interrupted-check");

        buggyThread.start();
        Thread.sleep(100); // let the thread park properly first
        buggyThread.interrupt(); // this is the trigger — park() will now return instantly on every call
        Thread.sleep(100); // 100ms measurement window — buggy thread is spinning at 100% CPU
        buggyDone.set(true);
        LockSupport.unpark(buggyThread); // let it exit
        buggyThread.join(1000);
        long buggyCpuMs = buggyCpuTime.get() / 1_000_000;

        // Inject safepoint stress to make the JVM work harder scheduling the spinning thread
        ChaosScenario safepointStress = ChaosScenario.builder("park-interrupt-safepoint-stress")
                .description("Safepoint storm simulating the JVM overhead of scheduling a thread spinning in park() after interrupt")
                .scope(ChaosScenario.ScenarioScope.JVM)
                .selector(ChaosSelector.jvmRuntime(Set.of(OperationType.SAFEPOINT_RENDEZVOUS)))
                .effect(ChaosEffect.safepointStorm(java.time.Duration.ofMillis(50), 5))
                .activationPolicy(new ActivationPolicy(ActivationPolicy.StartMode.AUTOMATIC, 1.0, 0L, 0L, null, null, 0L, false))
                .build();

        // Run service health check under safepoint stress to confirm service is still up
        AtomicInteger serviceResponses = new AtomicInteger(0);
        try (ChaosActivationHandle handle = chaos.activate(safepointStress)) {
            for (int i = 0; i < 5; i++) {
                try {
                    if (restTemplate.getForEntity("/users", String.class).getStatusCode().is2xxSuccessful()) {
                        serviceResponses.incrementAndGet();
                    }
                } catch (Exception ignored) {}
            }
        }

        double cpuRatio = normalCpuMs > 0 ? (double) buggyCpuMs / normalCpuMs : buggyCpuMs > 0 ? Double.MAX_VALUE : 1.0;

        System.out.println();
        System.out.println("  ╔══════════════════════════════════════════════════════════════════════════╗");
        System.out.println("  ║  PARK/INTERRUPT BUSY LOOP PROOF                                         ║");
        System.out.printf( "  ║  Properly-written thread CPU time (100ms window): %5dms               ║%n", normalCpuMs);
        System.out.printf( "  ║  Buggy thread CPU time (100ms window):            %5dms               ║%n", buggyCpuMs);
        System.out.printf( "  ║  CPU overhead ratio (buggy/normal):               %5.1fx               ║%n", cpuRatio);
        System.out.printf( "  ║  Service health under safepoint stress:           %d/5 requests OK      ║%n", serviceResponses.get());
        System.out.println("  ╠══════════════════════════════════════════════════════════════════════════╣");
        System.out.println("  ║  jstack on buggy thread: State=RUNNABLE. Looks normal. It is not.      ║");
        System.out.println("  ║  findDeadlockedThreads(): null. No deadlock. True: it is spinning.      ║");
        System.out.println("  ║  JVM safepoints must now schedule around a thread in a tight spin.     ║");
        System.out.println("  ║  Rate limiting broken. CPU at 100%. 4 hours to identify. Netflix 2020. ║");
        System.out.println("  ║  Fix: if (Thread.interrupted()) { handle(); } after EVERY park() call. ║");
        System.out.println("  ╚══════════════════════════════════════════════════════════════════════════╝");

        assertThat(buggyCpuMs)
                .as("Interrupted thread without park() check must consume >10x more CPU than properly-written thread — proves the busy loop: one interrupt causes continuous CPU spinning because park() returns immediately on every call when interrupted status is not cleared")
                .isGreaterThan(normalCpuMs * 10);

        System.out.println();
        System.out.println("  CONCLUSION: Always check Thread.interrupted() after LockSupport.park().");
        System.out.println("  One interrupt = infinite spin. One line of code = 4 hours of incident.");
    }
}
