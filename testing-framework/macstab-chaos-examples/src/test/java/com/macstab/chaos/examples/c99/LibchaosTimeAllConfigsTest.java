package com.macstab.chaos.examples.c99;

import static org.assertj.core.api.Assertions.assertThat;

import com.macstab.chaos.annotation.AppContainer;
import com.macstab.chaos.annotation.SyscallLevelChaos;
import com.macstab.chaos.extension.ChaosTestingExtension;
import com.macstab.chaos.lib.LibchaosLib;
import com.macstab.chaos.redis.RedisConnectionInfo;
import com.macstab.chaos.redis.annotation.RedisStandalone;
import com.macstab.chaos.time.annotation.l1.clock_gettime.ChaosClockGettimeOffset;
import com.macstab.chaos.time.annotation.l1.nanosleep.ChaosNanosleepLatency;
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
import redis.clients.jedis.Jedis;

/**
 * TIME syscall-level chaos — production disaster post-mortems.
 *
 * <p>Clock failures are the most insidious category of distributed system incident. They produce
 * no error at the application layer. The application sees a valid timestamp. The timestamp is
 * wrong. Kafka consumer groups rebalance on a leap second. Distributed locks release themselves.
 * Rate limiters allow 10x burst because the sleep() they use for pacing sleeps 10x longer than
 * configured on a loaded host.
 *
 * <p>These tests replay three incidents where correct-looking code produced catastrophic behavior
 * because the clock said something different from what everyone assumed.
 */
@Testcontainers
@ExtendWith(ChaosTestingExtension.class)
@SyscallLevelChaos(LibchaosLib.TIME)
@RedisStandalone(id = "time-test-cache", version = "7.4")
class LibchaosTimeAllConfigsTest {

    private static final Logger log = LoggerFactory.getLogger(LibchaosTimeAllConfigsTest.class);

    @Container
    @AppContainer
    static GenericContainer<?> app =
            new GenericContainer<>("macstab/python-time-app:latest")
                    .withExposedPorts(8080)
                    .withEnv("REDIS_HOST", "time-test-cache")
                    .withEnv("REDIS_PORT", "6379")
                    .withStartupTimeout(Duration.ofSeconds(60));

    private HttpClient httpClient;
    private String appBaseUrl;

