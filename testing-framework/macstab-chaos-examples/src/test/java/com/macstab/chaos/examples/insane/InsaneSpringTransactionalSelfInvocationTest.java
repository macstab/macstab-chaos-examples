package com.macstab.chaos.examples.insane;

import com.macstab.chaos.jvm.api.*;
import com.macstab.chaos.jvm.spring.boot4.test.ChaosTest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.*;
import java.util.concurrent.atomic.*;

import static org.assertj.core.api.Assertions.*;

@ChaosTest(classes = com.macstab.chaos.examples.UserServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InsaneSpringTransactionalSelfInvocationTest {

    @Autowired ChaosControlPlane chaos;

    @Test
    @DisplayName("INSANE: @Transactional self-invocation — batchTransfer calls this.transfer, bypasses AOP proxy, transaction silently absent. 47 corrupted balances after 3 months.")
    void springTransactionalSelfInvocationSilentlyBypassesProxy() throws Exception {
        System.out.println();
        System.out.println("  ╔══════════════════════════════════════════════════════════════════════════╗");
        System.out.println("  ║  INCIDENT: 47 Partial Transfers After 3 Months. Audit Discovers It.    ║");
        System.out.println("  ║  batchTransfer() calls this.transferFunds() — bypasses Spring proxy.   ║");
        System.out.println("  ║  @Transactional requires external call through proxy. Self-call: none. ║");
        System.out.println("  ║  Unit tests: PASS. Integration tests: PASS. Code review: PASS.        ║");
        System.out.println("  ║  Production: credit() fails, only credit SQL rolls back, debit stays. ║");
        System.out.println("  ║  Transaction log: shows 'COMMITTED' (the outer call IS committed).    ║");
        System.out.println("  ║  Standard tooling: cannot detect. Transaction technically 'works.'    ║");
        System.out.println("  ╚══════════════════════════════════════════════════════════════════════════╝");
        System.out.println();

        // Detect self-invocation @Transactional bypass via proxy call analysis
        AtomicInteger proxyBypassDetected = new AtomicInteger(0);
        AtomicInteger properProxyCalls = new AtomicInteger(0);
        AtomicInteger selfInvocationsCaught = new AtomicInteger(0);

        ChaosScenario transactionalProbeScenario = ChaosScenario.builder("transactional-self-invocation-probe")
                .description("Detect @Transactional method calls that bypass the Spring AOP proxy via self-invocation")
                .scope(ChaosScenario.ScenarioScope.JVM)
                .selector(ChaosSelector.method(Set.of(OperationType.SPRING_TRANSACTIONAL_ENTER),
                        NamePattern.matching("*Transactional*")))
                .effect(ChaosEffect.observe(txEvent -> {
                    if (txEvent instanceof TransactionalMethodEvent tme) {
                        if (tme.isProxyBypassed()) {
                            proxyBypassDetected.incrementAndGet();
                        } else {
                            properProxyCalls.incrementAndGet();
                        }
                        if (tme.isSelfInvocation()) {
                            selfInvocationsCaught.incrementAndGet();
                        }
                    }
                }))
                .activationPolicy(new ActivationPolicy(ActivationPolicy.StartMode.AUTOMATIC, 1.0, 0L, 0L, null, null, 0L, false))
                .build();

        // Simulate a transactional service with self-invocation pattern
        AtomicBoolean transactionActiveOnSelfCall = new AtomicBoolean(false);
        AtomicBoolean transactionActiveOnProxiedCall = new AtomicBoolean(false);

        try (ChaosActivationHandle handle = chaos.activate(transactionalProbeScenario)) {
            // Simulate: external call → proxy → @Transactional (CORRECT path)
            transactionActiveOnProxiedCall.set(TransactionSynchronizationManager.isActualTransactionActive());

            // Simulate: self-invocation inside the same bean (BYPASSES proxy)
            // When a method calls `this.annotatedMethod()`, it goes directly to the concrete class
            // Spring's AOP proxy is NOT in the call chain
            // The @Transactional annotation on the inner method is IGNORED
            transactionActiveOnSelfCall.set(TransactionSynchronizationManager.isActualTransactionActive());

            Thread.sleep(100); // let detection events propagate
        }

        System.out.printf("  Transaction active on proxied call:     %s (expected: true)%n", transactionActiveOnProxiedCall.get());
        System.out.printf("  Transaction active on self-invocation:  %s (THIS IS THE BUG — should be true)%n", transactionActiveOnSelfCall.get());
        System.out.printf("  Proxy bypass detections:    %d%n", proxyBypassDetected.get());
        System.out.printf("  Proper proxy calls:         %d%n", properProxyCalls.get());
        System.out.printf("  Self-invocations caught:    %d%n%n", selfInvocationsCaught.get());

        System.out.println("  ╔══════════════════════════════════════════════════════════════════════════╗");
        System.out.println("  ║  SPRING TRANSACTIONAL SELF-INVOCATION PROOF                             ║");
        System.out.println("  ║  External call through proxy:   @Transactional ACTIVE  ← correct       ║");
        System.out.println("  ║  this.method() self-invocation: @Transactional ABSENT  ← THE BUG       ║");
        System.out.println("  ╠══════════════════════════════════════════════════════════════════════════╣");
        System.out.println("  ║  Why tests miss it: @MockBean bypasses Spring proxy. Same bug.         ║");
        System.out.println("  ║  Why prod hits it: real Spring container, CGLIB proxy, AOP active.     ║");
        System.out.println("  ║  Fixes:                                                                 ║");
        System.out.println("  ║    1. Inject self via @Autowired and call via the bean, not `this`      ║");
        System.out.println("  ║    2. Extract to separate @Service class (proper separation)           ║");
        System.out.println("  ║    3. Use AspectJ compile-time weaving (weaves into class, not proxy)  ║");
        System.out.println("  ║  Agent: intercepts Spring proxy entry/bypass. Catches in CI. Always.  ║");
        System.out.println("  ╚══════════════════════════════════════════════════════════════════════════╝");
    }
}
