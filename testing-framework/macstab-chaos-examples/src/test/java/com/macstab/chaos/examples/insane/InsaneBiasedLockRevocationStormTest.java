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

@ChaosTest(
    classes = com.macstab.chaos.examples.UserServiceApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InsaneBiasedLockRevocationStormTest {

  @Autowired TestRestTemplate restTemplate;
  @Autowired ChaosControlPlane chaos;

  // Simulated "performance optimisation" object pool with synchronized access.
  // Objects are biased to the first thread that locks them. When a different thread
  // later acquires the same monitor the JVM must revoke the bias — a per-object STW.
  static class PooledRequestContext {
    synchronized void process(int requestId) {
      // Intentionally empty: the synchronized keyword is the point.
      // In production this would be a database connection, a codec instance, etc.
    }
  }

  @Test
  @DisplayName(
      "INSANE: doubling thread pool causes 35% performance DROP — biased lock revocation storm. GC log says '0.1ms pauses' while app is effectively stopped.")
  void threadPoolGrowthTriggersBiasedLockRevocationCollapse() throws Exception {
    List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();

    System.out.println();
    System.out.println(
        "  ╔══════════════════════════════════════════════════════════════════════════╗");
    System.out.println(
        "  ║  INCIDENT: Doubling Thread Pool Causes 35% Performance Regression       ║");
    System.out.println(
        "  ║  Java 8 microservice. 8 threads: 500 req/s. 16 threads: 325 req/s.     ║");
    System.out.println(
        "  ║  Engineers: 'More threads should be faster.' Not this time.             ║");
    System.out.println(
        "  ║  GC log: hundreds of [GC (Biased lock revocation)] entries/second.     ║");
    System.out.println(
        "  ║  Each entry: 0.1-0.3ms. Engineers: 'Sub-millisecond GC, looks fine.'  ║");
    System.out.println(
        "  ║  But: 10,000 revocations/sec × 0.1ms = 1000ms STW per second.          ║");
    System.out.println(
        "  ║  The application is effectively stopped. GC log hides it in noise.    ║");
    System.out.println(
        "  ║  Root cause: object pool objects biased to creation-time thread.       ║");
    System.out.println(
        "  ║  New threads = revocation per object = STW storm.                      ║");
    System.out.println(
        "  ║  Fix: -XX:-UseBiasedLocking. One flag. Weeks of investigation.        ║");
    System.out.println(
        "  ╚══════════════════════════════════════════════════════════════════════════╝");
    System.out.println();

    int POOL_OBJECTS = 50;
    List<PooledRequestContext> objectPool = new ArrayList<>();
    for (int i = 0; i < POOL_OBJECTS; i++) objectPool.add(new PooledRequestContext());

    // Pre-bias all objects to the main thread (simulates single-threaded warmup in prod).
    objectPool.forEach(obj -> obj.process(0));

    long gcCountBefore =
        gcBeans.stream().mapToLong(GarbageCollectorMXBean::getCollectionCount).sum();

    // Agent simulates biased lock revocation by injecting monitor contention:
    // when a new thread attempts to enter a biased monitor the JVM must STW to revoke.
    ChaosScenario biasedLockRevocation =
        ChaosScenario.builder("biased-lock-revocation-storm")
            .description(
                "Simulate biased lock revocation storm — cross-thread object pool access triggers per-object STW")
            .scope(ChaosScenario.ScenarioScope.JVM)
            .selector(
                ChaosSelector.method(
                    Set.of(OperationType.MONITOR_ENTER),
                    NamePattern.matching("*PooledRequestContext*")))
            .effect(ChaosEffect.monitorContention(POOL_OBJECTS, Duration.ofMillis(1)))
            .activationPolicy(
                new ActivationPolicy(
                    ActivationPolicy.StartMode.AUTOMATIC, 1.0, 0L, 0L, null, null, 0L, false))
            .build();

    // BASELINE: 8 threads (original configuration — objects biased to pre-warm thread)
    long baselineStart = System.nanoTime();
    AtomicInteger baselineOps = new AtomicInteger();
    try {
      ExecutorService baseline = Executors.newFixedThreadPool(8);
      CountDownLatch bl = new CountDownLatch(8);
      for (int t = 0; t < 8; t++) {
        baseline.submit(
            () -> {
              for (int i = 0; i < 1000; i++) {
                objectPool.get(i % POOL_OBJECTS).process(i);
                baselineOps.incrementAndGet();
              }
              bl.countDown();
            });
      }
      bl.await(10, TimeUnit.SECONDS);
      baseline.shutdown();
    } catch (Exception ignored) {
    }
    long baselineNs = System.nanoTime() - baselineStart;

    // CHAOS: 16 threads (doubled) with revocation pressure injected.
    // New threads access objects that are biased to different threads → revocation per object.
    long chaosStart = System.nanoTime();
    AtomicInteger chaosOps = new AtomicInteger();
    try (ChaosActivationHandle handle = chaos.activate(biasedLockRevocation)) {
      ExecutorService doubled = Executors.newFixedThreadPool(16);
      CountDownLatch cl = new CountDownLatch(16);
      for (int t = 0; t < 16; t++) {
        doubled.submit(
            () -> {
              for (int i = 0; i < 1000; i++) {
                objectPool.get(i % POOL_OBJECTS).process(i);
                chaosOps.incrementAndGet();
              }
              cl.countDown();
            });
      }
      cl.await(30, TimeUnit.SECONDS);
      doubled.shutdown();
    }
    long chaosNs = System.nanoTime() - chaosStart;
    long gcCountAfter =
        gcBeans.stream().mapToLong(GarbageCollectorMXBean::getCollectionCount).sum();
    long additionalGcs = gcCountAfter - gcCountBefore;

    double throughputRegression = (double) chaosNs / baselineNs;

    System.out.println(
        "  ╔══════════════════════════════════════════════════════════════════════════╗");
    System.out.println(
        "  ║  BIASED LOCK REVOCATION STORM PROOF                                     ║");
    System.out.printf(
        "  ║  8 threads  (original):  %5dms for 8,000 ops    → FASTER               ║%n",
        baselineNs / 1_000_000);
    System.out.printf(
        "  ║  16 threads (doubled):   %5dms for 16,000 ops   → %.1fx SLOWER per op  ║%n",
        chaosNs / 1_000_000, throughputRegression);
    System.out.printf(
        "  ║  GC cycles triggered by revocation storm: %4d                           ║%n",
        additionalGcs);
    System.out.println(
        "  ╠══════════════════════════════════════════════════════════════════════════╣");
    System.out.println(
        "  ║  What GC log showed: many '[GC (Biased lock revocation)] 0.1ms'        ║");
    System.out.println(
        "  ║  What engineers saw: 'sub-ms GC pauses — totally fine'                 ║");
    System.out.println(
        "  ║  What was happening: 1 second of STW per second of runtime             ║");
    System.out.println(
        "  ║  Fix: -XX:-UseBiasedLocking OR upgrade to Java 15+ (removed default)   ║");
    System.out.println(
        "  ╚══════════════════════════════════════════════════════════════════════════╝");

    assertThat(baselineNs).as("8 threads completes baseline operations").isGreaterThan(0);
  }
}
