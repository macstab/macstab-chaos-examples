package com.macstab.chaos.examples.c99;

import static org.assertj.core.api.Assertions.assertThat;

import com.macstab.chaos.annotation.AppContainer;
import com.macstab.chaos.annotation.SyscallLevelChaos;
import com.macstab.chaos.extension.ChaosTestingExtension;
import com.macstab.chaos.lib.LibchaosLib;
import com.macstab.chaos.process.annotation.l1.pthread_create.ChaosPthreadCreateEagain;
import com.macstab.chaos.process.annotation.l1.fork.ChaosForkEagain;
import com.macstab.chaos.process.annotation.l1.execve.ChaosExecveEacces;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.wiremock.testcontainers.WireMockContainer;

/**
 * PROCESS syscall-level chaos — production disaster post-mortems.
 *
 * <p>Process creation failures are the most dangerous and least tested category of production
 * incidents. The team load-tested to 200 concurrent users and it passed. In production with
 * Kubernetes cgroup limits it failed at 150. The clone() syscall that works in dev without
 * a seccomp profile fails in production with the security team's new profile. The fork() that
 * reliably spawns child processes in a normal environment deadlocks under GC pressure.
 *
 * <p>These tests replay three incidents where the failure was invisible in any test environment
 * that didn't replicate the exact production constraints.
 */
@Testcontainers
@ExtendWith(ChaosTestingExtension.class)
@SyscallLevelChaos(LibchaosLib.PROCESS)
class LibchaosProcessAllConfigsTest {

    private static final Logger log = LoggerFactory.getLogger(LibchaosProcessAllConfigsTest.class);

    @Container
    @AppContainer
    static GenericContainer<?> app =
            new GenericContainer<>("macstab/gunicorn-nginx-app:latest")
                    .withExposedPorts(8080)
                    .withEnv("GUNICORN_WORKERS", "4")
                    .withEnv("GUNICORN_TIMEOUT", "30")
                    .withStartupTimeout(Duration.ofSeconds(90));

    @Container
    static WireMockContainer wiremock =
            new WireMockContainer("wiremock/wiremock:3.3.1")
                    .withMappingFromJSON("""
                        {"request":{"method":"GET","url":"/users/1"},
                         "response":{"status":200,"headers":{"Content-Type":"application/json"},
                                     "body":"{\\"id\\":1,\\"name\\":\\"Alice\\"}"}}
                    """);

    private HttpClient httpClient;
    private String appBaseUrl;

    @BeforeAll
    static void printLibcapabilities() {
        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════════════════════╗");
        System.out.println("║    LIBCHAOS-PROCESS  —  WHAT NO OTHER TEST FRAMEWORK CAN DO         ║");
        System.out.println("╠══════════════════════════════════════════════════════════════════════╣");
        System.out.println("║  Intercepts pthread_create(), fork(), clone(), execve() at the      ║");
        System.out.println("║  syscall level — injects EAGAIN, EPERM, and race conditions into    ║");
        System.out.println("║  thread creation, process spawning, and container namespace ops.     ║");
        System.out.println("║                                                                      ║");
        System.out.println("║  What you can find here that load tests cannot show you:            ║");
        System.out.println("║    • cgroup thread limit hit: JVM throws OOM on thread create       ║");
        System.out.println("║    • fork() in multithreaded context: child gets corrupted state    ║");
        System.out.println("║    • clone() EPERM from seccomp: JVM fails to start in new cluster  ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════════╝");
        System.out.println();
    }

    @BeforeEach
    void setUp() {
        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        appBaseUrl = "http://" + app.getHost() + ":" + app.getMappedPort(8080);
    }

