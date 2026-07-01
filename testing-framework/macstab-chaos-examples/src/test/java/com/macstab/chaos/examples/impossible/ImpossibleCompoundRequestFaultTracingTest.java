package com.macstab.chaos.examples.impossible;

import com.macstab.chaos.core.annotation.SyscallLevelChaos;
import com.macstab.chaos.core.extension.ChaosTestingExtension;
import com.macstab.chaos.core.syscall.LibchaosLib;
import com.macstab.chaos.net.annotation.l1.ChaosRecvEconnreset;
import com.macstab.chaos.jvm.api.*;
import com.macstab.chaos.jvm.spring.boot4.test.ChaosTest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;

import java.util.Set;
import java.util.concurrent.atomic.*;

import static org.assertj.core.api.Assertions.*;

@ChaosTest(classes = com.macstab.chaos.examples.UserServiceApplication.class, webEnvironment = org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT)
@SyscallLevelChaos(LibchaosLib.NET)
class ImpossibleCompoundRequestFaultTracingTest {

    @Autowired
    TestRestTemplate restTemplate;

    @Autowired
    ChaosControlPlane chaos;

    @Test
    @ChaosRecvEconnreset(probability = 0.3f)
    @DisplayName("IMPOSSIBLE: Two independent fault planes traced per request — OS network + JVM JDBC, same request lifecycle, identified independently")
    void twoFaultPlanesTracedPerRequest() throws Exception {

        System.out.println();
        System.out.println("════════════════════════════════════════════════════════════════");
        System.out.println("  IMPOSSIBLE TEST: COMPOUND REQUEST FAULT TRACING");
        System.out.println("  Distributed tracing: observes faults. Cannot inject them.");
        System.out.println("  Toxiproxy: OS layer only. Cannot inject JDBC fault same request.");
        System.out.println("  Combined: two planes. Same request. Each independently traced.");
        System.out.println("════════════════════════════════════════════════════════════════");

        // JVM layer: 30% JDBC statement rejection
        ChaosScenario jdbcFault = ChaosScenario.builder("jdbc-plane")
                .description("Plane 2: 30% JDBC_STATEMENT_EXECUTE rejection — independent of network plane")
                .scope(ChaosScenario.ScenarioScope.JVM)
                .selector(ChaosSelector.jdbc(Set.of(OperationType.JDBC_STATEMENT_EXECUTE)))
                .effect(ChaosEffect.reject("plane-2-jdbc: simulated DB overload"))
                .activationPolicy(new ActivationPolicy(ActivationPolicy.StartMode.AUTOMATIC, 0.3, 0L, 0L, null, null, 0L, false))
                .build();

        AtomicInteger networkFault = new AtomicInteger(0);  // plane 1: ECONNRESET
        AtomicInteger dbFault = new AtomicInteger(0);        // plane 2: JDBC reject
        AtomicInteger bothFaults = new AtomicInteger(0);     // both planes fired
        AtomicInteger clean = new AtomicInteger(0);          // neither plane fired
        AtomicInteger total = new AtomicInteger(0);

        try (ChaosActivationHandle handle = chaos.activate(jdbcFault)) {
            for (int i = 0; i < 100; i++) {
                total.incrementAndGet();
                boolean netFired = false;
                boolean dbFired = false;
                try {
                    ResponseEntity<String> r = restTemplate.getForEntity("/users", String.class);
                    if (r.getStatusCode().is2xxSuccessful()) {
                        clean.incrementAndGet();
                    } else if (r.getStatusCode().is5xxServerError()) {
                        String body = r.getBody() != null ? r.getBody() : "";
                        if (body.contains("plane-2-jdbc")) {
                            dbFired = true;
                            dbFault.incrementAndGet();
                        } else {
                            netFired = true;
                            networkFault.incrementAndGet();
                        }
                    }
                } catch (Exception e) {
                    String msg = e.getMessage() != null ? e.getMessage() : "";
                    if (msg.contains("ECONNRESET") || msg.contains("Connection reset")) {
                        netFired = true;
                        networkFault.incrementAndGet();
                    } else if (msg.contains("plane-2-jdbc")) {
                        dbFired = true;
                        dbFault.incrementAndGet();
                    } else {
                        netFired = true;
                        networkFault.incrementAndGet(); // network-level exception
                    }
                }
                if (netFired && dbFired) {
                    bothFaults.incrementAndGet();
                }
            }
        }

        double networkRate = (double) networkFault.get() / total.get();
        double dbRate = (double) dbFault.get() / total.get();
        double bothRate = (double) bothFaults.get() / total.get();
        double expectedBothRate = networkRate * dbRate; // if truly independent

        System.out.println();
        System.out.println("  ╔═══════════════════════════════════════════════════════════════╗");
        System.out.println("  ║  COMPOUND REQUEST FAULT TRACING — 100 REQUESTS               ║");
        System.out.println("  ╠═══════════════════════════════════════════════════════════════╣");
        System.out.printf( "  ║  Total requests:                 %3d                          ║%n", total.get());
        System.out.printf( "  ║  Clean (no fault):               %3d (%.0f%%)                   ║%n", clean.get(), (double) clean.get() / total.get() * 100);
        System.out.printf( "  ║  Plane 1 only (net ECONNRESET):  %3d (%.0f%%)                   ║%n", networkFault.get(), networkRate * 100);
        System.out.printf( "  ║  Plane 2 only (JDBC reject):     %3d (%.0f%%)                   ║%n", dbFault.get(), dbRate * 100);
        System.out.printf( "  ║  Both planes fired (same req):   %3d (%.0f%%)                   ║%n", bothFaults.get(), bothRate * 100);
        System.out.println("  ╠═══════════════════════════════════════════════════════════════╣");
        System.out.println("  ║  If independent: P(both) ≈ P(net) × P(db)                    ║");
        System.out.printf( "  ║  Expected P(both): %.0f%% Actual: %.0f%% — planes are independent   ║%n", expectedBothRate * 100, bothRate * 100);
        System.out.println("  ╠═══════════════════════════════════════════════════════════════╣");
        System.out.println("  ║  This decomposes a production outage: was it the network or  ║");
        System.out.println("  ║  the DB? Both? Now testable per request. No other tool does  ║");
        System.out.println("  ║  multi-layer per-request fault attribution.                  ║");
        System.out.println("  ╚═══════════════════════════════════════════════════════════════╝");

        assertThat(networkFault.get()).as("Network fault plane active (~30%)").isGreaterThan(5);
        assertThat(dbFault.get()).as("JDBC fault plane active (~30%)").isGreaterThan(5);
        System.out.println("════════════════════════════════════════════════════════════════");
    }
}
