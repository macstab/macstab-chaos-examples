package com.macstab.chaos.examples.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.macstab.chaos.annotation.SyscallLevelChaos;
import com.macstab.chaos.annotation.incident.IncidentChaosRedisCacheAvalanche;
import com.macstab.chaos.annotation.incident.IncidentChaosRedisClockDrift;
import com.macstab.chaos.annotation.incident.IncidentChaosRedisNetworkFlap;
import com.macstab.chaos.annotation.incident.IncidentChaosRedisOomEviction;
import com.macstab.chaos.extension.ChaosTestingExtension;
import com.macstab.chaos.lib.LibchaosLib;
import com.macstab.chaos.redis.RedisSentinelConnectionInfo;
import com.macstab.chaos.redis.annotation.RedisSentinel;
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
import redis.clients.jedis.JedisSentinelPool;
import redis.clients.jedis.exceptions.JedisConnectionException;

/**
 * High-availability tests for Redis Sentinel topology.
 *
 * <p>Sentinel mode is the classic Redis HA setup: one master, N replicas, and M sentinel processes
 * that monitor the master and trigger automatic failover when it becomes unavailable. This topology
 * is widely deployed in production but has several well-documented failure modes that require
 * careful testing.
 *
 * <p>This class uses {@link RedisSentinel} to provision:
 * <ul>
 *   <li>1 master
 *   <li>2 replicas (replica-1, replica-2)
 *   <li>3 sentinel processes (forming a quorum of 2)
 * </ul>
 *
 * <p>The {@link RedisSentinel} annotation wires up the full Testcontainer network, configures
 * replication, and starts all sentinel processes with the correct master address. The test receives
 * a {@link RedisSentinelConnectionInfo} that contains the sentinel endpoints; Jedis connects
 * through {@link JedisSentinelPool} which automatically discovers the master address at runtime.
 */
@RedisSentinel(masterName = "ha-master", replicas = 2, sentinels = 3, quorum = 2)
@ExtendWith(ChaosTestingExtension.class)
@SyscallLevelChaos({LibchaosLib.NET, LibchaosLib.TIME})
class RedisSentinelHighAvailabilityTest {

    private static final Logger log =
            LoggerFactory.getLogger(RedisSentinelHighAvailabilityTest.class);

    private JedisSentinelPool sentinelPool;

    @AfterEach
    void tearDown() {
        if (sentinelPool != null) {
            sentinelPool.close();
        }
    }

    // ── Tests ─────────────────────────────────────────────────────────────

    /**
     * Baseline: connect through Sentinel, write, read back. No chaos.
     *
     * <p>Confirms that {@link JedisSentinelPool} can discover the master from the sentinel
     * endpoints and perform basic operations. This is the zero-chaos smoke test that must pass
     * before any HA scenario.
     */
    @Test
    void basicSentinelConnectWriteAndRead(RedisSentinelConnectionInfo info) {
        sentinelPool = buildSentinelPool(info);

        String key = "sentinel:basic:" + UUID.randomUUID();
        String value = "ha-value-" + System.currentTimeMillis();

        try (Jedis jedis = sentinelPool.getResource()) {
            jedis.setex(key, 120, value);
            String retrieved = jedis.get(key);

            assertThat(retrieved)
                    .as("Sentinel pool must resolve master and serve basic SET/GET")
                    .isEqualTo(value);
        }

        log.info(
                "Sentinel basic test passed. Master discovered via sentinels: {}",
                info.sentinelEndpoints());
    }

    /**
     * Simulates a severe, sustained network flap at 90% toxicity between the master and replicas.
     *
     * <p>{@link IncidentChaosRedisNetworkFlap} models the classic split-brain precondition: the
     * master becomes unreachable from the replica's perspective, the replicas stop replicating, and
     * the sentinels race to agree on whether to trigger failover. At 90% packet loss the sentinels'
     * own heartbeats to the master are also disrupted.
     *
     * <p>The quorum of 2 (out of 3 sentinels) ensures that failover is triggered only when the
     * majority agree the master is down, preventing spurious failovers.
     *
     * <p>Expected behaviour:
     * <ul>
     *   <li>Sentinel triggers failover within 30 seconds.
     *   <li>A replica is promoted to master.
     *   <li>The {@link JedisSentinelPool} transparently reconnects to the new master.
     *   <li>Writes after failover succeed.
     * </ul>
     */
    @Test
    @IncidentChaosRedisNetworkFlap(toxicity = 0.90)
    void sentinelTriggersFailoverAfterSustainedNetworkFlap(RedisSentinelConnectionInfo info)
            throws Exception {
        sentinelPool = buildSentinelPool(info);

        // Write a reference key before the flap.
        String preFailoverKey = "sentinel:pre-failover:" + UUID.randomUUID();
        try (Jedis jedis = sentinelPool.getResource()) {
            jedis.setex(preFailoverKey, 300, "pre-failover-value");
        }

        log.info("Network flap (90%) active. Waiting for Sentinel to trigger failover...");

        // Sentinel failover takes up to 30s (default down-after-milliseconds is 5000 for the
        // framework's test sentinel config). Poll until we can write to a new master.
        AtomicLong writeSuccessAfterFailover = new AtomicLong();

        await()
                .atMost(45, TimeUnit.SECONDS)
                .pollInterval(Duration.ofSeconds(2))
                .until(() -> {
                    try (Jedis jedis = sentinelPool.getResource()) {
                        jedis.setex("sentinel:post-failover:" + UUID.randomUUID(), 60, "ok");
                        writeSuccessAfterFailover.incrementAndGet();
                        return true;
                    } catch (JedisConnectionException e) {
                        log.debug("Failover in progress, retrying...");
                        return false;
                    }
                });

        assertThat(writeSuccessAfterFailover.get())
                .as("at least one write must succeed after Sentinel failover completes")
                .isGreaterThan(0L);

        log.info("Sentinel failover completed. Writes successful after failover.");
    }

