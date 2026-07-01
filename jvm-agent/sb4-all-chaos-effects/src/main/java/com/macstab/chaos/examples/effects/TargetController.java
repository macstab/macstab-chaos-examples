package com.macstab.chaos.examples.effects;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * HTTP entry points that exercise each {@link TargetService} method.
 * Each endpoint isolates one interception point so tests can target chaos precisely.
 */
@RestController
public class TargetController {

    private final TargetService targetService;

    /** Constructor injection. */
    public TargetController(TargetService targetService) {
        this.targetService = targetService;
    }

    /**
     * Submits {@code count} tasks to the executor and reports results.
     *
     * @param count number of tasks (default 10)
     * @return JSON: {submitted, succeeded, nullResults}
     */
    @GetMapping("/tasks")
    public Map<String, Object> tasks(@RequestParam(defaultValue = "10") int count) {
        List<String> results = targetService.executeTasks(count);
        long succeeded = results.stream().filter(r -> r != null).count();
        long nullResults = results.stream().filter(r -> r == null).count();
        return Map.of(
                "submitted", count,
                "succeeded", succeeded,
                "nullResults", nullResults
        );
    }

    /**
     * Fetches the given URL via {@link TargetService#fetchFromNetwork}.
     *
     * @param url target URL
     * @return JSON: {body} — body is null when the agent corrupted the return value
     */
    @GetMapping("/fetch")
    public Map<String, Object> fetch(@RequestParam String url) {
        String body;
        try {
            body = targetService.fetchFromNetwork(url);
        } catch (Exception e) {
            body = null;
        }
        return Map.of(
                "body", body != null ? body : "null",
                "isNull", body == null
        );
    }

    /**
     * Executes the given SQL via {@link TargetService#executeQuery}.
     *
     * @param sql SQL query (default: select all events)
     * @return JSON: {rowCount, error} — error is non-null when the agent injected an exception
     */
    @GetMapping("/query")
    public Map<String, Object> query(
            @RequestParam(defaultValue = "SELECT name FROM events") String sql) {
        try {
            int rowCount = targetService.executeQuery(sql);
            return Map.of("rowCount", rowCount, "error", false);
        } catch (Exception e) {
            return Map.of("rowCount", -1, "error", true, "message", e.getMessage());
        }
    }

    /**
     * Creates {@code count} CompletableFutures via {@link TargetService#completeFutures}.
     *
     * @param count number of futures (default 10)
     * @return JSON: {total, succeeded, failed}
     */
    @GetMapping("/futures")
    public Map<String, Object> futures(@RequestParam(defaultValue = "10") int count) {
        List<String> results = targetService.completeFutures(count);
        long succeeded = results.stream().filter(r -> r != null).count();
        long failed = results.stream().filter(r -> r == null).count();
        return Map.of(
                "total", count,
                "succeeded", succeeded,
                "failed", failed
        );
    }

    /**
     * Returns the current time via {@link TargetService#getCurrentTime()}.
     *
     * @return JSON: {currentTimeMillis}
     */
    @GetMapping("/time")
    public Map<String, Object> time() {
        return Map.of("currentTimeMillis", targetService.getCurrentTime());
    }
}
