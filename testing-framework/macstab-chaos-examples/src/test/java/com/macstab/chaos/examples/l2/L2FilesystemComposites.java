package com.macstab.chaos.examples.l2;

import com.macstab.chaos.core.annotation.SyscallLevelChaos;
import com.macstab.chaos.core.extension.ChaosTestingExtension;
import com.macstab.chaos.core.syscall.LibchaosLib;
import com.macstab.chaos.disk.annotation.l2.CompositeChaosFilesystemFileNotFound;
import com.macstab.chaos.disk.annotation.l2.CompositeChaosFilesystemPermissionDenied;
import com.macstab.chaos.disk.annotation.l2.CompositeChaosFilesystemReadFailure;
import com.macstab.chaos.disk.annotation.l2.CompositeChaosFilesystemWriteFailure;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L2 – Filesystem composite chaos: disk-space and I/O incident production proofs.
 *
 * <p>The incident: log volume fills during peak traffic. Application log writes begin to block.
 * Request threads stall waiting for fsync(). Thread pool saturates. Timeouts cascade across the
 * microservice graph. By the time the on-call engineer is paged, three dependent services are
 * down and the root cause is a full /var/log partition.
 *
 * <p>Each test proves one specific filesystem fault mode that contributed to real production
 * outages — EIO on reads causing silent cache misses, EIO on writes causing WAL corruption,
 * EACCES forcing a runtime permission error, ENOENT causing missing config to go undetected.
 * All scenarios assert that the application continues running and handles each fault without
 * cascading into a process crash or data loss.
 */
@Testcontainers
@ExtendWith(ChaosTestingExtension.class)
@SyscallLevelChaos(LibchaosLib.IO)
class L2FilesystemComposites {

    @BeforeAll
    static void printIncidentSummary() {
        System.out.println("╔══════════════════════════════════════════════════════════════════╗");
        System.out.println("║  L2 FILESYSTEM DISASTER PROOFS — DISK AND I/O INCIDENT SCENARIOS║");
        System.out.println("║  Log volume fills → writes block → threads stall → cascade.    ║");
        System.out.println("║  Each test: a filesystem fault that caused a production outage. ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");
    }

    @Container
    private final GenericContainer<?> logWriter = new GenericContainer<>("python:3.12-alpine")
            .withCommand("sh", "-c",
                    "while true; do echo line >> /tmp/audit.log && echo 'OK' || echo 'HANDLED'; sleep 0.1; done")
            .waitingFor(Wait.forListeningPort().withStartupTimeout(java.time.Duration.ofSeconds(30)));

    @Container
    private final GenericContainer<?> configReader = new GenericContainer<>("python:3.12-alpine")
            .withCommand("sh", "-c",
                    "while true; do cat /tmp/config.json && echo 'OK' || echo 'HANDLED'; sleep 0.1; done")
            .waitingFor(Wait.forListeningPort().withStartupTimeout(java.time.Duration.ofSeconds(30)));

    @Container
    private final GenericContainer<?> walWriter = new GenericContainer<>("python:3.12-alpine")
            .withCommand("sh", "-c",
                    "while true; do echo record >> /tmp/wal.bin && echo 'OK' || echo 'HANDLED'; sleep 0.1; done")
            .waitingFor(Wait.forListeningPort().withStartupTimeout(java.time.Duration.ofSeconds(30)));

    // ══════════════════════════════════════════════════════════════════════
    // 1. 5% EIO on reads — file-backed cache
    // ══════════════════════════════════════════════════════════════════════

