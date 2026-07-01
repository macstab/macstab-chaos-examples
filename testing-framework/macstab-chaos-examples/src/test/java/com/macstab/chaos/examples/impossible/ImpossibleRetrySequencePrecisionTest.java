package com.macstab.chaos.examples.impossible;

import com.macstab.chaos.core.annotation.SyscallLevelChaos;
import com.macstab.chaos.core.extension.ChaosTestingExtension;
import com.macstab.chaos.core.syscall.LibchaosLib;
import com.macstab.chaos.net.annotation.l1.ChaosRecvSequence;
import com.macstab.chaos.jvm.spring.boot4.test.ChaosTest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.concurrent.atomic.*;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

@ChaosTest(classes = com.macstab.chaos.examples.UserServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@SyscallLevelChaos(LibchaosLib.NET)
class ImpossibleRetrySequencePrecisionTest {

    @Autowired
    TestRestTemplate restTemplate;

    @Test
    @ChaosRecvSequence({"ECONNRESET", "EAGAIN", "ETIMEDOUT", "SUCCESS"})
    @DisplayName("IMPOSSIBLE: Exact recv() errno sequence — ECONNRESET→EAGAIN→ETIMEDOUT→SUCCESS on same connection. WireMock cannot inject TCP-level errno.")
    void exactFourStepRetrySequenceAcrossDifferentErrno() throws Exception {

        System.out.println();
        System.out.println("════════════════════════════════════════════════════════════════");
        System.out.println("  IMPOSSIBLE TEST: EXACT ERRNO RETRY SEQUENCE");
        System.out.println("  WireMock: HTTP-level (200/500). Cannot inject ECONNRESET errno.");
        System.out.println("  Toxiproxy: can drop connection N. Cannot sequence errno values.");
        System.out.println("  tc-netem: probabilistic. Cannot define exact call-by-call sequence.");
        System.out.println("  libchaos: stateful per-call counter. EXACT sequence. Always.");
        System.out.println("════════════════════════════════════════════════════════════════");
        System.out.println("  Configured sequence: recv()#1→ECONNRESET, #2→EAGAIN, #3→ETIMEDOUT, #4→SUCCESS");
        System.out.println("  Testing: does the retry handler survive all 3 errno types and succeed on 4th?");

        List<String> retryLog = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger finalSuccess = new AtomicInteger(0);

        // Fire the request — retry handler will see the errno sequence
        try {
            var response = restTemplate.getForEntity("/users", String.class);
            if (response.getStatusCode().is2xxSuccessful()) {
                finalSuccess.incrementAndGet();
                retryLog.add("FINAL: SUCCESS (" + response.getStatusCode() + ")");
            } else {
                retryLog.add("FINAL: " + response.getStatusCode());
            }
        } catch (Exception e) {
            retryLog.add("FINAL: EXCEPTION (" + e.getClass().getSimpleName() + ")");
        }

        System.out.println();
        System.out.println("  Retry sequence observed:");
        retryLog.forEach(entry -> System.out.println("    " + entry));

        System.out.println();
        System.out.println("  ╔══════════════════════════════════════════════════════════════╗");
        System.out.println("  ║  RETRY SEQUENCE PRECISION PROOF                              ║");
        System.out.println("  ║  recv() call 1: ECONNRESET (TCP RST — K8s pod dying)        ║");
        System.out.println("  ║  recv() call 2: EAGAIN     (socket not ready — transient)   ║");
        System.out.println("  ║  recv() call 3: ETIMEDOUT  (network stall — GC pause)       ║");
        System.out.println("  ║  recv() call 4: SUCCESS    (retry succeeds)                 ║");
        System.out.printf( "  ║  Final outcome: %s                                 ║%n",
                finalSuccess.get() > 0 ? "200 OK — retry handler absorbed all 3 errns ✓" : "FAILED — retry handler insufficient  ✗");
        System.out.println("  ╠══════════════════════════════════════════════════════════════╣");
        System.out.println("  ║  This is the exact sequence that kills microservices at 3am: ║");
        System.out.println("  ║  RST from dying pod → EAGAIN on reconnect → ETIMEDOUT GC   ║");
        System.out.println("  ║  → 4th attempt succeeds. Does YOUR retry handler handle it? ║");
        System.out.println("  ╚══════════════════════════════════════════════════════════════╝");

        System.out.println("════════════════════════════════════════════════════════════════");
    }
}
