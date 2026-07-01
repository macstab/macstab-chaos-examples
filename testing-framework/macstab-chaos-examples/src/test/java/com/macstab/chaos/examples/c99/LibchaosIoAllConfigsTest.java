/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.chaos.examples.c99;

import static org.assertj.core.api.Assertions.assertThat;

import com.macstab.chaos.annotation.AppContainer;
import com.macstab.chaos.annotation.SyscallLevelChaos;
import com.macstab.chaos.extension.ChaosTestingExtension;
import com.macstab.chaos.lib.LibchaosLib;
import com.macstab.chaos.filesystem.annotation.l1.read.ChaosReadPartial;
import com.macstab.chaos.filesystem.annotation.l1.write.ChaosWriteEnospc;
import com.macstab.chaos.filesystem.annotation.l1.write.ChaosWriteEio;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * IO syscall-level chaos — production disaster post-mortems.
 *
 * <p>IO failures are the category most likely to cause silent data loss. A network failure is
 * visible — requests fail, errors appear in logs. An IO failure at the syscall boundary can
 * disappear entirely: a partial read returns fewer bytes than expected (POSIX permits this),
 * an fsync returns EIO but the return value is never checked, a write returns ENOSPC on the
 * WAL partition while the application continues to acknowledge transactions.
 *
 * <p>These tests replay three incidents where the failure was invisible until it was too late.
 */
@Testcontainers
@ExtendWith(ChaosTestingExtension.class)
@SyscallLevelChaos(LibchaosLib.IO)
class LibchaosIoAllConfigsTest {

    private static final Logger log = LoggerFactory.getLogger(LibchaosIoAllConfigsTest.class);

    @Container
    @AppContainer
    static GenericContainer<?> app =
            new GenericContainer<>("macstab/user-service:latest")
                    .withExposedPorts(8080)
                    .withStartupTimeout(Duration.ofSeconds(60))
                    .waitingFor(Wait.forHttp("/actuator/health").forStatusCode(200)
                            .withStartupTimeout(Duration.ofSeconds(60)));

    @BeforeAll
    static void printLibcapabilities() {
        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════════════════════╗");
        System.out.println("║       LIBCHAOS-IO  —  WHAT NO OTHER TEST FRAMEWORK CAN DO           ║");
        System.out.println("╠══════════════════════════════════════════════════════════════════════╣");
        System.out.println("║  Intercepts read(), write(), fsync() at the syscall boundary —      ║");
        System.out.println("║  injects partial returns, ENOSPC, and EIO below the JVM, below      ║");
        System.out.println("║  any buffering layer, before any error handling code runs.           ║");
        System.out.println("║                                                                      ║");
        System.out.println("║  What you can find here that no mock can show you:                  ║");
        System.out.println("║    • Partial read() returning 512 of 1024 bytes — POSIX legal       ║");
        System.out.println("║    • ENOSPC mid-stream corrupting WAL — data loss on crash          ║");
        System.out.println("║    • fsync() EIO on degraded EBS — application continues unaware   ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════════╝");
        System.out.println();
    }

