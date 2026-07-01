package com.macstab.chaos.examples.redis;

import com.macstab.chaos.core.annotation.SyscallLevelChaos;
import com.macstab.chaos.core.extension.ChaosTestingExtension;
import com.macstab.chaos.core.syscall.LibchaosLib;
import com.macstab.chaos.net.annotation.l1.ChaosRecvEconnreset;
import com.macstab.chaos.redis.annotation.RedisSentinel;
import com.macstab.chaos.redis.annotation.l3.IncidentChaosRedisNetworkFlap;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.junit.jupiter.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.*;

@Testcontainers
@ExtendWith(ChaosTestingExtension.class)
@SyscallLevelChaos(LibchaosLib.NET)
@RedisSentinel(id = "minimal", masterName = "minimal-master", replicas = 1, sentinels = 3, quorum = 2)
@RedisSentinel(id = "standard", masterName = "standard-master", replicas = 2, sentinels = 3, quorum = 2)
@RedisSentinel(id = "large", masterName = "large-master", replicas = 3, sentinels = 5, quorum = 3)
class RedisSentinelAllTopologiesTest {

    @Autowired
    StringRedisTemplate minimal;

    @Autowired
    StringRedisTemplate standard;

    @Autowired
    StringRedisTemplate large;

    @Test
    @DisplayName("All 3 sentinel topologies operate independently — no cross-contamination")
    void allTopologiesOperateIndependently() {
        minimal.opsForValue().set("topology", "minimal");
        standard.opsForValue().set("topology", "standard");
        large.opsForValue().set("topology", "large");

        assertThat(minimal.opsForValue().get("topology")).isEqualTo("minimal");
        assertThat(standard.opsForValue().get("topology")).isEqualTo("standard");
        assertThat(large.opsForValue().get("topology")).isEqualTo("large");
    }

    @Test
    @ChaosRecvEconnreset(probability = 0.3f)
    @DisplayName("30% ECONNRESET: large topology (5 sentinels) most resilient — quorum=3 of 5")
    void largeTopologyMostResilientUnderNetworkChaos() throws Exception {
        AtomicInteger minimalOk = new AtomicInteger(0);
        AtomicInteger standardOk = new AtomicInteger(0);
        AtomicInteger largeOk = new AtomicInteger(0);

        for (int i = 0; i < 20; i++) {
            try { minimal.opsForValue().set("k:" + i, "v"); minimalOk.incrementAndGet(); } catch (Exception ignored) {}
            try { standard.opsForValue().set("k:" + i, "v"); standardOk.incrementAndGet(); } catch (Exception ignored) {}
            try { large.opsForValue().set("k:" + i, "v"); largeOk.incrementAndGet(); } catch (Exception ignored) {}
        }

        System.out.printf("30%% ECONNRESET resilience: minimal=%d/20, standard=%d/20, large=%d/20%n",
                minimalOk.get(), standardOk.get(), largeOk.get());

        assertThat(largeOk.get()).as("Large topology most resilient (5 sentinels)").isGreaterThanOrEqualTo(minimalOk.get());
    }

    @Test
    @DisplayName("Baseline write throughput: all topologies accept writes without chaos")
    void baselineWriteThroughput() {
        for (int i = 0; i < 50; i++) {
            minimal.opsForValue().set("baseline:" + i, "v" + i);
            standard.opsForValue().set("baseline:" + i, "v" + i);
            large.opsForValue().set("baseline:" + i, "v" + i);
        }

        assertThat(minimal.opsForValue().get("baseline:49")).isEqualTo("v49");
        assertThat(standard.opsForValue().get("baseline:49")).isEqualTo("v49");
        assertThat(large.opsForValue().get("baseline:49")).isEqualTo("v49");
    }

    @Test
    @IncidentChaosRedisNetworkFlap(toxicity = 0.9f, id = "minimal")
    @DisplayName("90% network flap on minimal topology ONLY — standard and large unaffected (container isolation)")
    void networkFlapOnMinimalOnlyIsolated() throws Exception {
        AtomicInteger standardOk = new AtomicInteger(0);
        AtomicInteger largeOk = new AtomicInteger(0);

        for (int i = 0; i < 10; i++) {
            try { standard.opsForValue().set("isolated:" + i, "v"); standardOk.incrementAndGet(); } catch (Exception ignored) {}
            try { large.opsForValue().set("isolated:" + i, "v"); largeOk.incrementAndGet(); } catch (Exception ignored) {}
        }

        System.out.printf("Isolation test: standard=%d/10 ok, large=%d/10 ok (chaos only on minimal)%n",
                standardOk.get(), largeOk.get());

        assertThat(standardOk.get()).as("Standard topology not affected by chaos on minimal").isGreaterThan(7);
        assertThat(largeOk.get()).as("Large topology not affected by chaos on minimal").isGreaterThan(7);
    }
}
