package com.macstab.chaos.examples.impossible;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * Fan-out service: fires {@code count} parallel tasks, each doing an HTTP GET to the
 * downstream stub and an INSERT into the events table.  Returns a list of per-task
 * results so callers can tally successes and failures independently.
 *
 * The HTTP base URL is configurable so tests can point it at WireMock.
 */
@Service
public class FanOutService {

    private static final Logger log = LoggerFactory.getLogger(FanOutService.class);

    private static final String INSERT_EVENT =
            "INSERT INTO events (name) VALUES (?)";

    private final ExecutorService executor;
    private final JdbcTemplate jdbc;
    private final HttpClient httpClient;

    @Value("${fanout.downstream.url:http://localhost:8099}")
    private String downstreamUrl;

    public FanOutService(
            @Qualifier("virtualThreadExecutor") ExecutorService executor,
            JdbcTemplate jdbc
    ) {
        this.executor = executor;
        this.jdbc = jdbc;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .executor(executor)
                .build();
    }

    /**
     * Result of a single fan-out task.
     *
     * @param index     task index (0-based)
     * @param succeeded true if both HTTP and DB steps completed without exception
     * @param error     exception message if {@code succeeded} is false, otherwise null
     */
    public record TaskResult(int index, boolean succeeded, String error) {
        static TaskResult ok(int index) {
            return new TaskResult(index, true, null);
        }

        static TaskResult failed(int index, Throwable t) {
            return new TaskResult(index, false, t.getMessage());
        }
    }

    /**
     * Fires {@code count} CompletableFuture tasks in parallel on the virtual thread executor.
     * Each task: HTTP GET to downstream + INSERT into events.  Partial failures are captured
     * in the result list rather than propagated as exceptions.
     *
     * @param count number of parallel tasks
     * @return list of per-task results, length == count
     */
    public List<TaskResult> fanOut(int count) {
        List<CompletableFuture<TaskResult>> futures = new ArrayList<>(count);

        for (int i = 0; i < count; i++) {
            final int idx = i;
            CompletableFuture<TaskResult> future = CompletableFuture.supplyAsync(() -> {
                try {
                    // HTTP step — chaos agent may inject latency, RST, or rejection here
                    HttpRequest req = HttpRequest.newBuilder()
                            .uri(URI.create(downstreamUrl + "/downstream"))
                            .GET()
                            .timeout(Duration.ofSeconds(5))
                            .build();
                    httpClient.send(req, HttpResponse.BodyHandlers.discarding());

                    // DB step — chaos agent may inject JDBC faults or null return values here
                    String name = "event-" + UUID.randomUUID().toString().substring(0, 8);
                    jdbc.update(INSERT_EVENT, name);

                    return TaskResult.ok(idx);
                } catch (Exception e) {
                    log.debug("Task {} failed: {}", idx, e.getMessage());
                    return TaskResult.failed(idx, e);
                }
            }, executor);

            futures.add(future);
        }

        // Wait for all — exceptional completions are captured in TaskResult.failed, not thrown
        return futures.stream()
                .map(f -> {
                    try {
                        return f.join();
                    } catch (Exception e) {
                        return TaskResult.failed(-1, e);
                    }
                })
                .toList();
    }

    /** Exposed for tests to reconfigure the downstream URL (e.g. WireMock port). */
    public void setDownstreamUrl(String url) {
        this.downstreamUrl = url;
    }
}