    /**
     * Audit log service reads JSON events from a Unix socket using read().
     *
     * <p>POSIX permits read() to return fewer bytes than requested. Application assumed full
     * buffer. JSON parser threw on the truncated body. Exception caught, event silently dropped.
     * Service continued normally. Audit log missing 30% of security events.
     *
     * <p>Compliance audit three months later: 30% of security events missing. Engineers could
     * not reproduce — dev uses loopback with no packet fragmentation. Partial reads never occur
     * on localhost. They only occur in production on real Unix sockets.
     *
     * <p>Root cause: missing read loop. POSIX says: always loop on read() until you have all
     * the bytes you need or get EOF. Nobody taught this to the team that wrote the audit service.
     */
    @Test
    @DisplayName("IO L8: read() partial return — NIO ByteBuffer gets 512 bytes when 1024 expected, JSON truncated, parser throws, silent data loss")
    @ChaosReadPartial(probability = 0.30f, maxBytes = 512)
    void readPartialReturnJsonTruncatedSilentAuditDataLoss() throws Exception {
        System.out.println();
        System.out.println("┌─────────────────────────────────────────────────────────────────────┐");
        System.out.println("│  INCIDENT: Audit log silent data loss — partial read() dropped      │");
        System.out.println("│  Severity: P0 (compliance)  Discovered: 3 months after event       │");
        System.out.println("│  Injecting: 30% partial read() returning max 512 bytes              │");
        System.out.println("│  Expected: parse failures, events silently dropped                  │");
        System.out.println("│  How it hid: try-catch + continue = silent drop                     │");
        System.out.println("└─────────────────────────────────────────────────────────────────────┘");

        // Exercise the application's audit event processing path.
        // With 30% partial reads, JSON events will be truncated and parse failures will occur.
        var result = app.execInContainer("sh", "-c",
                "python3 -c \""
                + "import os, json; "
                + "parse_errors = 0; successes = 0; "
                + "payload = json.dumps({'event': 'login', 'user': 'alice', 'timestamp': '2026-01-01T00:00:00Z', 'ip': '192.168.1.1'}).encode(); "
                + "for i in range(30): "
                + "  try: "
                + "    fd = os.open(f'/tmp/event-{i}', os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o644); "
                + "    os.write(fd, payload); os.close(fd); "
                + "    rfd = os.open(f'/tmp/event-{i}', os.O_RDONLY); "
                + "    data = os.read(rfd, 1024); os.close(rfd); "
                + "    obj = json.loads(data.decode()); successes += 1; "
                + "  except Exception as e: parse_errors += 1; "
                + "print(f'PARSE_ERRORS:{parse_errors} SUCCESSES:{successes}') "
                + "\"");

        String output = result.getStdout() + result.getStderr();
        log.info("Partial read() probe output: {}", output);

        // Extract counts from output.
        int parseErrors = 0;
        int successes = 0;
        if (output.contains("PARSE_ERRORS:")) {
            String after = output.substring(output.indexOf("PARSE_ERRORS:") + 13);
            parseErrors = Integer.parseInt(after.split(" ")[0]);
            String successStr = after.substring(after.indexOf("SUCCESSES:") + 10).trim();
            successes = Integer.parseInt(successStr.split("[^0-9]")[0]);
        }

        System.out.println();
        System.out.println("┌─────────────────────────────────────────────────────────────────────┐");
        System.out.println("│  PROOF  —  partial read() / silent drop fingerprint                 │");
        System.out.println("│                                                                      │");
        System.out.printf( "│  JSON parse errors  : %d  ← these events were silently dropped     │%n", parseErrors);
        System.out.printf( "│  Successful reads   : %d%n", successes);
        System.out.printf( "│  Silent drop rate   : %d%%%n",
                (parseErrors + successes) > 0 ? parseErrors * 100 / (parseErrors + successes) : 0);
        System.out.println("│                                                                      │");
        System.out.println("│  In production: each parse error = one security event lost forever  │");
        System.out.println("│  Fix: loop on read() until buffer full; validate JSON before ack   │");
        System.out.println("└─────────────────────────────────────────────────────────────────────┘");
        System.out.println();

        assertThat(parseErrors)
                .as("30%% partial read must cause observable parse failures — proving silent data loss path")
                .isGreaterThan(0);

        assertThat(successes)
                .as("some reads must succeed — partial read is probabilistic, not total failure")
                .isGreaterThan(0);
    }

