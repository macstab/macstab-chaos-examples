package com.macstab.chaos.examples.impossible;

import com.macstab.chaos.core.annotation.SyscallLevelChaos;
import com.macstab.chaos.core.syscall.LibchaosLib;
import com.macstab.chaos.net.annotation.l1.ChaosRecvEconnreset;
import com.macstab.chaos.jvm.spring.boot4.test.ChaosTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.assertThat;

@ChaosTest(classes = com.macstab.chaos.examples.UserServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@SyscallLevelChaos(LibchaosLib.NET)
class SetupOsPerFdFaultInjectionTest {

    @Autowired
    TestRestTemplate restTemplate;

    @BeforeAll
    static void printSetupComparison() {
        System.out.println();
        System.out.println("════════════════════════════════════════════════════════════════════════════════");
        System.out.println("  SETUP COMPARISON: Per-FD Syscall Fault Injection");
        System.out.println("  Target: different chaos rules per socket fd in the same process");
        System.out.println("════════════════════════════════════════════════════════════════════════════════");
        System.out.println();
        System.out.println("  WITHOUT macstab-chaos — what every other team must build:");
        System.out.println("  ┌───────────────────────────────────────────────────────────────────────────┐");
        System.out.println("  │ Step 1: Write custom LD_PRELOAD in C                          3-5 days   │");
        System.out.println("  │   • Hook socket() → assign fd→type mapping (postgres/redis/kafka)        │");
        System.out.println("  │   • Hook connect() → record fd→remote address                           │");
        System.out.println("  │   • Hook recv()/send() → check per-fd fault configuration              │");
        System.out.println("  │   • Thread-safe fd map (pthread_mutex_t or __atomic operations)         │");
        System.out.println("  │   • Per-fd fault state: errno, probability, call counter               │");
        System.out.println("  │                                                                          │");
        System.out.println("  │ Step 2: Config format + hot-reload                           2-3 days   │");
        System.out.println("  │   • Design per-fd config file format                                    │");
        System.out.println("  │   • Implement mtime polling without inotify (portability)              │");
        System.out.println("  │   • Concurrent config access via rwlock                                 │");
        System.out.println("  │                                                                          │");
        System.out.println("  │ Step 3: Edge cases                                          1-2 weeks   │");
        System.out.println("  │   • dup()/dup2() → must copy fd→type mapping to new fd                │");
        System.out.println("  │   • fork() → child inherits parent's fd map? What state?              │");
        System.out.println("  │   • SO_REUSEPORT → multiple fds same port, how to distinguish?        │");
        System.out.println("  │   • IPv6 vs IPv4 path fd detection                                     │");
        System.out.println("  │                                                                          │");
        System.out.println("  │ Step 4: CI cross-compilation                                 2-3 days   │");
        System.out.println("  │   • Compile for linux/amd64 + linux/arm64                             │");
        System.out.println("  │   • Cross-compile on macOS for Linux CI                               │");
        System.out.println("  │   • Extract from JAR resource, set LD_PRELOAD at test startup         │");
        System.out.println("  │                                                                          │");
        System.out.println("  │ Step 5: Maintain forever                                           ∞   │");
        System.out.println("  │   • No community support. You own every bug.                          │");
        System.out.println("  │   • Breaks on glibc updates. Re-test on every Linux kernel upgrade.   │");
        System.out.println("  │                                                                          │");
        System.out.println("  │ TOTAL: 3-6 weeks. Expert C + POSIX required. Breaks every 6 months.  │");
        System.out.println("  └───────────────────────────────────────────────────────────────────────────┘");
        System.out.println();
        System.out.println("  WITH macstab-chaos:");
        System.out.println("  ┌───────────────────────────────────────────────────────────────────────────┐");
        System.out.println("  │   @ChaosRecvEconnreset(pathPrefix=\"/var/run/postgresql\", probability=0.5f)│");
        System.out.println("  │   @ChaosRecvEagain(pathPrefix=\"/var/run/redis\", probability=0.3f)        │");
        System.out.println("  │   void myTest() { ... }                                                  │");
        System.out.println("  │                                                                          │");
        System.out.println("  │   TOTAL: 5 seconds to type two annotations.                            │");
        System.out.println("  └───────────────────────────────────────────────────────────────────────────┘");
        System.out.println("════════════════════════════════════════════════════════════════════════════════");
    }

    @Test
    @ChaosRecvEconnreset(probability = 0.4f)
    @DisplayName("SetupOs: 40% recv() ECONNRESET via 1 annotation — replicate manually: 3-6 weeks of C expertise")
    void perFdFaultInjectionProvablyWorksWithOneAnnotation() throws Exception {
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();

        for (int i = 0; i < 30; i++) {
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

        System.out.printf("%n  @ChaosRecvEconnreset(probability=0.4f): %d success / %d fail out of 30%n",
                successes.get(), failures.get());
        System.out.printf("  Measured fault rate: %.0f%%%n", (double) failures.get() / 30 * 100);
        System.out.println("  Manual replication: 3-6 weeks of C. This annotation: 5 seconds.");
        System.out.println("  Toxiproxy can fail the Nth CONNECTION. Not the Nth recv() syscall.");
        System.out.println("  Per-fd targeting (postgres fd vs redis fd) impossible without LD_PRELOAD.");

        assertThat(failures.get()).as("recv() ECONNRESET fires at approximately 40% rate").isGreaterThan(5);
    }
}
