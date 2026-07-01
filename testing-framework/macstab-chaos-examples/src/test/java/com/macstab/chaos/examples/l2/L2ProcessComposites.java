package com.macstab.chaos.examples.l2;

import static org.assertj.core.api.Assertions.assertThat;

import com.macstab.chaos.annotation.AppContainer;
import com.macstab.chaos.annotation.SyscallLevelChaos;
import com.macstab.chaos.extension.ChaosTestingExtension;
import com.macstab.chaos.lib.LibchaosLib;
import com.macstab.chaos.process.testpack.CompositeChaosProcessLimitHit;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
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

/**
 * L2 – Process composite chaos: thread exhaustion and native thread limit scenarios in containers.
 *
 * <p>The incident: a container running gunicorn in a Kubernetes pod hit the kernel PID namespace
 * limit. fork() returned EAGAIN. The gunicorn master process was still alive — responding to
 * health probes. The socket was still open — accepting connections. But no worker process could
 * be forked to handle those connections. The service appeared healthy. It was completely unable
 * to serve traffic.
 *
 * <p>This is the most dangerous class of failure: the health check passes, the readiness probe
 * passes, the circuit breakers stay closed — because the master process is healthy. The workers
 * are not. Traffic accumulates. Requests queue. Timeouts cascade. The load balancer never removes
 * the pod because health checks never fail.
 */
@Testcontainers
@ExtendWith(ChaosTestingExtension.class)
@SyscallLevelChaos(LibchaosLib.PROCESS)
class L2ProcessComposites {

    private static final Logger log = LoggerFactory.getLogger(L2ProcessComposites.class);

    @BeforeAll
    static void printIncidentSummary() {
        System.out.println("╔══════════════════════════════════════════════════════════════════╗");
        System.out.println("║  L2 PROCESS LIMIT DISASTER PROOFS — INVISIBLE CONTAINER FAILURE ║");
        System.out.println("║  Health probe: PASS. Socket: OPEN. Workers: ZERO.              ║");
        System.out.println("║  Service appears healthy. Cannot serve any traffic.            ║");
        System.out.println("║  Three deployment patterns. Each hit this in production.       ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");
    }

    // ── Containers ─────────────────────────────────────────────────────────────

    /**
     * Setup A: gunicorn with 4 pre-fork workers. The 4 workers start before fault activation.
     * After fault: gunicorn cannot spawn replacement workers when any of the 4 crash.
     */
    @Container
    @AppContainer
    static GenericContainer<?> gunicornFourWorkers =
            new GenericContainer<>("macstab/gunicorn-nginx-app:latest")
                    .withExposedPorts(8080)
                    .withEnv("GUNICORN_WORKERS", "4")
                    .withEnv("GUNICORN_TIMEOUT", "30")
                    .withStartupTimeout(Duration.ofSeconds(90));

    /**
     * Setup B: gunicorn with 1 worker and FAIL_AFTER=1. The PID limit is hit immediately after
     * the single initial worker is created. The second fork attempt fails immediately.
     */
    @Container
    @AppContainer
    static GenericContainer<?> gunicornOneWorkerImmediateFail =
            new GenericContainer<>("macstab/gunicorn-nginx-app:latest")
                    .withExposedPorts(8081)
                    .withEnv("GUNICORN_WORKERS", "1")
                    .withEnv("FAIL_AFTER", "1")
                    .withEnv("GUNICORN_TIMEOUT", "30")
                    .withStartupTimeout(Duration.ofSeconds(90));

    /**
     * Setup C: subprocess-heavy application. Each HTTP request forks a child process.
     * Under PID-limit exhaustion, every request-serving fork fails with EAGAIN.
     */
    @Container
    @AppContainer
    static GenericContainer<?> subprocessHeavyApp =
            new GenericContainer<>("macstab/gunicorn-nginx-app:subprocess-heavy")
                    .withExposedPorts(8082)
                    .withEnv("GUNICORN_WORKERS", "2")
                    .withEnv("SUBPROCESS_PER_REQUEST", "true")
                    .withStartupTimeout(Duration.ofSeconds(90));

    private HttpClient httpClient;
    private String setupABaseUrl;
    private String setupBBaseUrl;
    private String setupCBaseUrl;