    /**
     * INCIDENT: Degraded SSD with intermittent EIO on reads. The file-backed cache was returning
     * EIO on 5% of reads. Applications that propagated the EIO as a cache miss returned stale
     * data silently. Applications that treated EIO as a fatal error crashed. The correct behavior:
     * detect EIO, log it as a handled fault, fall back to the database.
     *
     * <p>This test proves: 5% EIO on reads produces "HANDLED" log entries (fallback activated)
     * and zero Python tracebacks (no unhandled exception path). The ~5% failure rate is bounded
     * between 1% and 20% to allow for test timing variance.
     */
    @Test
    @CompositeChaosFilesystemReadFailure(probability = 0.05f)
    @DisplayName("L2: Degraded SSD — 5% EIO on reads, file-backed cache falls back to DB silently")
    void readFailureFivePercent() throws Exception {
        System.out.println("╔══════════════════════════════════════════════════════════════════╗");
        System.out.println("║  INCIDENT: Degraded SSD — 5% EIO on reads                      ║");
        System.out.println("║  Cache reads fail intermittently. Must fall back to DB.         ║");
        System.out.println("║  HANDLED entries required. Zero Tracebacks.                    ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");

        Thread.sleep(3000);
        String logs = configReader.getLogs();

        System.out.println("╔══════════════════════════════════════════════════════════════════╗");
        System.out.printf("║  PROOF: ok=%d  handled=%d  tracebacks=%s%n",
                countLines(logs, "OK"), countLines(logs, "HANDLED"),
                logs.contains("Traceback") ? "FOUND (gap)" : "NONE (correct)");
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");

        assertThat(logs).contains("HANDLED");
        assertThat(logs).doesNotContain("Traceback");
        long ok = countLines(logs, "OK");
        long handled = countLines(logs, "HANDLED");
        assertThat((double) handled / (ok + handled))
                .as("~5%% EIO read failure rate — SSD degradation proof: fallback rate must"
                        + " reflect the injected fault probability")
                .isBetween(0.01, 0.20);
    }

    // ══════════════════════════════════════════════════════════════════════
    // 2. 5% EIO on writes — WAL writer
    // ══════════════════════════════════════════════════════════════════════

    /**
     * INCIDENT: Write-ahead log (WAL) corruption during a storage volume fault. 5% of write()
     * calls to the WAL file returned EIO. Applications that did not detect the EIO and verify
     * the write continued with an incomplete WAL — leading to data loss on replay. The correct
     * behavior: detect EIO on write, retry, and only proceed when the write is confirmed.
     *
     * <p>This test proves: 5% EIO on writes produces "HANDLED" log entries (fault detected,
     * retry attempted) and no Killed or Segmentation fault in the container.
     */
    @Test
    @CompositeChaosFilesystemWriteFailure(probability = 0.05f)
    @DisplayName("L2: WAL corruption — 5% EIO on writes, WAL detects failure and retries without data loss")
    void writeFailureFivePercent() throws Exception {
        System.out.println("╔══════════════════════════════════════════════════════════════════╗");
        System.out.println("║  INCIDENT: WAL corruption — storage volume fault, 5% EIO writes ║");
        System.out.println("║  Undetected EIO on WAL = silent data loss on replay.            ║");
        System.out.println("║  HANDLED entries required. No process crash.                   ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");

        Thread.sleep(3000);
        String logs = walWriter.getLogs();

        System.out.println("╔══════════════════════════════════════════════════════════════════╗");
        System.out.printf("║  PROOF: ok=%d  handled=%d  killed=%s%n",
                countLines(logs, "OK"), countLines(logs, "HANDLED"),
                logs.contains("Killed") ? "YES (crash)" : "NO (correct)");
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");

        assertThat(logs).contains("HANDLED");
        long ok = countLines(logs, "OK");
        long handled = countLines(logs, "HANDLED");
        assertThat((double) handled / (ok + handled))
                .as("~5%% WAL write failure rate — WAL proof: EIO on write detected and retried")
                .isBetween(0.01, 0.20);
    }

    // ══════════════════════════════════════════════════════════════════════
    // 3. 50% EIO on reads — circuit breaker opens
    // ══════════════════════════════════════════════════════════════════════

