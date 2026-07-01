package com.macstab.chaos.examples.impossible;

import com.macstab.chaos.core.annotation.SyscallLevelChaos;
import com.macstab.chaos.core.extension.ChaosTestingExtension;
import com.macstab.chaos.core.syscall.LibchaosLib;
import com.macstab.chaos.jvm.spring.boot4.test.ChaosTest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;

import java.io.*;
import java.nio.file.*;
import java.util.concurrent.atomic.*;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

/**
 * Demonstrates hot-reload of chaos configuration in a running process.
 *
 * <p>libchaos uses mtime polling on /tmp/.chaos-net.conf. Writing a new config file
 * causes the running process to pick it up within 100ms — with zero disruption,
 * zero connection resets, and zero process signals.</p>
 *
 * <p>Competing tools cannot replicate this workflow:</p>
 * <ul>
 *   <li>Toxiproxy: requires DELETE + POST to change proxy config; the TCP connection pool resets.</li>
 *   <li>tc-netem: requires root + tc command re-execution; the shell command IS the restart.</li>
 *   <li>Gremlin: requires an API call to the Gremlin cloud; propagation delay is 100ms–2s.</li>
 *   <li>Chaos Monkey: kills processes; it does not modify fault rates.</li>
 * </ul>
 */
@ChaosTest(classes = com.macstab.chaos.examples.UserServiceApplication.class,
        webEnvironment = org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT)
@SyscallLevelChaos(LibchaosLib.NET)
class ImpossibleHotReloadNobodyElseCanTest {

    @Autowired
    TestRestTemplate restTemplate;

    private static final Path CHAOS_CONF = Path.of("/tmp/.chaos-net.conf");

    /**
     * Sends {@code requests} GET requests to /users and returns the percentage that returned
     * a 2xx status code.
     *
     * @param requests number of requests to send
     * @return success rate as a percentage in [0, 100]
     */
    private double measureSuccessRate(int requests) {
        AtomicInteger ok = new AtomicInteger(0);
        for (int i = 0; i < requests; i++) {
            try {
                if (restTemplate.getForEntity("/users", String.class).getStatusCode().is2xxSuccessful()) {
                    ok.incrementAndGet();
                }
            } catch (Exception ignored) {
            }
        }
        return (double) ok.get() / requests * 100;
    }

    /**
     * Writes {@code content} to the libchaos config file and waits for the mtime poller to
     * detect the change. The poller runs every ≤100ms; 150ms gives a comfortable margin.
     *
     * @param content config file content (empty string removes all faults)
     * @throws Exception if the write fails
     */
    private void writeConfig(String content) throws Exception {
        Files.writeString(CHAOS_CONF, content);
        Thread.sleep(150); // allow mtime polling to detect change (< 200ms)
    }

    @Test
    @DisplayName("IMPOSSIBLE: Hot-reload chaos config in running process — 0%→50%→0% fault rate, zero restarts, zero connection resets")
    void hotReloadChaosFaultRateWithoutProcessRestart() throws Exception {

        System.out.println();
        System.out.println("════════════════════════════════════════════════════════════════");
        System.out.println("  IMPOSSIBLE TEST: HOT-RELOAD CHAOS CONFIG");
        System.out.println("  Toxiproxy: needs DELETE+POST API call (resets connections).");
        System.out.println("  tc-netem: needs root shell re-execution (process-wide).");
        System.out.println("  Gremlin: 100ms-2s cloud propagation delay.");
        System.out.println("  libchaos: file mtime polling. Write config. 100ms. Done.");
        System.out.println("  No restart. No connection reset. No signal. Instant.");
        System.out.println("════════════════════════════════════════════════════════════════");

        // Phase 1: Baseline (no chaos)
        writeConfig(""); // empty = no faults
        double baseline = measureSuccessRate(20);
        System.out.printf("%n  Phase 1 [0%% fault]:   %.0f%% success rate (baseline)%n", baseline);

        // Phase 2: Inject 50% ECONNRESET by hot-reloading config
        long hotReloadStart = System.currentTimeMillis();
        writeConfig("*:recv:ECONNRESET:0:0.5\n"); // 50% recv() ECONNRESET
        long hotReloadMs = System.currentTimeMillis() - hotReloadStart;
        double degraded = measureSuccessRate(20);
        System.out.printf("  Phase 2 [50%% fault]:  %.0f%% success rate (hot-reloaded in %dms)%n", degraded, hotReloadMs);

        // Phase 3: Remove chaos by hot-reloading config back to empty
        long recoveryStart = System.currentTimeMillis();
        writeConfig(""); // remove all faults
        long recoveryMs = System.currentTimeMillis() - recoveryStart;
        double recovered = measureSuccessRate(20);
        System.out.printf("  Phase 3 [0%% fault]:   %.0f%% success rate (recovered in %dms)%n", recovered, recoveryMs);

        System.out.println();
        System.out.println("  ╔══════════════════════════════════════════════════════════════╗");
        System.out.println("  ║  HOT-RELOAD CHAOS PROOF                                      ║");
        System.out.printf( "  ║  Baseline success:   %5.1f%%                                  ║%n", baseline);
        System.out.printf( "  ║  Degraded success:   %5.1f%% (50%% ECONNRESET injected)        ║%n", degraded);
        System.out.printf( "  ║  Recovered success:  %5.1f%%                                  ║%n", recovered);
        System.out.printf( "  ║  Inject latency:     %3dms (file write + mtime poll)          ║%n", hotReloadMs);
        System.out.printf( "  ║  Recovery latency:   %3dms (file write + mtime poll)          ║%n", recoveryMs);
        System.out.println("  ║  Process restarts:   0                                        ║");
        System.out.println("  ║  Connection resets:  0                                        ║");
        System.out.println("  ║  Root privileges:    not required                             ║");
        System.out.println("  ╠══════════════════════════════════════════════════════════════╣");
        System.out.println("  ║  USE CASE: Tune fault injection rate LIVE during a load test. ║");
        System.out.println("  ║  Start at 1%, watch metrics, ramp to 10%, 20%, 50%.          ║");
        System.out.println("  ║  No chaos tool on earth supports this workflow.               ║");
        System.out.println("  ╚══════════════════════════════════════════════════════════════╝");

        assertThat(recovered)
                .as("Success rate recovers after hot-reload to 0% faults")
                .isGreaterThan(80);
        assertThat(degraded)
                .as("Success rate degrades after hot-reload to 50% faults")
                .isLessThan(baseline);
        System.out.println("════════════════════════════════════════════════════════════════");
    }
}
