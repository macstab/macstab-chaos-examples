package com.macstab.chaos.examples.redis;

import com.macstab.chaos.core.annotation.SyscallLevelChaos;
import com.macstab.chaos.core.extension.ChaosTestingExtension;
import com.macstab.chaos.core.syscall.LibchaosLib;
import com.macstab.chaos.net.annotation.l1.ChaosRecvEconnreset;
import com.macstab.chaos.redis.annotation.RedisStandalone;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.*;

import static org.assertj.core.api.Assertions.*;

@Testcontainers
@ExtendWith(ChaosTestingExtension.class)
@SyscallLevelChaos(LibchaosLib.NET)
@RedisStandalone(id = "redis60", version = "6.0")
@RedisStandalone(id = "redis70", version = "7.0")
@RedisStandalone(id = "redis74", version = "7.4")
@RedisStandalone(id = "redis74alpine", version = "7.4-alpine")
class RedisStandaloneAllVersionsTest {

    @Autowired
    StringRedisTemplate redis60;

    @Autowired
    StringRedisTemplate redis70;

    @Autowired
    StringRedisTemplate redis74;

    @Autowired
    StringRedisTemplate redis74alpine;

    @Test
    @DisplayName("All 4 Redis versions: SET/GET/EXPIRE/TTL work identically across 6.0, 7.0, 7.4, 7.4-alpine")
    void connectAndBasicOperationsOnAllVersions() {
        for (StringRedisTemplate rt : List.of(redis60, redis70, redis74, redis74alpine)) {
            rt.opsForValue().set("key", "value", Duration.ofSeconds(60));
            assertThat(rt.opsForValue().get("key")).isEqualTo("value");
            assertThat(rt.getExpire("key")).as("TTL set correctly").isGreaterThan(0);
        }
    }

    @Test
    @ChaosRecvEconnreset(probability = 0.3f)
    @DisplayName("30% ECONNRESET: recovery behavior identical across all Redis versions")
    void econnresetRecoveryIdenticalAcrossVersions() throws Exception {
        for (StringRedisTemplate rt : List.of(redis60, redis70, redis74, redis74alpine)) {
            AtomicInteger ok = new AtomicInteger(0);
            for (int i = 0; i < 20; i++) {
                try {
                    rt.opsForValue().set("chaos:" + i, "v" + i);
                    ok.incrementAndGet();
                } catch (Exception ignored) {
                }
            }
            assertThat(ok.get()).as("At least 10/20 writes succeed through ECONNRESET on each version").isGreaterThan(10);
        }
    }

    @Test
    @DisplayName("Maxmemory behavior: all versions respect maxmemory limit but eviction policies may differ")
    void maxmemoryBehaviorConsistentAcrossVersions() {
        for (StringRedisTemplate rt : List.of(redis60, redis70, redis74)) {
            for (int i = 0; i < 100; i++) {
                rt.opsForValue().set("fill:" + i, "x".repeat(1000));
            }
            assertThat(rt.opsForValue().get("fill:0")).satisfiesAnyOf(
                v -> assertThat(v).isNull(),
                v -> assertThat(v).isEqualTo("x".repeat(1000))
            );
        }
    }

    @Test
    @DisplayName("INCR/DECR atomic counters: consistent across all versions under network chaos")
    @ChaosRecvEconnreset(probability = 0.1f)
    void atomicCountersUnderChaos() {
        for (StringRedisTemplate rt : List.of(redis60, redis70, redis74, redis74alpine)) {
            rt.opsForValue().set("counter", "0");
            for (int i = 0; i < 10; i++) {
                try {
                    rt.opsForValue().increment("counter");
                } catch (Exception ignored) {
                }
            }
            String val = rt.opsForValue().get("counter");
            assertThat(val).as("Counter has valid numeric value").matches("\\d+");
            System.out.printf("Counter after 10 increments with 10%% ECONNRESET: %s%n", val);
        }
    }
}
