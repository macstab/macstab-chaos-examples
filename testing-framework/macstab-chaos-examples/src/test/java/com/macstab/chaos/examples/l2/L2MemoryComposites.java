package com.macstab.chaos.examples.l2;

import static org.assertj.core.api.Assertions.assertThat;

import com.macstab.chaos.annotation.AppContainer;
import com.macstab.chaos.annotation.SyscallLevelChaos;
import com.macstab.chaos.extension.ChaosTestingExtension;
import com.macstab.chaos.lib.LibchaosLib;
import com.macstab.chaos.memory.testpack.CompositeChaosMemoryPressure;
import com.macstab.chaos.memory.testpack.CompositeChaosOomKill;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
 * L2 – Memory composite chaos: off-heap + heap combined production disaster scenarios.
 *
 * <p>Heap metrics lie during native memory pressure. The JVM reports heap at 60% used — healthy.
 * The cgroup memory controller is at 98% — the OOM killer is loading its weapon. The metrics
 * dashboard shows green. The on-call engineer is asleep. The container is killed at 2:17 AM.
 *
 * <p>Each scenario in this class is designed to prove that your application handles memory
 * faults at the mmap/malloc level — below the JVM heap — where heap metrics are blind.
 */
@Testcontainers
@ExtendWith(ChaosTestingExtension.class)
@SyscallLevelChaos(LibchaosLib.MEMORY)
class L2MemoryComposites {

    private static final Logger log = LoggerFactory.getLogger(L2MemoryComposites.class);

    @BeforeAll
    static void printIncidentSummary() {
        System.out.println("╔══════════════════════════════════════════════════════════════════╗");
        System.out.println("║  L2 MEMORY DISASTER PROOFS — HEAP METRICS LIE                  ║");
        System.out.println("║  JVM heap: 60% (healthy). cgroup memory: 98% (OOM loading gun).║");
        System.out.println("║  Dashboard shows green. Container killed at 2:17 AM.           ║");
        System.out.println("║  These tests prove your app survives what heap metrics hide.   ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");
    }

    // ── Containers ─────────────────────────────────────────────────────────────

    /**
     * Java container — constrained direct memory to ensure ENOMEM faults hit quickly.
     * JVM heap looks fine. Direct buffer allocations (off-heap mmap) will fail under pressure.
     */
    @Container
    @AppContainer
    static GenericContainer<?> javaApp =
            new GenericContainer<>("macstab/java-memory-app:openjdk21-slim")
                    .withExposedPorts(8080)
                    .withEnv("JVM_OPTS", "-Xms64m -Xmx256m -XX:MaxDirectMemorySize=128m")
                    .withStartupTimeout(Duration.ofSeconds(90));

    /** Node.js container — libuv event loop allocations under native memory pressure. */
    @Container
    @AppContainer
    static GenericContainer<?> nodeApp =
            new GenericContainer<>("macstab/node-memory-app:node20-alpine")
                    .withExposedPorts(3000)
                    .withEnv("NODE_OPTIONS", "--max-old-space-size=128")
                    .withStartupTimeout(Duration.ofSeconds(60));

    /** Python container — ctypes malloc: the native allocation path heap metrics never see. */
    @Container
    @AppContainer
    static GenericContainer<?> pythonApp =
            new GenericContainer<>("macstab/python-memory-app:python3-alpine")
                    .withExposedPorts(8000)
                    .withStartupTimeout(Duration.ofSeconds(60));

    private HttpClient httpClient;
    private String javaBaseUrl;
    private String nodeBaseUrl;
    private String pythonBaseUrl;

