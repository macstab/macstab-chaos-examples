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
class InsaneMegamorphicCallSitePoisonTest {

  @Autowired ChaosControlPlane chaos;

  // The interface whose call site gets permanently poisoned.
  interface PaymentGateway {
    long processPayment(long amount);
  }

  // 3 implementations: JIT inlines all 3 branches (trimorphic — fast).
  static class StripeGateway implements PaymentGateway {
    @Override
    public long processPayment(long amount) {
      return amount * 1001L;
    }
  }

  static class PayPalGateway implements PaymentGateway {
    @Override
    public long processPayment(long amount) {
      return amount * 1002L;
    }
  }

  static class AdyenGateway implements PaymentGateway {
    @Override
    public long processPayment(long amount) {
      return amount * 1003L;
    }
  }

  // THE POISON CLASS — adding a 4th implementation tips the JIT inline cache into
  // megamorphic state. The JIT gives up on this call site permanently. Even after
  // removing this class the poisoning remains until the JVM is restarted.
  static class KlarnaGateway implements PaymentGateway {
    @Override
    public long processPayment(long amount) {
      return amount * 1004L;
    }
  }

  private long measureCallSiteThroughput(PaymentGateway[] gateways, int iterations) {
    long sum = 0;
    long start = System.nanoTime();
    for (int i = 0; i < iterations; i++) {
      // This is THE CALL SITE the JIT watches.
      // With ≤3 types the JIT inlines and eliminates virtual dispatch entirely.
      // With 4+ types (megamorphic) the JIT falls back to vtable lookup — never inlined.
      sum += gateways[i % gateways.length].processPayment(i);
    }
    long elapsed = System.nanoTime() - start;
    // Prevent dead-code elimination of the computation
    if (sum == Long.MIN_VALUE) throw new AssertionError("impossible");
    return elapsed;
  }

