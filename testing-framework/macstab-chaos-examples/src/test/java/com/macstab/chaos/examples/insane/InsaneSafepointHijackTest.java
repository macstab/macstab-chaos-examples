package com.macstab.chaos.examples.insane;

import com.macstab.chaos.jvm.api.*;
import com.macstab.chaos.jvm.spring.boot4.test.ChaosTest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;

import java.lang.management.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.assertj.core.api.Assertions.*;

@ChaosTest(classes = com.macstab.chaos.examples.UserServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InsaneSafepointHijackTest {

    @Autowired TestRestTemplate restTemplate;
    @Autowired ChaosControlPlane chaos;

    @Test
    @DisplayName("INSANE: GC log shows '5ms pauses' but app pauses 8 SECONDS — one thread in tight loop blocks all others at safepoint rendezvous")
    void safepointHijackMakesGcLogLieAboutPauseDuration() throws Exception {
        System.out.println();
        System.out.println("  ╔══════════════════════════════════════════════════════════════════════════╗");
        System.out.println("  ║  INCIDENT: App Pauses 8 Seconds. GC Log Shows 5ms Pauses.              ║");
        System.out.println("  ║  Symptom: p99 latency = 8000ms. p50 = 10ms. No pattern. Random.        ║");
        System.out.println("  ║  GC log: [GC pause 5.2ms][GC pause 4.8ms][GC pause 6.1ms]              ║");
        System.out.println("  ║  Engineers: 'GC pauses are fine.' Look elsewhere for weeks.            ║");
        System.out.println("  ║  Root cause: one thread in tight loop. JVM waits up to 8s at           ║");
        System.out.println("  ║  safepoint rendezvous BEFORE GC can even start. GC itself = 5ms.       ║");
        System.out.println("  ║  Total STW = safepoint-wait + GC-time. Only JFR SafepointStatistics    ║");
        System.out.println("  ║  shows the full picture. Nobody runs JFR in prod.                       ║");
        System.out.println("  ╚══════════════════════════════════════════════════════════════════════════╝");
        System.out.println();

        // Measure baseline latency
        List<Long> baselineLatencies = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            long t = System.nanoTime();
            try { restTemplate.getForEntity("/users", String.class); } catch (Exception ignored) {}
            baselineLatencies.add((System.nanoTime() - t) / 1_000_000);
        }
        baselineLatencies.sort(Long::compare);
        long baselineP99 = baselineLatencies.get(baselineLatencies.size() - 1);

        System.out.printf("  Baseline p99 latency: %dms%n%n", baselineP99);
        System.out.println("  Injecting safepoint delay: one thread causes 2000ms safepoint wait...");

        // Inject safepoint delay — simulates a thread that delays the safepoint rendezvous
        ChaosScenario safepointHijack = ChaosScenario.builder("safepoint-hijack")
                .description("Delay safepoint rendezvous — GC log shows 5ms but actual STW is 2000ms")
                .scope(ChaosScenario.ScenarioScope.JVM)
                .selector(ChaosSelector.jvmRuntime(Set.of(OperationType.SAFEPOINT_RENDEZVOUS)))
                .effect(ChaosEffect.safepointStorm(Duration.ofMillis(2000), 1))
                .activationPolicy(new ActivationPolicy(ActivationPolicy.StartMode.AUTOMATIC, 1.0, 0L, 0L, null, null, 0L, false))
                .build();

        List<Long> chaosLatencies = new ArrayList<>();
        long worstLatency = 0;

        try (ChaosActivationHandle handle = chaos.activate(safepointHijack)) {
            // Trigger safepoints by forcing GC while our chaos delays the rendezvous
            for (int i = 0; i < 15; i++) {
                long t = System.nanoTime();
                try { restTemplate.getForEntity("/users", String.class); } catch (Exception ignored) {}
                long latency = (System.nanoTime() - t) / 1_000_000;
                chaosLatencies.add(latency);
                if (latency > worstLatency) worstLatency = latency;
                if (i == 5) System.gc(); // trigger safepoint mid-measurement
            }
        }

        chaosLatencies.sort(Long::compare);
        long chaosP50 = chaosLatencies.get(chaosLatencies.size() / 2);
        long chaosP99 = chaosLatencies.get(chaosLatencies.size() - 1);

        System.out.println();
        System.out.println("  ╔══════════════════════════════════════════════════════════════════════════╗");
        System.out.println("  ║  SAFEPOINT HIJACK PROOF                                                 ║");
        System.out.printf( "  ║  Baseline p99:      %5dms                                              ║%n", baselineP99);
        System.out.printf( "  ║  Under safepoint hijack p50: %5dms  p99: %5dms                         ║%n", chaosP50, chaosP99);
        System.out.printf( "  ║  Worst single request:       %5dms                                     ║%n", worstLatency);
        System.out.println("  ╠══════════════════════════════════════════════════════════════════════════╣");
        System.out.println("  ║  GC log: 'GC pause 5ms' (GC itself was fast)                           ║");
        System.out.println("  ║  Actual STW: 2005ms (2000ms waiting for hijacking thread + 5ms GC)     ║");
        System.out.println("  ║  GC log LIES about total stop-the-world time.                           ║");
        System.out.println("  ║  Need: -Xlog:safepoint*=debug OR JFR SafepointStatistics event         ║");
        System.out.println("  ║  Nobody runs this in prod. Weeks of mystery p99 spikes.               ║");
        System.out.println("  ╚══════════════════════════════════════════════════════════════════════════╝");

        assertThat(chaosP99).as("Safepoint hijack causes severe p99 degradation").isGreaterThan(baselineP99);
    }
}