    @BeforeEach
    void setUp() {
        httpClient =
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(5))
                        .build();
        javaBaseUrl = "http://" + javaApp.getHost() + ":" + javaApp.getMappedPort(8080);
        nodeBaseUrl = "http://" + nodeApp.getHost() + ":" + nodeApp.getMappedPort(3000);
        pythonBaseUrl = "http://" + pythonApp.getHost() + ":" + pythonApp.getMappedPort(8000);
    }

    // ── 1% ENOMEM — low memory pressure (cgroup near limit) ──────────────────

    /**
     * INCIDENT: Java container running near cgroup memory.max limit. JVM heap reported at 62%.
     * Direct buffer allocations (off-heap mmap) failing at 1% — invisible to heap metrics.
     * The on-call team had no alert for direct buffer pressure. The first sign of the problem
     * was HTTP 500 responses from an unhandled OutOfMemoryError: Direct buffer memory.
     *
     * <p>This test proves: 1% ENOMEM on mmap is handled by the OOM handler. OutOfMemoryError
     * never reaches HTTP. Success rate > 95%. Heap dashboard would have shown nothing wrong.
     */
    @Test
    @DisplayName("L2: cgroup near limit — 1% mmap ENOMEM (Java) invisible to heap metrics, OOM handler fires")
    @CompositeChaosMemoryPressure(toxicity = 0.01)
    void lowMemoryPressure_javaApp_oomHandlerFiresAndRecovers() throws Exception {
        System.out.println("╔══════════════════════════════════════════════════════════════════╗");
        System.out.println("║  INCIDENT: Java near cgroup limit. Heap: 62%. Dashboard: GREEN. ║");
        System.out.println("║  1% mmap ENOMEM — invisible to heap metrics.                   ║");
        System.out.println("║  OOM handler must fire. Success rate > 95%. Zero HTTP 500.     ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");

        int totalRequests = 200;
        AtomicLong successCount = new AtomicLong();
        AtomicLong gracefulOomCount = new AtomicLong();
        AtomicLong unhandledErrorCount = new AtomicLong();

        for (int i = 0; i < totalRequests; i++) {
            HttpRequest request =
                    HttpRequest.newBuilder()
                            .uri(URI.create(javaBaseUrl + "/alloc?size=1048576"))
                            .POST(HttpRequest.BodyPublishers.noBody())
                            .timeout(Duration.ofSeconds(5))
                            .build();

            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            switch (response.statusCode()) {
                case 200 -> successCount.incrementAndGet();
                case 503 -> gracefulOomCount.incrementAndGet();
                default -> {
                    unhandledErrorCount.incrementAndGet();
                    log.warn("Request {} returned unexpected {} under 1%% ENOMEM: {}",
                            i + 1, response.statusCode(), response.body());
                }
            }
        }

        double successRate = (double) successCount.get() / totalRequests * 100.0;

        System.out.println("╔══════════════════════════════════════════════════════════════════╗");
        System.out.printf("║  PROOF: success=%.1f%%  gracefulOOM=%d  unhandled=%d%n",
                successRate, gracefulOomCount.get(), unhandledErrorCount.get());
        System.out.printf("║  Heap dashboard would show: %.0f%% heap used (looks healthy)%n",
                62.0); // Simulated — the whole point
        System.out.printf("║  OOM handler: %s%n",
                unhandledErrorCount.get() == 0 ? "WORKING (invisible ENOMEM handled)"
                        : "MISSING (OutOfMemoryError reached HTTP)");
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");

        assertThat(successRate)
                .as("at 1%% mmap ENOMEM, OOM handler + retry must achieve > 95%% success —"
                        + " cgroup limit proof: heap metrics are blind to direct buffer pressure;"
                        + " OutOfMemoryError must never propagate as HTTP 500")
                .isGreaterThan(95.0);

        assertThat(unhandledErrorCount.get())
                .as("OutOfMemoryError must never leak as HTTP 500 — heap dashboard lies proof:"
                        + " OOM handler is mandatory when heap metrics hide the real pressure")
                .isEqualTo(0L);
    }

    /**
     * INCIDENT: Node.js container near cgroup limit. libuv event loop allocates buffers for
     * incoming network data via anonymous mmap. With 1% ENOMEM, some libuv allocations fail.
     * Node.js does not retry automatically. An unhandled ERR_OUT_OF_MEMORY terminates the
     * process — closing the listening socket and causing ConnectException for all waiting clients.
     *
     * <p>This test proves: ERR_OUT_OF_MEMORY is caught at the request handler level and returned
     * as a structured 503 — not a process crash. Zero ConnectExceptions (process survived).
     */
    @Test
    @DisplayName("L2: Node.js cgroup pressure — 1% libuv mmap ENOMEM, ERR_OUT_OF_MEMORY caught, no crash")
    @CompositeChaosMemoryPressure(toxicity = 0.01, id = "node-app")
    void lowMemoryPressure_nodeApp_liouvAllocationHandledWithoutCrash() throws Exception {
        System.out.println("╔══════════════════════════════════════════════════════════════════╗");
        System.out.println("║  INCIDENT: Node.js cgroup limit — 1% libuv mmap ENOMEM         ║");
        System.out.println("║  Unhandled ERR_OUT_OF_MEMORY = process crash = ConnectRefused.  ║");
        System.out.println("║  100 requests. Zero ConnectException (process must survive).   ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");

        int totalRequests = 100;
        AtomicLong successCount = new AtomicLong();
        AtomicLong crashIndicatorCount = new AtomicLong();

        for (int i = 0; i < totalRequests; i++) {
            try {
                HttpRequest request =
                        HttpRequest.newBuilder()
                                .uri(URI.create(nodeBaseUrl + "/alloc?size=1048576"))
                                .POST(HttpRequest.BodyPublishers.noBody())
                                .timeout(Duration.ofSeconds(5))
                                .build();

                HttpResponse<String> response =
                        httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200 || response.statusCode() == 503) {
                    successCount.incrementAndGet();
                }
            } catch (java.net.ConnectException e) {
                crashIndicatorCount.incrementAndGet();
                log.error("Request {} connection refused — Node.js may have crashed under 1%%"
                        + " libuv ENOMEM: {}", i + 1, e.getMessage());
            }
        }

        System.out.println("╔══════════════════════════════════════════════════════════════════╗");
        System.out.printf("║  PROOF: responded=%d  crashes=%d  (crashes must be 0)%n",
                successCount.get(), crashIndicatorCount.get());
        System.out.printf("║  ERR_OUT_OF_MEMORY handling: %s%n",
                crashIndicatorCount.get() == 0 ? "CAUGHT (process survived)"
                        : "UNCAUGHT (process crashed — all clients got ConnectRefused)");
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");

        assertThat(crashIndicatorCount.get())
                .as("Node.js process must not crash under 1%% libuv mmap ENOMEM — cgroup"
                        + " pressure proof: ERR_OUT_OF_MEMORY must be caught at handler level,"
                        + " not terminate the process and drop all waiting clients")
                .isEqualTo(0L);

        assertThat(successCount.get())
                .as("at least 90 of 100 Node.js requests must succeed or degrade gracefully under 1%% ENOMEM")
                .isGreaterThanOrEqualTo(90L);
    }

    /**
     * INCIDENT: Python service using ctypes for native image processing. The native C library
     * called malloc() internally. With 1% ENOMEM on anonymous mmap, some malloc() calls failed.
     * Python surfaced the failure as MemoryError. The exception propagated through the
     * application stack and appeared as an HTTP 500 with a Python traceback in the response body.
     * The traceback was visible to clients — a data exposure incident on top of the outage.
     *
     * <p>This test proves: ctypes MemoryError is caught at the request handler and returned as
     * a structured 503. Zero Python tracebacks appear in HTTP responses.
     */
    @Test
    @DisplayName("L2: ctypes MemoryError — 1% malloc ENOMEM, Python traceback must not reach HTTP response")
    @CompositeChaosMemoryPressure(toxicity = 0.01, id = "python-app")
    void lowMemoryPressure_pythonApp_ctypesMemoryErrorHandledCleanly() throws Exception {
        System.out.println("╔══════════════════════════════════════════════════════════════════╗");
        System.out.println("║  INCIDENT: Python ctypes MemoryError — traceback in HTTP body  ║");
        System.out.println("║  Clients saw Python stack trace = data exposure + outage.       ║");
        System.out.println("║  100 requests. Zero Tracebacks in HTTP response body.          ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");

        int totalRequests = 100;
        AtomicLong unhandledTracebackCount = new AtomicLong();
        AtomicLong respondedCount = new AtomicLong();

        for (int i = 0; i < totalRequests; i++) {
            HttpRequest request =
                    HttpRequest.newBuilder()
                            .uri(URI.create(pythonBaseUrl + "/alloc?size=1048576"))
                            .POST(HttpRequest.BodyPublishers.noBody())
                            .timeout(Duration.ofSeconds(5))
                            .build();

            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            respondedCount.incrementAndGet();

            if (response.statusCode() == 500
                    && (response.body().contains("Traceback")
                            || response.body().contains("MemoryError"))) {
                unhandledTracebackCount.incrementAndGet();
                log.error("Request {} returned unhandled Python MemoryError under 1%% ctypes ENOMEM: {}",
                        i + 1, response.body());
            }
        }

        System.out.println("╔══════════════════════════════════════════════════════════════════╗");
        System.out.printf("║  PROOF: responded=%d  tracebacksInHttp=%d  (must be 0)%n",
                respondedCount.get(), unhandledTracebackCount.get());
        System.out.printf("║  Data exposure risk: %s%n",
                unhandledTracebackCount.get() == 0 ? "CONTAINED (structured 503)"
                        : "EXPOSED (Python stack visible to clients)");
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");

        assertThat(unhandledTracebackCount.get())
                .as("ctypes MemoryError must never appear as an HTTP 500 Python traceback —"
                        + " data exposure proof: MemoryError must be caught and returned as"
                        + " a structured 503, not expose the call stack to clients")
                .isEqualTo(0L);
    }

    // ── 10% ENOMEM — high memory pressure, no data corruption ───────────────

    /**
     * INCIDENT: Java container severely memory-constrained after a misconfigured cgroup limit
     * change. 10% of direct buffer allocations failing. At this rate, the OOM handler fires
     * frequently. The critical concern: buffers partially written before the OOM must not be
     * committed. A 200 OK response must never carry corrupted data. This is the memory equivalent
     * of a torn write — invisible unless you verify the checksum on every successful response.
     *
     * <p>This test proves: at 10% ENOMEM, zero data corruption occurs. Every 200 OK response
     * carries the correct checksum. Corrupted partial writes are not committed.
     */
    @Test
    @DisplayName("L2: Misconfigured cgroup limit — 10% ENOMEM, no data corruption, every 200 OK verified")
    @CompositeChaosMemoryPressure(toxicity = 0.10)
    void highMemoryPressure_circuitBreakerEngagesNoDataCorruption() throws Exception {
        System.out.println("╔══════════════════════════════════════════════════════════════════╗");
        System.out.println("║  INCIDENT: cgroup limit misconfigured — 10% ENOMEM             ║");
        System.out.println("║  Partial buffer writes before OOM = silent data corruption.     ║");
        System.out.println("║  Every 200 OK response must carry correct checksum.            ║");
        System.out.println("║  100 requests. Zero corruption. All controlled.                ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");

        int totalRequests = 100;
        AtomicLong successWithCorrectChecksum = new AtomicLong();
        AtomicLong corruptionCount = new AtomicLong();
        AtomicLong gracefulShedCount = new AtomicLong();

        for (int i = 0; i < totalRequests; i++) {
            int expectedChecksum = i * 31 + 17;
            HttpRequest request =
                    HttpRequest.newBuilder()
                            .uri(URI.create(javaBaseUrl + "/alloc?size=1048576&checksum=" + expectedChecksum))
                            .POST(HttpRequest.BodyPublishers.noBody())
                            .timeout(Duration.ofSeconds(5))
                            .build();

            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                if (response.body().contains(String.valueOf(expectedChecksum))) {
                    successWithCorrectChecksum.incrementAndGet();
                } else {
                    corruptionCount.incrementAndGet();
                    log.error("Data corruption on request {}: expected checksum {} not in body: {}",
                            i + 1, expectedChecksum, response.body());
                }
            } else if (response.statusCode() == 503) {
                gracefulShedCount.incrementAndGet();
            }
        }

        System.out.println("╔══════════════════════════════════════════════════════════════════╗");
        System.out.printf("║  PROOF: correctData=%d  corruption=%d  gracefulShed=%d  total=%d%n",
                successWithCorrectChecksum.get(), corruptionCount.get(),
                gracefulShedCount.get(), totalRequests);
        System.out.printf("║  Data integrity: %s%n",
                corruptionCount.get() == 0 ? "INTACT (no partial writes committed)"
                        : corruptionCount.get() + " CORRUPTED (partial writes reaching clients)");
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");

        assertThat(corruptionCount.get())
                .as("zero data corruption at 10%% ENOMEM — cgroup misconfiguration proof: OOM"
                        + " handler must not reuse partially-written buffers; every 200 OK must"
                        + " carry the correct checksum")
                .isEqualTo(0L);

        assertThat(successWithCorrectChecksum.get() + gracefulShedCount.get())
                .as("all requests must be handled (correct data or graceful 503 shed) — no"
                        + " silent drops under 10%% ENOMEM from cgroup pressure")
                .isEqualTo((long) totalRequests);
    }

    // ── 100% ENOMEM — full exhaustion, no SIGSEGV ───────────────────────────

    /**
     * INCIDENT: Container address space limit hit. Every ByteBuffer.allocateDirect() call
     * returned MAP_FAILED = (void*)-1 from a failed mmap(). Applications that did not check the
     * return value of mmap (relying on the JVM to throw OutOfMemoryError) and instead tried to
     * dereference the returned pointer received SIGSEGV. The JVM died with a hs_err_pid file
     * and no structured error — just a silent pod restart in Kubernetes.
     *
     * <p>This test proves: 100% mmap failure does not SIGSEGV. The JVM stays alive. Every
     * allocation request returns a controlled HTTP 503. The health probe remains responsive.
     */
    @Test
    @DisplayName("L2: Address space exhaustion — 100% mmap ENOMEM, no SIGSEGV, health probe responds")
    @CompositeChaosOomKill
    void fullMemoryExhaustion_gracefulDegradationNoSigsegv() throws Exception {
        System.out.println("╔══════════════════════════════════════════════════════════════════╗");
        System.out.println("║  INCIDENT: Container address space limit hit — MAP_FAILED 100%  ║");
        System.out.println("║  mmap returns (void*)-1. Deref = SIGSEGV. Pod restarts silently.║");
        System.out.println("║  100% ENOMEM. Zero SIGSEGV. Health probe must respond.         ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");

        int totalRequests = 30;
        AtomicLong gracefulErrorCount = new AtomicLong();
        AtomicLong crashCount = new AtomicLong();

        for (int i = 0; i < totalRequests; i++) {
            HttpRequest request =
                    HttpRequest.newBuilder()
                            .uri(URI.create(javaBaseUrl + "/alloc?size=1048576"))
                            .POST(HttpRequest.BodyPublishers.noBody())
                            .timeout(Duration.ofSeconds(5))
                            .build();

            try {
                HttpResponse<String> response =
                        httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 503 || response.statusCode() == 507) {
                    gracefulErrorCount.incrementAndGet();
                } else if (response.statusCode() == 500) {
                    crashCount.incrementAndGet();
                    log.error("Request {} returned HTTP 500 under 100%% ENOMEM (unhandled OOM): {}",
                            i + 1, response.body());
                }
            } catch (Exception e) {
                crashCount.incrementAndGet();
                log.error("Request {} connection failed — JVM may have crashed under 100%% ENOMEM: {}",
                        i + 1, e.getMessage());
            }
        }

        // Health probe — the JVM must still be alive.
        HttpResponse<String> healthResponse =
                httpClient.send(
                        HttpRequest.newBuilder()
                                .uri(URI.create(javaBaseUrl + "/health"))
                                .GET()
                                .timeout(Duration.ofSeconds(5))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());

        System.out.println("╔══════════════════════════════════════════════════════════════════╗");
        System.out.printf("║  PROOF: graceful503=%d  crashes=%d  healthProbe=HTTP%d%n",
                gracefulErrorCount.get(), crashCount.get(), healthResponse.statusCode());
        System.out.printf("║  SIGSEGV: %s%n",
                crashCount.get() == 0 ? "NO (MAP_FAILED handled correctly)"
                        : "YES (mmap return value not checked)");
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");

        assertThat(crashCount.get())
                .as("application must not crash (SIGSEGV or unhandled OOM) under 100%% mmap"
                        + " failure — address space exhaustion proof: MAP_FAILED = (void*)-1"
                        + " must never be dereferenced; graceful HTTP 503 required")
                .isEqualTo(0L);

        assertThat(healthResponse.statusCode())
                .as("health probe must return 200 even when all direct-buffer allocations fail —"
                        + " JVM must remain alive and responsive to Kubernetes liveness checks")
                .isEqualTo(200);
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private static long percentile(List<Long> values, int pct) {
        if (values.isEmpty()) {
            return 0L;
        }
        List<Long> sorted = new ArrayList<>(values);
        sorted.sort(Long::compareTo);
        int index = (int) Math.ceil(pct / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }
}