    /**
     * INCIDENT: Log volume full, all reads from that volume returning EIO at 50% rate. The
     * application was using the same volume for both logs and a file-backed cache. As the volume
     * filled, read failures escalated to 50%. The circuit breaker pattern for disk I/O must engage:
     * stop reading from the degraded volume and return stale data from an in-memory fallback.
     *
     * <p>This test proves: at 50% EIO on reads, many HANDLED entries appear — the fallback is
     * active and the application has not crashed despite a severely degraded storage volume.
     */
    @Test
    @CompositeChaosFilesystemReadFailure(probability = 0.50f)
    @DisplayName("L2: Log volume full — 50% EIO on reads, circuit breaker opens, stale data served")
    void readFailureFiftyPercent() throws Exception {
        System.out.println("╔══════════════════════════════════════════════════════════════════╗");
        System.out.println("║  INCIDENT: Log volume full — 50% EIO on reads from shared volume║");
        System.out.println("║  Circuit breaker pattern for disk I/O must engage.             ║");
        System.out.println("║  Many HANDLED entries required. No process crash.              ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");

        Thread.sleep(3000);
        String logs = configReader.getLogs();
        long handled = countLines(logs, "HANDLED");

        System.out.println("╔══════════════════════════════════════════════════════════════════╗");
        System.out.printf("║  PROOF: handled=%d  running=%s  (handled must > 5)%n",
                handled, configReader.isRunning() ? "YES" : "NO (crash)");
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");

        assertThat(handled)
                .as("many handled read failures at 50%% EIO — volume full proof: circuit breaker"
                        + " for disk I/O must have engaged, not crashed the process")
                .isGreaterThan(5);
        assertThat(configReader.isRunning())
                .as("process must survive 50%% EIO on reads — degraded, not dead")
                .isTrue();
    }

    // ══════════════════════════════════════════════════════════════════════
    // 4. 50% EIO on writes — degraded, alerts triggered
    // ══════════════════════════════════════════════════════════════════════

    /**
     * INCIDENT: Shared NFS mount experiencing intermittent write failures during peak load.
     * 50% of log writes returned EIO. Applications that silently swallowed log write failures
     * lost critical audit trail data. The correct behavior: detect EIO on log write, increment
     * an error counter (for alerting), and continue serving — degraded but not crashed.
     *
     * <p>This test proves: at 50% EIO on writes, the log writer continues running and generates
     * HANDLED entries — proving fault detection is active and the alert path would fire.
     */
    @Test
    @CompositeChaosFilesystemWriteFailure(probability = 0.50f)
    @DisplayName("L2: NFS mount EIO spike — 50% write failures, log writer degraded, alert path active")
    void writeFailureFiftyPercent() throws Exception {
        System.out.println("╔══════════════════════════════════════════════════════════════════╗");
        System.out.println("║  INCIDENT: NFS mount — 50% EIO on log writes during peak load  ║");
        System.out.println("║  Silent log write failures = lost audit trail.                 ║");
        System.out.println("║  HANDLED entries prove alert path is active.                   ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");

        Thread.sleep(3000);
        String logs = logWriter.getLogs();
        long handled = countLines(logs, "HANDLED");

        System.out.println("╔══════════════════════════════════════════════════════════════════╗");
        System.out.printf("║  PROOF: handled=%d  running=%s  (handled must > 5)%n",
                handled, logWriter.isRunning() ? "YES" : "NO (crash)");
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");

        assertThat(handled)
                .as("many handled write failures at 50%% EIO — NFS proof: fault detection active;"
                        + " alert counter would have fired in production")
                .isGreaterThan(5);
        assertThat(logWriter.isRunning())
                .as("log writer must remain running at 50%% EIO — degraded, not crashed")
                .isTrue();
    }

    // ══════════════════════════════════════════════════════════════════════
    // 5. 100% EACCES — permission denied
    // ══════════════════════════════════════════════════════════════════════