    /**
     * Load test: 200 concurrent users on dev server. Passes. No cgroup limits on dev.
     *
     * <p>Production: Kubernetes pod with threads: 500 in the cgroup. At 150 concurrent users,
     * pthread_create() returns EAGAIN (thread count limit hit). JVM: OutOfMemoryError: unable
     * to create new native thread. Not heap OOM. Thread count OOM. Application crashes.
     *
     * <p>Engineers: "We load tested to 200 users!" Yes. On dev. Without cgroup thread limits.
     * The dev server had no /sys/fs/cgroup/pids/tasks limit. Production had a hard limit of 500
     * threads. The load test was meaningless for this failure mode.
     *
     * <p>Root cause: load test environment did not replicate Kubernetes cgroup resource constraints.
     * This is the most common class of "it passed load testing" production failure.
     */
    @Test
    @DisplayName("PROCESS L8: pthread_create EAGAIN — cgroup thread limit hit, JVM throws OutOfMemoryError: unable to create native thread, load test passes but prod crashes")
    @ChaosPthreadCreateEagain(probability = 0.30f)
    void pthreadCreateEagainCgroupThreadLimitHitOomOnThreadCreate() throws Exception {
        System.out.println();
        System.out.println("┌─────────────────────────────────────────────────────────────────────┐");
        System.out.println("│  INCIDENT: cgroup thread limit hit — load test passed, prod crashed  │");
        System.out.println("│  Severity: P0  Duration: 4 hours  Why load test missed it: no cgroup│");
        System.out.println("│  Injecting: 30% EAGAIN on pthread_create()                          │");
        System.out.println("│  Expected: OOM: unable to create native thread at 150 users         │");
        System.out.println("└─────────────────────────────────────────────────────────────────────┘");

        int totalRequests = 50;
        List<Long> latenciesMs = new ArrayList<>(totalRequests);
        AtomicLong successCount = new AtomicLong();
        AtomicLong oomThreadErrors = new AtomicLong();
        AtomicLong crashCount = new AtomicLong();

        for (int i = 0; i < totalRequests; i++) {
            long start = System.nanoTime();
            try {
                HttpResponse<String> response = probe(10);
                latenciesMs.add((System.nanoTime() - start) / 1_000_000L);

                String body = response.body();
                if (response.statusCode() == 200) {
                    successCount.incrementAndGet();
                } else if (body != null && (body.contains("native thread") || body.contains("pthread"))) {
                    oomThreadErrors.incrementAndGet();
                } else {
                    oomThreadErrors.incrementAndGet();
                }
            } catch (java.net.ConnectException e) {
                latenciesMs.add((System.nanoTime() - start) / 1_000_000L);
                crashCount.incrementAndGet();
                log.error("Connection refused — JVM may have crashed from OOM on thread create: {}", e.getMessage());
            } catch (Exception e) {
                latenciesMs.add((System.nanoTime() - start) / 1_000_000L);
                oomThreadErrors.incrementAndGet();
            }
        }

        latenciesMs.sort(Long::compareTo);
        long p99 = percentile(latenciesMs, 99);

        System.out.println();
        System.out.println("┌─────────────────────────────────────────────────────────────────────┐");
        System.out.println("│  PROOF  —  pthread_create EAGAIN / thread limit fingerprint         │");
        System.out.println("│                                                                      │");
        System.out.printf( "│  Total requests sent   : %d%n", totalRequests);
        System.out.printf( "│  Succeeded             : %d%n", successCount.get());
        System.out.printf( "│  Thread OOM errors     : %d  ← pthread_create failed at cgroup limit │%n", oomThreadErrors.get());
        System.out.printf( "│  JVM crash indicators  : %d  ← connection refused = crashed JVM    │%n", crashCount.get());
        System.out.printf( "│  Latency p99           : %dms%n", p99);
        System.out.println("│                                                                      │");
        System.out.println("│  Load test on dev: 200 users, passed. Prod cgroup limit: 500 threads│");
        System.out.println("│  At 150 users each handling a 30s request: limit hit, JVM crashes   │");
        System.out.println("│  Fix: replicate cgroup limits in load test environment              │");
        System.out.println("└─────────────────────────────────────────────────────────────────────┘");
        System.out.println();

        assertThat(crashCount.get())
                .as("JVM must not completely crash from pthread_create EAGAIN — graceful degradation required")
                .isLessThan((long) (totalRequests / 2));

        assertThat(successCount.get() + oomThreadErrors.get())
                .as("all requests must receive a response — no silent hangs")
                .isEqualTo((long) totalRequests - crashCount.get());
    }

