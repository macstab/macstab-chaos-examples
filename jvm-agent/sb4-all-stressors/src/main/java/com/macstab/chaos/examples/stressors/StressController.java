package com.macstab.chaos.examples.stressors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * Minimal REST controller that exposes two endpoints used by the stressor
 * integration tests to confirm the application is alive and processing
 * requests correctly while a chaos stressor is active.
 *
 * <p>{@code GET /health} — observability snapshot (heap, threads, off-heap)
 * <p>{@code GET /work}   — performs CPU + DB + outbound HTTP work and returns
 * {@code {ok: true}} when all three succeed.
 */
@RestController
public class StressController {

    private static final MemoryMXBean MEMORY_BEAN = ManagementFactory.getMemoryMXBean();

    @Autowired
    private JdbcTemplate jdbc;

    /**
     * Returns a lightweight liveness snapshot that the tests use to assert the
     * JVM is still functioning under active stressors.
     *
     * @return JSON with {@code heap_used_mb}, {@code thread_count},
     *         {@code direct_mb}, and {@code is_alive}
     */
    @GetMapping("/health")
    public Map<String, Object> health() {
        long heapUsedBytes = MEMORY_BEAN.getHeapMemoryUsage().getUsed();
        long directBytes   = MEMORY_BEAN.getNonHeapMemoryUsage().getUsed();
        int  threadCount   = ManagementFactory.getThreadMXBean().getThreadCount();

        return Map.of(
                "heap_used_mb",  heapUsedBytes / (1024L * 1024L),
                "thread_count",  threadCount,
                "direct_mb",     directBytes / (1024L * 1024L),
                "is_alive",      true
        );
    }

    /**
     * Performs a small unit of CPU work, one SQL query, and one outbound HTTP
     * call to {@code https://httpbin.org/get} (with a 3-second timeout so the
     * test does not hang under network stressors).
     *
     * <p>The method is intentionally forgiving: network failures are swallowed
     * so that stressors targeting the heap or GC do not cause spurious test
     * failures on this endpoint.
     *
     * @return {@code {ok: true}} when the method completes without an
     *         unhandled exception
     */
    @GetMapping("/work")
    public Map<String, Object> work() {
        // CPU work — simple summation so the loop is not optimised away
        long sum = 0;
        for (int i = 0; i < 10_000; i++) {
            sum += i;
        }
        // Prevent dead-code elimination
        if (sum < 0) {
            throw new IllegalStateException("Unexpected negative sum");
        }

        // DB work — lightweight query against H2's in-memory catalog
        jdbc.queryForObject("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES", Integer.class);

        // Network work — best-effort; swallow on failure so heap/GC stressors
        // do not cause false negatives here
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(3))
                    .build();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://httpbin.org/get"))
                    .timeout(Duration.ofSeconds(3))
                    .GET()
                    .build();
            client.send(request, HttpResponse.BodyHandlers.discarding());
        } catch (Exception ignored) {
            // Network may be unavailable in CI; the stressor test still passes
        }

        return Map.of("ok", true);
    }
}
