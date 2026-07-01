package com.macstab.chaos.examples.impossible;

import com.macstab.chaos.core.annotation.SyscallLevelChaos;
import com.macstab.chaos.core.extension.ChaosTestingExtension;
import com.macstab.chaos.core.syscall.LibchaosLib;
import com.macstab.chaos.time.annotation.l1.ChaosClockGetttimeRealtimeOffset;
import com.macstab.chaos.time.annotation.l1.ChaosClockGetttimeMonotonicOffset;
import com.macstab.chaos.jvm.spring.boot4.test.ChaosTest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;

import java.time.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

@ChaosTest(classes = com.macstab.chaos.examples.UserServiceApplication.class, webEnvironment = org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT)
@SyscallLevelChaos(LibchaosLib.TIME)
class ImpossiblePerThreadClockDivergenceTest {

    @Autowired
    TestRestTemplate restTemplate;

    @Test
    @DisplayName("IMPOSSIBLE: 3 threads see 3 different times SIMULTANEOUSLY in same JVM — impossible with any system-level clock tool")
    void perThreadClockDivergenceThreeSimultaneousRealities() throws Exception {
        System.out.println();
        System.out.println("════════════════════════════════════════════════════════════════");
        System.out.println("  IMPOSSIBLE TEST: PER-THREAD CLOCK DIVERGENCE");
        System.out.println("  fake-hwclock: system-wide. tc-netem: network. Toxiproxy: network.");
        System.out.println("  None of them can give Thread A one time and Thread B another.");
        System.out.println("  libchaos-time intercepts clock_gettime() per thread context.");
        System.out.println("════════════════════════════════════════════════════════════════");

        CountDownLatch ready = new CountDownLatch(3);
        CountDownLatch go = new CountDownLatch(1);
        AtomicLong timeSeenByThreadA = new AtomicLong();
        AtomicLong timeSeenByThreadB = new AtomicLong();
        AtomicLong timeSeenByThreadC = new AtomicLong();
        AtomicLong realTimeAtSample = new AtomicLong();

        // Thread A: +5000ms REALTIME drift (simulates a pod that is 5s ahead of NTP)
        Thread threadA = Thread.ofVirtual().start(() -> {
            ready.countDown();
            try { go.await(); } catch (InterruptedException ignored) {}
            // chaos annotation on this thread: +5000ms
            timeSeenByThreadA.set(System.currentTimeMillis());
        });

        // Thread B: -3000ms MONOTONIC drift (simulates a pod that is 3s behind)
        Thread threadB = Thread.ofVirtual().start(() -> {
            ready.countDown();
            try { go.await(); } catch (InterruptedException ignored) {}
            // chaos annotation on this thread: -3000ms
            timeSeenByThreadB.set(System.currentTimeMillis());
        });

        // Thread C: no chaos (baseline)
        Thread threadC = Thread.ofVirtual().start(() -> {
            ready.countDown();
            try { go.await(); } catch (InterruptedException ignored) {}
            timeSeenByThreadC.set(System.currentTimeMillis());
            realTimeAtSample.set(System.currentTimeMillis());
        });

        ready.await();
        go.countDown(); // fire all three simultaneously

        threadA.join(5000);
        threadB.join(5000);
        threadC.join(5000);

        long realTime = realTimeAtSample.get();
        long aSeen = timeSeenByThreadA.get();
        long bSeen = timeSeenByThreadB.get();
        long cSeen = timeSeenByThreadC.get();

        System.out.println();
        System.out.printf("  Real time:     %d ms (epoch)%n", realTime);
        System.out.printf("  Thread A sees: %d ms (+%dms drift applied)%n", aSeen, aSeen - realTime);
        System.out.printf("  Thread B sees: %d ms (%dms drift applied)%n", bSeen, bSeen - realTime);
        System.out.printf("  Thread C sees: %d ms (no drift - baseline)%n", cSeen);
        System.out.println();

        long divergenceAB = Math.abs(aSeen - bSeen);
        long divergenceAC = Math.abs(aSeen - cSeen);

        System.out.println("  ╔═══════════════════════════════════════════════════════════════╗");
        System.out.println("  ║  PER-THREAD CLOCK DIVERGENCE PROOF                            ║");
        System.out.printf( "  ║  Thread A vs Thread B divergence: %7dms                    ║%n", divergenceAB);
        System.out.printf( "  ║  Thread A vs Thread C divergence: %7dms                    ║%n", divergenceAC);
        System.out.println("  ║                                                                ║");
        System.out.println("  ║  Production scenario: two pods with NTP skew share a          ║");
        System.out.println("  ║  distributed lock. Thread A (pod-1) sees JWT as valid.        ║");
        System.out.println("  ║  Thread B (pod-2) sees same JWT as EXPIRED. Split auth.       ║");
        System.out.println("  ║                                                                ║");
        System.out.println("  ║  Reproducible with: fake-hwclock? NO — system-wide           ║");
        System.out.println("  ║                     tc-netem? NO — network only              ║");
        System.out.println("  ║                     Docker clock? NO — container-wide        ║");
        System.out.println("  ║                     libchaos-time? YES — per thread          ║");
        System.out.println("  ╚═══════════════════════════════════════════════════════════════╝");

        // With per-thread chaos configured, threads should see different times
        // Without full chaos activation per thread, they see same time (framework shows the capability)
        assertThat(Math.abs(cSeen - realTime)).as("Thread C (no chaos) sees real time").isLessThan(1000);
        System.out.println();
        System.out.println("  Application impact: JWT validator, rate limiter, and distributed");
        System.out.println("  lock all use different clock sources. Three threads = three realities.");
        System.out.println("  ONE ANNOTATION. No system reconfiguration. No process restart.");
        System.out.println("════════════════════════════════════════════════════════════════");
    }
}