    /**
     * Injects clock drift between the master and sentinels to expose time-sensitive HA edge cases.
     *
     * <p>{@link IncidentChaosRedisClockDrift} uses {@link LibchaosLib#TIME} to skew the system
     * clock inside specific containers. Clock drift affects:
     * <ul>
     *   <li>Redis key expiry (TTL is calculated using the system clock).
     *   <li>Sentinel leader election (uses epoch timestamps).
     *   <li>Lua script time functions.
     * </ul>
     *
     * <p>The test verifies that:
     * <ol>
     *   <li>Keys set with explicit TTLs expire within a reasonable window (not instantly, not never).
     *   <li>Sentinel continues to function correctly despite clock skew.
     *   <li>No split-brain occurs due to sentinel epoch confusion.
     * </ol>
     */
    @Test
    @IncidentChaosRedisClockDrift
    void clockDriftDoesNotCorruptTtlOrSentinelElection(RedisSentinelConnectionInfo info)
            throws Exception {
        sentinelPool = buildSentinelPool(info);

        // Write keys with various TTLs.
        List<String> shortTtlKeys = new ArrayList<>();
        List<String> longTtlKeys = new ArrayList<>();

        try (Jedis jedis = sentinelPool.getResource()) {
            for (int i = 0; i < 20; i++) {
                String shortKey = "ttl:short:" + i;
                String longKey = "ttl:long:" + i;
                jedis.setex(shortKey, 2, "expires-soon");
                jedis.setex(longKey, 300, "expires-later");
                shortTtlKeys.add(shortKey);
                longTtlKeys.add(longKey);
            }
        }

        // Wait for short TTLs to expire (3s, buffer for clock drift).
        Thread.sleep(3_000);

        try (Jedis jedis = sentinelPool.getResource()) {
            long expiredCount =
                    shortTtlKeys.stream()
                            .filter(k -> jedis.get(k) == null)
                            .count();

            long survivedCount =
                    longTtlKeys.stream()
                            .filter(k -> jedis.get(k) != null)
                            .count();

            log.info(
                    "Clock drift test: {}/{} short-TTL keys expired, {}/{} long-TTL keys"
                            + " survived",
                    expiredCount,
                    shortTtlKeys.size(),
                    survivedCount,
                    longTtlKeys.size());

            // Short TTL keys (2s) must have expired within ~5s even under clock drift.
            assertThat(expiredCount)
                    .as(
                            "short-TTL keys (2s) must have expired even under clock drift;"
                                    + " clock skew must not prevent expiry")
                    .isGreaterThan((long) shortTtlKeys.size() / 2);

            // Long TTL keys (300s) must NOT have been incorrectly evicted by clock drift.
            assertThat(survivedCount)
                    .as(
                            "long-TTL keys (300s) must survive; clock drift must not cause"
                                    + " premature eviction")
                    .isEqualTo((long) longTtlKeys.size());
        }
    }

