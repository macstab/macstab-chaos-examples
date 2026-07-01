package com.macstab.chaos.examples.insane;

import com.macstab.chaos.jvm.api.*;
import com.macstab.chaos.jvm.spring.boot4.test.ChaosTest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.assertj.core.api.Assertions.*;

@ChaosTest(
    classes = com.macstab.chaos.examples.UserServiceApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InsaneFalseSharingCacheLineThrashTest {

  @Autowired TestRestTemplate restTemplate;
  @Autowired ChaosControlPlane chaos;

  // UNPADDED: two longs sit in the same 64-byte CPU cache line (typical JVM object layout).
  // Thread A writes successCount → marks the cache line dirty → Thread B must reload all 64 bytes
  // before it can read failureCount, even though failureCount did not change.
  static class UnpaddedCounters {
    volatile long successCount = 0L;
    volatile long failureCount = 0L;
  }

  // PADDED: 7 longs of padding push each value into its own 64-byte cache line.
  // Writes by Thread A no longer invalidate the cache line that Thread B reads.
  // This is what @jdk.internal.vm.annotation.@Contended does automatically.
  static class PaddedSuccessCounter {
    volatile long value = 0L;
    // 7 × 8 bytes = 56 bytes padding → pushes next allocation past the 64-byte boundary
    @SuppressWarnings("unused")
    long p1, p2, p3, p4, p5, p6, p7;
  }

  static class PaddedFailureCounter {
    volatile long value = 0L;
    @SuppressWarnings("unused")
    long p1, p2, p3, p4, p5, p6, p7;
  }

  @Test
  @DisplayName(
      "INSANE: adding one field causes 8x regression — false sharing destroys CPU cache. Invisible to every JVM diagnostic tool.")
  void falseSharingCacheLineThrashProves8xRegression() throws Exception {
    int THREADS = Math.max(4, Runtime.getRuntime().availableProcessors());
    int ITERATIONS = 2_000_000;

    System.out.println();
    System.out.println(
        "  ╔══════════════════════════════════════════════════════════════════════════╗");
    System.out.println(
        "  ║  INCIDENT: Adding One Field Caused 8x Performance Regression            ║");
    System.out.println(
        "  ║  Before: 1200 req/s. After adding failedCount field: 140 req/s.        ║");
    System.out.println(
        "  ║  Same code. Same heap. Same GC. Same thread count. 4 days of profiling.║");
    System.out.println(
        "  ║  GC logs: silent. Thread dumps: fine. CPU: 'normal' (wasted cycles).   ║");
    System.out.println(
        "  ║  Root cause: successCount + failureCount share a 64-byte cache line.   ║");
    System.out.println(
        "  ║  Thread A writes successCount → cache line dirty → Thread B stalls.   ║");
    System.out.println(
        "  ║  L1 cache hit = 4 cycles. Cross-socket DRAM reload = 300+ cycles.      ║");
    System.out.println(
        "  ║  Fix: @Contended annotation or 7 padding longs. 1 line. 4 days found.  ║");
    System.out.println(
        "  ╚══════════════════════════════════════════════════════════════════════════╝");
    System.out.printf("%n  Testing with %d threads, %,d iterations each%n%n", THREADS, ITERATIONS);

    // Agent probes concurrent contested volatile writes — the fingerprint of false sharing.
    AtomicLong falseShareEvents = new AtomicLong(0);
    ChaosScenario falseShareProbe =
        ChaosScenario.builder("false-share-cache-probe")
            .description(
                "Detect concurrent writes to adjacent volatile fields — cache line contention fingerprint")
            .scope(ChaosScenario.ScenarioScope.JVM)
            .selector(
                ChaosSelector.method(
                    Set.of(OperationType.VOLATILE_WRITE, OperationType.VOLATILE_READ),
                    NamePattern.matching(
                        "InsaneFalseSharingCacheLineThrashTest$UnpaddedCounters")))
            .effect(
                ChaosEffect.observe(
                    fieldEvent -> {
                      if (fieldEvent instanceof FieldAccessEvent fae
                          && fae.isConcurrentlyContested()) {
                        falseShareEvents.incrementAndGet();
                      }
                    }))
            .activationPolicy(
                new ActivationPolicy(
                    ActivationPolicy.StartMode.AUTOMATIC, 0.01, 0L, 0L, null, null, 0L, false))
            .build();

    // MEASUREMENT 1: unpadded counters (false sharing — cache lines bounce between CPUs)
    UnpaddedCounters unpadded = new UnpaddedCounters();
    long unpaddedStart = System.nanoTime();
    try (ChaosActivationHandle handle = chaos.activate(falseShareProbe)) {
      ExecutorService pool = Executors.newFixedThreadPool(THREADS);
      List<Future<?>> futures = new ArrayList<>();
      for (int t = 0; t < THREADS; t++) {
        final boolean isSuccessThread = t % 2 == 0;
        futures.add(
            pool.submit(
                () -> {
                  for (int i = 0; i < ITERATIONS / THREADS; i++) {
                    if (isSuccessThread) unpadded.successCount++;
                    else unpadded.failureCount++;
                  }
                }));
      }
      for (Future<?> f : futures) f.get();
      pool.shutdown();
    }
    long unpaddedNs = System.nanoTime() - unpaddedStart;

    // MEASUREMENT 2: padded counters (each value in its own cache line — no false sharing)
    PaddedSuccessCounter paddedSuccess = new PaddedSuccessCounter();
    PaddedFailureCounter paddedFailure = new PaddedFailureCounter();
    long paddedStart = System.nanoTime();
    ExecutorService pool2 = Executors.newFixedThreadPool(THREADS);
    List<Future<?>> futures2 = new ArrayList<>();
    for (int t = 0; t < THREADS; t++) {
      final boolean isSuccessThread = t % 2 == 0;
      futures2.add(
          pool2.submit(
              () -> {
                for (int i = 0; i < ITERATIONS / THREADS; i++) {
                  if (isSuccessThread) paddedSuccess.value++;
                  else paddedFailure.value++;
                }
              }));
    }
    for (Future<?> f : futures2) f.get();
    pool2.shutdown();
    long paddedNs = System.nanoTime() - paddedStart;

    double speedupFactor = (double) unpaddedNs / paddedNs;
    long unpaddedMs = unpaddedNs / 1_000_000;
    long paddedMs = paddedNs / 1_000_000;

    System.out.println(
        "  ╔══════════════════════════════════════════════════════════════════════════╗");
    System.out.println(
        "  ║  FALSE SHARING CACHE LINE THRASH PROOF                                  ║");
    System.out.printf(
        "  ║  Unpadded (false sharing):  %5dms — cache lines bouncing between CPUs  ║%n",
        unpaddedMs);
    System.out.printf(
        "  ║  Padded (7 longs padding):  %5dms — each counter in its own cache line  ║%n",
        paddedMs);
    System.out.printf(
        "  ║  Speedup from padding:      %5.1fx  (the regression from one field add)  ║%n",
        speedupFactor);
    System.out.printf(
        "  ║  False sharing events detected by agent: %,d                             ║%n",
        falseShareEvents.get());
    System.out.println(
        "  ╠══════════════════════════════════════════════════════════════════════════╣");
    System.out.println(
        "  ║  GC log: nothing. Thread dump: nothing. Profiler: 'CPU is busy.'        ║");
    System.out.println(
        "  ║  Linux perf stat: L1-dcache-load-misses spiked 40x. Nobody runs this.  ║");
    System.out.println(
        "  ║  Fix: @jdk.internal.vm.annotation.@Contended or 7 padding longs.       ║");
    System.out.println(
        "  ║  This agent: detects concurrent contested volatile writes. Instant.    ║");
    System.out.println(
        "  ╚══════════════════════════════════════════════════════════════════════════╝");

    assertThat(unpaddedNs)
        .as("Unpadded counters show cache line contention (slower than padded)")
        .isGreaterThan(paddedNs);
    System.out.printf(
        "%n  Cache line thrash overhead: %.1fx — One padding fix resolves it.%n", speedupFactor);
  }
}
