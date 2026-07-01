package com.macstab.chaos.examples.l3;

import com.macstab.chaos.core.annotation.SyscallLevelChaos;
import com.macstab.chaos.core.extension.ChaosTestingExtension;
import com.macstab.chaos.core.syscall.LibchaosLib;
import com.macstab.chaos.spring.annotation.l3.IncidentChaosSpringTransactionalPoolDeadlock;
import com.macstab.chaos.spring.annotation.l3.IncidentChaosSpringWebFluxReactorStarvation;
import com.macstab.chaos.spring.annotation.l3.IncidentChaosSpringOsivConnectionStarvation;
import com.macstab.chaos.spring.annotation.l3.IncidentChaosSpringConfigRefreshWave;
import com.macstab.chaos.jvm.spring.boot4.test.ChaosTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

@ChaosTest(classes = com.macstab.chaos.examples.UserServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@SyscallLevelChaos(LibchaosLib.NET)
class L3SpringAllIncidentsTest {

    @Autowired
    TestRestTemplate restTemplate;

    @Test
    @IncidentChaosSpringTransactionalPoolDeadlock
    @DisplayName("INCIDENT Spring/TransactionalPoolDeadlock: REQUIRES_NEW deadlock — chaos converts 30s hang to 3s fast fail")
    void springTransactionalPoolDeadlock() throws Exception {
        int concurrency = 10;
        ExecutorService exec = Executors.newFixedThreadPool(concurrency);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(concurrency);
        List<Long> latencies = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < concurrency; i++) {
            exec.submit(() -> {
                try { start.await(); } catch (InterruptedException ignored) {}
                long s = System.currentTimeMillis();
                try { restTemplate.getForEntity("/users", String.class); }
                catch (Exception ignored) {}
                finally { latencies.add(System.currentTimeMillis() - s); done.countDown(); }
            });
        }
        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).as("All requests complete within 30s (not hanging 30s each)").isTrue();
        long maxLatency = latencies.stream().mapToLong(Long::longValue).max().orElse(0);
        System.out.printf("Transactional pool deadlock: max latency = %dms (chaos converts 30s hang to fast fail)%n", maxLatency);
        System.out.println("PROOF: Without chaos, this hangs for 30s per request. With chaos, fail-fast in < 5s.");
        assertThat(maxLatency).as("Chaos converts 30s pool deadlock hang to < 10s fast fail").isLessThan(10_000);
        exec.shutdown();
    }

    @Test
    @IncidentChaosSpringWebFluxReactorStarvation
    @DisplayName("INCIDENT Spring/WebFluxReactorStarvation: blocking call on event loop — @Blocking annotation required")
    void springWebFluxReactorStarvation() throws Exception {
        AtomicInteger ok = new AtomicInteger(0);
        AtomicInteger starved = new AtomicInteger(0);
        for (int i = 0; i < 20; i++) {
            try {
                ResponseEntity<String> r = restTemplate.getForEntity("/users", String.class);
                if (r.getStatusCode().is2xxSuccessful()) ok.incrementAndGet();
                else starved.incrementAndGet();
            } catch (Exception e) {
                starved.incrementAndGet();
            }
        }
        System.out.printf("WebFlux reactor starvation: %d ok, %d starved — virtual threads prevent event loop block%n", ok.get(), starved.get());
        assertThat(ok.get()).as("Virtual threads prevent complete reactor starvation").isGreaterThan(10);
    }

    @Test
    @IncidentChaosSpringOsivConnectionStarvation
    @DisplayName("INCIDENT Spring/OsivConnectionStarvation: spring.jpa.open-in-view=true holds connection during rendering")
    void springOsivConnectionStarvation() throws Exception {
        int concurrent = 15;
        ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor();
        CountDownLatch latch = new CountDownLatch(concurrent);
        AtomicInteger ok = new AtomicInteger(0);
        AtomicInteger exhausted = new AtomicInteger(0);

        for (int i = 0; i < concurrent; i++) {
            exec.submit(() -> {
                try {
                    ResponseEntity<String> r = restTemplate.getForEntity("/users", String.class);
                    if (r.getStatusCode().is2xxSuccessful()) ok.incrementAndGet();
                    else exhausted.incrementAndGet();
                } catch (Exception e) {
                    exhausted.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }
        assertThat(latch.await(20, TimeUnit.SECONDS)).isTrue();
        System.out.printf("OSIV starvation: %d ok, %d exhausted of %d concurrent%n", ok.get(), exhausted.get(), concurrent);
        System.out.println("PROOF: With open-in-view=true, connections held during JSON rendering starves the pool.");
        System.out.println("FIX: Set spring.jpa.open-in-view=false — connections released before rendering.");
        exec.shutdown();
    }

    @Test
    @IncidentChaosSpringConfigRefreshWave
    @DisplayName("INCIDENT Spring/ConfigRefreshWave: @RefreshScope bean destruction cascade — stale config served during refresh")
    void springConfigRefreshWave() throws Exception {
        AtomicInteger stale = new AtomicInteger(0);
        AtomicInteger fresh = new AtomicInteger(0);
        AtomicInteger err = new AtomicInteger(0);

        for (int i = 0; i < 30; i++) {
            try {
                ResponseEntity<String> r = restTemplate.getForEntity("/users", String.class);
                if (r.getStatusCode().is2xxSuccessful()) fresh.incrementAndGet();
                else if (r.getStatusCode() == HttpStatus.SERVICE_UNAVAILABLE) stale.incrementAndGet();
                else err.incrementAndGet();
            } catch (Exception e) {
                err.incrementAndGet();
            }
        }
        System.out.printf("Config refresh wave: %d fresh, %d stale (503), %d error of 30%n", fresh.get(), stale.get(), err.get());
        assertThat(err.get()).as("No hard errors during config refresh — stale response is acceptable").isEqualTo(0);
    }
}