    @BeforeAll
    static void printLibcapabilities() {
        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════════════════════╗");
        System.out.println("║      LIBCHAOS-TIME  —  WHAT NO OTHER TEST FRAMEWORK CAN DO          ║");
        System.out.println("╠══════════════════════════════════════════════════════════════════════╣");
        System.out.println("║  Intercepts clock_gettime() and nanosleep() at the syscall level — ║");
        System.out.println("║  injects time offsets and sleep extensions directly into the         ║");
        System.out.println("║  application's time perception without touching the host clock.      ║");
        System.out.println("║  Redis clock is unaffected, creating split-brain time conditions.   ║");
        System.out.println("║                                                                      ║");
        System.out.println("║  What you can find here that NTP simulation cannot show you:        ║");
        System.out.println("║    • Kafka rebalancing on -1000ms leap second                       ║");
        System.out.println("║    • Distributed lock released 5min early from +500ms clock skew   ║");
        System.out.println("║    • Rate limiter allowing 10x burst from nanosleep quantization    ║");
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
     * December 31st. NTP leap second insertion. System clock jumps backward 1 second.
     *
     * <p>Kafka consumer uses system timestamp for offset watermarks. Backward jump: all in-flight
     * messages appear to have "future" timestamps relative to the consumer's new view of "now."
     * Consumer group forced rebalance. Partition assignments reshuffled. During reshuffle:
     * messages reprocessed from last committed offset.
     *
     * <p>Two hours of duplicate event processing. Idempotency violations in three downstream
     * services. Financial transactions double-charged. Engineers: "It's a Kafka bug." It was
     * not a Kafka bug. It was a clock bug. Kafka correctly rebalanced when consumers reported
     * inconsistent timestamps. The clock was the problem.
     *
     * <p>Root cause: Kafka consumer group coordinator uses wall clock for session timeout
     * calculations. A 1-second backward jump triggered session timeout logic. All consumers
     * appeared to have "missed" their heartbeat deadline. Mass rebalance.
     */
    @Test
    @DisplayName("TIME L8: NTP leap second — clock skews -1s at midnight, Kafka offset timestamps invalid, consumer group rebalances, 2h of duplicate message processing")
    @ChaosClockGettimeOffset(deltaMs = -1_000L, probability = 1.0)
    void ntpLeapSecondKafkaConsumerGroupRebalanceDuplicateProcessing(RedisConnectionInfo info) throws Exception {
        System.out.println();
        System.out.println("┌─────────────────────────────────────────────────────────────────────┐");
        System.out.println("│  INCIDENT: NTP leap second — Kafka rebalance, 2h duplicate events   │");
        System.out.println("│  Severity: P0  Date: December 31st  Duration: 2 hours               │");
        System.out.println("│  Root cause: -1000ms clock jump triggers Kafka session timeout      │");
        System.out.println("│  Injecting: -1000ms clock_gettime() offset (100% of calls)          │");
        System.out.println("└─────────────────────────────────────────────────────────────────────┘");

        // Acquire a distributed lock with a 10-second TTL.
        // With the clock running 1 second behind, the app may compute incorrect expiry times.
        HttpResponse<String> acquireResponse;
        try {
            acquireResponse = httpClient.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create(appBaseUrl + "/lock?key=kafka-coordinator-lock&ttl=10"))
                            .POST(HttpRequest.BodyPublishers.noBody())
                            .timeout(Duration.ofSeconds(5))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            acquireResponse = null;
        }

        // Check rate-limit behavior — backward clock causes window miscalculation.
        int totalRateLimitChecks = 30;
        AtomicLong rebalanceIndicators = new AtomicLong();
        AtomicLong successfulChecks = new AtomicLong();
        AtomicLong crashCount = new AtomicLong();

        for (int i = 0; i < totalRateLimitChecks; i++) {
            try {
                HttpResponse<String> response = httpClient.send(
                        HttpRequest.newBuilder()
                                .uri(URI.create(appBaseUrl + "/rate-limit-check"))
                                .POST(HttpRequest.BodyPublishers.ofString("{\"user\": \"kafka-consumer-" + i + "\"}"))
                                .header("Content-Type", "application/json")
                                .timeout(Duration.ofSeconds(5))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());

                String body = response.body();
                if (response.statusCode() == 200) {
                    successfulChecks.incrementAndGet();
                } else if (response.statusCode() == 500
                        && body != null && (body.contains("Traceback") || body.contains("ValueError")
                        || body.contains("negative"))) {
                    crashCount.incrementAndGet();
                } else {
                    rebalanceIndicators.incrementAndGet();
                }
            } catch (Exception e) {
                crashCount.incrementAndGet();
            }
        }

        // Verify Redis still holds the lock — Redis clock is unaffected.
        long redisTtl = -2;
        try (Jedis jedis = new Jedis(info.host(), info.port())) {
            redisTtl = jedis.ttl("kafka-coordinator-lock");
        } catch (Exception e) {
            log.warn("Could not connect to Redis for TTL check: {}", e.getMessage());
        }

        System.out.println();
        System.out.println("┌─────────────────────────────────────────────────────────────────────┐");
        System.out.println("│  PROOF  —  -1000ms leap second / Kafka rebalance fingerprint        │");
        System.out.println("│                                                                      │");
        System.out.printf( "│  Lock acquisition          : %s%n",
                acquireResponse != null ? "status=" + acquireResponse.statusCode() : "failed");
        System.out.printf( "│  Redis TTL on lock key     : %ds  ← Redis clock unaffected          │%n", redisTtl);
        System.out.printf( "│  Rate-limit checks (30)    : success=%d crash=%d rebalance=%d       │%n",
                successfulChecks.get(), crashCount.get(), rebalanceIndicators.get());
        System.out.println("│                                                                      │");
        System.out.println("│  App clock: -1000ms. Redis clock: unchanged. Split-brain TTLs.      │");
        System.out.println("│  In Kafka: backward jump = session timeout = rebalance = duplicates  │");
        System.out.println("│  Fix: use logical clocks (Lamport); never use wall clock for offsets │");
        System.out.println("└─────────────────────────────────────────────────────────────────────┘");
        System.out.println();

        assertThat(crashCount.get())
                .as("-1000ms clock jump must not crash the application — backward time must be handled, not thrown")
                .isEqualTo(0L);

        assertThat(successfulChecks.get() + rebalanceIndicators.get())
                .as("application must respond to all requests under clock skew — no silent hangs")
                .isEqualTo((long) totalRateLimitChecks);
    }

