package com.macstab.chaos.examples.effects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Service providing one method per agent interception point so that each chaos effect
 * can be targeted precisely in integration tests.
 *
 * <ul>
 *   <li>{@link #executeTasks} — submits work via a {@link ThreadPoolExecutor}</li>
 *   <li>{@link #fetchFromNetwork} — fires an outbound {@link HttpClient#send} call</li>
 *   <li>{@link #executeQuery} — runs a {@link JdbcTemplate} query</li>
 *   <li>{@link #completeFutures} — builds a {@link CompletableFuture} chain</li>
 *   <li>{@link #getCurrentTime} — calls {@link System#currentTimeMillis()}</li>
 * </ul>
 */
@Service
public class TargetService {

    private static final Logger log = LoggerFactory.getLogger(TargetService.class);

    private final JdbcTemplate jdbc;
    private final HttpClient httpClient;

    /** Bounded thread pool sized to expose EXECUTOR_SUBMIT interception behaviour. */
    private final ThreadPoolExecutor taskExecutor;

    public TargetService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        this.taskExecutor = (ThreadPoolExecutor) Executors.newFixedThreadPool(10);
    }

    /**
     * Submits {@code count} tasks to a bounded {@link ThreadPoolExecutor} and collects
     * their string results.  The chaos agent can intercept {@code EXECUTOR_SUBMIT} on
     * this pool to inject delays, gates, rejections, or suppressions.
     *
     * @param count number of tasks to submit
     * @return list of task results; suppressed submissions appear as {@code null}
     */
    public List<String> executeTasks(int count) {
        List<Future<String>> futures = new ArrayList<>(count);

        for (int i = 0; i < count; i++) {
            final int index = i;
            try {
                Future<String> f = taskExecutor.submit(() -> "result-" + index);
                futures.add(f);
            } catch (Exception e) {
                log.debug("Task {} submission failed: {}", index, e.getMessage());
                futures.add(null);
            }
        }

        List<String> results = new ArrayList<>(count);
        for (Future<String> f : futures) {
            if (f == null) {
                results.add(null);
                continue;
            }
            try {
                results.add(f.get(5, TimeUnit.SECONDS));
            } catch (Exception e) {
                log.debug("Task result retrieval failed: {}", e.getMessage());
                results.add(null);
            }
        }
        return results;
    }

    /**
     * Sends an HTTP GET to {@code url} using {@link HttpClient#send} and returns the
     * response body as a string.  The chaos agent can intercept {@code HTTP_CLIENT_SEND}
     * or {@code METHOD_EXIT} on this method to corrupt the return value.
     *
     * @param url the target URL
     * @return response body, or {@code null} if the agent corrupted the return value
     * @throws Exception if the HTTP call fails
     */
    public String fetchFromNetwork(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .timeout(Duration.ofSeconds(5))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        return response.body();
    }

    /**
     * Executes a SQL query via {@link JdbcTemplate} and returns the number of rows
     * returned.  The chaos agent can intercept {@code METHOD_ENTER} on this method to
     * inject checked exceptions such as {@link java.sql.SQLException}.
     *
     * @param sql the SQL query to run (must return rows with at least a {@code name} column)
     * @return number of rows in the result set
     */
    public int executeQuery(String sql) {
        List<String> rows = jdbc.query(sql, (rs, rowNum) -> rs.getString(1));
        return rows.size();
    }

    /**
     * Creates a chain of {@code count} {@link CompletableFuture} tasks, each returning a
     * string result.  The chaos agent can intercept {@code ASYNC_COMPLETE} or
     * {@code ASYNC_COMPLETE_EXCEPTIONALLY} to inject exceptional completions.
     *
     * @param count number of futures to create
     * @return list of results; futures that completed exceptionally appear as {@code null}
     */
    public List<String> completeFutures(int count) {
        ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor();
        List<CompletableFuture<String>> futures = new ArrayList<>(count);

        for (int i = 0; i < count; i++) {
            final int index = i;
            CompletableFuture<String> cf = CompletableFuture.supplyAsync(
                    () -> "future-result-" + index,
                    exec
            );
            futures.add(cf);
        }

        List<String> results = new ArrayList<>(count);
        for (CompletableFuture<String> cf : futures) {
            try {
                results.add(cf.join());
            } catch (Exception e) {
                log.debug("Future completed exceptionally: {}", e.getMessage());
                results.add(null);
            }
        }

        exec.shutdownNow();
        return results;
    }

    /**
     * Returns the current wall-clock time in milliseconds via {@link System#currentTimeMillis()}.
     * The chaos agent can intercept {@code SYSTEM_CLOCK_MILLIS} with a clock-skew effect to
     * shift the value returned to this caller.
     *
     * @return current time in milliseconds since the Unix epoch, possibly skewed
     */
    public long getCurrentTime() {
        return System.currentTimeMillis();
    }

    /**
     * Inserts a row into the {@code events} table so JDBC tests have data to query back.
     *
     * @return the generated event name
     */
    public String insertEvent() {
        String name = "event-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("INSERT INTO events (name) VALUES (?)", name);
        return name;
    }
}
