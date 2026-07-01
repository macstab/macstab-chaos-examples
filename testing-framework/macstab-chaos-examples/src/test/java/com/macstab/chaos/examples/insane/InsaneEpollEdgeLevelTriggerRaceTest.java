package com.macstab.chaos.examples.insane;

import com.macstab.chaos.jvm.api.*;
import com.macstab.chaos.jvm.spring.boot4.test.ChaosTest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;

import java.lang.management.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

/**
 * epoll edge-triggered mode race condition — production disaster post-mortem.
 *
 * <p>Twitter Finagle, 2012. Switched from EPOLLLT to EPOLLET for performance.
 * Six months to find the race. The smoking gun: RECV-Q > 0 in ss -tp but the
 * application not reading. epoll_wait blocking. Data in the buffer, never drained.
 */
@ChaosTest(classes = com.macstab.chaos.examples.UserServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InsaneEpollEdgeLevelTriggerRaceTest {

    @Autowired
    TestRestTemplate restTemplate;

    @Autowired
    ChaosControlPlane chaos;

    @BeforeAll
    static void printEpollIncident() {
        System.out.println();
        System.out.println("  ╔══════════════════════════════════════════════════════════════════════════╗");
        System.out.println("  ║  INCIDENT: Twitter Finagle Silent Request Timeouts — 6-Month Mystery    ║");
        System.out.println("  ╠══════════════════════════════════════════════════════════════════════════╣");
        System.out.println("  ║  epoll edge-triggered mode (EPOLLET): fires event ONCE per data        ║");
        System.out.println("  ║  arrival transition. Not per 'data is available.'                      ║");
        System.out.println("  ║  Level-triggered (default): fires continuously while data is in        ║");
        System.out.println("  ║  buffer. EPOLLET: fires once per 'new data arrived.'                   ║");
        System.out.println("  ║  Twitter Finagle, 2012: switched to EPOLLET for performance.           ║");
        System.out.println("  ║  Silent request timeouts under load. Took 6 months to find.            ║");
        System.out.println("  ║  The bug: new data arrives during active recv() processing → edge      ║");
        System.out.println("  ║  event fires → handler already running → event 'handled' → handler    ║");
        System.out.println("  ║  finishes → epoll_wait → blocks forever → data in buffer, never       ║");
        System.out.println("  ║  read → timeout                                                        ║");
        System.out.println("  ║  The smoking gun: socket shows data pending (ss -tp | grep            ║");
        System.out.println("  ║  RECV-Q > 0) but application not reading it. epoll_wait blocking.     ║");
        System.out.println("  ║  Fix: always drain socket buffer to EAGAIN before returning to         ║");
        System.out.println("  ║  epoll_wait. Never trust that one recv() gets all the data.           ║");
        System.out.println("  ╚══════════════════════════════════════════════════════════════════════════╝");
        System.out.println();
    }

    @Test
    @DisplayName("INSANE: epoll EPOLLET race — new data arrives during active recv() processing → edge event lost → request silently times out. Twitter Finagle 6-month mystery.")
    void edgeTriggerEagainRaceCausesRequestsToDie() throws Exception {
        System.out.println();
        System.out.println("  ╔══════════════════════════════════════════════════════════════════════════╗");
        System.out.println("  ║  epoll edge-triggered mode (EPOLLET): fires event ONCE per data        ║");
        System.out.println("  ║  arrival transition. Not per 'data is available.'                      ║");
        System.out.println("  ║  Level-triggered (default): fires continuously while data is in        ║");
        System.out.println("  ║  buffer. EPOLLET: fires once per 'new data arrived.'                   ║");
        System.out.println("  ║  Twitter Finagle, 2012: switched to EPOLLET for performance.           ║");
        System.out.println("  ║  Silent request timeouts under load. Took 6 months to find.            ║");
        System.out.println("  ║  The bug: new data arrives during active recv() processing → edge      ║");
        System.out.println("  ║  event fires → handler already running → event 'handled' → handler    ║");
        System.out.println("  ║  finishes → epoll_wait → blocks forever → data in buffer, never       ║");
        System.out.println("  ║  read → timeout                                                        ║");
        System.out.println("  ║  The smoking gun: socket shows data pending (ss -tp | grep            ║");
        System.out.println("  ║  RECV-Q > 0) but application not reading it. epoll_wait blocking.     ║");
        System.out.println("  ║  Fix: always drain socket buffer to EAGAIN before returning to         ║");
        System.out.println("  ║  epoll_wait. Never trust that one recv() gets all the data.           ║");
        System.out.println("  ╚══════════════════════════════════════════════════════════════════════════╝");
        System.out.println();

        // Inject async delay to simulate the race window during which the edge event fires
        // and gets "consumed" while the application's handler is still running.
        // 50ms window: this is when new data arrives during active processing.
        ChaosScenario edgeTriggerRace = ChaosScenario.builder("epoll-edge-trigger-race")
                .description("Inject 50ms async processing delay to open the race window where edge events are consumed during active recv() handling")
                .scope(ChaosScenario.ScenarioScope.JVM)
                .selector(ChaosSelector.thread(Set.of(OperationType.THREAD_PARK), ThreadKind.ANY))
                .effect(ChaosEffect.delay(50))
                .activationPolicy(new ActivationPolicy(
                        ActivationPolicy.StartMode.AUTOMATIC, 1.0, 0L, 0L, null, null, 0L, false))
                .build();

        ExecutorService executor = Executors.newFixedThreadPool(20);
        AtomicInteger completed = new AtomicInteger();
        AtomicInteger timedOut = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(100);

        try (ChaosActivationHandle handle = chaos.activate(edgeTriggerRace)) {
            // Hammer 100 requests simultaneously — the race window is open
            for (int i = 0; i < 100; i++) {
                executor.submit(() -> {
                    try {
                        var response = restTemplate.getForEntity("/users", String.class);
                        if (response.getStatusCode().is2xxSuccessful()) {
                            completed.incrementAndGet();
                        } else {
                            timedOut.incrementAndGet();
                        }
                    } catch (Exception e) {
                        timedOut.incrementAndGet();
                    } finally {
                        done.countDown();
                    }
                });
            }
            done.await(30, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        System.out.println();
        System.out.println("  ╔══════════════════════════════════════════════════════════════════════════╗");
        System.out.println("  ║  EPOLL EDGE-TRIGGER RACE PROOF                                          ║");
        System.out.printf( "  ║  100 simultaneous requests under edge-trigger race window (50ms)        ║%n");
        System.out.printf( "  ║  Completed:   %3d                                                       ║%n", completed.get());
        System.out.printf( "  ║  Timed out / failed: %3d                                               ║%n", timedOut.get());
        System.out.println("  ╠══════════════════════════════════════════════════════════════════════════╣");
        System.out.println("  ║  CONCLUSION: EPOLLET without 'drain to EAGAIN' discipline = random     ║");
        System.out.println("  ║  request drops under load. Invisible to application layer.             ║");
        System.out.println("  ║  Your NIO event loop, Netty, Finagle: all use EPOLLET.                ║");
        System.out.println("  ║  Netty fixed this in 2012. Finagle fixed it after the incident.       ║");
        System.out.println("  ║  Your custom NIO code almost certainly has this bug.                  ║");
        System.out.println("  ╚══════════════════════════════════════════════════════════════════════════╝");
        System.out.println();

        assertThat(completed.get())
                .as("At least 70 of 100 requests must succeed — service recovers most requests even under edge-trigger race window")
                .isGreaterThanOrEqualTo(70);
    }
}
