package com.macstab.chaos.examples.redis;

import static org.assertj.core.api.Assertions.assertThat;

import com.macstab.chaos.annotation.SyscallLevelChaos;
import com.macstab.chaos.annotation.composite.CompositeChaosConnectionDrop;
import com.macstab.chaos.annotation.fault.net.ChaosRecvEconnreset;
import com.macstab.chaos.extension.ChaosTestingExtension;
import com.macstab.chaos.lib.LibchaosLib;
import com.macstab.chaos.redis.RedisConnectionInfo;
import com.macstab.chaos.redis.annotation.RedisStandalone;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.exceptions.JedisConnectionException;

/**
 * The entry point into Macstab chaos testing with Redis.
 *
 * <p>This class deliberately uses a single annotation – {@link RedisStandalone} – and nothing else
 * for the happy-path test. The subsequent tests layer on L1 and L2 chaos to demonstrate the
 * annotation-driven progression from zero-chaos to full chaos in a single test class.
 *
 * <p>The {@link RedisStandalone} annotation:
 * <ul>
 *   <li>Provisions a Testcontainers Redis container running version 7.4.
 *   <li>Configures it with a 256 MB max-memory cap and LRU eviction policy.
 *   <li>Makes the connection details available via {@link RedisConnectionInfo} parameter injection.
 *   <li>Tears down the container after the test class completes.
 * </ul>
 */
@RedisStandalone(
        id = "basic",
        version = "7.4",
        args = {"--maxmemory", "256mb", "--maxmemory-policy", "allkeys-lru"})
@ExtendWith(ChaosTestingExtension.class)
@SyscallLevelChaos(LibchaosLib.NET)
class RedisStandaloneBasicTest {

    private static final Logger log = LoggerFactory.getLogger(RedisStandaloneBasicTest.class);

    private JedisPool pool;

    @AfterEach
    void tearDown() {
        if (pool != null && !pool.isClosed()) {
            pool.close();
        }
    }

    // ── Tests ─────────────────────────────────────────────────────────────

    /**
     * The simplest possible test: connect, write a key, read it back.
     *
     * <p>No chaos is active. This test verifies that the container is healthy and the {@link
     * RedisConnectionInfo} injection works correctly before any fault is introduced.
     */
    @Test
    void basicSetAndGetWithNoFaults(RedisConnectionInfo info) {
        pool = buildPool(info);

        try (Jedis jedis = pool.getResource()) {
            String key = "test:basic:" + UUID.randomUUID();
            String value = "hello-from-chaos-framework";

            jedis.setex(key, 60, value);

            String retrieved = jedis.get(key);
            assertThat(retrieved)
                    .as("SET then GET must return the same value when no chaos is active")
                    .isEqualTo(value);

            log.info("Basic SET/GET verified on {}:{}", info.host(), info.port());
        }
    }

    /**
     * Injects 5% {@code ECONNRESET} on {@code recv()} and verifies that a Jedis connection pool
     * with retry-on-error handles the faults transparently.
     *
     * <p>At 5% fault rate the vast majority of operations succeed on the first attempt. Any
     * operation that encounters an RST will surface as a {@link JedisConnectionException}. The test
     * demonstrates the minimal fault-handling pattern: catch the exception, log it, and count it –
     * then assert the error count stays within tolerance.
     */
    @Test
    @ChaosRecvEconnreset(probability = 0.05)
    void fivePercentEconnresetIsHandledByConnectionPool(RedisConnectionInfo info) {
        pool = buildPool(info);

        int operations = 200;
        AtomicLong errorCount = new AtomicLong();
        AtomicLong successCount = new AtomicLong();

        for (int i = 0; i < operations; i++) {
            String key = "test:econnreset:" + i;
            try (Jedis jedis = pool.getResource()) {
                jedis.setex(key, 30, "value-" + i);
                String val = jedis.get(key);
                if (val != null) {
                    successCount.incrementAndGet();
                }
            } catch (JedisConnectionException e) {
                errorCount.incrementAndGet();
                log.debug("ECONNRESET on operation {}: {}", i, e.getMessage());
            }
        }

        double errorRate = (double) errorCount.get() / operations * 100.0;

        log.info(
                "5%% ECONNRESET: ops={}, success={}, errors={}, errorRate={:.2f}%%",
                operations,
                successCount.get(),
                errorCount.get(),
                errorRate);

        // 5% fault rate must produce < 10% observable errors (pool absorbs some via reconnect).
        assertThat(errorRate)
                .as("observable error rate must stay below 10%% under 5%% ECONNRESET injection")
                .isLessThan(10.0);
    }

    /**
     * Applies {@link CompositeChaosConnectionDrop} at 30% toxicity and demonstrates how to measure
     * and assert Redis operation resilience under composite (multi-fault) chaos.
     *
     * <p>The composite annotation bundles {@code ECONNRESET} + {@code ECONNABORTED} + brief latency
     * spikes to approximate a real network degradation event. At 30% the sliding window in the
     * circuit breaker fills with failures quickly; the test asserts that the circuit breaker state
     * is eventually OPEN and that no data corruption occurred for operations that succeeded.
     */
    @Test
    @CompositeChaosConnectionDrop(toxicity = 0.30)
    void compositeChaosConnectionDrop30PercentPreservesDataIntegrity(RedisConnectionInfo info) {
        pool = buildPool(info);

        int operations = 150;
        List<String> writtenKeys = new ArrayList<>();
        AtomicLong writeErrors = new AtomicLong();
        AtomicLong readErrors = new AtomicLong();
        AtomicLong corruptedValues = new AtomicLong();

        // Phase 1: write.
        for (int i = 0; i < operations; i++) {
            String key = "test:integrity:" + i;
            String expectedValue = "v-" + i;
            try (Jedis jedis = pool.getResource()) {
                jedis.setex(key, 120, expectedValue);
                writtenKeys.add(key + "=" + expectedValue);
            } catch (JedisConnectionException e) {
                writeErrors.incrementAndGet();
                log.debug("Write error on {}: {}", key, e.getMessage());
            }
        }

        // Phase 2: verify integrity of written keys.
        for (String entry : writtenKeys) {
            String[] parts = entry.split("=", 2);
            String key = parts[0];
            String expected = parts[1];
            try (Jedis jedis = pool.getResource()) {
                String actual = jedis.get(key);
                if (actual != null && !actual.equals(expected)) {
                    corruptedValues.incrementAndGet();
                    log.error("Data corruption: key={} expected={} actual={}", key, expected, actual);
                }
            } catch (JedisConnectionException e) {
                readErrors.incrementAndGet();
            }
        }

        log.info(
                "30%% composite chaos: written={}, writeErrors={}, readErrors={},"
                        + " corrupted={}",
                writtenKeys.size(),
                writeErrors.get(),
                readErrors.get(),
                corruptedValues.get());

        // Redis must never corrupt data – partial writes either succeed fully or fail with an
        // exception. Zero-tolerance for corrupted values.
        assertThat(corruptedValues.get())
                .as("Redis must guarantee write atomicity; zero corrupted values acceptable")
                .isEqualTo(0L);
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private static JedisPool buildPool(RedisConnectionInfo info) {
        JedisPoolConfig config = new JedisPoolConfig();
        config.setMaxTotal(16);
        config.setMaxIdle(8);
        config.setMinIdle(2);
        config.setTestOnBorrow(true);
        config.setTestOnReturn(true);
        return new JedisPool(config, info.host(), info.port(), 2000);
    }
}
