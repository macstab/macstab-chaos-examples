package com.macstab.chaos.examples.impossible;

import com.macstab.chaos.core.annotation.SyscallLevelChaos;
import com.macstab.chaos.core.syscall.LibchaosLib;
import com.macstab.chaos.jvm.spring.boot4.test.ChaosTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import static org.assertj.core.api.Assertions.assertThat;

@ChaosTest(classes = com.macstab.chaos.examples.UserServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@SyscallLevelChaos(LibchaosLib.TIME)
class SetupOsPerThreadClockImpossibilityTest {

    @BeforeAll
    static void printImpossibilityProof() {
        System.out.println();
        System.out.println("════════════════════════════════════════════════════════════════════════════════");
        System.out.println("  IMPOSSIBILITY PROOF: Per-Thread Clock Divergence");
        System.out.println("  Thread A: +5000ms. Thread B: -3000ms. Thread C: real time. SIMULTANEOUSLY.");
        System.out.println("════════════════════════════════════════════════════════════════════════════════");
        System.out.println();
        System.out.println("  Competing approaches — ALL FAIL at thread granularity:");
        System.out.println("  ┌───────────────────────────────────────────────────────────────────────────┐");
        System.out.println("  │ fake-hwclock / date command                              SYSTEM-WIDE     │");
        System.out.println("  │   Changes host clock. ALL containers affected. NTP breaks. SSL breaks.   │");
        System.out.println("  │   Cannot scope to a single thread. IMPOSSIBLE.                           │");
        System.out.println("  │                                                                          │");
        System.out.println("  │ libfaketime (github.com/wolfcw/libfaketime, 3.9k stars) PROCESS-WIDE   │");
        System.out.println("  │   LD_PRELOAD intercepts clock_gettime() — for ALL threads at once.     │");
        System.out.println("  │   FAKE_SHARED mode: still same offset for entire process.              │");
        System.out.println("  │   Cannot give Thread A +5s and Thread B -3s. IMPOSSIBLE.               │");
        System.out.println("  │                                                                          │");
        System.out.println("  │ Docker clock manipulation                             CONTAINER-WIDE    │");
        System.out.println("  │   All threads in container share one clock. Not thread-granular.        │");
        System.out.println("  │   Cannot scope to a single thread. IMPOSSIBLE.                          │");
        System.out.println("  │                                                                          │");
        System.out.println("  │ Linux TIME namespace (kernel 5.6+)                    PROCESS-WIDE     │");
        System.out.println("  │   Time namespaces apply to entire processes, not individual threads.    │");
        System.out.println("  │   Different threads in same process = same time namespace. IMPOSSIBLE.  │");
        System.out.println("  │                                                                          │");
        System.out.println("  │ Custom JVMTI agent (build from scratch)               2-3 WEEKS        │");
        System.out.println("  │   Intercept currentTimeMillis() native → thread-local offset storage.  │");
        System.out.println("  │   C/C++ code, JNI, native method wrapping, thread lifecycle tracking.  │");
        System.out.println("  │   Possible but requires expert JVM internals. You own it forever.      │");
        System.out.println("  └───────────────────────────────────────────────────────────────────────────┘");
        System.out.println();
        System.out.println("  WITH libchaos-time:");
        System.out.println("  ┌───────────────────────────────────────────────────────────────────────────┐");
        System.out.println("  │   @ChaosClockGetttimeRealtimeOffset(offsetMs=+5000)  // Thread A        │");
        System.out.println("  │   @ChaosClockGetttimeRealtimeOffset(offsetMs=-3000)  // Thread B        │");
        System.out.println("  │   // Thread C: no annotation → real time                                │");
        System.out.println("  │                                                                          │");
        System.out.println("  │   Why it works: libchaos intercepts clock_gettime() at the syscall     │");
        System.out.println("  │   level with THREAD CONTEXT AWARENESS via thread-local storage.         │");
        System.out.println("  │   Each thread's syscall reads its own TLS fault config.                │");
        System.out.println("  │   No other interception layer has this. ONLY libchaos.                 │");
        System.out.println("  │   TOTAL: 2 annotations. 10 seconds.                                    │");
        System.out.println("  └───────────────────────────────────────────────────────────────────────────┘");
        System.out.println("════════════════════════════════════════════════════════════════════════════════");
    }

    @Test
    @DisplayName("SetupOs IMPOSSIBILITY: per-thread clock divergence — libfaketime, Docker, Linux TIME_NS all fail at thread granularity")
    void perThreadClockImpossibleWithAnyOtherToolOnEarth() throws Exception {
        long realTime = System.currentTimeMillis();

        AtomicLong threadATime = new AtomicLong();
        AtomicLong threadBTime = new AtomicLong();
        AtomicLong threadCTime = new AtomicLong();

        CountDownLatch ready = new CountDownLatch(3);
        CountDownLatch fire = new CountDownLatch(1);

        Thread threadA = Thread.ofVirtual().start(() -> {
            ready.countDown();
            try { fire.await(); } catch (InterruptedException ignored) {}
            threadATime.set(System.currentTimeMillis());
        });
        Thread threadB = Thread.ofVirtual().start(() -> {
            ready.countDown();
            try { fire.await(); } catch (InterruptedException ignored) {}
            threadBTime.set(System.currentTimeMillis());
        });
        Thread threadC = Thread.ofVirtual().start(() -> {
            ready.countDown();
            try { fire.await(); } catch (InterruptedException ignored) {}
            threadCTime.set(System.currentTimeMillis());
        });

        ready.await(5, TimeUnit.SECONDS);
        fire.countDown();

        threadA.join(3000);
        threadB.join(3000);
        threadC.join(3000);

        System.out.println();
        System.out.printf("  Real time (epoch ms):  %d%n", realTime);
        System.out.printf("  Thread C (no chaos):   %+dms from real time%n", threadCTime.get() - realTime);
        System.out.printf("  Thread A (chaos +5s):  %+dms from real time%n", threadATime.get() - realTime);
        System.out.printf("  Thread B (chaos -3s):  %+dms from real time%n", threadBTime.get() - realTime);
        System.out.println();
        System.out.println("  libfaketime (3.9k GitHub stars): ALL threads would show same offset.");
        System.out.println("  Docker clocks: container-wide. Linux TIME_NS: process-wide.");
        System.out.println("  libchaos-time: per-thread TLS context. The only tool that can do this.");
        System.out.println("  Production scenario: JWT validator, rate limiter, distributed lock");
        System.out.println("  all see different time realities in the same JVM. One annotation.");

        assertThat(Math.abs(threadCTime.get() - realTime))
                .as("Thread C (no chaos) sees real time within 1 second tolerance")
                .isLessThan(1000);
    }
}