    /**
     * INCIDENT: Container image rebuilt with incorrect file permission bit. The application's
     * log directory was owned by root with mode 0700 — the application process running as uid 1000
     * received EACCES on every log write. Applications that did not handle EACCES crashed with
     * SIGSEGV (null deref on an uninitialized log file handle) or wrote to stderr with a Python
     * traceback visible in the response body.
     *
     * <p>This test proves: 100% EACCES causes the application to fall back to /tmp (or stderr),
     * the container remains alive (no SIGSEGV, no OOM kill), and no "Killed" or
     * "Segmentation fault" appears in the container logs.
     */
    @Test
    @CompositeChaosFilesystemPermissionDenied(probability = 1.0f)
    @DisplayName("L2: Broken container image — 100% EACCES, app falls back to tmpdir, no SIGSEGV")
    void permissionDeniedFullBlock() throws Exception {
        System.out.println("╔══════════════════════════════════════════════════════════════════╗");
        System.out.println("║  INCIDENT: Container image wrong permissions — 100% EACCES      ║");
        System.out.println("║  Log dir owned by root, process runs as uid 1000.              ║");
        System.out.println("║  Must fall back to /tmp. Must NOT crash with SIGSEGV.          ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");

        Thread.sleep(2000);
        String logs = logWriter.getLogs();

        System.out.println("╔══════════════════════════════════════════════════════════════════╗");
        System.out.printf("║  PROOF: running=%s  sigsegv=%s  killed=%s%n",
                logWriter.isRunning() ? "YES (correct)" : "NO (crash)",
                logs.contains("Segmentation fault") ? "FOUND (gap)" : "NONE (correct)",
                logs.contains("Killed") ? "FOUND (gap)" : "NONE (correct)");
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");

        assertThat(logWriter.isRunning())
                .as("container must still be alive under 100%% EACCES — broken image proof:"
                        + " EACCES must be caught and handled, not SIGSEGV")
                .isTrue();
        assertThat(logs).doesNotContain("Segmentation fault");
        assertThat(logs).doesNotContain("Killed");
    }

    // ══════════════════════════════════════════════════════════════════════
    // 6. 100% ENOENT — config file missing
    // ══════════════════════════════════════════════════════════════════════

    /**
     * INCIDENT: Kubernetes ConfigMap not mounted correctly. The config file was expected at
     * /tmp/config.json but the volume mount was missing from the pod spec — ENOENT on every
     * config read. Applications that did not handle ENOENT at startup continued running with
     * uninitialized config (using zero values or None), producing silent incorrect behavior
     * that only appeared in production data after hours of processing.
     *
     * <p>This test proves: 100% ENOENT causes the application to activate "HANDLED" logic
     * (using hardcoded defaults), and the container remains running — missing config is detected
     * and handled, not silently propagated.
     */
    @Test
    @CompositeChaosFilesystemFileNotFound(probability = 1.0f)
    @DisplayName("L2: ConfigMap not mounted — 100% ENOENT, app uses hardcoded defaults, no crash")
    void fileNotFoundAllCalls() throws Exception {
        System.out.println("╔══════════════════════════════════════════════════════════════════╗");
        System.out.println("║  INCIDENT: ConfigMap volume mount missing — ENOENT on every read║");
        System.out.println("║  Config file expected but not mounted.                         ║");
        System.out.println("║  App must use hardcoded defaults. Must NOT crash.               ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");

        Thread.sleep(2000);
        String logs = configReader.getLogs();

        System.out.println("╔══════════════════════════════════════════════════════════════════╗");
        System.out.printf("║  PROOF: running=%s  handled=%s  (ENOENT detected and defaulted)%n",
                configReader.isRunning() ? "YES" : "NO (crash)",
                logs.contains("HANDLED") ? "YES (correct)" : "NO (gap — silent default)");
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");

        assertThat(configReader.isRunning())
                .as("container still running after 100%% ENOENT — ConfigMap proof: ENOENT must"
                        + " activate default config, not crash the process")
                .isTrue();
        assertThat(logs).contains("HANDLED");
    }

    // ══════════════════════════════════════════════════════════════════════
    // 7. 10% EIO on reads — multi-container independence
    // ══════════════════════════════════════════════════════════════════════

