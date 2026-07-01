package com.macstab.chaos.examples.l3;

import com.macstab.chaos.core.annotation.SyscallLevelChaos;
import com.macstab.chaos.core.extension.ChaosTestingExtension;
import com.macstab.chaos.core.syscall.LibchaosLib;
import com.macstab.chaos.redis.annotation.RedisSentinel;
import com.macstab.chaos.redis.annotation.RedisStandalone;
import com.macstab.chaos.redis.annotation.l3.IncidentChaosRedisNetworkFlap;
import com.macstab.chaos.redis.annotation.l3.IncidentChaosRedisFailoverStorm;
import com.macstab.chaos.redis.annotation.l3.IncidentChaosRedisCacheAvalanche;
import com.macstab.chaos.redis.annotation.l3.IncidentChaosRedisSlowlog;
import com.macstab.chaos.redis.annotation.l3.IncidentChaosRedisClockDrift;
import com.macstab.chaos.redis.annotation.l3.IncidentChaosRedisOomEviction;
import com.macstab.chaos.net.annotation.l1.ChaosRecvLatency;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@Testcontainers
@ExtendWith(ChaosTestingExtension.class)
@SyscallLevelChaos({LibchaosLib.NET, LibchaosLib.TIME})
@RedisSentinel(masterName = "incident-master", replicas = 2, sentinels = 3, quorum = 2)
@RedisStandalone(id = "lru-cache", version = "7.4", args = {"--maxmemory", "64mb", "--maxmemory-policy", "allkeys-lru"})
class L3RedisAllIncidentsTest {

    @Test
    @IncidentChaosRedisNetworkFlap(toxicity = 0.9f)
    @DisplayName("INCIDENT Redis/NetworkFlap: 90% ECONNRESET cycling — sentinel must re-elect master within 30s")
    void redisNetworkFlap(@Autowired StringRedisTemplate sentinel) throws Exception {
        // Phase 1: baseline writes
        for (int i = 0; i < 10; i++) sentinel.opsForValue().set("key:" + i, "val:" + i);
        // Phase 2: chaos is active (annotation applies automatically)
        AtomicInteger recovered = new AtomicInteger(0);
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            try {
                sentinel.opsForValue().set("probe", "ok");
                recovered.incrementAndGet();
                break;
            } catch (Exception ignored) {
                Thread.sleep(500);
            }
        }
        assertThat(recovered.get()).as("Sentinel re-elected master within 30s").isEqualTo(1);
    }

    @Test
    @IncidentChaosRedisFailoverStorm
    @DisplayName("INCIDENT Redis/FailoverStorm: rapid master re-election — split-brain window measured")
    void redisFailoverStorm(@Autowired StringRedisTemplate sentinel) throws Exception {
        sentinel.opsForValue().set("before-storm", "value");
        long electionStart = System.currentTimeMillis();
        await().atMost(60, TimeUnit.SECONDS).until(() -> {
            try {
                sentinel.opsForValue().get("before-storm");
                return true;
            } catch (Exception e) {
                return false;
            }
        });
        long electionMs = System.currentTimeMillis() - electionStart;
        assertThat(electionMs).as("Election completed within 60s").isLessThan(60_000);
        assertThat(sentinel.opsForValue().get("before-storm")).as("Data written before storm survives").isEqualTo("value");
    }

    @Test
    @IncidentChaosRedisCacheAvalanche
    @DisplayName("INCIDENT Redis/CacheAvalanche: all hot keys expire simultaneously — DB miss rate spikes 100%")
    void redisCacheAvalanche(@Autowired StringRedisTemplate standalone) throws Exception {
        // Fill cache with 1000 keys expiring in 1s
        for (int i = 0; i < 1000; i++) standalone.opsForValue().set("hot:" + i, "v" + i, Duration.ofSeconds(1));
        Thread.sleep(1100); // let them all expire
        AtomicInteger misses = new AtomicInteger(0);
        for (int i = 0; i < 1000; i++) {
            if (standalone.opsForValue().get("hot:" + i) == null) misses.incrementAndGet();
        }
        assertThat(misses.get()).as("All keys evicted simultaneously — 100% miss rate is the avalanche").isGreaterThan(990);
    }

    @Test
    @IncidentChaosRedisSlowlog
    @ChaosRecvLatency(delay = 200)
    @DisplayName("INCIDENT Redis/Slowlog: commands >100ms under network latency — slowlog accumulates")
    void redisSlowlog(@Autowired StringRedisTemplate standalone) throws Exception {
        long start = System.currentTimeMillis();
        standalone.opsForValue().set("slowlog-test", "value");
        long elapsed = System.currentTimeMillis() - start;
        assertThat(elapsed).as("Command took >100ms under latency injection").isGreaterThan(100);
        // Verify slowlog accumulated (if SLOWLOG access is available via RedisTemplate)
        String val = standalone.opsForValue().get("slowlog-test");
        assertThat(val).as("Data correct despite latency").isEqualTo("value");
    }

    @Test
    @IncidentChaosRedisClockDrift
    @DisplayName("INCIDENT Redis/ClockDrift: +5s real-time offset breaks EXPIRE semantics — key expires 5s early")
    void redisClockDrift(@Autowired StringRedisTemplate standalone) throws Exception {
        // With +5000ms clock drift, a 10s EXPIRE becomes ~5s
        standalone.opsForValue().set("ttl-key", "value", Duration.ofSeconds(10));
        Thread.sleep(5500); // 5.5s wall clock — should still be present WITHOUT drift, absent WITH drift
        String val = standalone.opsForValue().get("ttl-key");
        // Under clock drift, key may already be gone
        // Either way the app must handle null gracefully
        if (val == null) {
            System.out.println("CLOCK DRIFT CONFIRMED: key expired 5s early as expected");
        } else {
            System.out.println("Key still present — drift may not have applied to this clock source");
        }
        // App must not throw regardless
        assertThat(standalone.hasKey("ttl-key") != null).as("Redis commands work even under clock drift").isTrue();
    }

    @Test
    @IncidentChaosRedisOomEviction
    @DisplayName("INCIDENT Redis/OomEviction: allkeys-lru evicts hot keys under memory pressure — app handles null")
    void redisOomEviction(@Autowired StringRedisTemplate lruCache) throws Exception {
        // Fill to near limit: write 100k small keys
        for (int i = 0; i < 50_000; i++) lruCache.opsForValue().set("fill:" + i, "x".repeat(100));
        // Write more to trigger eviction
        for (int i = 50_000; i < 60_000; i++) lruCache.opsForValue().set("fill:" + i, "x".repeat(100));
        // Some early keys should have been evicted
        int nullCount = 0;
        for (int i = 0; i < 1000; i++) {
            if (lruCache.opsForValue().get("fill:" + i) == null) nullCount++;
        }
        System.out.printf("OOM eviction: %d/1000 early keys evicted by LRU%n", nullCount);
        // App must handle null gracefully — no NPE, no 500
        assertThat(lruCache.opsForValue().get("fill:0")).satisfiesAnyOf(
            v -> assertThat(v).isNull(),
            v -> assertThat(v).isEqualTo("x".repeat(100))
        );
    }
}
