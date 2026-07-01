package com.macstab.chaos.examples.jdbcdeadlock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Triggers the JDBC pool deadlock scenario by firing 10 concurrent processOrder() calls.
 *
 * Each call requires 2 connections simultaneously (outer C1 + inner REQUIRES_NEW C2).
 * With pool-size=5, 5 concurrent outer transactions exhaust the pool before any inner
 * transaction can obtain C2, resulting in a deadlock.
 */
@RestController
public class DeadlockController {

    private static final Logger log = LoggerFactory.getLogger(DeadlockController.class);

    private static final int CONCURRENT_CALLS = 10;

    private final OuterService outerService;

    public DeadlockController(OuterService outerService) {
        this.outerService = outerService;
    }

    /**
     * Fires {@value #CONCURRENT_CALLS} concurrent processOrder() calls using virtual threads.
     * Returns a summary of how many succeeded, how many failed, and total wall-clock time.
     */
    @GetMapping("/trigger")
    public Map<String, Object> trigger() {
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);

        long startMs = Instant.now().toEpochMilli();

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>(CONCURRENT_CALLS);

            for (int i = 0; i < CONCURRENT_CALLS; i++) {
                String ref = "ORDER-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
                futures.add(executor.submit(() -> {
                    try {
                        long id = outerService.processOrder(ref);
                        log.debug("processOrder succeeded, id={}", id);
                        successCount.incrementAndGet();
                    } catch (Exception e) {
                        log.warn("processOrder failed for ref={}: {}", ref, e.getMessage());
                        failureCount.incrementAndGet();
                    }
                }));
            }

            for (Future<?> f : futures) {
                try {
                    f.get(35, TimeUnit.SECONDS);
                } catch (Exception e) {
                    failureCount.incrementAndGet();
                }
            }
        }

        long totalMs = Instant.now().toEpochMilli() - startMs;

        return Map.of(
                "success_count", successCount.get(),
                "failure_count", failureCount.get(),
                "total_ms", totalMs,
                "concurrent_calls", CONCURRENT_CALLS
        );
    }
}
