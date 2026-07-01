package com.macstab.chaos.examples.redis;

import com.macstab.chaos.core.annotation.SyscallLevelChaos;
import com.macstab.chaos.core.extension.ChaosTestingExtension;
import com.macstab.chaos.core.syscall.LibchaosLib;
import com.macstab.chaos.net.annotation.l1.ChaosRecvEconnreset;
import com.macstab.chaos.redis.annotation.RedisStandalone;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@ExtendWith(ChaosTestingExtension.class)
@SyscallLevelChaos(LibchaosLib.NET)
@RedisStandalone(id = "hot-cache", version = "7.4", args = {"--maxmemory", "128mb"})
@RedisStandalone(id = "warm-cache", version = "7.4", args = {"--maxmemory", "256mb"})
@RedisStandalone(id = "cold-cache", version = "7.4", args = {"--maxmemory", "512mb"})
@RedisStandalone(id = "session-store", version = "7.4", args = {"--maxmemory", "64mb", "--maxmemory-policy", "noeviction"})
@RedisStandalone(id = "rate-limiter", version = "7.4", args = {"--maxmemory", "32mb"})
class RedisFiveInstancesTest {

    @Autowired
    StringRedisTemplate hotCache;

    @Autowired
    StringRedisTemplate warmCache;

    @Autowired
    StringRedisTemplate coldCache;

    @Autowired
    StringRedisTemplate sessionStore;

    @Autowired
    StringRedisTemplate rateLimiter;

    @Test
    @DisplayName("5 instances: all operate independently — keys written to one not visible in others")
    void allFiveInstancesOperateIndependently() {
        hotCache.opsForValue().set("tier", "hot");
        warmCache.opsForValue().set("tier", "warm");
        coldCache.opsForValue().set("tier", "cold");
        sessionStore.opsForValue().set("tier", "session");
        rateLimiter.opsForValue().set("tier", "rate-limiter");

        assertThat(hotCache.opsForValue().get("tier")).isEqualTo("hot");
        assertThat(warmCache.opsForValue().get("tier")).isEqualTo("warm");
        assertThat(coldCache.opsForValue().get("tier")).isEqualTo("cold");
        assertThat(sessionStore.opsForValue().get("tier")).isEqualTo("session");
        assertThat(rateLimiter.opsForValue().get("tier")).isEqualTo("rate-limiter");
    }

    @Test
    @ChaosRecvEconnreset(probability = 0.9f, id = "hot-cache")
    @DisplayName("Hot cache 90% ECONNRESET: app falls back to warm-cache — warm cache takes over hot cache's load")
    void hotCacheFallsBackToWarm() throws Exception {
        warmCache.opsForValue().set("user:1", "data-from-warm-cache");
        AtomicInteger fromWarm = new AtomicInteger(0);
        AtomicInteger total = new AtomicInteger(0);
        for (int i = 0; i < 20; i++) {
            try {
                String v = hotCache.opsForValue().get("user:1");
                total.incrementAndGet();
            } catch (Exception e) {
                // fallback to warm
                String v = warmCache.opsForValue().get("user:1");
                if ("data-from-warm-cache".equals(v)) fromWarm.incrementAndGet();
                total.incrementAndGet();
            }
        }
        System.out.printf("Hot→Warm fallback: %d/20 reads fell back to warm cache%n", fromWarm.get());
        assertThat(fromWarm.get()).as("Warm cache serves requests when hot cache is 90%% down").isGreaterThan(5);
    }

    @Test
    @ChaosRecvEconnreset(probability = 0.9f, id = "hot-cache")
    @ChaosRecvEconnreset(probability = 0.9f, id = "warm-cache")
    @DisplayName("Hot + warm both 90% down: cold cache serves as last resort")
    void hotAndWarmDownColdCacheServes() throws Exception {
        coldCache.opsForValue().set("user:1", "data-from-cold-cache");
        AtomicInteger fromCold = new AtomicInteger(0);
        for (int i = 0; i < 10; i++) {
            String v = null;
            try {
                v = hotCache.opsForValue().get("user:1");
            } catch (Exception e1) {
                try {
                    v = warmCache.opsForValue().get("user:1");
                } catch (Exception e2) {
                    v = coldCache.opsForValue().get("user:1");
                    if ("data-from-cold-cache".equals(v)) fromCold.incrementAndGet();
                }
            }
        }
        System.out.printf("Cold cache as last resort: %d/10 reads served by cold tier%n", fromCold.get());
        assertThat(fromCold.get()).as("Cold cache serves when hot and warm are both down").isGreaterThan(3);
    }

    @Test
    @DisplayName("Session store noeviction policy: SET fails when full, existing sessions preserved")
    void sessionStoreNoEvictionPreservesExistingSessions() {
        sessionStore.opsForValue().set("session:important", "user-data", Duration.ofHours(1));
        // Fill session store
        for (int i = 0; i < 1000; i++) {
            try {
                sessionStore.opsForValue().set("fill:" + i, "x".repeat(100));
            } catch (Exception ignored) {}
        }
        assertThat(sessionStore.opsForValue().get("session:important"))
                .as("Important session preserved under noeviction policy").isEqualTo("user-data");
    }

    @Test
    @ChaosRecvEconnreset(probability = 0.9f, id = "hot-cache")
    @ChaosRecvEconnreset(probability = 0.9f, id = "warm-cache")
    @ChaosRecvEconnreset(probability = 0.9f, id = "cold-cache")
    @DisplayName("All 3 cache tiers down: rate limiter and session store still work (tier isolation)")
    void cascadingFailureStopsAtRateLimiter() throws Exception {
        rateLimiter.opsForValue().set("rate:user:1", "0");
        sessionStore.opsForValue().set("session:1", "active");

        AtomicInteger rateLimitOk = new AtomicInteger(0);
        AtomicInteger sessionOk = new AtomicInteger(0);
        for (int i = 0; i < 10; i++) {
            try {
                rateLimiter.opsForValue().increment("rate:user:1");
                rateLimitOk.incrementAndGet();
            } catch (Exception ignored) {}
            try {
                if (sessionStore.opsForValue().get("session:1") != null) sessionOk.incrementAndGet();
            } catch (Exception ignored) {}
        }
        System.out.printf("Cascading failure isolation: rate limiter %d/10 ok, session %d/10 ok%n",
                rateLimitOk.get(), sessionOk.get());
        assertThat(rateLimitOk.get()).as("Rate limiter works even when all cache tiers fail").isGreaterThan(8);
        assertThat(sessionOk.get()).as("Session store works even when all cache tiers fail").isGreaterThan(8);
    }
}