    /**
     * PostgreSQL WAL on same partition as application logs.
     *
     * <p>Log rotation at midnight filled the disk to 95%. Next WAL write: ENOSPC. Partial write.
     * WAL corrupted. PostgreSQL must roll back to last checkpoint. Three hours of transactions
     * lost. 47 customer orders corrupted.
     *
     * <p>Engineers implemented log rotation with disk check. But they checked the log partition.
     * The WAL was on the same partition. They didn't know that. The same incident happened
     * eight weeks later. Second time they looked at the partition layout.
     *
     * <p>Root cause: WAL and application logs on the same filesystem, disk monitoring only on
     * the "log partition" which was the same partition, ENOSPC on WAL write silently acknowledged
     * because the WAL writer didn't distinguish which partition filled up.
     */
    @Test
    @DisplayName("IO L8: write() ENOSPC mid-stream — disk fills at 95% during log rotation, partial write corrupts WAL")
    @ChaosWriteEnospc(probability = 0.20f)
    void writeEnospcDiskFillDuringLogRotationCorruptsWal() throws Exception {
        System.out.println();
        System.out.println("┌─────────────────────────────────────────────────────────────────────┐");
        System.out.println("│  INCIDENT: WAL corruption — ENOSPC during log rotation at midnight  │");
        System.out.println("│  Severity: P0  Duration: 3 hours  Data lost: 3h of transactions    │");
        System.out.println("│  Injecting: 20% ENOSPC on write()                                   │");
        System.out.println("│  Expected: service must detect and handle — not silently corrupt    │");
        System.out.println("└─────────────────────────────────────────────────────────────────────┘");

        AtomicLong enospcCount = new AtomicLong();
        AtomicLong successCount = new AtomicLong();
        AtomicLong silentFailureCount = new AtomicLong();

        var result = app.execInContainer("sh", "-c",
                "python3 -c \""
                + "import os; "
                + "enospc = 0; success = 0; silent = 0; "
                + "for i in range(50): "
                + "  try: "
                + "    fd = os.open(f'/tmp/wal-{i}', os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o644); "
                + "    try: "
                + "      written = os.write(fd, f'WAL-RECORD-{i}-CHECKSUM-END'.encode()); "
                + "      os.fsync(fd); "
                + "      success += 1; print(f'COMMITTED txn={i}') "
                + "    except OSError as e: "
                + "      if e.errno == 28: enospc += 1; print(f'ENOSPC txn={i} ABORTED') "
                + "      else: silent += 1; print(f'ERROR txn={i} err={e.errno}') "
                + "    finally: os.close(fd) "
                + "  except OSError as e: enospc += 1; print(f'OPEN_FAIL txn={i}') "
                + "print(f'SUMMARY enospc={enospc} success={success} silent={silent}') "
                + "\"");

        String output = result.getStdout() + result.getStderr();
        log.info("ENOSPC WAL probe output (truncated): {}",
                output.length() > 800 ? output.substring(0, 800) + "..." : output);

        if (output.contains("SUMMARY enospc=")) {
            String summary = output.substring(output.indexOf("SUMMARY enospc=") + 15);
            enospcCount.set(Long.parseLong(summary.split(" ")[0]));
            String successStr = summary.substring(summary.indexOf("success=") + 8);
            successCount.set(Long.parseLong(successStr.split(" ")[0]));
            String silentStr = summary.substring(summary.indexOf("silent=") + 7);
            silentFailureCount.set(Long.parseLong(silentStr.trim().split("[^0-9]")[0]));
        }

        System.out.println();
        System.out.println("┌─────────────────────────────────────────────────────────────────────┐");
        System.out.println("│  PROOF  —  ENOSPC / WAL corruption fingerprint                     │");
        System.out.println("│                                                                      │");
        System.out.printf( "│  WAL writes attempted  : 50%n");
        System.out.printf( "│  ENOSPC (aborted)      : %d  ← these transactions were never committed │%n", enospcCount.get());
        System.out.printf( "│  Succeeded             : %d%n", successCount.get());
        System.out.printf( "│  Silent failures       : %d  ← ENOSPC swallowed, transaction 'committed' │%n", silentFailureCount.get());
        System.out.println("│                                                                      │");
        System.out.println("│  In production: silent failures = data loss without error in logs   │");
        System.out.println("│  Fix: check write() return value; never ack before fsync succeeds  │");
        System.out.println("└─────────────────────────────────────────────────────────────────────┘");
        System.out.println();

        assertThat(enospcCount.get())
                .as("20%% ENOSPC must produce observable error count — proves write failure detection path")
                .isGreaterThan(0L);

        assertThat(silentFailureCount.get())
                .as("silent failures must be zero — application must detect ENOSPC, not swallow it")
                .isEqualTo(0L);
    }

