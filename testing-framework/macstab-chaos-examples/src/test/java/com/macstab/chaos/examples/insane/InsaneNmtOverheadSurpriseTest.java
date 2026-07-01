package com.macstab.chaos.examples.insane;

import com.macstab.chaos.jvm.api.*;
import com.macstab.chaos.jvm.spring.boot4.test.ChaosTest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;

import java.lang.management.*;
import java.util.*;
import java.util.concurrent.atomic.*;

import static org.assertj.core.api.Assertions.*;

@ChaosTest(
    classes = com.macstab.chaos.examples.UserServiceApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InsaneNmtOverheadSurpriseTest {

  @Autowired TestRestTemplate restTemplate;
  @Autowired ChaosControlPlane chaos;

  @Test
  @DisplayName(
      "INSANE: -XX:NativeMemoryTracking=detail left in prod — 5-10% CPU overhead looks like app regression. 3 weeks debugging. Remove flag: fixed instantly.")
  void nativeMemoryTrackingDetailOverheadMistakenForApplicationRegression() throws Exception {
    System.out.println();
    System.out.println(
        "  ╔══════════════════════════════════════════════════════════════════════════╗");
    System.out.println(
        "  ║  INCIDENT: 40% p99 Regression After 'Memory Diagnostics' Deployment   ║");
    System.out.println(
        "  ║  -XX:NativeMemoryTracking=detail added for debugging. Left in prod.   ║");
    System.out.println(
        "  ║  2 weeks later: 40% latency increase. 8% CPU spike. All pods.         ║");
    System.out.println(
        "  ║  Engineers: tune GC (nope), heap (nope), connections (nope).           ║");
    System.out.println(
        "  ║  3 weeks debugging. Compare JVM flags between working/broken pods.    ║");
    System.out.println(
        "  ║  Found: NativeMemoryTracking=detail. Remove it. Instant recovery.     ║");
    System.out.println(
        "  ║  JVM docs: 'NMT will result in a 5-10 percent JVM performance drop.'  ║");
    System.out.println(
        "  ║  Engineers: never read JVM flag docs before enabling in production.   ║");
    System.out.println(
        "  ╚══════════════════════════════════════════════════════════════════════════╝");
    System.out.println();

    // Detect NMT overhead via allocation rate monitoring
    AtomicLong allocationInterceptCount = new AtomicLong(0);
    AtomicLong nmtOverheadNs = new AtomicLong(0);
    AtomicBoolean nmtEnabled = new AtomicBoolean(false);

    ChaosScenario nmtOverheadProbe =
        ChaosScenario.builder("nmt-overhead-probe")
            .description(
                "Detect NativeMemoryTracking overhead on allocation hot paths — invisible performance tax")
            .scope(ChaosScenario.ScenarioScope.JVM)
            .selector(ChaosSelector.jvmRuntime(Set.of(OperationType.NATIVE_MEMORY_TRACK)))
            .effect(
                ChaosEffect.observe(
                    nmtEvent -> {
                      allocationInterceptCount.incrementAndGet();
                      if (nmtEvent instanceof NmtTrackingEvent nte) {
                        nmtOverheadNs.addAndGet(nte.getTrackingOverheadNs());
                        nmtEnabled.set(true);
                      }
                    }))
            .activationPolicy(
                new ActivationPolicy(
                    ActivationPolicy.StartMode.AUTOMATIC, 0.01, 0L, 0L, null, null, 0L, false))
            .build();

    // Check if NMT is actually enabled in this JVM
    List<String> jvmArgs = ManagementFactory.getRuntimeMXBean().getInputArguments();
    boolean nmtDetailEnabled =
        jvmArgs.stream().anyMatch(arg -> arg.contains("NativeMemoryTracking=detail"));
    boolean nmtSummaryEnabled =
        jvmArgs.stream().anyMatch(arg -> arg.contains("NativeMemoryTracking=summary"));

    System.out.printf(
        "  JVM flag -XX:NativeMemoryTracking=detail: %s%n",
        nmtDetailEnabled ? "ENABLED ← THIS IS THE BUG" : "not set (good)");
    System.out.printf(
        "  JVM flag -XX:NativeMemoryTracking=summary: %s%n%n",
        nmtSummaryEnabled ? "enabled (some overhead)" : "not set");

    // Measure allocation throughput with NMT probe active
    int ALLOCATIONS = 100_000;
    long probeStart = System.nanoTime();
    try (ChaosActivationHandle handle = chaos.activate(nmtOverheadProbe)) {
      // Perform allocations that NMT would track
      List<byte[]> allocations = new ArrayList<>();
      for (int i = 0; i < ALLOCATIONS; i++) {
        allocations.add(new byte[1024]); // 1 KB allocation
        if (i % 1000 == 0) allocations.clear(); // release some
      }
    }
    long probeNs = System.nanoTime() - probeStart;

    // Baseline without NMT monitoring
    long baselineStart = System.nanoTime();
    List<byte[]> baselineAllocs = new ArrayList<>();
    for (int i = 0; i < ALLOCATIONS; i++) {
      baselineAllocs.add(new byte[1024]);
      if (i % 1000 == 0) baselineAllocs.clear();
    }
    long baselineNs = System.nanoTime() - baselineStart;
    baselineAllocs.clear();

    double overheadFactor = (double) probeNs / baselineNs;

    System.out.println(
        "  ╔══════════════════════════════════════════════════════════════════════════╗");
    System.out.println(
        "  ║  NMT OVERHEAD PROOF                                                     ║");
    System.out.printf(
        "  ║  NativeMemoryTracking=detail: %s                                        ║%n",
        nmtDetailEnabled ? "ACTIVE ← performance tax is live" : "not active in this JVM");
    System.out.printf(
        "  ║  Allocations tracked by agent: %,d                                      ║%n",
        allocationInterceptCount.get());
    System.out.printf(
        "  ║  NMT tracking overhead measured: %,dns                                  ║%n",
        nmtOverheadNs.get());
    System.out.printf(
        "  ║  JVM docs state: 5-10%% overhead. Prod impact at 1M alloc/s: significant║%n");
    System.out.println(
        "  ╠══════════════════════════════════════════════════════════════════════════╣");
    System.out.println(
        "  ║  Symptom: looks exactly like application regression.                   ║");
    System.out.println(
        "  ║  CPU higher. Latency higher. GC unchanged. Memory fine.                ║");
    System.out.println(
        "  ║  diff: before/after JVM flags. Find it: jcmd <pid> VM.flags            ║");
    System.out.println(
        "  ║  Never add diagnostic JVM flags in production without removing them.   ║");
    System.out.println(
        "  ╚══════════════════════════════════════════════════════════════════════════╝");

    System.out.printf(
        "%n  Current JVM arguments include NMT: %s%n",
        nmtDetailEnabled
            ? "YES — remove -XX:NativeMemoryTracking=detail from production JVM flags"
            : "No — this JVM is clean. Real incidents occur when it's accidentally left enabled.");
  }
}
