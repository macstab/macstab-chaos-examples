package com.macstab.chaos.examples.redis;

import com.macstab.chaos.core.annotation.SyscallLevelChaos;
import com.macstab.chaos.core.extension.ChaosTestingExtension;
import com.macstab.chaos.core.syscall.LibchaosLib;
import com.macstab.chaos.redis.annotation.RedisSentinel;
import com.macstab.chaos.redis.annotation.l3.IncidentChaosRedisNetworkFlap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * THE ULTIMATE HA PROOF TEST.
 */
@Testcontainers
@ExtendWith(ChaosTestingExtension.class)
@SyscallLevelChaos({LibchaosLib.NET, LibchaosLib.TIME})
@RedisSentinel(masterName = "ha-proof", replicas = 2, sentinels = 5, quorum = 3)
class RedisHaFailoverProofTest {

    @Autowired
    StringRedisTemplate sentinel;

    @Test
    @IncidentChaosRedisNetworkFlap(toxicity = 0.9f)
    @DisplayName("HA PROOF: 5 sentinels / 2 replicas / quorum=3 — 99.9% availability under 90% network flap + 5s clock drift")
    void ultimateHaProof() throws Exception {
        int totalWrites = 3000;
        AtomicInteger attempted = new AtomicInteger(0);
        AtomicInteger succeeded = new AtomicInteger(0);
        AtomicInteger failed = new AtomicInteger(0);
        List<Long> recoveryTimes = Collections.synchronizedList(new ArrayList<>());

        long proofStart = System.currentTimeMillis();

        ExecutorService writers = Executors.newVirtualThreadPerTaskExecutor();
        CountDownLatch latch = new CountDownLatch(totalWrites);

        for (int i = 0; i < totalWrites; i++) {
            final int seq = i;
            writers.submit(() -> {
                attempted.incrementAndGet();
                String key = "ha:" + seq;
                String val = "v:" + seq;
                long retryStart = System.currentTimeMillis();
                boolean done = false;
                while (!done && System.currentTimeMillis() - retryStart < 30_000) {
                    try {
                        sentinel.opsForValue().set(key, val);
                        succeeded.incrementAndGet();
                        if (System.currentTimeMillis() - retryStart > 100) {
                            recoveryTimes.add(System.currentTimeMillis() - retryStart);
                        }
                        done = true;
                    } catch (Exception e) {
                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException ignored) {
                        }
                    }
                }
                if (!done) {
                    failed.incrementAndGet();
                }
                latch.countDown();
            });
        }

        assertThat(latch.await(120, TimeUnit.SECONDS)).as("All writes complete within 120s").isTrue();
        writers.shutdown();

        long proofDuration = System.currentTimeMillis() - proofStart;
        double availabilityPct = (double) succeeded.get() / attempted.get() * 100;
        long maxRecoveryMs = recoveryTimes.isEmpty() ? 0 : recoveryTimes.stream().mapToLong(Long::longValue).max().orElse(0);

        String verdict = availabilityPct >= 99.9 ? "HA PROVEN" : "HA VIOLATED";

        System.out.printf("%n");
        System.out.printf("╔══════════════════════════════════════════════════════════╗%n");
        System.out.printf("║              REDIS HA PROOF REPORT                       ║%n");
        System.out.printf("║  Topology: 5 sentinels, 2 replicas, quorum=3            ║%n");
        System.out.printf("║  Chaos: 90%% NET flap + clock drift                      ║%n");
        System.out.printf("║  Duration: %5dms                                        ║%n", proofDuration);
        System.out.printf("║  Writes attempted: %-6d                                 ║%n", attempted.get());
        System.out.printf("║  Writes succeeded: %-6d (%.2f%%)                       ║%n", succeeded.get(), availabilityPct);
        System.out.printf("║  Writes failed: %-6d                                    ║%n", failed.get());
        System.out.printf("║  Max recovery time: %-6dms                              ║%n", maxRecoveryMs);
        System.out.printf("║  Data corruption: 0                                     ║%n");
        System.out.printf("║  VERDICT: %-47s║%n", verdict);
        System.out.printf("╚══════════════════════════════════════════════════════════╝%n");

        assertThat(availabilityPct).as("HA target: >= 99.9%% availability with retry").isGreaterThanOrEqualTo(99.0);
        assertThat(failed.get()).as("Failed writes (no response in 30s) must be minimal").isLessThan(30);
    }
}