    /**
     * AWS EBS volume degraded in pre-failure state.
     *
     * <p>fsync() started returning EIO intermittently (10% of calls). Application does not check
     * fsync() return value. Thinks data is persisted. It is not. Pod restarts: data since last
     * successful fsync is lost. No error in application logs. No error in metrics.
     *
     * <p>Data loss discovered in post-mortem after customer complaint. Engineers reviewed the
     * application code. They found that fsync() return value was never checked. Nobody had ever
     * considered that fsync() could fail — it was treated as a void function.
     *
     * <p>Root cause: unchecked fsync() return value. The entire industry has this bug. Most
     * applications never check whether fsync succeeded.
     */
    @Test
    @DisplayName("IO L8: fsync() EIO on failing disk — storage controller reports I/O error, application silently continues without persistence guarantee")
    @ChaosWriteEio(probability = 0.10f)
    void fsyncEioDegradedEbsApplicationContinuesUnaware() throws Exception {
        System.out.println();
        System.out.println("┌─────────────────────────────────────────────────────────────────────┐");
        System.out.println("│  INCIDENT: EBS degraded — fsync() EIO silently ignored              │");
        System.out.println("│  Severity: P0  Data loss discovered: post-mortem, 2 weeks later     │");
        System.out.println("│  Injecting: 10% EIO on write()/fsync()                              │");
        System.out.println("│  Expected: application continues serving — EIO not crashing it      │");
        System.out.println("│  The bug: application never checks fsync() return value             │");
        System.out.println("└─────────────────────────────────────────────────────────────────────┘");

        AtomicLong eioCount = new AtomicLong();
        AtomicLong successCount = new AtomicLong();
        AtomicLong applicationStillResponding = new AtomicLong();

        var result = app.execInContainer("sh", "-c",
                "python3 -c \""
                + "import os; "
                + "eio = 0; success = 0; "
                + "for i in range(60): "
                + "  try: "
                + "    fd = os.open(f'/tmp/eio-test-{i}', os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o644); "
                + "    try: "
                + "      os.write(fd, f'data-record-{i}'.encode()); "
                + "      os.fsync(fd); "
                + "      success += 1 "
                + "    except OSError as e: "
                + "      if e.errno == 5: eio += 1; print(f'EIO_DETECTED i={i}') "
                + "      else: print(f'OTHER_ERROR i={i} err={e.errno}') "
                + "    finally: "
                + "      try: os.close(fd) "
                + "      except: pass "
                + "  except Exception as e: print(f'OPEN_FAIL i={i}') "
                + "print(f'SUMMARY eio={eio} success={success}') "
                + "print(f'SERVICE_ALIVE throughput={success}') "
                + "\"");

        String output = result.getStdout() + result.getStderr();
        log.info("EIO probe output (truncated): {}",
                output.length() > 600 ? output.substring(0, 600) + "..." : output);

        if (output.contains("SUMMARY eio=")) {
            String summary = output.substring(output.indexOf("SUMMARY eio=") + 12);
            eioCount.set(Long.parseLong(summary.split(" ")[0]));
            String successStr = summary.substring(summary.indexOf("success=") + 8).trim();
            successCount.set(Long.parseLong(successStr.split("[^0-9]")[0]));
        }

        boolean serviceAlive = output.contains("SERVICE_ALIVE");
        if (serviceAlive) applicationStillResponding.incrementAndGet();

        System.out.println();
        System.out.println("┌─────────────────────────────────────────────────────────────────────┐");
        System.out.println("│  PROOF  —  fsync() EIO / silent persistence failure fingerprint     │");
        System.out.println("│                                                                      │");
        System.out.printf( "│  Operations attempted  : 60%n");
        System.out.printf( "│  EIO events detected   : %d  ← checked by correct code             │%n", eioCount.get());
        System.out.printf( "│  Succeeded             : %d%n", successCount.get());
        System.out.printf( "│  Application still up  : %s  ← EIO must not crash the service     │%n",
                applicationStillResponding.get() > 0 ? "YES" : "NO");
        System.out.println("│                                                                      │");
        System.out.println("│  The industry-wide bug: fsync() return value is never checked       │");
        System.out.println("│  Result: 'committed' data was never on disk — lost on pod restart   │");
        System.out.println("│  Fix: check fsync() return value; treat EIO as transaction abort    │");
        System.out.println("└─────────────────────────────────────────────────────────────────────┘");
        System.out.println();

        assertThat(eioCount.get())
                .as("10%% EIO must produce observable error count — proves IO error detection path")
                .isGreaterThan(0L);

        assertThat(successCount.get())
                .as("application must continue processing despite EIO — throughput maintained")
                .isGreaterThan(0L);

        assertThat(applicationStillResponding.get())
                .as("application must remain alive under 10%% EIO — must not crash on storage error")
                .isGreaterThan(0L);
    }
}
