package com.macstab.chaos.examples.insane;

import com.macstab.chaos.jvm.api.*;
import com.macstab.chaos.jvm.spring.boot4.test.ChaosTest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.*;
import java.util.concurrent.atomic.*;

import static org.assertj.core.api.Assertions.*;

@ChaosTest(
    classes = com.macstab.chaos.examples.UserServiceApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InsaneHashCodeCollisionDosTest {

  @Autowired ChaosControlPlane chaos;

  // Session key that looks fine but has a pathological hash distribution when
  // the fingerprinting library produces a fixed prefix for a specific device model.
  static class DeviceFingerprint {
    final String value;

    DeviceFingerprint(String v) {
      this.value = v;
    }

    @Override
    public boolean equals(Object o) {
      return o instanceof DeviceFingerprint d && value.equals(d.value);
    }

    // THE BUG: only the first 8 characters contribute to the hash.
    // All iPhone 14 fingerprints begin with "device-a" (library uses device model hash;
    // every iPhone 14 has the same model hash, so the prefix is identical).
    // Result: 50,000 iPhone sessions all hash to the same HashMap bucket → O(n) scan.
    @Override
    public int hashCode() {
      return value.substring(0, Math.min(8, value.length())).hashCode();
    }
  }

  @Test
  @DisplayName(
      "INSANE: HashMap O(1) → O(n) collision → iPhone users 400x slower than Android. Engineers blame Apple CDN for 3 weeks.")
  void hashCodeCollisionDegradesToLinearScanInvisibleToMonitoring() throws Exception {
    System.out.println();
    System.out.println(
        "  ╔══════════════════════════════════════════════════════════════════════════╗");
    System.out.println(
        "  ║  INCIDENT: iPhone Users Experience 2000ms Response. Android: 5ms.       ║");
    System.out.println(
        "  ║  60% of users on iPhone 14. All other devices fine. Only iPhones slow. ║");
    System.out.println(
        "  ║  Engineers: check CDN (fine), check iOS-specific code (none), blame    ║");
    System.out.println(
        "  ║  Apple's servers, reconfigure CDN region, open Apple support ticket.   ║");
    System.out.println(
        "  ║  3 weeks later: HashMap<DeviceFingerprint, UserSession> profiled.      ║");
    System.out.println(
        "  ║  All iPhone 14 fingerprints hash to same bucket (library bug).         ║");
    System.out.println(
        "  ║  60k iPhone sessions in one linked list. get() = O(60k) scan.          ║");
    System.out.println(
        "  ║  Monitoring: HashMap GET latency is not a JMX metric. Never exposed.  ║");
    System.out.println(
        "  ╚══════════════════════════════════════════════════════════════════════════╝");
    System.out.println();

    int MAP_SIZE = 50_000;
    int LOOKUPS = 10_000;

    // Agent detects deep HashMap bucket traversals — the O(n) degradation fingerprint.
    AtomicInteger collisionBuckets = new AtomicInteger(0);
    ChaosScenario hashCollisionProbe =
        ChaosScenario.builder("hashcode-collision-probe")
            .description(
                "Detect HashMap bucket collision density — O(1) vs O(n) degradation fingerprint")
            .scope(ChaosScenario.ScenarioScope.JVM)
            .selector(
                ChaosSelector.method(
                    Set.of(OperationType.HASHMAP_GET), NamePattern.matching("java.util.HashMap")))
            .effect(
                ChaosEffect.observe(
                    mapEvent -> {
                      if (mapEvent instanceof HashMapAccessEvent hae && hae.getBucketDepth() > 8) {
                        collisionBuckets.incrementAndGet();
                      }
                    }))
            .activationPolicy(
                new ActivationPolicy(
                    ActivationPolicy.StartMode.AUTOMATIC, 0.1, 0L, 0L, null, null, 0L, false))
            .build();

    // Build map with NORMAL distribution (Android devices — unique 8-char prefixes).
    Map<DeviceFingerprint, String> normalMap = new HashMap<>();
    for (int i = 0; i < MAP_SIZE; i++) {
      normalMap.put(
          new DeviceFingerprint("device-" + String.format("%08x", i) + "-extra"), "session-" + i);
    }
    List<DeviceFingerprint> normalKeys = new ArrayList<>(normalMap.keySet());
    long normalStart = System.nanoTime();
    for (int i = 0; i < LOOKUPS; i++) normalMap.get(normalKeys.get(i % normalKeys.size()));
    long normalNs = System.nanoTime() - normalStart;

    // Build map with COLLISION distribution (iPhone 14 — all share the same 8-char prefix).
    // hashCode() of "device-a" is the same for every entry → all land in the same bucket.
    // Java 8+ treeifies at 8 entries (O(log n)) but O(log 50000) is still catastrophic vs O(1).
    Map<DeviceFingerprint, String> collisionMap = new HashMap<>();
    for (int i = 0; i < MAP_SIZE; i++) {
      collisionMap.put(
          new DeviceFingerprint("device-a" + String.format("%08x", i) + "-ios14"),
          "session-" + i);
    }
    List<DeviceFingerprint> collisionKeys = new ArrayList<>(collisionMap.keySet());

    long collisionStart = System.nanoTime();
    try (ChaosActivationHandle handle = chaos.activate(hashCollisionProbe)) {
      for (int i = 0; i < LOOKUPS; i++)
        collisionMap.get(collisionKeys.get(i % collisionKeys.size()));
    }
    long collisionNs = System.nanoTime() - collisionStart;

    double slowdownFactor = (double) collisionNs / normalNs;
    long normalMicros = normalNs / 1000 / LOOKUPS;
    long collisionMicros = collisionNs / 1000 / LOOKUPS;

    System.out.println(
        "  ╔══════════════════════════════════════════════════════════════════════════╗");
    System.out.println(
        "  ║  HASHCODE COLLISION DoS PROOF                                           ║");
    System.out.printf(
        "  ║  Android devices (good hash): %4dμs avg lookup  O(1)                   ║%n",
        normalMicros);
    System.out.printf(
        "  ║  iPhone 14 (collision hash):  %4dμs avg lookup  O(n/O(log n) tree)     ║%n",
        collisionMicros);
    System.out.printf(
        "  ║  Slowdown factor:             %4.0fx  ← this is 'why iPhone is slow'     ║%n",
        slowdownFactor);
    System.out.printf(
        "  ║  Deep bucket accesses detected: %4d  (agent sees bucket depth > 8)      ║%n",
        collisionBuckets.get());
    System.out.printf(
        "  ║  Map size: %,d entries, all same hash bucket. One tree/list.            ║%n",
        MAP_SIZE);
    System.out.println(
        "  ╠══════════════════════════════════════════════════════════════════════════╣");
    System.out.println(
        "  ║  Monitoring shows: iPhone response 2000ms. Android 5ms. No root cause. ║");
    System.out.println(
        "  ║  HashMap bucket depth: not a JMX/Micrometer metric. Never exposed.    ║");
    System.out.println(
        "  ║  Fix: fix hashCode() to use full fingerprint. One line. 3 weeks found. ║");
    System.out.println(
        "  ╚══════════════════════════════════════════════════════════════════════════╝");

    assertThat(collisionNs)
        .as("Collision map is significantly slower than well-distributed map")
        .isGreaterThan(normalNs);
  }
}