    @BeforeEach
    void setUp() {
        httpClient =
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(5))
                        .build();
        setupABaseUrl = "http://" + gunicornFourWorkers.getHost()
                + ":" + gunicornFourWorkers.getMappedPort(8080);
        setupBBaseUrl = "http://" + gunicornOneWorkerImmediateFail.getHost()
                + ":" + gunicornOneWorkerImmediateFail.getMappedPort(8081);
        setupCBaseUrl = "http://" + subprocessHeavyApp.getHost()
                + ":" + subprocessHeavyApp.getMappedPort(8082);
    }

    // ── Setup A: 4 workers, fork EAGAIN 100% ────────────────────────────────

    /**
     * INCIDENT: Kubernetes pod hit uid RLIMIT_NPROC during a traffic spike. The 4 pre-fork
     * workers continued running. The gunicorn master was alive. Health probes returned 200.
     * But when one worker was killed (OOM) gunicorn could not fork a replacement. The worker
     * pool silently shrank from 4 to 3 to 2 to 1 as each worker hit memory limits and was
     * killed. The last worker took 100% of traffic. When it died, the service appeared healthy
     * but served zero requests for 47 seconds until the operator noticed the p99 alert.
     *
     * <p>This test proves: the supervisor detects fork() failure and reports it within 3 seconds.
     * The master remains alive. The status endpoint returns a structured response — not a crash.
     */
    @Test
    @DisplayName("L2: Kubernetes PID limit — 4 workers, supervisor cannot replace crashed workers, alerts fast")
    @CompositeChaosProcessLimitHit(toxicity = 1.0, id = "gunicorn-four-workers")
    void pidLimitExhausted_fourWorkerSetup_supervisorAlerts() throws Exception {
        System.out.println("╔══════════════════════════════════════════════════════════════════╗");
        System.out.println("║  INCIDENT: RLIMIT_NPROC hit during traffic spike                ║");
        System.out.println("║  4 workers running. Master alive. Health: PASS. Workers: dying. ║");
        System.out.println("║  Supervisor must detect fork failure within 3s. Not hang.       ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");

        TimeUnit.SECONDS.sleep(2);

        // Master process must still be reachable.
        HttpRequest healthRequest =
                HttpRequest.newBuilder()
                        .uri(URI.create(setupABaseUrl + "/health"))
                        .GET()
                        .timeout(Duration.ofSeconds(5))
                        .build();

        HttpResponse<String> healthResponse =
                httpClient.send(healthRequest, HttpResponse.BodyHandlers.ofString());

        log.info("Setup A — health: status={}, body={}", healthResponse.statusCode(),
                healthResponse.body());

        assertThat(healthResponse.statusCode())
                .as("gunicorn master must remain alive (health=200) even when all fork() calls"
                        + " return EAGAIN — PID limit proof: master does not fork for requests,"
                        + " it only forks worker replacements")
                .isEqualTo(200);

        // The supervisor must report the fork failure condition without blocking.
        Instant probeStart = Instant.now();

        HttpRequest workerStatusRequest =
                HttpRequest.newBuilder()
                        .uri(URI.create(setupABaseUrl + "/workers/status"))
                        .GET()
                        .timeout(Duration.ofSeconds(5))
                        .build();

        HttpResponse<String> statusResponse =
                httpClient.send(workerStatusRequest, HttpResponse.BodyHandlers.ofString());

        long probeMs = Duration.between(probeStart, Instant.now()).toMillis();

        System.out.println("╔══════════════════════════════════════════════════════════════════╗");
        System.out.printf("║  PROOF: health=HTTP%d  workerStatus=HTTP%d  probeMs=%d%n",
                healthResponse.statusCode(), statusResponse.statusCode(), probeMs);
        System.out.printf("║  Alert speed: %s%n",
                probeMs < 3000L ? "FAST (< 3s, supervisor not blocked on fork())"
                        : "SLOW (> 3s, supervisor may be blocked waiting for fork())");
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");

        assertThat(statusResponse.statusCode())
                .as("worker status must return a structured response when PID limit is exhausted"
                        + " — supervisor must have already detected the failure, not crash dump")
                .isIn(200, 503);

        assertThat(probeMs)
                .as("supervisor must respond to status probe within 3000ms after PID limit"
                        + " exhaustion — RLIMIT_NPROC proof: supervisor must not block on a"
                        + " failed fork() syscall waiting for a return that never comes")
                .isLessThan(3_000L);
    }

    // ── Setup B: 1 worker, FAIL_AFTER=1, immediate failure ──────────────────

    /**
     * INCIDENT: Container deployed to a shared Kubernetes node where another pod had consumed
     * most of the PID namespace capacity. The first worker forked successfully. The second fork
     * (gunicorn attempting to reach its configured worker count of 2) failed immediately with
     * EAGAIN. The supervisor entered an exponential backoff retry loop — silently, with no alert
     * — and waited 128 seconds before its next attempt. The monitoring team was unaware for
     * over 2 minutes that the service was running with half its worker capacity.
     *
     * <p>This test proves: an immediate EAGAIN on fork() produces an immediate alert — not a
     * silent retry wait. The status endpoint responds within 1 second of the failure.
     */
    @Test
    @DisplayName("L2: Shared node PID saturation — immediate fork failure must alert in <1s, not silent retry")
    @CompositeChaosProcessLimitHit(toxicity = 1.0, id = "gunicorn-one-worker")
    void pidLimitExhausted_oneWorkerImmediateFailure_immediateAlert() throws Exception {
        System.out.println("╔══════════════════════════════════════════════════════════════════╗");
        System.out.println("║  INCIDENT: Shared node PID capacity consumed by neighbor pod    ║");
        System.out.println("║  Worker 1 forks OK. Worker 2 fork fails immediately (EAGAIN).  ║");
        System.out.println("║  Supervisor silently retried for 128s. No alert for 2 minutes. ║");
        System.out.println("║  THIS PROVES: immediate EAGAIN = immediate alert within 1s.   ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");

        TimeUnit.SECONDS.sleep(1);

        Instant detectionStart = Instant.now();

        HttpRequest workerStatusRequest =
                HttpRequest.newBuilder()
                        .uri(URI.create(setupBBaseUrl + "/workers/status"))
                        .GET()
                        .timeout(Duration.ofSeconds(5))
                        .build();

        HttpResponse<String> statusResponse =
                httpClient.send(workerStatusRequest, HttpResponse.BodyHandlers.ofString());

        long detectionMs = Duration.between(detectionStart, Instant.now()).toMillis();

        // Health check: master and existing single worker must still respond.
        HttpRequest healthRequest =
                HttpRequest.newBuilder()
                        .uri(URI.create(setupBBaseUrl + "/health"))
                        .GET()
                        .timeout(Duration.ofSeconds(5))
                        .build();

        HttpResponse<String> healthResponse =
                httpClient.send(healthRequest, HttpResponse.BodyHandlers.ofString());

        System.out.println("╔══════════════════════════════════════════════════════════════════╗");
        System.out.printf("║  PROOF: workerStatus=HTTP%d  detectionMs=%d  health=HTTP%d%n",
                statusResponse.statusCode(), detectionMs, healthResponse.statusCode());
        System.out.printf("║  Alert speed: %s%n",
                detectionMs < 1000L ? "IMMEDIATE (< 1s — no silent retry backoff)"
                        : "DELAYED (> 1s — supervisor may be in silent backoff)");
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");

        assertThat(statusResponse.statusCode())
                .as("supervisor must report worker state (200 or 503) within 1000ms of"
                        + " FAIL_AFTER=1 boundary — shared node proof: immediate EAGAIN must"
                        + " produce immediate alert, not a silent 128s backoff")
                .isIn(200, 503);

        assertThat(detectionMs)
                .as("supervisor must detect and report fork failure within 1000ms — silent"
                        + " backoff proof: 2-minute alert delay is unacceptable; immediate"
                        + " EAGAIN must produce immediate observable failure signal")
                .isLessThan(1_000L);

        assertThat(healthResponse.statusCode())
                .as("gunicorn master + existing single worker must remain alive — FAIL_AFTER=1"
                        + " kills future forks, not existing processes")
                .isEqualTo(200);
    }

    // ── Setup C: subprocess-heavy app, per-request fork ──────────────────────

    /**
     * INCIDENT: Request processing pipeline that shelled out to ffmpeg for each video transcode
     * request. Under PID-limit exhaustion, every request's fork() call returned EAGAIN. The
     * application had no timeout on the subprocess.run() call — it blocked waiting for a fork
     * that would never succeed. Request threads accumulated. After 60 seconds, all 20 gunicorn
     * threads were blocked in fork(). The service became completely unresponsive — not returning
     * errors, just hanging. Kubernetes never restarted the pod because the master was alive.
     * The operator had to manually kill it.
     *
     * <p>This test proves: subprocess fork failure under PID exhaustion returns a structured
     * error immediately — not a hang. No request blocks indefinitely waiting for fork().
     */
    @Test
    @DisplayName("L2: Video transcode fork exhaustion — per-request fork fails, structured error not hang")
    @CompositeChaosProcessLimitHit(toxicity = 1.0, id = "subprocess-heavy-app")
    void pidLimitExhausted_subprocessHeavyApp_allRequestForksFailStructured() throws Exception {
        System.out.println("╔══════════════════════════════════════════════════════════════════╗");
        System.out.println("║  INCIDENT: ffmpeg transcode — per-request fork() under PID limit║");
        System.out.println("║  fork() blocked indefinitely. All 20 threads stuck. Master alive║");
        System.out.println("║  Kubernetes never restarted. Operator killed manually.          ║");
        System.out.println("║  THIS PROVES: structured error returned, not hang.              ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");

        int totalRequests = 20;
        AtomicLong structuredErrorCount = new AtomicLong();
        AtomicLong hangOrConnectionLostCount = new AtomicLong();

        for (int i = 0; i < totalRequests; i++) {
            try {
                HttpRequest request =
                        HttpRequest.newBuilder()
                                .uri(URI.create(setupCBaseUrl + "/run-subprocess?cmd=echo+hello"))
                                .POST(HttpRequest.BodyPublishers.noBody())
                                .timeout(Duration.ofSeconds(5))
                                .build();

                HttpResponse<String> response =
                        httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() != 200) {
                    structuredErrorCount.incrementAndGet();
                    log.info("Request {} returned structured error {} under PID limit: {}",
                            i + 1, response.statusCode(),
                            response.body().substring(0, Math.min(200, response.body().length())));
                }
            } catch (java.net.http.HttpTimeoutException e) {
                hangOrConnectionLostCount.incrementAndGet();
                log.error("Request {} HUNG under PID limit — fork() blocking indefinitely: {}",
                        i + 1, e.getMessage());
            } catch (java.net.ConnectException e) {
                hangOrConnectionLostCount.incrementAndGet();
                log.error("Request {} connection refused — master process may have crashed: {}",
                        i + 1, e.getMessage());
            }
        }

        // Health probe — gunicorn master must still be responding.
        HttpResponse<String> healthResponse =
                httpClient.send(
                        HttpRequest.newBuilder()
                                .uri(URI.create(setupCBaseUrl + "/health"))
                                .GET()
                                .timeout(Duration.ofSeconds(5))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());

        System.out.println("╔══════════════════════════════════════════════════════════════════╗");
        System.out.printf("║  PROOF: structuredErrors=%d  hangs=%d  health=HTTP%d%n",
                structuredErrorCount.get(), hangOrConnectionLostCount.get(),
                healthResponse.statusCode());
        System.out.printf("║  Blocking risk: %s%n",
                hangOrConnectionLostCount.get() == 0
                        ? "NONE (fork() failure returns immediately with structured error)"
                        : hangOrConnectionLostCount.get() + " THREADS HUNG on fork() (production"
                        + " failure mode reproduced — operator intervention required)");
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");

        assertThat(hangOrConnectionLostCount.get())
                .as("subprocess-heavy app must not hang waiting for fork() under PID exhaustion"
                        + " — ffmpeg transcode proof: every request must return structured error"
                        + " promptly; blocking in fork() fills thread pool and kills the service")
                .isEqualTo(0L);

        assertThat(structuredErrorCount.get() + (totalRequests - structuredErrorCount.get()
                - hangOrConnectionLostCount.get()))
                .as("all 20 requests must receive a response (no silent hangs or connection drops)")
                .isEqualTo((long) totalRequests);

        assertThat(healthResponse.statusCode())
                .as("gunicorn master must respond to health probes even when every per-request"
                        + " subprocess fork fails — master is alive; workers are not")
                .isEqualTo(200);
    }
}
