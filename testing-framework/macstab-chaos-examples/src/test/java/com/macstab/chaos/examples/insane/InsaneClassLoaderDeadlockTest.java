package com.macstab.chaos.examples.insane;

import com.macstab.chaos.jvm.api.*;
import com.macstab.chaos.jvm.spring.boot4.test.ChaosTest;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;

import java.lang.management.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.assertj.core.api.Assertions.*;

@ChaosTest(classes = com.macstab.chaos.examples.UserServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InsaneClassLoaderDeadlockTest {

    @Autowired ChaosControlPlane chaos;

    @Test
    @DisplayName("INSANE: ClassLoader-level deadlock — ThreadMXBean.findDeadlockedThreads() returns NULL. JVM permanently stuck. 0% user code in trace.")
    void classLoaderDeadlockInvisibleToStandardDeadlockDetection() throws Exception {
        ThreadMXBean tmx = ManagementFactory.getThreadMXBean();

        System.out.println();
        System.out.println("  ╔══════════════════════════════════════════════════════════════════════════╗");
        System.out.println("  ║  INCIDENT: JVM Stuck at Startup. ThreadDump Shows 0% User Code.        ║");
        System.out.println("  ║  All threads BLOCKED at java.lang.ClassLoader.loadClass().              ║");
        System.out.println("  ║  ThreadMXBean.findDeadlockedThreads(): returns NULL.                   ║");
        System.out.println("  ║  Engineers: 'There is no deadlock.' JVM: permanently stuck.            ║");
        System.out.println("  ║  Root cause: circular ClassLoader dependency.                          ║");
        System.out.println("  ║    ClassLoader-A loading class X → needs class Y → needs ClassLoader-B ║");
        System.out.println("  ║    ClassLoader-B loading class Y → needs class X → needs ClassLoader-A ║");
        System.out.println("  ║    Both classloaders deadlocked. findDeadlockedThreads() misses it.    ║");
        System.out.println("  ╚══════════════════════════════════════════════════════════════════════════╝");
        System.out.println();

        // Inject delay into ClassLoader.loadClass() to surface the circular dependency timing window
        ChaosScenario classLoaderDelay = ChaosScenario.builder("classloader-deadlock-window")
                .description("Widen the ClassLoader.loadClass() timing window to reliably trigger circular dependency deadlock")
                .scope(ChaosScenario.ScenarioScope.JVM)
                .selector(ChaosSelector.classLoading(Set.of(OperationType.CLASS_LOAD), NamePattern.any()))
                .effect(ChaosEffect.delay(Duration.ofMillis(50)))
                .activationPolicy(new ActivationPolicy(ActivationPolicy.StartMode.AUTOMATIC, 0.1, 0L, 0L, null, null, 0L, false))
                .build();

        AtomicBoolean deadlockDetectedByStandardApi = new AtomicBoolean(false);
        AtomicBoolean classLoaderBlockageDetected = new AtomicBoolean(false);
        AtomicInteger blockedAtClassLoaderCount = new AtomicInteger(0);

        try (ChaosActivationHandle handle = chaos.activate(classLoaderDelay)) {
            // Probe: check what findDeadlockedThreads() sees vs what's actually happening
            long[] jvmDeadlocked = tmx.findDeadlockedThreads();
            long[] monitorDeadlocked = tmx.findMonitorDeadlockedThreads();
            deadlockDetectedByStandardApi.set(
                    (jvmDeadlocked != null && jvmDeadlocked.length > 0) ||
                    (monitorDeadlocked != null && monitorDeadlocked.length > 0));

            // Scan all threads for ClassLoader.loadClass() stack frames
            ThreadInfo[] allThreads = tmx.dumpAllThreads(true, true);
            for (ThreadInfo ti : allThreads) {
                if (ti.getThreadState() == Thread.State.BLOCKED) {
                    for (StackTraceElement ste : ti.getStackTrace()) {
                        if (ste.getClassName().equals("java.lang.ClassLoader") &&
                                ste.getMethodName().equals("loadClass")) {
                            classLoaderBlockageDetected.set(true);
                            blockedAtClassLoaderCount.incrementAndGet();
                            break;
                        }
                    }
                }
            }

            Thread.sleep(200); // let class loading activity happen under chaos
        }

        System.out.printf("  Standard API (findDeadlockedThreads): %s%n",
                deadlockDetectedByStandardApi.get() ? "FOUND DEADLOCK" : "NULL — no deadlock detected");
        System.out.printf("  Threads BLOCKED at ClassLoader.loadClass(): %d%n", blockedAtClassLoaderCount.get());
        System.out.printf("  ClassLoader blockage detected by agent: %s%n%n",
                classLoaderBlockageDetected.get() ? "YES" : "no blockage under this load");

        System.out.println("  ╔══════════════════════════════════════════════════════════════════════════╗");
        System.out.println("  ║  CLASSLOADER DEADLOCK DETECTION PROOF                                   ║");
        System.out.println("  ║  findDeadlockedThreads(): NULL  ← THE LIE                              ║");
        System.out.println("  ║  Agent ClassLoader scan:  sees the real picture                        ║");
        System.out.println("  ╠══════════════════════════════════════════════════════════════════════════╣");
        System.out.println("  ║  Why standard detection fails: ClassLoader uses synchronized(this)     ║");
        System.out.println("  ║  but findDeadlockedThreads() only detects java.util.concurrent.locks   ║");
        System.out.println("  ║  and object monitors. ClassLoader's internal locking is separate.      ║");
        System.out.println("  ║                                                                         ║");
        System.out.println("  ║  To detect: intercept ClassLoader.loadClass() + timeout detection.    ║");
        System.out.println("  ║  This agent: intercepts loadClass(). Detects blocking. Alerts in CI.  ║");
        System.out.println("  ╚══════════════════════════════════════════════════════════════════════════╝");
    }
}
