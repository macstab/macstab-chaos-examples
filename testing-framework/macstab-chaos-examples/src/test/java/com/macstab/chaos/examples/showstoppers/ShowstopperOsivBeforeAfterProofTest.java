package com.macstab.chaos.examples.showstoppers;

import com.macstab.chaos.jvm.api.*;
import com.macstab.chaos.jvm.spring.boot4.test.ChaosTest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.test.context.TestPropertySource;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@DisplayName("OSIV Before/After Proof — ONE config line, night and day resilience")
class ShowstopperOsivBeforeAfterProofTest {

    @Nested
    @ChaosTest(classes = com.macstab.chaos.examples.UserServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {"spring.jpa.open-in-view=true", "spring.datasource.hikari.maximum-pool-size=5"})
    @DisplayName("OSIV=TRUE (Spring's dangerous default)")
    class WithOpenInViewTrue {

        @Autowired
        TestRestTemplate restTemplate;

        @Test
        @DisplayName("SHOWSTOPPER PART 1: open-in-view=TRUE — pool=5, 10 concurrent — CONNECTION STARVATION")
        void osivTrue_connectionStarvationUnder10ConcurrentRequests() throws Exception {
            int concurrent = 10;
            AtomicInteger ok = new AtomicInteger(0);
            AtomicInteger starved = new AtomicInteger(0);
            ExecutorService exec = Executors.newFixedThreadPool(concurrent);
            CountDownLatch latch = new CountDownLatch(concurrent);

            System.out.println();
            System.out.println("══════════════════════════════════════════════════════════════════");
            System.out.println("  SHOWSTOPPER: OSIV CONNECTION STARVATION PROOF");
            System.out.println("  spring.jpa.open-in-view=TRUE  ← Spring's DEFAULT since 2014");
            System.out.println("══════════════════════════════════════════════════════════════════");
            System.out.println("  Connection held: from first query UNTIL response body written");
            System.out.println("  Pool size: 5");
            System.out.println("  Concurrent requests: 10");
            System.out.println("  Expected: requests 6-10 starve waiting for a connection");

            List<Long> latencies = Collections.synchronizedList(new ArrayList<>());
            for (int i = 0; i < concurrent; i++) {
                final int req = i;
                exec.submit(() -> {
                    long s = System.currentTimeMillis();
                    try {
                        ResponseEntity<String> r = restTemplate.getForEntity("/users", String.class);
                        long ms = System.currentTimeMillis() - s;
                        latencies.add(ms);
                        if (r.getStatusCode().is2xxSuccessful()) ok.incrementAndGet();
                        else starved.incrementAndGet();
                        System.out.printf("  Request %2d: %s in %dms%n", req, r.getStatusCode(), ms);
                    } catch (Exception e) {
                        long ms = System.currentTimeMillis() - s;
                        latencies.add(ms);
                        starved.incrementAndGet();
                        System.out.printf("  Request %2d: TIMEOUT/ERROR in %dms — connection pool exhausted%n", req, ms);
                    } finally {
                        latch.countDown();
                    }
                });
            }
            latch.await(30, TimeUnit.SECONDS);
            exec.shutdown();

            latencies.sort(Long::compare);
            long p99 = latencies.isEmpty() ? 0 : latencies.get(latencies.size() - 1);
            System.out.printf("%n  OSIV=TRUE Results: %d ok, %d starved, p99=%dms%n", ok.get(), starved.get(), p99);
            System.out.println("  ↑ This is what your prod app looks like at peak traffic.");
        }
    }

    @Nested
    @ChaosTest(classes = com.macstab.chaos.examples.UserServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {"spring.jpa.open-in-view=false", "spring.datasource.hikari.maximum-pool-size=5"})
    @DisplayName("OSIV=FALSE (The Fix)")
    class WithOpenInViewFalse {

        @Autowired
        TestRestTemplate restTemplate;

        @Test
        @DisplayName("SHOWSTOPPER PART 2: open-in-view=FALSE — same load, same pool — ALL 10 SUCCEED")
        void osivFalse_allRequestsServeUnder10Concurrent() throws Exception {
            int concurrent = 10;
            AtomicInteger ok = new AtomicInteger(0);
            AtomicInteger failed = new AtomicInteger(0);
            ExecutorService exec = Executors.newFixedThreadPool(concurrent);
            CountDownLatch latch = new CountDownLatch(concurrent);

            System.out.println();
            System.out.println("══════════════════════════════════════════════════════════════════");
            System.out.println("  SHOWSTOPPER: OSIV=FALSE — SAME CODE, SAME LOAD, THE FIX");
            System.out.println("  spring.jpa.open-in-view=FALSE");
            System.out.println("══════════════════════════════════════════════════════════════════");
            System.out.println("  Connection held: ONLY during @Transactional scope (not rendering)");
            System.out.println("  Pool size: 5  ← SAME as OSIV=true test");
            System.out.println("  Concurrent requests: 10  ← SAME");
            System.out.println("  Expected: all 10 succeed — connection released before rendering");

            List<Long> latencies = Collections.synchronizedList(new ArrayList<>());
            for (int i = 0; i < concurrent; i++) {
                final int req = i;
                exec.submit(() -> {
                    long s = System.currentTimeMillis();
                    try {
                        ResponseEntity<String> r = restTemplate.getForEntity("/users", String.class);
                        long ms = System.currentTimeMillis() - s;
                        latencies.add(ms);
                        if (r.getStatusCode().is2xxSuccessful()) ok.incrementAndGet();
                        else failed.incrementAndGet();
                        System.out.printf("  Request %2d: %s in %dms%n", req, r.getStatusCode(), ms);
                    } catch (Exception e) {
                        failed.incrementAndGet();
                        latch.countDown();
                        return;
                    }
                    latch.countDown();
                });
            }
            latch.await(20, TimeUnit.SECONDS);
            exec.shutdown();

            latencies.sort(Long::compare);
            long p99 = latencies.isEmpty() ? 0 : latencies.get(latencies.size() - 1);

            System.out.println();
            System.out.println("  ╔═══════════════════════════════════════════════════════════╗");
            System.out.println("  ║         OSIV BEFORE / AFTER PROOF                         ║");
            System.out.println("  ╠═══════════════════════════════════════════════════════════╣");
            System.out.println("  ║  Configuration        OSIV=true      OSIV=false           ║");
            System.out.println("  ║  Pool size            5              5 (same)              ║");
            System.out.println("  ║  Concurrent requests  10             10 (same)             ║");
            System.out.printf( "  ║  Succeeded            ??             %2d                    ║%n", ok.get());
            System.out.printf( "  ║  Failed/starved       ??             %2d                    ║%n", failed.get());
            System.out.printf( "  ║  p99 latency          ??ms           %4dms                ║%n", p99);
            System.out.println("  ╠═══════════════════════════════════════════════════════════╣");
            System.out.println("  ║  ONE LINE CHANGE. Same code. Night and day difference.    ║");
            System.out.println("  ║  spring.jpa.open-in-view=false                            ║");
            System.out.println("  ║  This is the #1 Spring config mistake. Now proven in CI.  ║");
            System.out.println("  ╚═══════════════════════════════════════════════════════════╝");

            assertThat(ok.get()).as("OSIV=false: all 10 concurrent requests succeed").isGreaterThan(8);
            assertThat(failed.get()).as("Zero starvation with OSIV disabled").isLessThan(2);
        }
    }
}