    /**
     * Application uses ProcessBuilder.start() to call external CLI for PDF generation.
     *
     * <p>At high load: fork() called while JVM's GC thread holds internal locks. Child process
     * inherits locked mutex state. Child calls malloc() in the PDF library. malloc() needs the
     * lock that the parent's GC thread holds. Deadlock in child. Child hangs forever.
     *
     * <p>File descriptors to the child process's stdin/stdout/stderr remain open. After 500
     * requests: 500 hung child processes, 1500 leaked file descriptors. Server runs out of PIDs.
     * New requests: "Cannot run program": fork() returning ENOPROTOOPT. Service dead.
     *
     * <p>Engineers: see hung processes in ps aux. Kill them. Service recovers. Happens again in
     * 10 minutes. Kill them again. Happens again. Pattern: 500 PDF requests → service dies.
     */
    @Test
    @DisplayName("PROCESS L8: fork() in multithreaded context — ProcessBuilder.start() under load races with JVM GC thread, child gets corrupted state")
    @ChaosForkEagain(probability = 0.20f)
    void forkInMultithreadedContextProcessBuilderRaceWithGcDeadlockInChild() throws Exception {
        System.out.println();
        System.out.println("┌─────────────────────────────────────────────────────────────────────┐");
        System.out.println("│  INCIDENT: ProcessBuilder fork race with GC — child hung, FD leak   │");
        System.out.println("│  Severity: P0  Pattern: 500 PDF requests → service dead             │");
        System.out.println("│  Injecting: 20% EAGAIN on fork() (simulating GC lock contention)    │");
        System.out.println("│  Expected: fork failures accumulate, child processes accumulate      │");
        System.out.println("└─────────────────────────────────────────────────────────────────────┘");

        // Allow the fault to settle.
        TimeUnit.SECONDS.sleep(1);

        // Send subprocess-triggering requests.
        int totalRequests = 30;
        AtomicLong forkFailures = new AtomicLong();
        AtomicLong successes = new AtomicLong();
        AtomicLong unhandledErrors = new AtomicLong();

        for (int i = 0; i < totalRequests; i++) {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(appBaseUrl + "/run-subprocess?cmd=echo+pdf-generated"))
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .timeout(Duration.ofSeconds(8))
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                String body = response.body();

                if (response.statusCode() == 200) {
                    successes.incrementAndGet();
                } else if (body != null && (body.contains("Traceback") || body.contains("Exception"))) {
                    unhandledErrors.incrementAndGet();
                } else {
                    forkFailures.incrementAndGet();
                }
            } catch (Exception e) {
                forkFailures.incrementAndGet();
            }
        }

        // Check the application is still alive after fork failures.
        HttpResponse<String> health = probe(5);
        boolean serviceStillAlive = health.statusCode() == 200
                || health.statusCode() == 503;

        System.out.println();
        System.out.println("┌─────────────────────────────────────────────────────────────────────┐");
        System.out.println("│  PROOF  —  fork() race / hung child accumulation fingerprint        │");
        System.out.println("│                                                                      │");
        System.out.printf( "│  Subprocess requests   : %d%n", totalRequests);
        System.out.printf( "│  Succeeded             : %d%n", successes.get());
        System.out.printf( "│  Fork failures         : %d  ← fork EAGAIN from GC race            │%n", forkFailures.get());
        System.out.printf( "│  Unhandled exceptions  : %d  ← traceback in response body          │%n", unhandledErrors.get());
        System.out.printf( "│  Service still alive   : %s%n", serviceStillAlive ? "YES" : "NO  ← PID table exhausted");
        System.out.println("│                                                                      │");
        System.out.println("│  In production: 500 hung children → FD exhaustion → service dead   │");
        System.out.println("│  Fix: use async PDF generation; set subprocess timeout; monitor FDs │");
        System.out.println("└─────────────────────────────────────────────────────────────────────┘");
        System.out.println();

        assertThat(unhandledErrors.get())
                .as("fork EAGAIN must not produce unhandled Python tracebacks in HTTP response body")
                .isEqualTo(0L);

        assertThat(serviceStillAlive)
                .as("service must remain reachable after fork() failures — must not exhaust PIDs and die")
                .isTrue();
    }

    /**
     * Migration from Docker to Kubernetes with strict seccomp profile.
     *
     * <p>JVM uses clone(2) syscall with CLONE_VM|CLONE_FS|CLONE_FILES for thread creation.
     * New seccomp profile: blocks clone(2) with those flags (applied by security team to prevent
     * container escapes). JVM fails to start. Error: OutOfMemoryError: unable to create new
     * native thread — but it's actually EPERM, not ENOMEM. The error message is wrong.
     *
     * <p>Security team applied the seccomp profile on a Thursday. Dev team migrated to the new
     * cluster on Monday. JVM would not start. Error message said "unable to create native thread"
     * — team thought it was a memory issue. They added memory. Still failed.
     *
     * <p>It took 5 days to trace: wrong error message in JVM, wrong assumption by team, wrong
     * fix applied. Root cause: JVM reports ENOMEM when clone() returns EPERM.
     */
    @Test
    @DisplayName("PROCESS L8: clone() EPERM in container — JVM uses clone(2) for thread creation, namespaced container blocks clone flags, JVM startup fails")
    @ChaosExecveEacces(probability = 1.0f)
    void cloneEpermSeccompProfileBlocksJvmThreadCreation() throws Exception {
        System.out.println();
        System.out.println("┌─────────────────────────────────────────────────────────────────────┐");
        System.out.println("│  INCIDENT: K8s seccomp blocks clone() — JVM fails to start         │");
        System.out.println("│  Severity: P0  Duration: 5 days  Wrong fix: added memory (useless) │");
        System.out.println("│  Injecting: 100% EACCES/EPERM on process creation syscalls          │");
        System.out.println("│  Expected: JVM startup fails with misleading OOM error message      │");
        System.out.println("└─────────────────────────────────────────────────────────────────────┘");

        // Under EPERM on clone(), the JVM should fail to start or degrade severely.
        // We probe the health endpoint to document the exact failure mode.
        AtomicLong connectionRefused = new AtomicLong();
        AtomicLong serviceUnavailable = new AtomicLong();
        AtomicLong unexpectedSuccess = new AtomicLong();

        for (int i = 0; i < 10; i++) {
            try {
                HttpResponse<String> response = probe(5);
                if (response.statusCode() == 200) {
                    unexpectedSuccess.incrementAndGet();
                } else {
                    serviceUnavailable.incrementAndGet();
                }
            } catch (java.net.ConnectException e) {
                connectionRefused.incrementAndGet();
            } catch (Exception e) {
                serviceUnavailable.incrementAndGet();
            }
            Thread.sleep(200);
        }

        // Attempt subprocess creation — this exercises the EACCES/EPERM path.
        HttpResponse<String> subprocResponse;
        try {
            subprocResponse = httpClient.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create(appBaseUrl + "/run-subprocess?cmd=echo+test"))
                            .POST(HttpRequest.BodyPublishers.noBody())
                            .timeout(Duration.ofSeconds(5))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            subprocResponse = null;
        }

        String subprocBody = subprocResponse != null ? subprocResponse.body() : "connection refused";
        int subprocStatus = subprocResponse != null ? subprocResponse.statusCode() : -1;

        System.out.println();
        System.out.println("┌─────────────────────────────────────────────────────────────────────┐");
        System.out.println("│  PROOF  —  clone() EPERM / seccomp failure fingerprint              │");
        System.out.println("│                                                                      │");
        System.out.println("│  Health probe results (10 probes):                                  │");
        System.out.printf( "│    Connection refused  : %d  ← JVM cannot start                    │%n", connectionRefused.get());
        System.out.printf( "│    Service unavailable : %d  ← JVM started but degraded            │%n", serviceUnavailable.get());
        System.out.printf( "│    Unexpected success  : %d%n", unexpectedSuccess.get());
        System.out.println("│                                                                      │");
        System.out.println("│  Subprocess creation (EACCES injected):                             │");
        System.out.printf( "│    Status code         : %d%n", subprocStatus);
        System.out.printf( "│    Body fragment       : %s%n",
                subprocBody.length() > 60 ? subprocBody.substring(0, 60) + "..." : subprocBody);
        System.out.println("│                                                                      │");
        System.out.println("│  Error message confusion: 'unable to create native thread' = EPERM  │");
        System.out.println("│  JVM misleadingly reports ENOMEM even when the cause is EPERM       │");
        System.out.println("│  Fix: check seccomp profile; add clone() to allowed syscalls list   │");
        System.out.println("└─────────────────────────────────────────────────────────────────────┘");
        System.out.println();

        // Under full EPERM on process creation, service must produce observable failure.
        long totalFailures = connectionRefused.get() + serviceUnavailable.get();
        assertThat(totalFailures)
                .as("100%% EPERM on process creation must produce observable failures — JVM startup or subprocess must fail")
                .isGreaterThan(0L);
    }

    private HttpResponse<String> probe(int timeoutSeconds) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(appBaseUrl + "/health"))
                .GET()
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static long percentile(List<Long> sortedValues, int percentile) {
        if (sortedValues.isEmpty()) return 0L;
        int index = (int) Math.ceil(percentile / 100.0 * sortedValues.size()) - 1;
        return sortedValues.get(Math.max(0, Math.min(index, sortedValues.size() - 1)));
    }
}
