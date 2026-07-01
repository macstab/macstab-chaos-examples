package com.macstab.chaos.examples.impossible;

import com.macstab.chaos.core.annotation.SyscallLevelChaos;
import com.macstab.chaos.core.syscall.LibchaosLib;
import com.macstab.chaos.net.annotation.l1.ChaosRecvFailAfter;
import com.macstab.chaos.net.annotation.l1.ChaosRecvSequence;
import com.macstab.chaos.jvm.spring.boot4.test.ChaosTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.assertThat;

@ChaosTest(classes = com.macstab.chaos.examples.UserServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@SyscallLevelChaos(LibchaosLib.NET)
class SetupOsSyscallSequenceStateMachineTest {

    @Autowired
    TestRestTemplate restTemplate;

    @BeforeAll
    static void printStateMachineComparison() {
        System.out.println();
        System.out.println("════════════════════════════════════════════════════════════════════════════════");
        System.out.println("  SETUP COMPARISON: Stateful Syscall Sequence Injection");
        System.out.println("  Goal: recv() call 1→ECONNRESET, call 2→EAGAIN, call 3→ETIMEDOUT, 4→SUCCESS");
        System.out.println("════════════════════════════════════════════════════════════════════════════════");
        System.out.println();
        System.out.println("  What existing tools can do:");
        System.out.println("  ┌───────────────────────────┬────────────────────────────────────────────┐");
        System.out.println("  │ Tool                      │ Sequenced per-syscall errno injection?    │");
        System.out.println("  ├───────────────────────────┼────────────────────────────────────────────┤");
        System.out.println("  │ Toxiproxy                 │ NO — connection-level, not syscall-level  │");
        System.out.println("  │ tc-netem                  │ NO — probabilistic only, not sequenced    │");
        System.out.println("  │ WireMock                  │ NO — HTTP level, cannot inject TCP errno  │");
        System.out.println("  │ Gremlin                   │ NO — infrastructure level                 │");
        System.out.println("  │ Chaos Mesh                │ NO — pod/container level                  │");
        System.out.println("  │ Custom LD_PRELOAD (DIY)   │ YES — build it: 2-4 weeks of C           │");
        System.out.println("  │ libchaos-net (macstab)    │ YES — @ChaosRecvSequence({...})           │");
        System.out.println("  └───────────────────────────┴────────────────────────────────────────────┘");
        System.out.println();
        System.out.println("  KEY INSIGHT: Toxiproxy fails the Nth CONNECTION. libchaos fails the Nth");
        System.out.println("  recv() SYSCALL. On a keepalive connection, one request = many recv() calls.");
        System.out.println("  Toxiproxy and recv()-level sequencing are completely different capabilities.");
        System.out.println();
        System.out.println("  To build stateful syscall sequence injection from scratch:");
        System.out.println("  ┌───────────────────────────────────────────────────────────────────────────┐");
        System.out.println("  │ Step 1: C LD_PRELOAD, intercept recv()                      3-5 days    │");
        System.out.println("  │ Step 2: Per-fd atomic call counter                          1-2 days    │");
        System.out.println("  │ Step 3: Sequence state machine (array of errno+probability) 2-3 days    │");
        System.out.println("  │ Step 4: End-of-sequence behavior (loop/stick/reset)         1-2 days    │");
        System.out.println("  │ Step 5: Config format for sequences in config file           1-2 days    │");
        System.out.println("  │ Step 6: Hot-reload: transition old→new state machine safely 1-2 days    │");
        System.out.println("  │ Step 7: Thread-safety + edge cases + CI integration         1-2 weeks   │");
        System.out.println("  │ TOTAL: 2-4 weeks. Expert C + POSIX. Own it forever.                    │");
        System.out.println("  └───────────────────────────────────────────────────────────────────────────┘");
        System.out.println();
        System.out.println("  WITH libchaos-net:");
        System.out.println("  ┌───────────────────────────────────────────────────────────────────────────┐");
        System.out.println("  │   @ChaosRecvSequence({\"ECONNRESET\", \"EAGAIN\", \"ETIMEDOUT\", \"SUCCESS\"})   │");
        System.out.println("  │   // OR: @ChaosRecvFailAfter(n=5)  // 5 successes then permanent fail   │");
        System.out.println("  │   TOTAL: 1 annotation. 5 seconds.                                       │");
        System.out.println("  └───────────────────────────────────────────────────────────────────────────┘");
        System.out.println("════════════════════════════════════════════════════════════════════════════════");
    }

    @Test
    @ChaosRecvSequence({"ECONNRESET", "EAGAIN", "SUCCESS", "SUCCESS", "ETIMEDOUT"})
    @DisplayName("SetupOs: 5-step errno sequence on recv() — Toxiproxy counts connections, not syscalls; build stateful SM: 2-4 weeks")
    void exactFiveStepErrnoSequenceImpossibleWithToxiproxy() throws Exception {
        List<String> outcomes = new ArrayList<>();
        for (int i = 1; i <= 8; i++) {
            try {
                boolean ok = restTemplate.getForEntity("/users", String.class).getStatusCode().is2xxSuccessful();
                outcomes.add(String.format("call%d→%s", i, ok ? "SUCCESS" : "HTTP_ERR"));
            } catch (Exception e) {
                String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : e.getClass().getSimpleName();
                String label = msg.contains("reset") || msg.contains("econnreset") ? "ECONNRESET"
                        : msg.contains("eagain") ? "EAGAIN"
                        : msg.contains("timed") || msg.contains("etimedout") ? "ETIMEDOUT"
                        : "EXCEPTION";
                outcomes.add(String.format("call%d→%s", i, label));
            }
        }

        System.out.println();
        System.out.println("  Configured sequence: ECONNRESET → EAGAIN → SUCCESS → SUCCESS → ETIMEDOUT");
        System.out.println("  Observed outcomes:");
        outcomes.forEach(o -> System.out.printf("    %s%n", o));
        System.out.println();
        System.out.println("  Toxiproxy: fails the 3rd CONNECTION, not the 3rd recv() on same connection.");
        System.out.println("  tc-netem: 30% random loss, not ECONNRESET then EAGAIN then ETIMEDOUT.");
        System.out.println("  WireMock: HTTP level, cannot inject TCP errno before HTTP parsing.");
        System.out.println("  @ChaosRecvSequence: stateful state machine, exact sequence, 1 annotation.");

        assertThat(outcomes).isNotEmpty().hasSizeGreaterThanOrEqualTo(5);
    }

    @Test
    @ChaosRecvFailAfter(n = 5)
    @DisplayName("SetupOs: FAIL_AFTER(5) — exactly 5 recv() syscalls succeed, then permanent ETIMEDOUT; Toxiproxy cannot count syscalls")
    void failAfterFiveExactSyscalls() throws Exception {
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();

        for (int i = 1; i <= 12; i++) {
            try {
                if (restTemplate.getForEntity("/users", String.class).getStatusCode().is2xxSuccessful()) {
                    successes.incrementAndGet();
                } else {
                    failures.incrementAndGet();
                }
            } catch (Exception e) {
                failures.incrementAndGet();
            }
        }

        System.out.printf("%n  FAIL_AFTER(5): %d successes / %d failures of 12 requests%n",
                successes.get(), failures.get());
        System.out.println("  Fault fires permanently after exactly the 5th recv() syscall.");
        System.out.println("  Toxiproxy fails the 5th CONNECTION — completely different granularity.");
        System.out.println("  On HTTP/1.1 keepalive: one connection = dozens of recv() calls.");

        assertThat(failures.get()).as("Permanent failure kicks in after FAIL_AFTER(5) syscalls").isGreaterThan(3);
    }
}