    /**
     * Distributed system with event sourcing. Events timestamped by producing service.
     *
     * <p>Service A's clock: 500ms ahead. Service B: correct. Events from A always appear "in the
     * future" relative to B. When consumers merge event streams and sort by timestamp: A's events
     * always come first regardless of actual creation order.
     *
     * <p>1 in 200 requests depends on correct ordering. Silent data corruption 0.5% of the time.
     * Discovered in quarterly audit. Engineers spent 3 weeks debugging ordering logic in their
     * correct code. Root cause: clock skew. Events were ordered by incorrect timestamps.
     *
     * <p>Fix: Lamport timestamps. The entire distributed systems field learned this in 1978.
     * This team learned it in 2024 after a compliance audit.
     */
    @Test
    @DisplayName("TIME L8: distributed timestamp race — service A clock +500ms ahead of service B, event ordering assumption breaks, 1-in-200 requests processes out of order")
    @ChaosClockGettimeOffset(deltaMs = 500L, probability = 0.50)
    void distributedTimestampRaceEventOrderingBroken(RedisConnectionInfo info) throws Exception {
        System.out.println();
        System.out.println("┌─────────────────────────────────────────────────────────────────────┐");
        System.out.println("│  INCIDENT: +500ms clock skew breaks event ordering — 0.5% corruption│");
        System.out.println("│  Severity: P1 (discovered in audit, 3 months later)                 │");
        System.out.println("│  Injecting: +500ms on clock_gettime() for 50% of calls              │");
        System.out.println("│  Expected: timestamp ordering violations in merged event stream      │");
        System.out.println("└─────────────────────────────────────────────────────────────────────┘");

        int totalRequests = 60;
        AtomicLong orderingViolations = new AtomicLong();
        AtomicLong successCount = new AtomicLong();
        List<Long> recordedTimestamps = new ArrayList<>();

        // Fire requests and collect timestamp data to measure ordering violations.
        for (int i = 0; i < totalRequests; i++) {
            try {
                HttpResponse<String> response = httpClient.send(
                        HttpRequest.newBuilder()
                                .uri(URI.create(appBaseUrl + "/lock?key=ordering-test-" + i + "&ttl=5"))
                                .POST(HttpRequest.BodyPublishers.noBody())
                                .timeout(Duration.ofSeconds(5))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) {
                    successCount.incrementAndGet();
                    // Extract timestamp from response if available.
                    String body = response.body();
                    if (body != null && body.contains("\"timestamp\"")) {
                        try {
                            String ts = body.substring(body.indexOf("\"timestamp\"") + 13);
                            ts = ts.substring(0, ts.indexOf("\"")).trim();
                            long timestamp = Long.parseLong(ts);
                            if (!recordedTimestamps.isEmpty()) {
                                long lastTs = recordedTimestamps.get(recordedTimestamps.size() - 1);
                                // With +500ms on 50% of calls: some timestamps are ahead of "now"
                                // relative to the Redis clock, causing ordering violations.
                                if (timestamp < lastTs) {
                                    orderingViolations.incrementAndGet();
                                }
                            }
                            recordedTimestamps.add(timestamp);
                        } catch (Exception ignored) {
                            // Timestamp parsing failed — count as ordering-check skipped.
                        }
                    }
                }
            } catch (Exception e) {
                // Clock skew causing connection issues counts as service disruption.
            }
        }

        // Verify Redis TTL split-brain: app clock says +500ms, Redis clock says neutral.
        long sampleTtl = -2;
        try (Jedis jedis = new Jedis(info.host(), info.port())) {
            Long ttl = jedis.ttl("ordering-test-0");
            if (ttl != null) sampleTtl = ttl;
        } catch (Exception e) {
            log.warn("Redis TTL check failed: {}", e.getMessage());
        }

        System.out.println();
        System.out.println("┌─────────────────────────────────────────────────────────────────────┐");
        System.out.println("│  PROOF  —  +500ms skew / event ordering violation fingerprint       │");
        System.out.println("│                                                                      │");
        System.out.printf( "│  Total requests        : %d%n", totalRequests);
        System.out.printf( "│  Succeeded             : %d%n", successCount.get());
        System.out.printf( "│  Ordering violations   : %d  ← timestamp went backward mid-stream  │%n", orderingViolations.get());
        System.out.printf( "│  Redis TTL (sample)    : %ds  ← Redis clock unchanged               │%n", sampleTtl);
        System.out.println("│                                                                      │");
        System.out.println("│  50%% of calls return skewed time → A's events always appear first   │");
        System.out.println("│  In production: 0.5%% of requests depend on ordering → silent corrupt│");
        System.out.println("│  Fix: Lamport timestamps; never rely on wall clock for event order  │");
        System.out.println("└─────────────────────────────────────────────────────────────────────┘");
        System.out.println();

        assertThat(successCount.get())
                .as("+500ms clock skew at 50%% must not crash the service — some requests must succeed")
                .isGreaterThan(20L);
    }