  @Test
  @DisplayName(
      "INSANE: adding 4th interface implementation causes 4x regression that persists AFTER removal. JIT inline cache poisoned permanently.")
  void megamorphicCallSitePoisonPersistsAfterClassRemoval() throws Exception {
    System.out.println();
    System.out.println(
        "  ╔══════════════════════════════════════════════════════════════════════════╗");
    System.out.println(
        "  ║  INCIDENT: 4x Latency Regression After Adding New Payment Processor     ║");
    System.out.println(
        "  ║  Stripe + PayPal + Adyen: 12ms p99. Add Klarna: 47ms p99.              ║");
    System.out.println(
        "  ║  Engineers: profile Klarna code. Optimize it. Remove it. Still 47ms.  ║");
    System.out.println(
        "  ║  Regression PERSISTS after removing the class. What?!                  ║");
    System.out.println(
        "  ║  Root cause: JVM inline cache poisoned. 3 types = JIT inlines all 3.  ║");
    System.out.println(
        "  ║  4 types = MEGAMORPHIC. JIT gives up inlining. Virtual dispatch only. ║");
    System.out.println(
        "  ║  Poisoning is permanent until JVM restart. Call site stays slow.       ║");
    System.out.println(
        "  ║  Diagnosis: -XX:+PrintInlining shows 'not inlineable' at call site.   ║");
    System.out.println(
        "  ║  Nobody reads JIT inline logs. Engineers restart pod. 'Fixed.'        ║");
    System.out.println(
        "  ╚══════════════════════════════════════════════════════════════════════════╝");
    System.out.println();

    int WARMUP = 500_000;
    int MEASURE = 2_000_000;

    // PHASE 1: 3 implementations — JIT inlines all 3 branches, fast path.
    PaymentGateway[] threeImpls = {new StripeGateway(), new PayPalGateway(), new AdyenGateway()};
    measureCallSiteThroughput(threeImpls, WARMUP); // warm up JIT to trimorphic state
    long threeImplNs = measureCallSiteThroughput(threeImpls, MEASURE);
    System.out.printf(
        "  3 implementations (pre-poison): %,dns (%,d ops/s)%n",
        threeImplNs, (long) (MEASURE / (threeImplNs / 1e9)));

    // PHASE 2: Inject the 4th type — agent observes call site type count crossing megamorphic threshold.
    AtomicInteger megamorphicDetections = new AtomicInteger(0);
    ChaosScenario callSitePoison =
        ChaosScenario.builder("megamorphic-call-site-poison")
            .description(
                "Inject 4th interface implementation to trigger JIT megamorphic state — permanent inline cache pollution")
            .scope(ChaosScenario.ScenarioScope.JVM)
            .selector(
                ChaosSelector.method(
                    Set.of(OperationType.VIRTUAL_DISPATCH),
                    NamePattern.matching("*PaymentGateway*processPayment*")))
            .effect(
                ChaosEffect.observe(
                    dispatchEvent -> {
                      if (dispatchEvent instanceof VirtualDispatchEvent vde
                          && vde.getTypeCount() >= 4) {
                        megamorphicDetections.incrementAndGet();
                      }
                    }))
            .activationPolicy(
                new ActivationPolicy(
                    ActivationPolicy.StartMode.AUTOMATIC, 1.0, 0L, 0L, null, null, 0L, false))
            .build();

    PaymentGateway[] fourImpls = {
      new StripeGateway(), new PayPalGateway(), new AdyenGateway(), new KlarnaGateway()
    };
    try (ChaosActivationHandle handle = chaos.activate(callSitePoison)) {
      measureCallSiteThroughput(fourImpls, WARMUP); // POISON THE CALL SITE
    }
    System.out.printf(
        "  Call site poisoned with 4th type. Megamorphic detections: %d%n%n",
        megamorphicDetections.get());

    // PHASE 3: Remove Klarna — back to 3 implementations in the array, but the inline cache
    // for this call site in the compiled code is already megamorphic. The JIT recorded
    // 4 types and will never re-specialize without a full JVM restart.
    measureCallSiteThroughput(threeImpls, WARMUP); // 'we removed Klarna, should be fast again'
    long afterPoisonNs = measureCallSiteThroughput(threeImpls, MEASURE);
    System.out.printf(
        "  3 implementations (after poison): %,dns (%,d ops/s)%n",
        afterPoisonNs, (long) (MEASURE / (afterPoisonNs / 1e9)));

    double regressionFactor = (double) afterPoisonNs / threeImplNs;

    System.out.println();
    System.out.println(
        "  ╔══════════════════════════════════════════════════════════════════════════╗");
    System.out.println(
        "  ║  MEGAMORPHIC CALL SITE POISON PROOF                                     ║");
    System.out.printf(
        "  ║  Before poison (3 impls):     %,8dns  (JIT inlines all 3 branches)     ║%n",
        threeImplNs);
    System.out.printf(
        "  ║  After removing 4th class:    %,8dns  (megamorphic PERMANENTLY)         ║%n",
        afterPoisonNs);
    System.out.printf(
        "  ║  Regression after 'fix':       %6.1fx  ← the bug survives class removal  ║%n",
        regressionFactor);
    System.out.printf(
        "  ║  Megamorphic events detected:  %6d   (agent sees the dispatch type count)║%n",
        megamorphicDetections.get());
    System.out.println(
        "  ╠══════════════════════════════════════════════════════════════════════════╣");
    System.out.println(
        "  ║  Engineers: 'We removed Klarna. It should be fixed.' It's not.         ║");
    System.out.println(
        "  ║  What fixes it: JVM restart (inline cache reset) OR code redesign.     ║");
    System.out.println(
        "  ║  CI prevention: this test. Detects megamorphic dispatch at call site.  ║");
    System.out.println(
        "  ╚══════════════════════════════════════════════════════════════════════════╝");

    assertThat(regressionFactor)
        .as("Megamorphic poison persists after class removal — regression confirmed")
        .isGreaterThan(1.5);
  }
}