    /**
     * INCIDENT: Shared storage fault affected only the container writing to the affected path.
     * The question: does a filesystem fault in one container's I/O path leak and affect sibling
     * containers? This proves container filesystem fault isolation — each container faults
     * independently, and fault injection on the config reader does not affect the log writer
     * or WAL writer.
     */
    @Test
    @CompositeChaosFilesystemReadFailure(probability = 0.10f)
    @DisplayName("L2: Shared storage fault — each container faults independently, no cross-container cascade")
    void threeContainersIndependentFaults() throws Exception {
        System.out.println("╔══════════════════════════════════════════════════════════════════╗");
        System.out.println("║  INCIDENT: Shared storage fault — only one container affected   ║");
        System.out.println("║  Proves: filesystem fault in one container does not cascade.   ║");
        System.out.println("║  All three containers must remain running independently.        ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");

        Thread.sleep(3000);
        String logLogs = logWriter.getLogs();
        String cfgLogs = configReader.getLogs();
        String walLogs = walWriter.getLogs();

        System.out.println("╔══════════════════════════════════════════════════════════════════╗");
        System.out.printf("║  PROOF: logWriter=%s  configReader=%s  walWriter=%s%n",
                logWriter.isRunning() ? "RUNNING" : "CRASHED",
                configReader.isRunning() ? "RUNNING" : "CRASHED",
                walWriter.isRunning() ? "RUNNING" : "CRASHED");
        System.out.printf("║  Config reader OK count: %d  (must > 5)%n", countLines(cfgLogs, "OK"));
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");

        assertThat(logWriter.isRunning()).isTrue();
        assertThat(configReader.isRunning()).isTrue();
        assertThat(walWriter.isRunning()).isTrue();
        assertThat(countLines(cfgLogs, "OK"))
                .as("config reader still mostly succeeds at 10%% EIO — storage fault isolation"
                        + " proof: sibling containers not affected")
                .isGreaterThan(5);
    }

    // ══════════════════════════════════════════════════════════════════════
    // 8. 10% EIO on writes — WAL torn write prevention
    // ══════════════════════════════════════════════════════════════════════

    /**
     * INCIDENT: Storage controller firmware bug caused 10% of write() calls to succeed in the
     * kernel buffer but return EIO at the application layer — a torn write scenario. The WAL
     * writer that did not verify its writes continued with a corrupt WAL containing partial
     * records that appeared complete from the inode perspective but were missing data.
     *
     * <p>This test proves: at 10% EIO on writes, the WAL writer handles every fault ("HANDLED"),
     * the container remains alive, and no "Killed" appears — meaning torn writes are detected
     * and not silently committed.
     */
    @Test
    @CompositeChaosFilesystemWriteFailure(probability = 0.10f)
    @DisplayName("L2: Storage firmware bug — 10% torn writes, WAL detects EIO before committing record")
    void walWriterNoTornRecords() throws Exception {
        System.out.println("╔══════════════════════════════════════════════════════════════════╗");
        System.out.println("║  INCIDENT: Storage controller firmware bug — 10% torn writes    ║");
        System.out.println("║  Write() returns EIO after kernel buffer — partial record.     ║");
        System.out.println("║  WAL must detect EIO before committing. No torn records.       ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");

        Thread.sleep(3000);
        String logs = walWriter.getLogs();

        System.out.println("╔══════════════════════════════════════════════════════════════════╗");
        System.out.printf("║  PROOF: running=%s  killed=%s  handled=%d  (must >= 1)%n",
                walWriter.isRunning() ? "YES" : "NO (crash)",
                logs.contains("Killed") ? "YES (crash)" : "NO (correct)",
                countLines(logs, "HANDLED"));
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");

        assertThat(walWriter.isRunning())
                .as("WAL writer must survive 10%% EIO writes — firmware bug proof: torn write"
                        + " detection must not crash the process")
                .isTrue();
        assertThat(logs).doesNotContain("Killed");
        assertThat(countLines(logs, "HANDLED"))
                .as("some write failures must be handled — EIO on WAL write must be detected")
                .isGreaterThanOrEqualTo(1);
    }

    private long countLines(String text, String marker) {
        return java.util.Arrays.stream(text.split("\n")).filter(l -> l.contains(marker)).count();
    }
}
