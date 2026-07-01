package com.macstab.chaos.examples.redis;

import static org.assertj.core.api.Assertions.assertThat;

import com.macstab.chaos.annotation.SyscallLevelChaos;
import com.macstab.chaos.annotation.fault.net.ChaosRecvEconnreset;
import com.macstab.chaos.extension.ChaosTestingExtension;
import com.macstab.chaos.lib.LibchaosLib;
import com.macstab.chaos.redis.RedisConnectionInfo;
import com.macstab.chaos.redis.annotation.RedisStandalone;
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
 * Demonstrates multiple independent Redis instances within a single test class.
 *
 * <p>A real microservice rarely has just one Redis. A production deployment might use:
 * <ul>
 *   <li>A <b>session store</b> – holds user sessions; highest availability requirement.
 *   <li>A <b>rate limiter</b> – small memory budget, fire-and-forget writes, can lose data.
 *   <li>A <b>feature flag store</b> – rarely written, frequently read; uses a lightweight
 *       Alpine image.
 * </ul>
 *
 * <p>Three {@link RedisStandalone} annotations on the class configure three independent containers.
 * Each test method receives three {@link RedisConnectionInfo} parameters, injected by the framework
 * in declaration order. The key property exercised here is <em>blast radius isolation</em>: chaos
 * on one instance must not affect the other two.
 */
@RedisStandalone(id = "session-store", version = "7.4")
@RedisStandalone(id = "rate-limiter", version = "7.4", args = {"--maxmemory", "64mb"})
@RedisStandalone(id = "feature-flags", version = "7.4-alpine")
@ExtendWith(ChaosTestingExtension.class)
@SyscallLevelChaos(LibchaosLib.NET)
class MultipleRedisStandalonesTest {

    private static final Logger log = LoggerFactory.getLogger(MultipleRedisStandalonesTest.class);

    private final List<JedisPool> pools = new ArrayList<>();

    @AfterEach
    void tearDown() {
        pools.forEach(p -> {
            if (!p.isClosed()) {
                p.close();
            }
        });
        pools.clear();
    }

    // ── Tests ─────────────────────────────────────────────────────────────

    /**
     * Verifies that all three Redis instances are healthy and fully independent of one another.
     *
     * <p>Each instance is written to with a unique key prefix and the value is verified on read.
     * This is the baseline test: it must pass with no chaos applied.
     */
    @Test
    void allThreeInstancesAreIndependentAndHealthy(
            RedisConnectionInfo sessionStore,
            RedisConnectionInfo rateLimiter,
            RedisConnectionInfo featureFlags) {

        JedisPool sessionPool = buildPool(sessionStore, "session-store");
        JedisPool ratePool = buildPool(rateLimiter, "rate-limiter");
        JedisPool flagPool = buildPool(featureFlags, "feature-flags");

        String sessionKey = "session:" + UUID.randomUUID();
        String rateKey = "rate:user:42";
        String flagKey = "flag:dark-mode";

        // Write to each instance.
        try (Jedis s = sessionPool.getResource()) {
            s.setex(sessionKey, 3600, "session-data-abc");
        }
        try (Jedis r = ratePool.getResource()) {
            r.incr(rateKey);
            r.expire(rateKey, 60);
        }
        try (Jedis f = flagPool.getResource()) {
            f.set(flagKey, "true");
        }

        // Verify each instance independently.
        try (Jedis s = sessionPool.getResource()) {
            assertThat(s.get(sessionKey))
                    .as("session store must return the stored session")
                    .isEqualTo("session-data-abc");
        }
        try (Jedis r = ratePool.getResource()) {
            assertThat(r.get(rateKey))
                    .as("rate limiter must return the incremented counter")
                    .isEqualTo("1");
        }
        try (Jedis f = flagPool.getResource()) {
            assertThat(f.get(flagKey))
                    .as("feature flag store must return the flag value")
                    .isEqualTo("true");
        }

        log.info(
                "All three Redis instances verified: session={}:{}, rate={}:{}, flags={}:{}",
                sessionStore.host(), sessionStore.port(),
                rateLimiter.host(), rateLimiter.port(),
                featureFlags.host(), featureFlags.port());
    }