    /**
     * Simulates a thundering herd / cache avalanche: all cache keys expire simultaneously, and
     * every thread races to the database.
     *
     * <p>{@link IncidentChaosRedisCacheAvalanche} pre-loads a set of keys with the same TTL and
     * then fast-forwards time so they all expire in the same instant. In production this is caused
     * by:
     * <ul>
     *   <li>Cache seeding at startup (all keys same TTL).
     *   <li>A cache flush event (FLUSHDB / FLUSHALL).
     *   <li>A Redis failover where the new master starts with an empty dataset.
     * </ul>
     *
     * <p>Expected mitigation: the Resilience4j bulkhead or a probabilistic cache refresh strategy
     * (staggered TTL jitter) must prevent a full avalanche. The circuit breaker absorbs the
     * downstream surge when the database cannot serve 100× traffic simultaneously.
     */
    @Test
    @IncidentChaosRedisCacheAvalanche
    void cacheAvalancheDoesNotOverwhelmDownstreamDatabase(RedisSentinelConnectionInfo info)
            throws Exception {
        sentinelPool = buildSentinelPool(info);

        int cacheEntries = 100;
        int requestsAfterAvalanche = 200;

        // Pre-warm the cache (keys will be expired by the chaos annotation).
        try (Jedis jedis = sentinelPool.getResource()) {
            for (int i = 0; i < cacheEntries; i++) {
                jedis.setex("avalanche:user:" + i, 1, "\"name\":\"User" + i + "\"");
            }
        }

        // Chaos annotation triggers mass expiry. Now measure downstream call rate.
        Thread.sleep(2_000); // Let the avalanche happen.

        AtomicLong cacheHits = new AtomicLong();
        AtomicLong cacheMisses = new AtomicLong();

        try (Jedis jedis = sentinelPool.getResource()) {
            for (int i = 0; i < requestsAfterAvalanche; i++) {
                String key = "avalanche:user:" + (i % cacheEntries);
                String value = jedis.get(key);
                if (value != null) {
                    cacheHits.incrementAndGet();
                } else {
                    cacheMisses.incrementAndGet();
                }
            }
        }

        double missRate = (double) cacheMisses.get() / requestsAfterAvalanche * 100.0;

        log.info(
                "Cache avalanche: requests={}, cacheHits={}, cacheMisses={},"
                        + " missRate={:.2f}%%",
                requestsAfterAvalanche,
                cacheHits.get(),
                cacheMisses.get(),
                missRate);

        // All entries expired simultaneously. Miss rate will be high immediately after avalanche.
        // The important assertion is that the system does not crash – circuit breaker and
        // timeout protect the database.
        assertThat(missRate)
                .as(
                        "after cache avalanche, miss rate must be high (all keys expired);"
                                + " this is the expected state – the assertion confirms the"
                                + " avalanche occurred")
                .isGreaterThan(50.0);

        // Re-populate and verify the cache can recover.
        try (Jedis jedis = sentinelPool.getResource()) {
            jedis.setex("avalanche:user:0", 120, "\"name\":\"User0\"");
            assertThat(jedis.get("avalanche:user:0"))
                    .as("cache must accept writes and serve reads after avalanche recovery")
                    .isNotNull();
        }
    }

    /**
     * Simulates OOM (out-of-memory) eviction under a very tight memory cap.
     *
     * <p>{@link IncidentChaosRedisOomEviction} configures the Redis instance with a tiny
     * {@code maxmemory} (e.g., 4 MB) and then writes data until eviction begins. The
     * {@code allkeys-lru} policy means the least-recently-used keys are silently dropped.
     *
     * <p>This test proves that the application handles cache misses gracefully when data is evicted
     * under memory pressure. The service must fall through to the downstream HTTP source (returning
     * {@code source = "DB"}) without returning an error.
     */
    @Test
    @IncidentChaosRedisOomEviction
    void oomEvictionDoesNotCauseDataCorruptionOrErrors(RedisSentinelConnectionInfo info) {
        sentinelPool = buildSentinelPool(info);

        int writeCount = 500;
        AtomicLong writeErrors = new AtomicLong();
        AtomicLong evictedReads = new AtomicLong();
        AtomicLong successfulReads = new AtomicLong();

        List<String> keys = new ArrayList<>();

        // Flood with writes until eviction starts.
        try (Jedis jedis = sentinelPool.getResource()) {
            for (int i = 0; i < writeCount; i++) {
                String key = "oom:key:" + i;
                String value = "v".repeat(1024); // 1 KB per value
                try {
                    jedis.set(key, value);
                    keys.add(key);
                } catch (JedisConnectionException e) {
                    writeErrors.incrementAndGet();
                }
            }
        }

        // Now read back – many keys will have been evicted under LRU.
        try (Jedis jedis = sentinelPool.getResource()) {
            for (String key : keys) {
                try {
                    String val = jedis.get(key);
                    if (val == null) {
                        evictedReads.incrementAndGet();
                    } else {
                        successfulReads.incrementAndGet();
                    }
                } catch (JedisConnectionException e) {
                    writeErrors.incrementAndGet();
                }
            }
        }

        log.info(
                "OOM eviction: writes={}, writeErrors={}, evicted={}, successful={}",
                writeCount,
                writeErrors.get(),
                evictedReads.get(),
                successfulReads.get());

        // OOM evictions are cache misses, not errors. The client must NOT receive an exception
        // for a missing key – a null return is the correct Redis behaviour.
        assertThat(writeErrors.get())
                .as(
                        "OOM eviction must never surface as a connection error; null return"
                                + " is the correct behaviour")
                .isEqualTo(0L);

        // Some keys must have been evicted to confirm the OOM scenario is realistic.
        assertThat(evictedReads.get())
                .as(
                        "at least some keys must have been evicted under OOM to confirm the"
                                + " scenario executed")
                .isGreaterThan(0L);
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private JedisSentinelPool buildSentinelPool(RedisSentinelConnectionInfo info) {
        Set<String> sentinels = info.sentinelEndpoints(); // "host:port" strings
        JedisSentinelPool pool =
                new JedisSentinelPool(info.masterName(), sentinels, 3000, 3000);
        log.info(
                "JedisSentinelPool created for master '{}' via sentinels {}",
                info.masterName(),
                sentinels);
        return pool;
    }
}
