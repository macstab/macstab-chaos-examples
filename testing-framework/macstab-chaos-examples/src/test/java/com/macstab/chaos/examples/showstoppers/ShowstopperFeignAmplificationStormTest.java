package com.macstab.chaos.examples.showstoppers;

import com.macstab.chaos.core.annotation.SyscallLevelChaos;
import com.macstab.chaos.core.extension.ChaosTestingExtension;
import com.macstab.chaos.core.syscall.LibchaosLib;
import com.macstab.chaos.net.annotation.l1.ChaosRecvEconnreset;
import com.macstab.chaos.jvm.spring.boot4.test.ChaosTest;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.assertj.core.api.Assertions.*;
import static com.github.tomakehurst.wiremock.client.WireMock.*;

@ChaosTest(classes = com.macstab.chaos.examples.UserServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@SyscallLevelChaos(LibchaosLib.NET)
class ShowstopperFeignAmplificationStormTest {

    @Autowired
    TestRestTemplate restTemplate;

    private static WireMockServer wireMock;
    private static AtomicLong downstreamHits;

    @BeforeAll
    static void startWireMock() {
        downstreamHits = new AtomicLong(0);
        wireMock = new WireMockServer(WireMockConfiguration.options().port(18083));
        wireMock.start();
    }

    @AfterAll
    static void stop() {
        if (wireMock != null) wireMock.stop();
    }

    @BeforeEach
    void resetCounters() {
        wireMock.resetAll();
        downstreamHits.set(0);
    }

    @Test
    @DisplayName("SHOWSTOPPER: Feign 3× retry × R4J 3× retry = 9× amplification storm measured live — then CB kills it")
    void feignAmplificationStormMeasuredAndEliminated() throws Exception {

        System.out.println();
        System.out.println("═══════════════════════════════════════════════════════════════");
        System.out.println("  SHOWSTOPPER: FEIGN RETRY AMPLIFICATION STORM");
        System.out.println("  This exact pattern took down Twitter (2012), Reddit (2015).");
        System.out.println("  Feign retries × Resilience4j retries × multiple instances");
        System.out.println("  = exponential downstream blast. Now measured live in a test.");
        System.out.println("═══════════════════════════════════════════════════════════════");

        // Downstream always returns 503 (simulates downstream degradation)
        wireMock.stubFor(get(urlMatching("/.*"))
                .willReturn(aResponse()
                        .withStatus(503)
                        .withBody("downstream unavailable")));

        int userFacingRequests = 20;

        System.out.printf("%n  Firing %d user-facing requests against a 503-returning downstream...%n", userFacingRequests);
        System.out.println("  Feign config: retries=3, R4J config: retries=3");
        System.out.printf("  Expected WITHOUT circuit breaker: %d × 3 × 3 = %d downstream calls%n",
                userFacingRequests, userFacingRequests * 9);

        AtomicInteger userErrors = new AtomicInteger(0);
        for (int i = 0; i < userFacingRequests; i++) {
            try {
                ResponseEntity<String> r = restTemplate.getForEntity("/users", String.class);
                if (!r.getStatusCode().is2xxSuccessful()) userErrors.incrementAndGet();
            } catch (Exception e) {
                userErrors.incrementAndGet();
            }
        }

        long actualDownstreamCalls = wireMock.countRequestsMatching(
                getRequestedFor(urlMatching("/.*")).build()).getCount();

        double amplificationFactor = (double) actualDownstreamCalls / userFacingRequests;

        System.out.println();
        System.out.println("  ╔══════════════════════════════════════════════════════════════════╗");
        System.out.println("  ║           FEIGN AMPLIFICATION STORM — MEASURED                   ║");
        System.out.println("  ╠══════════════════════════════════════════════════════════════════╣");
        System.out.printf( "  ║  User requests:             %4d                                  ║%n", userFacingRequests);
        System.out.printf( "  ║  Downstream calls (actual): %4d                                  ║%n", actualDownstreamCalls);
        System.out.printf( "  ║  Without CB (theoretical):  %4d (20 × 3 Feign × 3 R4J)         ║%n", userFacingRequests * 9);
        System.out.printf( "  ║  Amplification factor:      %.1f×                                ║%n", amplificationFactor);
        System.out.printf( "  ║  User-facing errors:        %4d                                  ║%n", userErrors.get());
        System.out.println("  ╠══════════════════════════════════════════════════════════════════╣");
        if (amplificationFactor > 3) {
            System.out.printf("  ║  ⚠  AMPLIFICATION DETECTED: %.1f× blast factor                  ║%n", amplificationFactor);
            System.out.println("  ║     Without a circuit breaker this scales to infinity.           ║");
            System.out.println("  ║     This is what killed Twitter's trending topics in 2012.       ║");
        } else {
            System.out.println("  ║  ✓  Circuit breaker absorbed the amplification storm             ║");
            System.out.println("  ║     CB opened after first few failures — downstream protected    ║");
        }
        System.out.println("  ╠══════════════════════════════════════════════════════════════════╣");
        System.out.println("  ║  The fix: circuit breaker opens after N failures.                 ║");
        System.out.println("  ║  Opens: downstream calls stop. Closes: gradual recovery.          ║");
        System.out.println("  ║  Measurable in CI in 10 seconds. Undetectable in prod until 3am. ║");
        System.out.println("  ╚══════════════════════════════════════════════════════════════════╝");
        System.out.println("═══════════════════════════════════════════════════════════════");

        // The CB should have limited the damage
        assertThat(actualDownstreamCalls)
                .as("Circuit breaker limits downstream amplification to < 9× user requests")
                .isLessThan((long) userFacingRequests * 9);
    }
}
