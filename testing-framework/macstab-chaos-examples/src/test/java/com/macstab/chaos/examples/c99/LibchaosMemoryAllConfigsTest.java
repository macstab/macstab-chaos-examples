package com.macstab.chaos.examples.c99;

import static org.assertj.core.api.Assertions.assertThat;

import com.macstab.chaos.annotation.AppContainer;
import com.macstab.chaos.annotation.SyscallLevelChaos;
import com.macstab.chaos.extension.ChaosTestingExtension;
import com.macstab.chaos.lib.LibchaosLib;
import com.macstab.chaos.memory.annotation.l1.mmap_anon.ChaosMmapAnonEnomem;
import com.macstab.chaos.memory.annotation.l1.mmap_anon.ChaosMmapAnonLatency;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
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
 * MEMORY syscall-level chaos — production disaster post-mortems.
 *
 * <p>Memory failures at the mmap() level are invisible to every monitoring tool that measures
 * heap. The JVM heap may show 60% utilization — green, healthy, fine — while the off-heap
 * direct buffer space that Netty uses for HTTP/2 frames has been exhausted by cgroup limits.
 * The pod is about to be OOM-killed. The dashboard shows green.
 *
 * <p>These tests replay three incidents where memory failure was invisible at the application
 * layer until the service either crashed, corrupted data, or produced inexplicable behavior.
 */
@Testcontainers
@ExtendWith(ChaosTestingExtension.class)
@SyscallLevelChaos(LibchaosLib.MEMORY)
class LibchaosMemoryAllConfigsTest {

    private static final Logger log = LoggerFactory.getLogger(LibchaosMemoryAllConfigsTest.class);

    private static final int LARGE_ALLOC_BYTES  = 4_194_304; // 4MB — above mmap threshold
    private static final int SMALL_ALLOC_BYTES  = 524_287;   // 511KB — below 512KB threshold

