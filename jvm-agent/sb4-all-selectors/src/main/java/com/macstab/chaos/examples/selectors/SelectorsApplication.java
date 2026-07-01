package com.macstab.chaos.examples.selectors;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Application demonstrating all 13 chaos selector types supported by the macstab chaos agent.
 *
 * Beans exposed for injection into tests cover: platform thread pools, scheduled executors,
 * blocking queues, JDBC data sources, HTTP clients, and virtual thread executors.
 * A recurring @Scheduled method increments a counter so tests can verify scheduled
 * execution is not silently dropped under chaos conditions.
 */
@SpringBootApplication
@EnableScheduling
public class SelectorsApplication {

    public static void main(String[] args) {
        SpringApplication.run(SelectorsApplication.class, args);
    }

    /**
     * Fixed-size platform thread pool with 4 workers.  Used by executor-selector tests
     * that need a bounded pool to make timing math predictable.
     */
    @Bean(name = "platformThreadPool", destroyMethod = "shutdownNow")
    public ThreadPoolExecutor platformThreadPool() {
        return new ThreadPoolExecutor(
                4, 4,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(200),
                Thread.ofPlatform().name("platform-worker-", 0).factory()
        );
    }

    /**
     * Scheduled executor for chaos selector TEST 4 (SCHEDULING) and TEST 7 (SHUTDOWN).
     * Uses platform threads so shutdown timing is deterministic.
     */
    @Bean(name = "scheduledPool", destroyMethod = "shutdownNow")
    public ScheduledExecutorService scheduledPool() {
        return Executors.newScheduledThreadPool(
                4,
                Thread.ofPlatform().name("sched-worker-", 0).factory()
        );
    }

    /**
     * Shared blocking queue.  The producer/consumer test (TEST 5) puts and takes items
     * through this queue while chaos injects latency on QUEUE_PUT and QUEUE_TAKE.
     */
    @Bean
    public LinkedBlockingQueue<String> sharedQueue() {
        return new LinkedBlockingQueue<>(500);
    }

    /**
     * Java 11+ HTTP client backed by the virtual-thread executor.  Used for NIO-selector
     * and network-selector tests that need real socket I/O so the agent can intercept
     * NIO_CHANNEL_READ, NIO_CHANNEL_WRITE, and SOCKET_CONNECT operations.
     */
    @Bean
    public HttpClient httpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .build();
    }

    /**
     * Virtual-thread-per-task executor — used by tests that mix virtual and platform
     * thread behaviours to ensure the agent distinguishes them correctly.
     */
    @Bean(name = "virtualThreadExecutor", destroyMethod = "shutdownNow")
    public ExecutorService virtualThreadExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    // -------------------------------------------------------------------------
    // Scheduled heartbeat counter — observed by TEST 8 and GC-pressure scenarios
    // -------------------------------------------------------------------------

    /**
     * Counts every scheduled invocation.  Tests read this value via
     * {@link ScheduledCounter#get()} to verify that scheduled tasks are not dropped.
     */
    @Bean
    public ScheduledCounter scheduledCounter() {
        return new ScheduledCounter();
    }

    @Scheduled(fixedDelay = 100)
    public void heartbeat() {
        scheduledCounter().increment();
    }

    // -------------------------------------------------------------------------
    // UserService — used by TEST 9 (METHOD selector on findUser)
    // -------------------------------------------------------------------------

    /**
     * Simple user lookup service.  The chaos agent intercepts METHOD_ENTER on
     * {@code findUser} to inject latency, validating that method-level interception
     * works for arbitrary application methods.
     */
    @Bean
    public UserService userService(JdbcTemplate jdbc) {
        return new UserService(jdbc);
    }

    // -------------------------------------------------------------------------
    // Inner types
    // -------------------------------------------------------------------------

    /** Atomic counter exposed as a bean so both the scheduler and tests can share it. */
    public static class ScheduledCounter {

        private final AtomicLong count = new AtomicLong(0);

        /** Increment by one.  Called by the @Scheduled heartbeat. */
        public void increment() {
            count.incrementAndGet();
        }

        /** Return the current count without resetting it. */
        public long get() {
            return count.get();
        }
    }

    /**
     * Application service whose {@code findUser} method is the explicit target of the
     * METHOD selector in TEST 9.  Backed by the H2 in-memory database so tests do not
     * need an external data store.
     */
    @Service
    public static class UserService {

        private final JdbcTemplate jdbc;

        public UserService(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        /**
         * Looks up a user by name.
         *
         * @param name the user's name
         * @return the user record, or {@code null} if not found
         */
        public UserRecord findUser(String name) {
            return jdbc.query(
                    "SELECT id, name FROM users WHERE name = ?",
                    rs -> rs.next() ? new UserRecord(rs.getLong("id"), rs.getString("name")) : null,
                    name
            );
        }
    }

    /**
     * Immutable user record returned by {@link UserService#findUser(String)}.
     *
     * @param id   database primary key
     * @param name display name
     */
    public record UserRecord(long id, String name) {}
}
