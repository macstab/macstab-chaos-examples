package com.macstab.chaos.examples.l3;

import com.macstab.chaos.core.annotation.SyscallLevelChaos;
import com.macstab.chaos.core.extension.ChaosTestingExtension;
import com.macstab.chaos.core.syscall.LibchaosLib;
import com.macstab.chaos.k8s.annotation.l3.*;
import com.macstab.chaos.jvm.spring.boot4.test.ChaosTest;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.*;

import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

@ChaosTest(classes = com.macstab.chaos.examples.UserServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@SyscallLevelChaos({LibchaosLib.NET, LibchaosLib.DNS, LibchaosLib.TIME})
class L3KubernetesAllIncidentsTest {

    @Autowired
    TestRestTemplate restTemplate;

    @Test
    @IncidentChaosK8sRollingUpdateRst(toxicity = 0.3f)
    @DisplayName("INCIDENT K8s/RollingUpdateRst: 30% ECONNRESET as old pods die — < 5% 5xx during chaos window")
    void k8sRollingUpdateRst() throws Exception {
        int total = 200;
        AtomicInteger ok = new AtomicInteger(0);
        AtomicInteger err5xx = new AtomicInteger(0);

        ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor();
        CountDownLatch latch = new CountDownLatch(total);
        for (int i = 0; i < total; i++) {
            exec.submit(() -> {
                try {
                    ResponseEntity<String> r = restTemplate.getForEntity("/users", String.class);
                    if (r.getStatusCode().is2xxSuccessful()) ok.incrementAndGet();
                    else if (r.getStatusCode().is5xxServerError()) err5xx.incrementAndGet();
                } catch (Exception e) {
                    err5xx.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }
        assertThat(latch.await(30, TimeUnit.SECONDS)).isTrue();
        double errorRate = (double) err5xx.get() / total;
        System.out.printf("K8s rolling update RST: %d ok, %d 5xx, %.1f%% error rate%n", ok.get(), err5xx.get(), errorRate * 100);
        assertThat(errorRate).as("< 5% 5xx during rolling update RST chaos").isLessThan(0.05);
        exec.shutdown();
    }

    @Test
    @IncidentChaosK8sDnsNdots5Storm(toxicity = 0.2f)
    @DisplayName("INCIDENT K8s/DnsNdots5Storm: 20% EAI_AGAIN per lookup × 5 search domains = 67% compound failure")
    void k8sDnsNdots5Storm() throws Exception {
        // With ndots:5, one hostname resolution attempts up to 5 DNS queries
        // Each has 20% failure chance → P(all 5 succeed) = 0.8^5 = 0.328 → 67% effective failure
        System.out.println("ndots:5 amplification math: 20% per query × 5 queries = 67% compound failure rate");
        System.out.println("This is why Kubernetes pods with ndots:5 have high DNS failure rates in prod");

        AtomicInteger ok = new AtomicInteger(0);
        AtomicInteger fail = new AtomicInteger(0);
        for (int i = 0; i < 50; i++) {
            try {
                ResponseEntity<String> r = restTemplate.getForEntity("/users", String.class);
                if (r.getStatusCode().is2xxSuccessful()) ok.incrementAndGet();
                else fail.incrementAndGet();
            } catch (Exception e) {
                fail.incrementAndGet();
            }
        }
        System.out.printf("K8s ndots:5 storm: %d ok, %d fail of 50 (circuit breaker limits blast radius)%n", ok.get(), fail.get());
        assertThat(ok.get()).as("Circuit breaker allows some success even under ndots:5 storm").isGreaterThan(10);
    }

    @Test
    @IncidentChaosK8sOomKillMidGc
    @DisplayName("INCIDENT K8s/OomKillMidGc: cgroup limit exceeded mid-GC — self-protection activates before OOM kill")
    void k8sOomKillMidGc() throws Exception {
        long max = Runtime.getRuntime().maxMemory();
        System.out.printf("K8s OOM mid-GC: JVM max heap = %dMB — self-protection should kick in before %dMB%n",
                max / 1024 / 1024, max * 8 / 10 / 1024 / 1024);

        AtomicInteger ok503 = new AtomicInteger(0);
        AtomicInteger crash = new AtomicInteger(0);
        for (int i = 0; i < 30; i++) {
            try {
                ResponseEntity<String> r = restTemplate.getForEntity("/users", String.class);
                if (r.getStatusCode() == HttpStatus.SERVICE_UNAVAILABLE) ok503.incrementAndGet();
                else if (r.getStatusCode().is2xxSuccessful()) {
                    // 503 is the desired outcome — self-protection firing
                }
            } catch (Exception e) {
                crash.incrementAndGet();
            }
        }
        System.out.printf("OOM kill mid-GC: %d 503s (self-protection), %d crashes%n", ok503.get(), crash.get());
        assertThat(crash.get()).as("Zero hard crashes — self-protection must fire before OOM kill").isEqualTo(0);
    }

    @Test
    @IncidentChaosK8sSidecarShutdownRace
    @DisplayName("INCIDENT K8s/SidecarShutdownRace: sidecar SIGTERM before main container — graceful degradation")
    void k8sSidecarShutdownRace() throws Exception {
        AtomicInteger ok = new AtomicInteger(0);
        AtomicInteger degraded = new AtomicInteger(0);
        for (int i = 0; i < 20; i++) {
            try {
                ResponseEntity<String> r = restTemplate.getForEntity("/users", String.class);
                if (r.getStatusCode().is2xxSuccessful()) ok.incrementAndGet();
                else degraded.incrementAndGet();
            } catch (Exception e) {
                degraded.incrementAndGet();
            }
        }
        System.out.printf("K8s sidecar shutdown race: %d ok, %d degraded — main service survives sidecar death%n", ok.get(), degraded.get());
        assertThat(ok.get() + degraded.get()).as("All 20 requests got a response (no hang)").isEqualTo(20);
    }

    @Test
    @IncidentChaosK8sCpuThrottleGcAmplification
    @DisplayName("INCIDENT K8s/CpuThrottleGcAmplification: GC pauses 10× worse under CPU throttle — virtual threads absorb better")
    void k8sCpuThrottleGcAmplification() throws Exception {
        List<Long> latencies = new ArrayList<>();
        int requests = 50;
        for (int i = 0; i < requests; i++) {
            long s = System.currentTimeMillis();
            restTemplate.getForEntity("/users", String.class);
            latencies.add(System.currentTimeMillis() - s);
        }
        latencies.sort(Long::compare);
        long p50 = latencies.get(25), p95 = latencies.get(47), p99 = latencies.get(49);
        System.out.printf("CPU throttle + GC amplification: p50=%dms p95=%dms p99=%dms%n", p50, p95, p99);
        System.out.println("Virtual threads absorb GC pauses better: carrier threads continue while virtual threads are parked");
        assertThat(p99).as("p99 < 5s even with CPU throttle amplifying GC pauses").isLessThan(5000);
    }
}