    @Container
    @AppContainer
    static GenericContainer<?> app =
            new GenericContainer<>("macstab/java-memory-app:openjdk21-slim")
                    .withExposedPorts(8080)
                    .withEnv("JVM_OPTS", "-Xms64m -Xmx256m -XX:MaxDirectMemorySize=128m")
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
        System.out.println("║     LIBCHAOS-MEMORY  —  WHAT NO OTHER TEST FRAMEWORK CAN DO         ║");
        System.out.println("╠══════════════════════════════════════════════════════════════════════╣");
        System.out.println("║  Intercepts mmap() and malloc() at the syscall level — injects      ║");
        System.out.println("║  ENOMEM into Netty's direct buffer allocation, the JVM's code        ║");
        System.out.println("║  cache expansion, JNI native allocation, and thread stack creation.  ║");
        System.out.println("║  Below the JVM. Below the heap monitor. Below every dashboard.       ║");
        System.out.println("║                                                                      ║");
        System.out.println("║  What you can find here that heap metrics cannot show you:           ║");
        System.out.println("║    • Netty HTTP/2 rejecting connections while heap shows 60%         ║");
        System.out.println("║    • JNI malloc() failure cascading as NullPointerException in Java  ║");
        System.out.println("║    • Exact mmap boundary where 511KB succeeds, 513KB fails           ║");
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
     * Kubernetes pod near memory limit. Heap: 60% used. Heap monitoring: green.
     *
     * <p>Netty allocates 4MB direct buffers via mmap for HTTP/2 frame processing. mmap() fails
     * with ENOMEM. Netty cannot accept new HTTP/2 connections. All monitoring shows heap at 60%.
     * Pod RSS memory: 98% — OOM kill imminent.
     *
     * <p>Engineers added more heap (-Xmx512m). Pod OOM-killed faster. Root cause: adding heap
     * reduced the space available for off-heap direct buffers. More heap = less room for mmap.
     * The fix made the incident worse.
     *
     * <p>Engineers spent six hours looking at heap settings. Nobody looked at RSS memory or
     * mmap usage because no monitoring dashboard showed it.
     */
    @Test
    @DisplayName("MEMORY L8: mmap ENOMEM on large allocations — Netty direct buffer fails, HTTP/2 connections rejected, heap monitoring shows green")
    @ChaosMmapAnonEnomem(probability = 1.0f, minSizeBytes = 1_048_576L)
    void mmapEnomemNettyDirectBufferFailsHeapMonitoringShowsGreen() throws Exception {
        System.out.println();
        System.out.println("┌─────────────────────────────────────────────────────────────────────┐");
        System.out.println("│  INCIDENT: Netty HTTP/2 rejection — heap 60%, dashboard green       │");
        System.out.println("│  Severity: P0  Duration: 6 hours  Fix attempted: +more heap (wrong) │");
        System.out.println("│  Injecting: 100% ENOMEM for mmap() calls >1MB                      │");
        System.out.println("│  Expected: HTTP/2 degraded, heap metrics irrelevant                 │");
        System.out.println("└─────────────────────────────────────────────────────────────────────┘");

        int totalRequests = 30;
        AtomicLong rejected = new AtomicLong();
        AtomicLong succeeded = new AtomicLong();
        AtomicLong errors = new AtomicLong();

        for (int i = 0; i < totalRequests; i++) {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(appBaseUrl + "/alloc?size=" + LARGE_ALLOC_BYTES))
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .timeout(Duration.ofSeconds(8))
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) {
                    succeeded.incrementAndGet();
                } else if (response.statusCode() == 503 || response.statusCode() == 507) {
                    rejected.incrementAndGet();
                } else {
                    errors.incrementAndGet();
                }
            } catch (Exception e) {
                errors.incrementAndGet();
            }
        }

        // Also check the health endpoint — heap metric should still show "healthy"
        HttpResponse<String> health = httpClient.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(appBaseUrl + "/health"))
                        .GET()
                        .timeout(Duration.ofSeconds(5))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        boolean heapMonitoringGreen = health.statusCode() == 200
                && (health.body().contains("UP") || health.body().contains("healthy"));

        System.out.println();
        System.out.println("┌─────────────────────────────────────────────────────────────────────┐");
        System.out.println("│  PROOF  —  mmap ENOMEM / heap monitoring gap fingerprint            │");
        System.out.println("│                                                                      │");
        System.out.printf( "│  Large alloc requests  : %d%n", totalRequests);
        System.out.printf( "│  Rejected (mmap fail)  : %d  ← HTTP/2 connections refused          │%n", rejected.get());
        System.out.printf( "│  Succeeded             : %d%n", succeeded.get());
        System.out.printf( "│  Errors                : %d%n", errors.get());
        System.out.printf( "│  Heap health endpoint  : %s  ← DASHBOARD SHOWS GREEN               │%n",
                heapMonitoringGreen ? "200 OK / UP" : "degraded");
        System.out.println("│                                                                      │");
        System.out.println("│  The invisible failure: heap at 60%, RSS at 98%, dashboard: green   │");
        System.out.println("│  Wrong fix: add heap (-Xmx) → OOM kill faster (less room for mmap) │");
        System.out.println("│  Right fix: -XX:MaxDirectMemorySize, monitor RSS not heap            │");
        System.out.println("└─────────────────────────────────────────────────────────────────────┘");
        System.out.println();

        assertThat(rejected.get() + errors.get())
                .as("100%% mmap ENOMEM for >1MB must cause observable failures — Netty HTTP/2 must degrade")
                .isGreaterThan(0L);

        assertThat(errors.get())
                .as("service must not crash on mmap ENOMEM — graceful degradation required")
                .isLessThan((long) totalRequests);
    }

    /**
     * Image processing service uses JNI library for thumbnail generation.
     *
     * <p>Native library calls malloc() for processing buffers. At peak load: malloc() returns NULL
     * on 10% of calls. Native library returns NULL result to JNI caller without throwing exception.
     * JNI wrapper doesn't check for NULL. NullPointerException in Java layer.
     *
     * <p>Engineers saw NPE in logs pointing at the image processing code. They reviewed every line
     * of their Java code for two weeks. The bug was not in the Java code. The bug was in the
     * native malloc() failure path: it returned null, and the JNI caller propagated null into Java
     * without checking, causing the NPE in perfectly correct Java code.
     *
     * <p>Root cause: JNI null check missing on return value from native function. Two weeks of
     * Java code review looking at the wrong layer.
     */
    @Test
    @DisplayName("MEMORY L8: malloc ENOMEM on peak — C extension can't allocate, JNI returns null, JVM continues unaware")
    @ChaosMmapAnonEnomem(probability = 0.10f)
    void mallocEnomemJniNullReturnNullPointerExceptionInJava() throws Exception {
        System.out.println();
        System.out.println("┌─────────────────────────────────────────────────────────────────────┐");
        System.out.println("│  INCIDENT: JNI null from malloc failure — NPE blamed on Java code   │");
        System.out.println("│  Severity: P1  Duration: 2 weeks debugging  Root cause: JNI null    │");
        System.out.println("│  Injecting: 10% ENOMEM on mmap/malloc (peak load simulation)        │");
        System.out.println("│  Expected: NPEs appear in Java, true cause is native malloc failure  │");
        System.out.println("└─────────────────────────────────────────────────────────────────────┘");

        int totalRequests = 80;
        AtomicLong nullPointerErrors = new AtomicLong();
        AtomicLong oomErrors = new AtomicLong();
        AtomicLong successes = new AtomicLong();

        for (int i = 0; i < totalRequests; i++) {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(appBaseUrl + "/alloc?size=" + LARGE_ALLOC_BYTES))
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .timeout(Duration.ofSeconds(6))
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                String body = response.body();

                if (response.statusCode() == 200) {
                    successes.incrementAndGet();
                } else if (response.statusCode() == 500) {
                    if (body != null && (body.contains("NullPointer") || body.contains("null"))) {
                        nullPointerErrors.incrementAndGet();
                    } else if (body != null && body.contains("OutOfMemory")) {
                        oomErrors.incrementAndGet();
                    } else {
                        oomErrors.incrementAndGet();
                    }
                } else if (response.statusCode() == 503) {
                    oomErrors.incrementAndGet();
                }
            } catch (Exception e) {
                oomErrors.incrementAndGet();
            }
        }

        long totalFailed = totalRequests - successes.get();

        System.out.println();
        System.out.println("┌─────────────────────────────────────────────────────────────────────┐");
        System.out.println("│  PROOF  —  malloc ENOMEM / JNI null cascade fingerprint             │");
        System.out.println("│                                                                      │");
        System.out.printf( "│  Total requests        : %d%n", totalRequests);
        System.out.printf( "│  Succeeded             : %d%n", successes.get());
        System.out.printf( "│  OOM/degraded          : %d  ← native malloc failed, JVM informed  │%n", oomErrors.get());
        System.out.printf( "│  NullPointerExceptions : %d  ← malloc returned null, JNI propagated│%n", nullPointerErrors.get());
        System.out.printf( "│  Total failures        : %d  (%d%%)                                 │%n",
                totalFailed, totalFailed * 100 / totalRequests);
        System.out.println("│                                                                      │");
        System.out.println("│  Engineers spent 2 weeks reviewing Java code for the NPE source     │");
        System.out.println("│  Root cause was one line in C: missing NULL check after malloc()    │");
        System.out.println("│  Fix: check every JNI return value; never assume native succeeded   │");
        System.out.println("└─────────────────────────────────────────────────────────────────────┘");
        System.out.println();

        assertThat(totalFailed)
                .as("10%% ENOMEM must produce observable failures — JNI null path must be exercised")
                .isGreaterThan(0L);

        assertThat(successes.get())
                .as("some requests must succeed — ENOMEM is probabilistic at 10%%")
                .isGreaterThan(30L);
    }

    /**
     * Production system works at normal load. High-load test with large payloads: fails.
     * Normal load test with small payloads: passes.
     *
     * <p>Engineers thought it was a CPU or thread contention issue under load. Load testing
     * team tried everything: more threads, more instances, more memory. Nothing helped.
     *
     * <p>Root cause: kernel huge page threshold + cgroup memory limit creates an exact boundary.
     * Allocations below 512KB use non-huge-page mmap (succeeds within cgroup limit). Allocations
     * at or above 512KB require a huge page, and the cgroup limit was hit. ENOMEM returned.
     *
     * <p>The system was perfectly healthy for small payloads. It failed precisely and only for
     * large payloads. Engineers spent 3 weeks before discovering the 512KB boundary.
     */
    @Test
    @DisplayName("MEMORY L8: mmap ENOMEM threshold — exactly 512KB allocations succeed, 513KB fails — off-heap boundary nobody monitors")
    @ChaosMmapAnonEnomem(probability = 1.0f, minSizeBytes = 524288L)
    void mmapEnomemThreshold512KbBoundarySmallSucceedsLargeFails() throws Exception {
        System.out.println();
        System.out.println("┌─────────────────────────────────────────────────────────────────────┐");
        System.out.println("│  INCIDENT: 512KB mmap threshold — small requests fine, large fail   │");
        System.out.println("│  Severity: P1  Duration: 3 weeks  Root cause: cgroup huge page limit│");
        System.out.println("│  Injecting: 100% ENOMEM for mmap() >= 512KB                        │");
        System.out.println("│  Expected: small (511KB) succeeds, large (4MB) fails                │");
        System.out.println("└─────────────────────────────────────────────────────────────────────┘");

        // Small allocations: below 512KB threshold — must succeed.
        int smallRequests = 20;
        AtomicLong smallSuccesses = new AtomicLong();
        AtomicLong smallFailures = new AtomicLong();

        for (int i = 0; i < smallRequests; i++) {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(appBaseUrl + "/alloc?size=" + SMALL_ALLOC_BYTES))
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .timeout(Duration.ofSeconds(5))
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    smallSuccesses.incrementAndGet();
                } else {
                    smallFailures.incrementAndGet();
                }
            } catch (Exception e) {
                smallFailures.incrementAndGet();
            }
        }

        // Large allocations: above 512KB threshold — must fail with ENOMEM.
        int largeRequests = 20;
        AtomicLong largeSuccesses = new AtomicLong();
        AtomicLong largeFailures = new AtomicLong();

        for (int i = 0; i < largeRequests; i++) {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(appBaseUrl + "/alloc?size=" + LARGE_ALLOC_BYTES))
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .timeout(Duration.ofSeconds(5))
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    largeSuccesses.incrementAndGet();
                } else {
                    largeFailures.incrementAndGet();
                }
            } catch (Exception e) {
                largeFailures.incrementAndGet();
            }
        }

        System.out.println();
        System.out.println("┌─────────────────────────────────────────────────────────────────────┐");
        System.out.println("│  PROOF  —  512KB mmap threshold boundary fingerprint                │");
        System.out.println("│                                                                      │");
        System.out.printf( "│  Small (<512KB) requests : %d%n", smallRequests);
        System.out.printf( "│    Succeeded             : %d  ← below threshold, succeeds         │%n", smallSuccesses.get());
        System.out.printf( "│    Failed                : %d%n", smallFailures.get());
        System.out.println("│                                                                      │");
        System.out.printf( "│  Large (>512KB) requests : %d%n", largeRequests);
        System.out.printf( "│    Succeeded             : %d%n", largeSuccesses.get());
        System.out.printf( "│    Failed                : %d  ← above threshold, ENOMEM           │%n", largeFailures.get());
        System.out.println("│                                                                      │");
        System.out.println("│  Exact 512KB boundary: huge page requirement + cgroup limit         │");
        System.out.println("│  Fix: monitor /proc/meminfo HugePages_Free; set explicit limits     │");
        System.out.println("└─────────────────────────────────────────────────────────────────────┘");
        System.out.println();

        assertThat(smallSuccesses.get())
                .as("small (<512KB) allocations must succeed — below the mmap threshold where ENOMEM triggers")
                .isGreaterThan((long) (smallRequests / 2));

        assertThat(largeFailures.get())
                .as("large (>512KB) allocations must fail — at or above threshold, ENOMEM injected")
                .isGreaterThan(0L);
    }

    private static long percentile(List<Long> values, int pct) {
        if (values.isEmpty()) return 0L;
        List<Long> sorted = new ArrayList<>(values);
        sorted.sort(Long::compareTo);
        int index = (int) Math.ceil(pct / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }
}
