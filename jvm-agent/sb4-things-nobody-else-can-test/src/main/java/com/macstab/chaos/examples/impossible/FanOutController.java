package com.macstab.chaos.examples.impossible;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * HTTP entry point for the fan-out scenario.
 * GET /fanout?count=N triggers N parallel downstream+DB tasks and returns a summary.
 */
@RestController
public class FanOutController {

    private final FanOutService fanOutService;

    public FanOutController(FanOutService fanOutService) {
        this.fanOutService = fanOutService;
    }

    /**
     * Fires {@code count} parallel fan-out tasks.
     *
     * @param count number of parallel tasks (default 10)
     * @return JSON object: {succeeded, failed, wallMs}
     */
    @GetMapping("/fanout")
    public Map<String, Object> fanout(@RequestParam(defaultValue = "10") int count) {
        long startNs = System.nanoTime();

        List<FanOutService.TaskResult> results = fanOutService.fanOut(count);

        long wallMs = (System.nanoTime() - startNs) / 1_000_000L;

        long succeeded = results.stream().filter(FanOutService.TaskResult::succeeded).count();
        long failed = results.stream().filter(r -> !r.succeeded()).count();

        return Map.of(
                "succeeded", succeeded,
                "failed", failed,
                "wallMs", wallMs,
                "count", count
        );
    }
}
