package com.macstab.chaos.examples.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.macstab.chaos.annotation.SyscallLevelChaos;
import com.macstab.chaos.annotation.fault.net.ChaosRecvEconnreset;
import com.macstab.chaos.annotation.fault.net.ChaosRecvLatency;
import com.macstab.chaos.annotation.incident.IncidentChaosRedisNetworkFlap;
import com.macstab.chaos.extension.ChaosTestingExtension;
import com.macstab.chaos.lib.LibchaosLib;
import com.macstab.chaos.redis.RedisConnectionInfo;
import com.macstab.chaos.redis.RedisSentinelConnectionInfo;
import com.macstab.chaos.redis.annotation.RedisSentinel;
import com.macstab.chaos.redis.annotation.RedisStandalone;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.JedisSentinelPool;
import redis.clients.jedis.exceptions.JedisConnectionException;

/**
 * The most advanced Redis topology test: Standalone + Sentinel + Standalone simultaneously.
 *
 * <p>This class models a real multi-tier caching architecture:
 *
 * <pre>
 *   [Client]
 *       │
 *       ├─► [L1 Cache]  – Redis Standalone (hot, fast, no HA, small TTL)
 *       │
 *       ├─► [L2 Cache]  – Redis Sentinel  (warm, HA, larger TTL, auto-failover)
 *       │
 *       └─► [Rate Limiter] – Redis Standalone (separate instance, maxmemory cap)
 * </pre>
 *
 * <p>Three topology annotations are stacked on the class. The framework provisions all five
 * containers (L1 standalone, L2 master + 2 replicas + 3 sentinels, rate-limiter standalone) in
 * parallel and injects the correct connection info type for each:
 * <ul>
 *   <li>{@link RedisConnectionInfo} for standalones
 *   <li>{@link RedisSentinelConnectionInfo} for the Sentinel cluster
 * </ul>
 *
 * <p>Parameter injection uses the {@code id} attribute to match topology annotation to method
 * parameter.
 */
@RedisStandalone(id = "l1-cache", version = "7.4")
@RedisSentinel(id = "l2-cache", masterName = "l2-master", replicas = 2, sentinels = 3, quorum = 2)
@RedisStandalone(id = "rate-limiter", version = "7.4", args = {"--maxmemory", "32mb"})
@ExtendWith(ChaosTestingExtension.class)
@SyscallLevelChaos({LibchaosLib.NET, LibchaosLib.TIME})
class RedisMixedTopologyTest {

    private static final Logger log = LoggerFactory.getLogger(RedisMixedTopologyTest.class);

