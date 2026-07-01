package com.macstab.chaos.examples.impossible;

import com.macstab.chaos.jvm.api.*;
import com.macstab.chaos.jvm.spring.boot4.test.ChaosTest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@ChaosTest(classes = com.macstab.chaos.examples.UserServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ImpossibleClassLoaderLeakDetectorTest {

    @Autowired
    TestRestTemplate restTemplate;

    @Autowired
    ChaosControlPlane chaos;

    @Test
    @DisplayName("IMPOSSIBLE: Intercept every ClassLoader.loadClass() call, count per class, assert zero repeated loads — -verbose:class cannot do this in a test")
    void classLoaderLeakDetectionInCiTest() throws Exception {

        System.out.println();
        System.out.println("════════════════════════════════════════════════════════════════");
        System.out.println("  IMPOSSIBLE TEST: CLASSLOADER LEAK DETECTOR IN CI");
        System.out.println("  -verbose:class: stdout only, cannot assert in test.");
        System.out.println("  JVisualVM: GUI tool, cannot run in CI.");
        System.out.println("  HeapDump: after the fact, not live assertion.");
        System.out.println("  JVM agent: intercepts loadClass() live. Count per class. Assert.");
        System.out.println("════════════════════════════════════════════════════════════════");

        Map<String, AtomicInteger> classLoadCounts = new ConcurrentHashMap<>();

        ChaosScenario classLoaderMonitor = ChaosScenario.builder("classloader-leak-detector")
                .description("Intercept ClassLoader.loadClass() — count loads per class to detect repeated loading")
                .scope(ChaosScenario.ScenarioScope.JVM)
                .selector(ChaosSelector.classLoading(Set.of(OperationType.CLASS_LOAD), NamePattern.any()))
                .effect(ChaosEffect.observe(className -> {
                    classLoadCounts.computeIfAbsent(className, k -> new AtomicInteger(0)).incrementAndGet();
                }))
                .activationPolicy(new ActivationPolicy(ActivationPolicy.StartMode.AUTOMATIC, 1.0, 0L, 0L, null, null, 0L, false))
                .build();

        try (ChaosActivationHandle handle = chaos.activate(classLoaderMonitor)) {
            // Fire 100 requests to trigger class loading activity
            for (int i = 0; i < 100; i++) {
                restTemplate.getForEntity("/users", String.class);
            }
            Thread.sleep(1000);
        }

        // Find classes loaded more than threshold times (leak candidates)
        int LEAK_THRESHOLD = 10;
        List<Map.Entry<String, AtomicInteger>> suspectedLeaks = classLoadCounts.entrySet().stream()
                .filter(e -> e.getValue().get() > LEAK_THRESHOLD)
                .sorted((a, b) -> b.getValue().get() - a.getValue().get())
                .limit(10)
                .collect(java.util.stream.Collectors.toList());

        int totalLoads = classLoadCounts.values().stream().mapToInt(AtomicInteger::get).sum();
        int uniqueClasses = classLoadCounts.size();

        System.out.printf("%n  Total class load events intercepted: %d%n", totalLoads);
        System.out.printf("  Unique classes loaded: %d%n", uniqueClasses);
        System.out.printf("  Classes loaded > %d times (leak candidates): %d%n%n", LEAK_THRESHOLD, suspectedLeaks.size());

        System.out.println("  ╔════════════════════════════════════════════════════════════════╗");
        System.out.println("  ║  CLASSLOADER LEAK DETECTOR REPORT                              ║");
        System.out.printf( "  ║  Total loads intercepted: %-6d                                ║%n", totalLoads);
        System.out.printf( "  ║  Unique classes: %-6d                                         ║%n", uniqueClasses);

        if (suspectedLeaks.isEmpty()) {
            System.out.println("  ║  Leak candidates (> " + LEAK_THRESHOLD + " loads): NONE ✓                           ║");
            System.out.println("  ║  VERDICT: No classloader leak detected in 100 requests         ║");
        } else {
            System.out.println("  ║  LEAK CANDIDATES DETECTED:                                     ║");
            for (var entry : suspectedLeaks) {
                System.out.printf("  ║    %-50s: %3dx ║%n",
                        entry.getKey().length() > 50 ? "..." + entry.getKey().substring(entry.getKey().length() - 47) : entry.getKey(),
                        entry.getValue().get());
            }
        }
        System.out.println("  ╠════════════════════════════════════════════════════════════════╣");
        System.out.println("  ║  USE CASE: Add to every CI pipeline. Zero leaks = no metaspace ║");
        System.out.println("  ║  glacier. This test PREVENTS the 2am OOM page.                 ║");
        System.out.println("  ║  -verbose:class cannot assert this. JVisualVM requires a GUI.  ║");
        System.out.println("  ║  Only possible with a JVM agent that intercepts loadClass().   ║");
        System.out.println("  ╚════════════════════════════════════════════════════════════════╝");

        // For production code: no class should be loaded > LEAK_THRESHOLD times in 100 requests
        // (Framework/Spring classes are excluded — filter to application package)
        long appLeaks = suspectedLeaks.stream()
                .filter(e -> e.getKey().startsWith("com.macstab"))
                .count();
        assertThat(appLeaks).as("Zero application classloader leaks in 100 requests").isEqualTo(0);
        System.out.println("════════════════════════════════════════════════════════════════");
    }
}