    /**
     * Injects 40% {@code ECONNRESET} targeted at the <b>session store only</b> and proves that the
     * rate-limiter and feature-flag instances remain completely unaffected.
     *
     * <p>The {@code id = "session-store"} on the chaos annotation scopes the fault injection to the
     * container with that id. The libchaos library tracks containers by their assigned id and only
     * intercepts syscalls originating from connections to that specific container's port.
     *
     * <p>Blast radius is strictly limited to the session store; the other two instances must
     * continue to serve 100% of requests without error.
     */
    @Test
    @ChaosRecvEconnreset(probability = 0.40, id = "session-store")
    void sessionStoreChaosDoesNotAffectRateLimiter(
            RedisConnectionInfo sessionStore,
            RedisConnectionInfo rateLimiter,
            RedisConnectionInfo featureFlags) {

        JedisPool sessionPool = buildPool(sessionStore, "session-store");
        JedisPool ratePool = buildPool(rateLimiter, "rate-limiter");
        JedisPool flagPool = buildPool(featureFlags, "feature-flags");

        int operations = 100;
        AtomicLong sessionErrors = new AtomicLong();
        AtomicLong rateErrors = new AtomicLong();
        AtomicLong flagErrors = new AtomicLong();

        for (int i = 0; i < operations; i++) {
            // Session store – chaos active.
            try (Jedis s = sessionPool.getResource()) {
                s.setex("session:" + i, 60, "data");
            } catch (JedisConnectionException e) {
                sessionErrors.incrementAndGet();
            }

            // Rate limiter – must be clean.
            try (Jedis r = ratePool.getResource()) {
                r.incr("rate:op:" + i);
            } catch (JedisConnectionException e) {
                rateErrors.incrementAndGet();
                log.error("Unexpected rate limiter error on op {}: {}", i, e.getMessage());
            }

            // Feature flags – must be clean.
            try (Jedis f = flagPool.getResource()) {
                f.get("flag:feature-x");
            } catch (JedisConnectionException e) {
                flagErrors.incrementAndGet();
                log.error("Unexpected feature-flags error on op {}: {}", i, e.getMessage());
            }
        }

        log.info(
                "Session chaos (40%% ECONNRESET): sessionErrors={}, rateErrors={},"
                        + " flagErrors={}",
                sessionErrors.get(),
                rateErrors.get(),
                flagErrors.get());

        // Session store is under chaos – errors expected.
        assertThat(sessionErrors.get())
                .as("session store must experience errors under 40%% ECONNRESET")
                .isGreaterThan(0L);

        // The other two instances must be completely unaffected.
        assertThat(rateErrors.get())
                .as(
                        "rate limiter must have ZERO errors when chaos is scoped to"
                                + " session-store only")
                .isEqualTo(0L);

        assertThat(flagErrors.get())
                .as(
                        "feature flag store must have ZERO errors when chaos is scoped to"
                                + " session-store only")
                .isEqualTo(0L);
    }

    /**
     * Injects 60% {@code ECONNRESET} targeted at the <b>rate-limiter only</b> and proves that the
     * session store and feature-flags instances remain completely unaffected.
     *
     * <p>This is the mirror test of {@link #sessionStoreChaosDoesNotAffectRateLimiter}: it locks in
     * the blast-radius isolation guarantee from both directions.
     */
    @Test
    @ChaosRecvEconnreset(probability = 0.60, id = "rate-limiter")
    void rateLimiterChaosDoesNotAffectFeatureFlags(
            RedisConnectionInfo sessionStore,
            RedisConnectionInfo rateLimiter,
            RedisConnectionInfo featureFlags) {

        JedisPool sessionPool = buildPool(sessionStore, "session-store");
        JedisPool ratePool = buildPool(rateLimiter, "rate-limiter");
        JedisPool flagPool = buildPool(featureFlags, "feature-flags");

        int operations = 100;
        AtomicLong sessionErrors = new AtomicLong();
        AtomicLong rateErrors = new AtomicLong();
        AtomicLong flagErrors = new AtomicLong();

        for (int i = 0; i < operations; i++) {
            // Session store – must be clean.
            try (Jedis s = sessionPool.getResource()) {
                s.setex("session:clean:" + i, 60, "ok");
            } catch (JedisConnectionException e) {
                sessionErrors.incrementAndGet();
                log.error("Unexpected session store error on op {}: {}", i, e.getMessage());
            }

            // Rate limiter – chaos active.
            try (Jedis r = ratePool.getResource()) {
                r.incr("rate:chaos:" + i);
            } catch (JedisConnectionException e) {
                rateErrors.incrementAndGet();
            }

            // Feature flags – must be clean.
            try (Jedis f = flagPool.getResource()) {
                f.set("flag:op:" + i, i % 2 == 0 ? "true" : "false");
                f.get("flag:op:" + i);
            } catch (JedisConnectionException e) {
                flagErrors.incrementAndGet();
                log.error("Unexpected feature-flags error on op {}: {}", i, e.getMessage());
            }
        }

        log.info(
                "Rate-limiter chaos (60%% ECONNRESET): sessionErrors={}, rateErrors={},"
                        + " flagErrors={}",
                sessionErrors.get(),
                rateErrors.get(),
                flagErrors.get());

        // Rate limiter is under chaos – errors expected.
        assertThat(rateErrors.get())
                .as("rate limiter must experience errors under 60%% ECONNRESET")
                .isGreaterThan(0L);

        // The other two instances must be completely unaffected.
        assertThat(sessionErrors.get())
                .as("session store must have ZERO errors when chaos is scoped to rate-limiter only")
                .isEqualTo(0L);

        assertThat(flagErrors.get())
                .as(
                        "feature flag store must have ZERO errors when chaos is scoped to"
                                + " rate-limiter only")
                .isEqualTo(0L);
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private JedisPool buildPool(RedisConnectionInfo info, String label) {
        JedisPoolConfig config = new JedisPoolConfig();
        config.setMaxTotal(8);
        config.setMaxIdle(4);
        config.setMinIdle(1);
        config.setTestOnBorrow(false);
        JedisPool pool = new JedisPool(config, info.host(), info.port(), 2000);
        pools.add(pool);
        log.info("Pool created for {} at {}:{}", label, info.host(), info.port());
        return pool;
    }
}
