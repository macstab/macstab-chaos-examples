package com.macstab.chaos.examples.impossible;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Spring Boot 4 application demonstrating chaos scenarios that no other testing
 * approach can reliably reproduce:
 * <ul>
 *   <li>JVM safepoint cascades</li>
 *   <li>Code cache exhaustion / JIT suppression</li>
 *   <li>Virtual thread carrier pinning under monitor contention</li>
 *   <li>ThreadLocal leaks on pooled threads</li>
 *   <li>CompletableFuture exceptional completion propagation</li>
 *   <li>Live deadlock coexisting with normal request serving</li>
 *   <li>Return value corruption from JDBC</li>
 *   <li>GC pressure against scheduled tasks</li>
 * </ul>
 */
@SpringBootApplication
@EnableScheduling
public class ImpossibleApplication {

    public static void main(String[] args) {
        SpringApplication.run(ImpossibleApplication.class, args);
    }

    /**
     * Shared scheduled executor for background tasks (e.g. heartbeat counter in
     * {@link SchedulerController}).  10 threads so GC-induced pauses on one thread
     * do not stop all scheduled tasks.
     */
    @Bean(destroyMethod = "shutdownNow")
    public ScheduledExecutorService scheduledExecutorService() {
        return Executors.newScheduledThreadPool(10);
    }

    /**
     * Virtual-thread executor for fan-out HTTP + DB work.  Virtual threads are cheap
     * enough to create per-task, so no pool bounding is needed here.
     */
    @Bean(name = "virtualThreadExecutor", destroyMethod = "shutdownNow")
    public ExecutorService virtualThreadExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
