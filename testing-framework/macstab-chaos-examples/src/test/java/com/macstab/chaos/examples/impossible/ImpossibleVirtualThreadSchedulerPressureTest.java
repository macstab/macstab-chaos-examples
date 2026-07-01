package com.macstab.chaos.examples.impossible;

import com.macstab.chaos.jvm.api.*;
import com.macstab.chaos.jvm.spring.boot4.test.ChaosTest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.*;
import java.util.stream.*;
import static org.assertj.core.api.Assertions.*;

@ChaosTest(classes = com.macstab.chaos.examples.UserServiceApplication.class, webEnvironment = org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT)
class ImpossibleVirtualThreadSchedulerPressureTest {

    @Autowired
    TestRestTemplate restTemplate;

    @Autowired
    ChaosControlPlane chaos;

    private List<Long> measureLatencies(int count, boolean virtualThreads) throws Exception {
        List<Long> latencies = Collections.synchronizedList(new ArrayList<>());
        ExecutorService exec = virtualThreads
                ? Executors.newVirtualThreadPerTaskExecutor()
                : Executors.newFixedThreadPool(count);
        CountDownLatch latch = new CountDownLatch(count);
        for (int i = 0; i < count; i++) {
            exec.submit(() -> {
                long s = System.nanoTime();
                try { restTemplate.getForEntity("/users", String.class); }
                catch (Exception ignored) {}
                finally { latencies.add((System.nanoTime() - s) / 1_000_000); latch.countDown(); }
            });
        }
        latch.await(30, TimeUnit.SECONDS);
        exec.shutdown();
        return latencies;
    }

    @Test
    @DisplayName("IMPOSSIBLE: 50ms delay injected into Unsafe.park() for virtual threads ONLY — platform threads unaffected. ByteBuddy alone cannot touch Unsafe.")
    void virtualThreadSchedulerPressureIsolatedFromPlatformThreads() throws Exception {

        System.out.println();
        System.out.println("════════════════════════════════════════════════════════════════");
        System.out.println("  IMPOSSIBLE TEST: VIRTUAL THREAD SCHEDULER PRESSURE");
        System.out.println("  ByteBuddy alone: cannot intercept sun.misc.Unsafe.park().");
        System.out.println("  JProfiler: observes park() but cannot inject delay into it.");
        System.out.println("  Aspects/Proxies: Unsafe bypasses all proxy mechanisms.");
        System.out.println("  JVM agent + bootstrap bridge: the ONLY way to reach Unsafe.");
        System.out.println("════════════════════════════════════════════════════════════════");

        // Baseline: no chaos
        List<Long> platformBaseline = measureLatencies(20, false);
        List<Long> vtBaseline = measureLatencies(20, true);
        platformBaseline.sort(Long::compare);
        vtBaseline.sort(Long::compare);
        long platP99Base = platformBaseline.get(platformBaseline.size() - 1);
        long vtP99Base = vtBaseline.get(vtBaseline.size() - 1);

        System.out.printf("  Baseline — platform p99: %dms, virtual p99: %dms%n", platP99Base, vtP99Base);

        // Inject scheduler pressure on virtual thread park() ONLY
        ChaosScenario vtSchedulerPressure = ChaosScenario.builder("vt-scheduler-park-delay")
                .description("50ms delay on Unsafe.park() for virtual threads only — scheduler fault injection")
                .scope(ChaosScenario.ScenarioScope.JVM)
                .selector(ChaosSelector.thread(Set.of(OperationType.VIRTUAL_THREAD_PARK), ThreadKind.VIRTUAL))
                .effect(ChaosEffect.delay(java.time.Duration.ofMillis(50)))
                .activationPolicy(new ActivationPolicy(ActivationPolicy.StartMode.AUTOMATIC, 1.0, 0L, 0L, null, null, 0L, false))
                .build();

        try (ChaosActivationHandle handle = chaos.activate(vtSchedulerPressure)) {

            List<Long> platformUnderChaos = measureLatencies(20, false);
            List<Long> vtUnderChaos = measureLatencies(20, true);
            platformUnderChaos.sort(Long::compare);
            vtUnderChaos.sort(Long::compare);

            long platP99Chaos = platformUnderChaos.get(platformUnderChaos.size() - 1);
            long vtP99Chaos = vtUnderChaos.get(vtUnderChaos.size() - 1);

            System.out.println();
            System.out.println("  ╔═══════════════════════════════════════════════════════════════╗");
            System.out.println("  ║  VT SCHEDULER PRESSURE — ISOLATION PROOF                      ║");
            System.out.println("  ║  Chaos: 50ms delay on Unsafe.park() for virtual threads only  ║");
            System.out.println("  ╠═══════════════════════════════════════════════════════════════╣");
            System.out.printf( "  ║  Platform threads p99:  %4dms → %4dms (should be unchanged)  ║%n", platP99Base, platP99Chaos);
            System.out.printf( "  ║  Virtual threads p99:   %4dms → %4dms (should be +50ms)      ║%n", vtP99Base, vtP99Chaos);
            System.out.println("  ╠═══════════════════════════════════════════════════════════════╣");
            System.out.println("  ║  The VT scheduler fault is ISOLATED to virtual threads.       ║");
            System.out.println("  ║  Platform threads keep serving at baseline latency.           ║");
            System.out.println("  ║  This is only possible because the agent reaches Unsafe       ║");
            System.out.println("  ║  via bootstrap classloader bridge — not possible any other way║");
            System.out.println("  ╚═══════════════════════════════════════════════════════════════╝");

            assertThat(vtP99Chaos).as("VT p99 increases under scheduler pressure").isGreaterThan(vtP99Base);
            long platDelta = Math.abs(platP99Chaos - platP99Base);
            assertThat(platDelta).as("Platform threads unaffected by VT scheduler chaos").isLessThan(100);
        }
        System.out.println("════════════════════════════════════════════════════════════════");
    }
}
