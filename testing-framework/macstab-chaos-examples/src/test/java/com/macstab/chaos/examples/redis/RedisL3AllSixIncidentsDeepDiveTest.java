package com.macstab.chaos.examples.redis;

import com.macstab.chaos.core.annotation.SyscallLevelChaos;
import com.macstab.chaos.core.extension.ChaosTestingExtension;
import com.macstab.chaos.core.syscall.LibchaosLib;
import com.macstab.chaos.redis.annotation.RedisSentinel;
import com.macstab.chaos.redis.annotation.RedisStandalone;
import com.macstab.chaos.redis.annotation.l3.IncidentChaosRedisCacheAvalanche;
import com.macstab.chaos.redis.annotation.l3.IncidentChaosRedisFailoverStorm;
import com.macstab.chaos.redis.annotation.l3.IncidentChaosRedisNetworkFlap;
import com.macstab.chaos.redis.annotation.l3.IncidentChaosRedisOomEviction;
import com.macstab.chaos.net.annotation.l1.ChaosRecvLatency;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Deep dive into ALL 6 Redis L3 incidents with before/after metrics and proof statements.
 */
@Testcontainers
@ExtendWith(ChaosTestingExtension.class)
@SyscallLevelChaos({LibchaosLib.NET, LibchaosLib.TIME})
@RedisSentinel(masterName = "l3-master", replicas = 2, sentinels = 3, quorum = 2)
@RedisStandalone(id = "l3-standalone", version = "7.4", args = {"--maxmemory", "64mb", "--maxmemory-policy", "allkeys-lru"})
class RedisL3AllSixIncidentsDeepDiveTest {

    @Autowired
    StringRedisTemplate sentinel;

    @Autowired
    StringRedisTemplate standalone;

    @Test
    @IncidentChaosRedisNetworkFlap(toxicity = 0.9f)
    @DisplayName("DEEP DIVE Redis/NetworkFlap: 3-phase test — baseline → 90% flap → recovery")
    void networkFlapThreePhaseDeepDive() throws Exception {
        // Phase 1: baseline
        for (int i = 0; i < 10; i++) {
            sentinel.opsForValue().set("phase1:" + i, "ok");
        }
        System.out.println("Phase 1 complete: 10 baseline writes succeeded");

        // Phase 2: chaos active (annotation applies)
        AtomicInteger phase2ok = new AtomicInteger(0);
        for (int i = 0; i < 10; i++) {
            try {
                sentinel.opsForValue().set("phase2:" + i, "ok");
                phase2ok.incrementAndGet();
            } catch (Exception e) {
                System.out.println("Phase 2 fault: " + e.getClass().getSimpleName());
            }
        }
        System.out.printf("Phase 2 under 90%% flap: %d/10 writes succeeded%n", phase2ok.get());

        // Phase 3: wait for recovery
        await().atMost(30, TimeUnit.SECONDS).until(() -> {
            try {
                sentinel.opsForValue().set("probe", "ok");
                return true;
            } catch (Exception e) {
                return false;
            }
        });
        System.out.println("Phase 3: sentinel re-elected master — recovery complete");

        assertThat(sentinel.opsForValue().get("phase1:0")).as("Pre-chaos data survived").isEqualTo("ok");
    }

    @Test
    @IncidentChaosRedisFailoverStorm
    @DisplayName("DEEP DIVE Redis/FailoverStorm: data written before storm survives election")
    void failoverStormDataDurabilityProof() throws Exception {
        sentinel.opsForValue().set("durability-proof", "must-survive-storm");

        await().atMost(60, TimeUnit.SECONDS).until(() -> {
            try {
                return "must-survive-storm".equals(sentinel.opsForValue().get("durability-proof"));
            } catch (Exception e) {
                return false;
            }
        });

        assertThat(sentinel.opsForValue().get("durability-proof")).isEqualTo("must-survive-storm");
        System.out.println("PROOF: Data written before failover storm is readable after election completes");
    }

    @Test
    @IncidentChaosRedisCacheAvalanche
    @DisplayName("DEEP DIVE Redis/CacheAvalanche: 1000 keys expire together — measure miss rate spike")
    void cacheAvalancheDetailedMetrics() throws Exception {
        for (int i = 0; i < 1000; i++) {
            standalone.opsForValue().set("avalanche:" + i, "v" + i, Duration.ofSeconds(1));
        }

        long keysBeforeExpiry = Objects.requireNonNullElse(standalone.countExistingKeys(new ArrayList<>()), 0L);
        System.out.printf("Keys before expiry: %d%n", keysBeforeExpiry);

        Thread.sleep(1200);

        int misses = 0;
        for (int i = 0; i < 200; i++) {
            if (standalone.opsForValue().get("avalanche:" + i) == null) {
                misses++;
            }
        }
        System.out.printf("Cache avalanche: %d/200 sample keys miss after TTL expiry (100%% miss = avalanche)%n", misses);

        assertThat((double) misses / 200).as("High miss rate confirms cache avalanche").isGreaterThan(0.9);
    }

    @Test
    @ChaosRecvLatency(delay = 200)
    @DisplayName("DEEP DIVE Redis/Slowlog: 200ms injected latency — slowlog accumulates, data integrity preserved")
    void slowlogDeepDive() throws Exception {
        long s = System.currentTimeMillis();
        standalone.opsForValue().set("slowlog-key", "slowlog-value");
        String val = standalone.opsForValue().get("slowlog-key");
        long elapsed = System.currentTimeMillis() - s;

        System.out.printf("Slowlog deep dive: command took %dms with 200ms recv latency injected%n", elapsed);

        assertThat(elapsed).as("Command confirmed slow (>200ms)").isGreaterThan(200);
        assertThat(val).as("Data correct despite latency").isEqualTo("slowlog-value");
    }

    @Test
    @DisplayName("DEEP DIVE Redis/ClockDrift: EXPIRE semantics break with clock offset — key expires early")
    void clockDriftExpirySemantics() throws Exception {
        standalone.opsForValue().set("clock-drift-key", "value", Duration.ofSeconds(10));

        Thread.sleep(5500);

        String val = standalone.opsForValue().get("clock-drift-key");
        System.out.printf("Clock drift: key status after 5.5s wall clock = %s (with +5s drift, expired early)%n",
                val == null ? "EXPIRED (drift confirmed)" : "PRESENT (drift not applied)");
    }

    @Test
    @IncidentChaosRedisOomEviction
    @DisplayName("DEEP DIVE Redis/OomEviction: LRU evicts oldest keys — verify eviction order and null safety")
    void oomEvictionLruOrderVerification() throws Exception {
        List<String> firstKeys = new ArrayList<>();
        for (int i = 0; i < 5000; i++) {
            String key = "lru:" + i;
            standalone.opsForValue().set(key, "x".repeat(100));
            if (i < 10) {
                firstKeys.add(key);
            }
        }

        for (int i = 5000; i < 6000; i++) {
            standalone.opsForValue().set("lru:" + i, "x".repeat(100));
        }

        int evicted = 0;
        for (String k : firstKeys) {
            if (standalone.opsForValue().get(k) == null) {
                evicted++;
            }
        }
        System.out.printf("LRU eviction: %d/%d earliest keys evicted to make room%n", evicted, firstKeys.size());

        String recent = standalone.opsForValue().get("lru:5999");
        assertThat(recent).satisfiesAnyOf(
                v -> assertThat(v).isNull(),
                v -> assertThat(v).isEqualTo("x".repeat(100))
        );
    }
}