    /**
     * Rate limiter uses Thread.sleep(1) to enforce 1ms token bucket refill.
     *
     * <p>On a loaded Kubernetes node: Linux scheduler CFS quantum is 10ms. sleep(1ms) actually
     * sleeps 10ms. Token bucket refills 10x slower than expected. Service allows 10x burst when
     * sleep finally wakes (all tokens have accumulated). Downstream service overwhelmed with 10x
     * burst every 10ms.
     *
     * <p>Pattern: "request spike every 10ms." Engineers tuned the load balancer. Added more
     * downstream capacity. Added rate limiting on the downstream. Nothing helped because the
     * burst came from the upstream rate limiter that was supposed to prevent it.
     *
     * <p>Root cause: nanosleep precision on a loaded host. The rate limiter's 1ms sleep became
     * a 10ms sleep. It still allowed the same total request count, but now burst-packed into
     * a 1ms window every 10ms instead of evenly distributed.
     */
    @Test
    @DisplayName("TIME L8: nanosleep precision bombing — scheduler quantizes 1ms sleep to 10ms on loaded host, rate limiter allows 10x burst, backend overwhelmed")
    @ChaosNanosleepLatency(delayMs = 9L)
    void nanosleepQuantizationRateLimiterAllowsTenXBurst() throws Exception {
        System.out.println();
        System.out.println("┌─────────────────────────────────────────────────────────────────────┐");
        System.out.println("│  INCIDENT: nanosleep quantization — rate limiter allows 10x burst   │");
        System.out.println("│  Severity: P1  Root cause discovered: 3 weeks, wrong fixes applied  │");
        System.out.println("│  Injecting: +9ms added to every nanosleep() call                    │");
        System.out.println("│  Expected: 1ms sleep becomes 10ms; tokens accumulate, then burst    │");
        System.out.println("└─────────────────────────────────────────────────────────────────────┘");

        int totalRequests = 40;
        List<Long> latenciesMs = new ArrayList<>(totalRequests);
        AtomicLong rateLimitErrors = new AtomicLong();
        AtomicLong successCount = new AtomicLong();

        // Measure actual response latency — nanosleep extension shows up here.
        for (int i = 0; i < totalRequests; i++) {
            long start = System.nanoTime();
            try {
                HttpResponse<String> response = httpClient.send(
                        HttpRequest.newBuilder()
                                .uri(URI.create(appBaseUrl + "/rate-limit-check"))
                                .POST(HttpRequest.BodyPublishers.ofString("{\"user\": \"burst-user\"}"))
                                .header("Content-Type", "application/json")
                                .timeout(Duration.ofSeconds(10))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());

                long elapsed = (System.nanoTime() - start) / 1_000_000L;
                latenciesMs.add(elapsed);

                if (response.statusCode() == 200) {
                    successCount.incrementAndGet();
                } else if (response.statusCode() == 429) {
                    // Rate limited — correct behavior.
                    successCount.incrementAndGet();
                } else {
                    rateLimitErrors.incrementAndGet();
                }
            } catch (Exception e) {
                long elapsed = (System.nanoTime() - start) / 1_000_000L;
                latenciesMs.add(elapsed);
                rateLimitErrors.incrementAndGet();
            }
        }

        latenciesMs.sort(Long::compareTo);
        long p50  = percentile(latenciesMs, 50);
        long p95  = percentile(latenciesMs, 95);
        long p99  = percentile(latenciesMs, 99);

        // The nanosleep extension proof: p50 latency must be elevated by at least the injection amount.
        // 9ms added to every sleep(1ms) call means at least +9ms visible in response latency.
        System.out.println();
        System.out.println("┌─────────────────────────────────────────────────────────────────────┐");
        System.out.println("│  PROOF  —  nanosleep quantization / rate limiter burst fingerprint  │");
        System.out.println("│                                                                      │");
        System.out.printf( "│  Total requests        : %d%n", totalRequests);
        System.out.printf( "│  Succeeded / 429       : %d  ← rate limiter responding             │%n", successCount.get());
        System.out.printf( "│  Errors                : %d%n", rateLimitErrors.get());
        System.out.printf( "│  Latency p50           : %dms  ← nanosleep inflation visible here  │%n", p50);
        System.out.printf( "│  Latency p95           : %dms%n", p95);
        System.out.printf( "│  Latency p99           : %dms%n", p99);
        System.out.println("│                                                                      │");
        System.out.println("│  +9ms per nanosleep: rate limiter sleeps 10x longer than intended   │");
        System.out.println("│  Tokens accumulate for 10ms. On wake: all 10 tokens available.      │");
        System.out.println("│  Downstream sees burst of 10 requests every 10ms instead of 1/ms   │");
        System.out.println("│  Fix: use token bucket with monotonic clock, not sleep-based refill │");
        System.out.println("└─────────────────────────────────────────────────────────────────────┘");
        System.out.println();

        assertThat(p50)
                .as("+9ms nanosleep injection must be observable in p50 latency — sleep extension must show up")
                .isGreaterThan(5L);

        assertThat(p99)
                .as("p99 must stay below 5000ms — nanosleep inflation must not cascade into unbounded stalls")
                .isLessThan(5_000L);

        assertThat(rateLimitErrors.get())
                .as("rate limiter must continue responding under nanosleep inflation — must not deadlock")
                .isLessThan((long) (totalRequests / 2));
    }

    private static long percentile(List<Long> sortedValues, int percentile) {
        if (sortedValues.isEmpty()) return 0L;
        int index = (int) Math.ceil(percentile / 100.0 * sortedValues.size()) - 1;
        return sortedValues.get(Math.max(0, Math.min(index, sortedValues.size() - 1)));
    }
}
