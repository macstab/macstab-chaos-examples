package com.macstab.chaos.examples.impossible;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tracks how many times a fixed-rate scheduled task has fired.
 *
 * Used by testGcPressureDoesNotDropScheduledTasks to verify that GC pressure
 * (500MB/s allocation rate injected by the chaos agent) does not starve the
 * scheduler and cause task invocations to be dropped.
 *
 * The scheduler fires at 100ms intervals (~10/s).  Over a 5-second observation
 * window the counter should increment by at least 40 (allowing 20% GC-induced jitter).
 */
@RestController
@RequestMapping("/scheduler")
public class SchedulerController {

    private final AtomicLong fireCount = new AtomicLong(0L);

    /**
     * Increments the fire counter at a fixed rate of 100ms.
     * The scheduled executor is the shared 10-thread pool from {@link ImpossibleApplication}.
     */
    @Scheduled(fixedRate = 100)
    public void tick() {
        fireCount.incrementAndGet();
    }

    /**
     * Returns the current fire count.
     *
     * @return JSON: {count}
     */
    @GetMapping("/count")
    public Map<String, Object> count() {
        return Map.of("count", fireCount.get());
    }
}
