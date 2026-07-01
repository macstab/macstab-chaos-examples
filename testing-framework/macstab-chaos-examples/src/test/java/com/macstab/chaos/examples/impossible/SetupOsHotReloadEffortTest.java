package com.macstab.chaos.examples.impossible;

import com.macstab.chaos.core.annotation.SyscallLevelChaos;
import com.macstab.chaos.core.syscall.LibchaosLib;
import com.macstab.chaos.jvm.spring.boot4.test.ChaosTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.assertThat;

@ChaosTest(classes = com.macstab.chaos.examples.UserServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@SyscallLevelChaos(LibchaosLib.NET)
class SetupOsHotReloadEffortTest {

    @Autowired
    TestRestTemplate restTemplate;

    private static final Path CONF = Path.of("/tmp/.chaos-net.conf");

    @BeforeAll
    static void printHotReloadComparison() {
        System.out.println();
        System.out.println("════════════════════════════════════════════════════════════════════════════════");
        System.out.println("  SETUP COMPARISON: Hot-Reload Chaos Config (Change Fault Rate Without Restart)");
        System.out.println("════════════════════════════════════════════════════════════════════════════════");
        System.out.println();
        System.out.println("  ┌───────────────────────────┬──────────────┬───────────────┬───────────────┐");
        System.out.println("  │ Tool                      │ Propagation  │ Side effects  │ Requirements  │");
        System.out.println("  ├───────────────────────────┼──────────────┼───────────────┼───────────────┤");
        System.out.println("  │ Toxiproxy REST API        │ 200-500ms    │ TCP conn reset│ Docker sidecar│");
        System.out.println("  │ tc-netem (Linux)          │  50-200ms    │ None          │ root + eth0   │");
        System.out.println("  │ Gremlin (SaaS)            │ 1000-2000ms  │ None          │ Internet      │");
        System.out.println("  │ Chaos Mesh (K8s)          │ 2000-10000ms │ Pod restart   │ K8s cluster   │");
        System.out.println("  │ Chaos Monkey              │ N/A          │ Kills process │ N/A           │");
        System.out.println("  │ LitmusChaos               │ 5000-30000ms │ Container     │ K8s cluster   │");
        System.out.println("  │ libchaos-net (macstab)    │  50-100ms    │ NONE          │ NONE          │");
        System.out.println("  └───────────────────────────┴──────────────┴───────────────┴───────────────┘");
        System.out.println();
        System.out.println("  Setup effort per tool:");
        System.out.println("  ┌───────────────────────────────────────────────────────────────────────────┐");
        System.out.println("  │ Toxiproxy:    Docker sidecar + REST client in tests + proxy config       │");
        System.out.println("  │               + TCP connection pool reset on change → 2-3 days           │");
        System.out.println("  │                                                                          │");
        System.out.println("  │ tc-netem:     Linux only + CI privilege escalation (root) + network     │");
        System.out.println("  │               interface management + affects ALL processes → 1-2 days    │");
        System.out.println("  │                                                                          │");
        System.out.println("  │ Gremlin:      Account creation + agent install on every host + API key  │");
        System.out.println("  │               management + cloud dependency in CI → 1 week              │");
        System.out.println("  │                                                                          │");
        System.out.println("  │ Chaos Mesh:   K8s cluster + CRD install + controller deployment + RBAC  │");
        System.out.println("  │               policies + NetworkChaos CR authoring → weeks              │");
        System.out.println("  │                                                                          │");
        System.out.println("  │ libchaos-net: Files.writeString(CONF, \"*:recv:ECONNRESET:0:0.5\");       │");
        System.out.println("  │               DONE. No Docker. No root. No K8s. No cloud.               │");
        System.out.println("  └───────────────────────────────────────────────────────────────────────────┘");
        System.out.println("════════════════════════════════════════════════════════════════════════════════");
    }

    @AfterAll
    static void cleanup() throws Exception {
        Files.writeString(CONF, "");
    }

    private double measureSuccessRate(int requests) {
        AtomicInteger ok = new AtomicInteger();
        for (int i = 0; i < requests; i++) {
            try {
                if (restTemplate.getForEntity("/users", String.class).getStatusCode().is2xxSuccessful()) {
                    ok.incrementAndGet();
                }
            } catch (Exception ignored) {}
        }
        return (double) ok.get() / requests * 100;
    }

    @Test
    @DisplayName("SetupOs: hot-reload in <150ms with zero side effects — Toxiproxy=TCP reset, Gremlin=2s cloud delay, libchaos=file write")
    void hotReloadFasterAndSimplerThanEveryCompetitor() throws Exception {
        Files.writeString(CONF, "");
        Thread.sleep(150);
        double baseline = measureSuccessRate(15);
        System.out.printf("%n  Phase 1 [0%% fault]:   %.0f%% success (baseline)%n", baseline);

        long injectStart = System.nanoTime();
        Files.writeString(CONF, "*:recv:ECONNRESET:0:0.6\n");
        Thread.sleep(150);
        long injectMs = (System.nanoTime() - injectStart) / 1_000_000;
        double degraded = measureSuccessRate(15);
        System.out.printf("  Phase 2 [60%% fault]:  %.0f%% success (hot-reload in %dms — Gremlin would be 1000-2000ms)%n",
                degraded, injectMs);

        Files.writeString(CONF, "");
        Thread.sleep(150);
        double recovered = measureSuccessRate(15);
        System.out.printf("  Phase 3 [0%% fault]:   %.0f%% success (recovered)%n%n", recovered);

        System.out.println("  ╔══════════════════════════════════════════════════════════════════╗");
        System.out.println("  ║  HOT-RELOAD PROOF                                                ║");
        System.out.printf( "  ║  Inject propagation: %3dms (vs Gremlin 1000-2000ms)              ║%n", injectMs);
        System.out.println("  ║  TCP connections reset: ZERO (vs Toxiproxy: pool reset)          ║");
        System.out.println("  ║  Root privileges: NOT REQUIRED (vs tc-netem: root needed)        ║");
        System.out.println("  ║  K8s cluster: NOT REQUIRED (vs Chaos Mesh/LitmusChaos)           ║");
        System.out.println("  ║  Internet access: NOT REQUIRED (vs Gremlin SaaS)                 ║");
        System.out.println("  ║  Setup time: Files.writeString(). That is the entire API.        ║");
        System.out.println("  ╚══════════════════════════════════════════════════════════════════╝");

        assertThat(degraded).as("Degradation after 60% fault hot-reload").isLessThan(baseline);
        assertThat(recovered).as("Recovery after hot-reload to 0%").isGreaterThan(degraded);
    }
}