    private final List<AutoCloseable> resources = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (AutoCloseable resource : resources) {
            try {
                resource.close();
            } catch (Exception e) {
                log.warn("Error closing resource: {}", e.getMessage());
            }
        }
        resources.clear();
    }

    // ── Tests ─────────────────────────────────────────────────────────────

    /**
     * Verifies that all three independent Redis topologies are reachable and serve correct data.
     *
     * <p>Each tier writes a unique key and reads it back. Injection order matches annotation
     * declaration order: {@code l1-cache} → {@code l2-cache} → {@code rate-limiter}.
     */
    @Test
    void allThreeTiersAreIndependentAndHealthy(
            RedisConnectionInfo l1Cache,
            RedisSentinelConnectionInfo l2Cache,
            RedisConnectionInfo rateLimiter) {

        JedisPool l1Pool = standalonePool(l1Cache, "l1-cache");
        JedisSentinelPool l2Pool = sentinelPool(l2Cache);
        JedisPool rlPool = standalonePool(rateLimiter, "rate-limiter");

        String l1Key = "l1:" + UUID.randomUUID();
        String l2Key = "l2:" + UUID.randomUUID();
        String rlKey = "rl:user:1000";

        try (Jedis l1 = l1Pool.getResource()) {
            l1.setex(l1Key, 30, "l1-value");
        }
        try (Jedis l2 = l2Pool.getResource()) {
            l2.setex(l2Key, 300, "l2-value");
        }
        try (Jedis rl = rlPool.getResource()) {
            rl.incr(rlKey);
            rl.expire(rlKey, 60);
        }

        try (Jedis l1 = l1Pool.getResource()) {
            assertThat(l1.get(l1Key)).as("L1 standalone must return written value").isEqualTo("l1-value");
        }
        try (Jedis l2 = l2Pool.getResource()) {
            assertThat(l2.get(l2Key)).as("L2 sentinel must return written value").isEqualTo("l2-value");
        }
        try (Jedis rl = rlPool.getResource()) {
            assertThat(rl.get(rlKey)).as("Rate limiter standalone must return counter").isEqualTo("1");
        }

        log.info(
                "Mixed topology baseline: L1={}:{}, L2={}, RL={}:{}",
                l1Cache.host(), l1Cache.port(),
                l2Cache.sentinelEndpoints(),
                rateLimiter.host(), rateLimiter.port());
    }

    /**
     * Triggers a Sentinel failover on the L2 tier and verifies that L1 and the rate limiter are
     * completely unaffected.
     *
     * <p>The {@code id = "l2-cache"} on the chaos annotation scopes the network flap to only the
     * L2 Sentinel cluster's containers. The L1 standalone and rate-limiter containers continue to
     * operate normally. This proves the blast radius isolation guarantee for mixed topologies.
     */
    @Test
    @IncidentChaosRedisNetworkFlap(toxicity = 0.90, id = "l2-cache")
    void l2SentinelFlapDoesNotBreakL1OrRateLimiter(
            RedisConnectionInfo l1Cache,
            RedisSentinelConnectionInfo l2Cache,
            RedisConnectionInfo rateLimiter)
            throws Exception {

        JedisPool l1Pool = standalonePool(l1Cache, "l1-cache");
        JedisSentinelPool l2Pool = sentinelPool(l2Cache);
        JedisPool rlPool = standalonePool(rateLimiter, "rate-limiter");

        int operations = 100;
        AtomicLong l1Errors = new AtomicLong();
        AtomicLong l2Errors = new AtomicLong();
        AtomicLong rlErrors = new AtomicLong();

        for (int i = 0; i < operations; i++) {
            // L1 – must be clean throughout L2 chaos.
            try (Jedis l1 = l1Pool.getResource()) {
                l1.setex("l1:op:" + i, 60, "v-" + i);
            } catch (JedisConnectionException e) {
                l1Errors.incrementAndGet();
                log.error("Unexpected L1 error on op {}: {}", i, e.getMessage());
            }

            // L2 – chaos active; errors expected during failover window.
            try (Jedis l2 = l2Pool.getResource()) {
                l2.setex("l2:op:" + i, 300, "v-" + i);
            } catch (JedisConnectionException e) {
                l2Errors.incrementAndGet();
                log.debug("L2 error during failover on op {}: {}", i, e.getMessage());
            }

            // Rate limiter – must be clean throughout L2 chaos.
            try (Jedis rl = rlPool.getResource()) {
                rl.incr("rl:op:" + i);
            } catch (JedisConnectionException e) {
                rlErrors.incrementAndGet();
                log.error("Unexpected rate-limiter error on op {}: {}", i, e.getMessage());
            }
        }

        log.info(
                "L2 flap (90%% network): L1 errors={}, L2 errors={}, RL errors={}",
                l1Errors.get(), l2Errors.get(), rlErrors.get());

        // L1 and rate limiter must be completely isolated from L2 chaos.
        assertThat(l1Errors.get())
                .as("L1 standalone must have ZERO errors while L2 Sentinel is under network flap")
                .isEqualTo(0L);

        assertThat(rlErrors.get())
                .as(
                        "Rate limiter standalone must have ZERO errors while L2 Sentinel is under"
                                + " network flap")
                .isEqualTo(0L);

        // L2 is expected to have some errors during the failover window.
        assertThat(l2Errors.get())
                .as("L2 Sentinel must experience errors during 90%% network flap / failover")
                .isGreaterThan(0L);

        // After the failover, L2 must recover.
        await()
                .atMost(45, TimeUnit.SECONDS)
                .pollInterval(Duration.ofSeconds(2))
                .untilAsserted(() -> {
                    try (Jedis l2 = l2Pool.getResource()) {
                        l2.ping();
                    }
                });

        log.info("L2 Sentinel recovered after failover.");
    }

    /**
     * Applies mixed, independent chaos to all three tiers simultaneously and measures the
     * aggregate blast radius.
     *
     * <p>Fault assignments:
     * <ul>
     *   <li>L1 standalone: 20% {@code ECONNRESET} on recv() – simulates a flaky L1 cache
     *   <li>L2 Sentinel: 150 ms latency on 40% of recv() – simulates a degraded L2 cache
     *   <li>Rate limiter: no chaos – must serve 100% of operations cleanly
     * </ul>
     *
     * <p>The rate limiter being chaos-free while the caches are degraded represents the real-world
     * priority: rate limiting is a hard dependency (if it fails, users can DDoS the service);
     * caches are a soft dependency (a miss is recoverable).
     */
    @Test
    @ChaosRecvEconnreset(probability = 0.20, id = "l1-cache")
    @ChaosRecvLatency(delay = 150, probability = 0.40, id = "l2-cache")
    void mixedChaosAcrossAllThreeTiersWithIsolatedBlastRadius(
            RedisConnectionInfo l1Cache,
            RedisSentinelConnectionInfo l2Cache,
            RedisConnectionInfo rateLimiter) {

        JedisPool l1Pool = standalonePool(l1Cache, "l1-cache");
        JedisSentinelPool l2Pool = sentinelPool(l2Cache);
        JedisPool rlPool = standalonePool(rateLimiter, "rate-limiter");

        int operations = 200;
        AtomicLong l1Errors = new AtomicLong();
        AtomicLong l2SlowOps = new AtomicLong();
        AtomicLong rlErrors = new AtomicLong();
        AtomicLong l2TotalMs = new AtomicLong();

        for (int i = 0; i < operations; i++) {
            // L1 – 20% ECONNRESET.
            try (Jedis l1 = l1Pool.getResource()) {
                String val = l1.get("l1:key:" + (i % 50));
                if (val == null) {
                    // Cache miss – would normally populate from L2.
                    l1.setex("l1:key:" + (i % 50), 30, "repopulated-" + i);
                }
            } catch (JedisConnectionException e) {
                l1Errors.incrementAndGet();
            }

            // L2 – 40% latency (150 ms).
            long l2Start = System.nanoTime();
            try (Jedis l2 = l2Pool.getResource()) {
                l2.get("l2:key:" + (i % 100));
            } catch (JedisConnectionException e) {
                // Latency chaos may cause timeout exceptions; count them.
                l2SlowOps.incrementAndGet();
            }
            long l2Ms = (System.nanoTime() - l2Start) / 1_000_000L;
            l2TotalMs.addAndGet(l2Ms);
            if (l2Ms > 100) {
                l2SlowOps.incrementAndGet();
            }

            // Rate limiter – NO chaos; must be 100% clean.
            try (Jedis rl = rlPool.getResource()) {
                rl.incr("rl:user:" + (i % 10));
            } catch (JedisConnectionException e) {
                rlErrors.incrementAndGet();
                log.error("UNEXPECTED rate limiter error on op {}: {}", i, e.getMessage());
            }
        }

        double l1ErrorRate = (double) l1Errors.get() / operations * 100.0;
        double l2AvgMs = (double) l2TotalMs.get() / operations;

        log.info(
                "Mixed topology chaos:"
                        + " L1 errorRate={:.2f}%%, L2 slowOps={}, L2 avgLatency={:.1f}ms,"
                        + " RL errors={}",
                l1ErrorRate,
                l2SlowOps.get(),
                l2AvgMs,
                rlErrors.get());

        // L1: 20% ECONNRESET → expect some errors.
        assertThat(l1Errors.get())
                .as("L1 must experience errors under 20%% ECONNRESET")
                .isGreaterThan(0L);

        // L2: 40% latency → expect elevated avg latency.
        assertThat(l2AvgMs)
                .as("L2 average latency must be elevated under 40%% latency injection")
                .isGreaterThan(50.0);

        // Rate limiter: absolutely zero tolerance for errors.
        assertThat(rlErrors.get())
                .as("Rate limiter must have ZERO errors even under mixed chaos on L1 and L2")
                .isEqualTo(0L);
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private JedisPool standalonePool(RedisConnectionInfo info, String label) {
        JedisPoolConfig config = new JedisPoolConfig();
        config.setMaxTotal(16);
        config.setMaxIdle(8);
        config.setMinIdle(2);
        JedisPool pool = new JedisPool(config, info.host(), info.port(), 3000);
        resources.add(pool);
        log.info("Pool created for {} at {}:{}", label, info.host(), info.port());
        return pool;
    }

    private JedisSentinelPool sentinelPool(RedisSentinelConnectionInfo info) {
        Set<String> sentinels = info.sentinelEndpoints();
        JedisSentinelPool pool =
                new JedisSentinelPool(info.masterName(), sentinels, 3000, 3000);
        resources.add(pool);
        log.info(
                "SentinelPool created for master '{}' via {}",
                info.masterName(),
                sentinels);
        return pool;
    }
}
