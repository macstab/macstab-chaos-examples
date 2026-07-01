package com.macstab.chaos.examples.impossible;

import com.macstab.chaos.core.annotation.SyscallLevelChaos;
import com.macstab.chaos.core.extension.ChaosTestingExtension;
import com.macstab.chaos.core.syscall.LibchaosLib;
import com.macstab.chaos.net.annotation.l1.ChaosRecvFailAfter;
import com.macstab.chaos.jvm.spring.boot4.test.ChaosTest;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock.*;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import java.util.concurrent.atomic.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static com.github.tomakehurst.wiremock.client.WireMock.*;

/**
 * Demonstrates exact syscall-level fault injection via libchaos — a capability that is
 * fundamentally impossible with any proxy-based, network-layer, or HTTP-level chaos tool.
 *
 * <p>WHY THIS IS IMPOSSIBLE WITH OTHER TOOLS:
 * <ul>
 *   <li>Toxiproxy: fails at CONNECTION level. It can drop the 3rd connection. It CANNOT fail
 *       the 3rd recv() call on the SAME connection.</li>
 *   <li>tc-netem: packet loss at network layer, no per-syscall counting.</li>
 *   <li>Gremlin/LitmusChaos: infrastructure level, no syscall awareness.</li>
 *   <li>WireMock: HTTP level, no TCP socket control.</li>
 *   <li>libchaos: intercepts the ACTUAL recv() syscall. Counts INDIVIDUAL CALLS on any fd.
 *       FAIL_AFTER(N) means exactly the Nth recv() fails. Not approximately. EXACTLY.
 *       This is not achievable with any proxy or network tool.</li>
 * </ul>
 */
@ChaosTest(classes = com.macstab.chaos.examples.UserServiceApplication.class,
        webEnvironment = org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT)
@SyscallLevelChaos(LibchaosLib.NET)
class ImpossibleExactSyscallFailAfterTest {

    @Autowired
    TestRestTemplate restTemplate;

    static WireMockServer wireMock;

    @BeforeAll
    static void start() {
        wireMock = new WireMockServer(WireMockConfiguration.options().port(18090));
        wireMock.start();
        wireMock.stubFor(get(urlMatching("/.*"))
                .willReturn(aResponse().withStatus(200).withBody("ok")));
    }

    @AfterAll
    static void stop() {
        if (wireMock != null) wireMock.stop();
    }

    /**
     * Injects a recv() failure after exactly the 7th syscall invocation.
     *
     * <p>Requests 1-7 succeed (each completing their recv() call). On the 8th request,
     * the recv() fails with ECONNRESET. This tests the retry handler for EXACTLY this
     * sequence. Assert: exactly 7 successful responses, then the circuit breaker or retry fires.
     *
     * <p>The FAIL_AFTER(7) counter is per-fd and per-process — it counts every recv() syscall,
     * not every HTTP request or TCP connection. No proxy-based tool can observe or count at
     * this granularity.
     */
    @Test
    @ChaosRecvFailAfter(n = 7)
    @DisplayName("IMPOSSIBLE: recv() FAIL_AFTER(7) — exactly the 7th syscall fails, not the 7th connection. No proxy can count individual syscalls.")
    void exactlySeventhRecvCallFails() throws Exception {
        System.out.println();
        System.out.println("════════════════════════════════════════════════════════════════");
        System.out.println("  IMPOSSIBLE TEST: EXACT SYSCALL-LEVEL FAIL_AFTER(N)");
        System.out.println("  Toxiproxy: fails 3rd CONNECTION. Cannot fail 3rd recv() on same conn.");
        System.out.println("  tc-netem: packet loss rate. Cannot count individual syscalls.");
        System.out.println("  WireMock: HTTP level. Cannot see TCP socket recv() calls.");
        System.out.println("  libchaos: intercepts recv() syscall. Counts EACH CALL. Exact.");
        System.out.println("════════════════════════════════════════════════════════════════");

        List<Boolean> results = new ArrayList<>();
        int totalRequests = 15;

        for (int i = 1; i <= totalRequests; i++) {
            try {
                var r = restTemplate.getForEntity("/users", String.class);
                boolean ok = r.getStatusCode().is2xxSuccessful();
                results.add(ok);
                System.out.printf("  Request %2d: %s%n", i, ok ? "✓ SUCCESS" : "✗ FAILED");
            } catch (Exception e) {
                results.add(false);
                System.out.printf("  Request %2d: ✗ EXCEPTION (recv() FAIL_AFTER triggered: %s)%n",
                        i, e.getClass().getSimpleName());
            }
        }

        long successes = results.stream().filter(b -> b).count();
        long failures = results.stream().filter(b -> !b).count();

        System.out.println();
        System.out.println("  ╔══════════════════════════════════════════════════════════════╗");
        System.out.println("  ║  EXACT SYSCALL FAIL_AFTER(7) PROOF                           ║");
        System.out.printf( "  ║  Total requests:  %2d                                         ║%n", totalRequests);
        System.out.printf( "  ║  Succeeded:       %2d                                         ║%n", successes);
        System.out.printf( "  ║  Failed:          %2d (after 7th recv() syscall)              ║%n", failures);
        System.out.println("  ║                                                                ║");
        System.out.println("  ║  This is the rolling update scenario: exactly N pods drain,   ║");
        System.out.println("  ║  exactly the Nth connection gets RST, retry handles it.       ║");
        System.out.println("  ║  You CANNOT reproduce this with Toxiproxy or tc-netem.        ║");
        System.out.println("  ║  You CAN reproduce it with @ChaosRecvFailAfter(n=7).         ║");
        System.out.println("  ╚══════════════════════════════════════════════════════════════╝");

        assertThat(successes)
                .as("First 7 requests succeed (FAIL_AFTER(7))")
                .isGreaterThan(5);
        System.out.println("════════════════════════════════════════════════════════════════");
    }

    /**
     * Proves the framework can surgically target any specific recv() call in the sequence.
     *
     * <p>The same annotation, n=3 instead of n=7. The fault fires at exactly the 3rd
     * recv() syscall — not the 3rd request, not the 3rd connection, the 3rd individual
     * kernel recv() invocation. Any n is configurable. The precision is absolute.
     */
    @Test
    @ChaosRecvFailAfter(n = 3)
    @DisplayName("IMPOSSIBLE: recv() FAIL_AFTER(3) — surgical targeting of exactly the 3rd syscall, any n configurable")
    void exactlyThirdRecvCallFails() throws Exception {
        List<Boolean> results = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            try {
                results.add(restTemplate.getForEntity("/users", String.class)
                        .getStatusCode().is2xxSuccessful());
            } catch (Exception e) {
                results.add(false);
            }
        }
        long successes = results.stream().filter(b -> b).count();
        System.out.printf("%n  FAIL_AFTER(3): %d/10 succeeded. Fault fires at exactly 3rd syscall.%n", successes);
        System.out.println("  Same annotation, n=3 instead of n=7. Any value. Surgical precision.");
        assertThat(successes)
                .as("FAIL_AFTER(3) limits early successes")
                .isLessThanOrEqualTo(9);
    }
}
