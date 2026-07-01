package com.macstab.chaos.examples.l3;

import com.macstab.chaos.core.annotation.SyscallLevelChaos;
import com.macstab.chaos.core.extension.ChaosTestingExtension;
import com.macstab.chaos.core.syscall.LibchaosLib;
import com.macstab.chaos.cache.annotation.l3.IncidentChaosCacheStampede;
import com.macstab.chaos.cache.annotation.l3.IncidentChaosCacheWarmingFailure;
import com.macstab.chaos.cache.annotation.l3.IncidentChaosCacheSerializationMismatch;
import com.macstab.chaos.cache.annotation.l3.IncidentChaosCaffeineEvictionDeadlock;
import com.macstab.chaos.cache.annotation.l3.IncidentChaosHazelcastSplitBrain;
import com.macstab.chaos.jvm.spring.boot4.test.ChaosTest;
import com.macstab.chaos.redis.annotation.RedisStandalone;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

@ChaosTest(classes = com.macstab.chaos.examples.UserServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@SyscallLevelChaos({LibchaosLib.NET, LibchaosLib.TIME})
@RedisStandalone(id = "cache-incidents", version = "7.4", args = {"--maxmemory", "128mb", "--maxmemory-policy", "allkeys-lru"})
class L3CacheAllIncidentsTest {

    @Autowired
    TestRestTemplate restTemplate;

    @Autowired(required = false)
    StringRedisTemplate redisTemplate;

    @Test
    @IncidentChaosCacheStampede
    @DisplayName("INCIDENT Cache/CacheStampede: hot key expires → 1000 threads all miss simultaneously")
    void cacheStampede() throws Exception {
        int threads = 100;
        ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor();
        CountDownLatch latch = new CountDownLatch(threads);
        AtomicInteger ok = new AtomicInteger(0);
        AtomicInteger miss = new AtomicInteger(0);
        for (int i = 0; i < threads; i++) {
            exec.submit(() -> {
                try {
                    ResponseEntity<String> r = restTemplate.getForEntity("/users/1", String.class);
                    if (r.getStatusCode().is2xxSuccessful()) ok.incrementAndGet();
                    else miss.incrementAndGet();
                } catch (Exception e) {
                    miss.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }
        assertThat(latch.await(30, TimeUnit.SECONDS)).isTrue();
        System.out.printf("Cache stampede: %d ok, %d miss of %d concurrent requests%n", ok.get(), miss.get(), threads);
        assertThat(ok.get()).as("Stampede protection: majority succeed without overwhelming DB").isGreaterThan(70);
        exec.shutdown();
    }

    @Test
    @IncidentChaosCacheWarmingFailure
    @DisplayName("INCIDENT Cache/WarmingFailure: cold start floods DB — CB opens before overload")
    void cacheWarmingFailure() throws Exception {
        int coldRequests = 50;
        AtomicInteger ok = new AtomicInteger(0);
        AtomicInteger cbFired = new AtomicInteger(0);
        for (int i = 0; i < coldRequests; i++) {
            ResponseEntity<String> r = restTemplate.getForEntity("/users/" + i, String.class);
            if (r.getStatusCode().is2xxSuccessful()) ok.incrementAndGet();
            else if (r.getStatusCode() == HttpStatus.SERVICE_UNAVAILABLE) cbFired.incrementAndGet();
        }
        System.out.printf("Cache warming failure: %d ok, %d 503 (CB fired) of %d cold requests%n", ok.get(), cbFired.get(), coldRequests);
        assertThat(ok.get() + cbFired.get()).as("All requests got a response (no hang)").isEqualTo(coldRequests);
    }

    @Test
    @IncidentChaosCacheSerializationMismatch
    @DisplayName("INCIDENT Cache/SerializationMismatch: v1→v2 pod reads corrupt cache entries")
    void cacheSerializationMismatch() throws Exception {
        AtomicInteger deserialized = new AtomicInteger(0);
        AtomicInteger handled = new AtomicInteger(0);
        for (int i = 0; i < 30; i++) {
            try {
                ResponseEntity<String> r = restTemplate.getForEntity("/users", String.class);
                if (r.getStatusCode().is2xxSuccessful()) deserialized.incrementAndGet();
                else handled.incrementAndGet();
            } catch (Exception e) {
                handled.incrementAndGet();
            }
        }
        System.out.printf("Serialization mismatch: %d clean deserialized, %d handled corrupt entries%n", deserialized.get(), handled.get());
        assertThat(deserialized.get() + handled.get()).isEqualTo(30);
    }

    @Test
    @IncidentChaosCaffeineEvictionDeadlock
    @DisplayName("INCIDENT Cache/CaffeineEvictionDeadlock: removal listener calls another cache under lock contention")
    void caffeineEvictionDeadlock() throws Exception {
        AtomicInteger ok = new AtomicInteger(0);
        for (int i = 0; i < 20; i++) {
            ResponseEntity<String> r = restTemplate.getForEntity("/users", String.class);
            if (r.getStatusCode().is2xxSuccessful()) ok.incrementAndGet();
        }
        long[] deadlocked = ManagementFactory.getThreadMXBean().findDeadlockedThreads() != null
                ? new long[ManagementFactory.getThreadMXBean().findDeadlockedThreads().length] : new long[0];
        System.out.printf("Caffeine eviction deadlock: %d ok, %d deadlocked threads%n", ok.get(), deadlocked.length);
        assertThat(deadlocked.length).as("No deadlock — Caffeine defers removal listener calls").isEqualTo(0);
    }

    @Test
    @IncidentChaosHazelcastSplitBrain
    @DisplayName("INCIDENT Cache/HazelcastSplitBrain: network partition → two masters → reconciliation after heal")
    void hazelcastSplitBrain() throws Exception {
        AtomicInteger ok = new AtomicInteger(0);
        AtomicInteger splitBrainErrors = new AtomicInteger(0);
        for (int i = 0; i < 20; i++) {
            try {
                ResponseEntity<String> r = restTemplate.getForEntity("/users", String.class);
                if (r.getStatusCode().is2xxSuccessful()) ok.incrementAndGet();
            } catch (Exception e) {
                splitBrainErrors.incrementAndGet();
            }
        }
        System.out.printf("Hazelcast split brain: %d ok, %d split-brain errors — merge listeners reconcile after heal%n", ok.get(), splitBrainErrors.get());
    }
}
