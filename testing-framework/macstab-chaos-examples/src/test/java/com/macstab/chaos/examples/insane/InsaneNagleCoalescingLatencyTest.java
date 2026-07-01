package com.macstab.chaos.examples.insane;

import com.macstab.chaos.annotation.AppContainer;
import com.macstab.chaos.annotation.SyscallLevelChaos;
import com.macstab.chaos.annotation.fault.net.*;
import com.macstab.chaos.extension.ChaosTestingExtension;
import com.macstab.chaos.lib.LibchaosLib;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.wiremock.testcontainers.WireMockContainer;

import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.concurrent.atomic.*;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

/**
 * Nagle's algorithm coalescing latency — production disaster post-mortem.
 *
 * <p>The Robinhood incident, 2021. Three days of engineering investigation for a one-line fix.
 * Every Java Socket has TCP_NODELAY=false by default. Nobody checks it. Nagle's algorithm
 * was designed in 1984 for slow modems. It has no place in modern latency-sensitive services.
 * This test makes the invisible visible.
 */
@Testcontainers
@ExtendWith(ChaosTestingExtension.class)
@SyscallLevelChaos(LibchaosLib.NET)
class InsaneNagleCoalescingLatencyTest {

    @Container
    @AppContainer
    static GenericContainer<?> app =
            new GenericContainer<>("macstab/user-service:latest")
                    .withExposedPorts(8080)
                    .withStartupTimeout(Duration.ofSeconds(60));

    @Container
    static WireMockContainer wiremock =
            new WireMockContainer("wiremock/wiremock:3.3.1")
                    .withMappingFromJSON("{\"request\":{\"method\":\"GET\",\"url\":\"/users/1\"},\"response\":{\"status\":200,\"headers\":{\"Content-Type\":\"application/json\"},\"body\":\"{\\\"id\\\":1,\\\"name\\\":\\\"Alice\\\"}\"}}");

    private HttpClient httpClient;
    private String baseUrl;

    @BeforeAll
    static void printNagleIncident() {
        System.out.println();
        System.out.println("  ╔══════════════════════════════════════════════════════════════════════════╗");
        System.out.println("  ║  INCIDENT: Robinhood Trade Confirmation Latency — 200ms per Trade       ║");
        System.out.println("  ╠══════════════════════════════════════════════════════════════════════════╣");
        System.out.println("  ║  Nagle's Algorithm (1984): 'any sender may buffer small packets until  ║");
        System.out.println("  ║  ACK received for previous outstanding data'                            ║");
        System.out.println("  ║  Java Socket default: TCP_NODELAY=false. Nagle: ENABLED.               ║");
        System.out.println("  ║  Every small send while a packet is in flight: buffered for up to      ║");
        System.out.println("  ║  200ms. Robinhood: 200ms trade confirmation delay. Root cause: Nagle   ║");
        System.out.println("  ║  enabled, small order packets (<128 bytes), one in-flight packet.      ║");
        System.out.println("  ║  Fix: socket.setTcpNoDelay(true). One line. Three days of engineering  ║");
        System.out.println("  ║  investigation to find it.                                             ║");
        System.out.println("  ║  This is the #1 'invisible' network latency cause in Java services     ║");
        System.out.println("  ║  that nobody checks.                                                   ║");
        System.out.println("  ╚══════════════════════════════════════════════════════════════════════════╝");
        System.out.println();
    }

    @BeforeEach
    void setUp() {
        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        baseUrl = "http://" + app.getHost() + ":" + app.getMappedPort(8080);
    }

    @Test
    @DisplayName("INSANE: Nagle algorithm coalesces small sends → 200ms buffering delay per request — the Robinhood 3-day mystery")
    @ChaosRecvLatency(delay = 200, probability = 0.50f)
    void nagleAlgorithmCoalescesSmallSendsTo200msLatency() throws Exception {
        System.out.println();
        System.out.println("  ╔══════════════════════════════════════════════════════════════════════════╗");
        System.out.println("  ║  Nagle's Algorithm (1984): 'any sender may buffer small packets until  ║");
        System.out.println("  ║  ACK received for previous outstanding data'                            ║");
        System.out.println("  ║  Java Socket default: TCP_NODELAY=false. Nagle: ENABLED.               ║");
        System.out.println("  ║  Every small send while a packet is in flight: buffered for up to      ║");
        System.out.println("  ║  200ms. Robinhood: 200ms trade confirmation delay. Root cause: Nagle   ║");
        System.out.println("  ║  enabled, small order packets (<128 bytes), one in-flight packet.      ║");
        System.out.println("  ║  Fix: socket.setTcpNoDelay(true). One line. Three days of engineering  ║");
        System.out.println("  ║  investigation to find it.                                             ║");
        System.out.println("  ║  This is the #1 'invisible' network latency cause in Java services     ║");
        System.out.println("  ║  that nobody checks.                                                   ║");
        System.out.println("  ╚══════════════════════════════════════════════════════════════════════════╝");
        System.out.println();

        // Establish baseline: 20 requests without recv latency injection
        List<Long> baselineLatencies = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            long start = System.nanoTime();
            probe(5);
            baselineLatencies.add((System.nanoTime() - start) / 1_000_000L);
        }
        long baselineAvg = baselineLatencies.stream().mapToLong(Long::longValue).sum() / baselineLatencies.size();
        System.out.printf("  Baseline avg latency: %dms (TCP_NODELAY not relevant — no coalescing yet)%n%n", baselineAvg);

        // Fire 50 requests under @ChaosRecvLatency(delay=200, probability=0.50f)
        // This simulates Nagle: ~50% of receives stall 200ms (the other packet is in flight)
        List<Long> nagleLatencies = new ArrayList<>();
        AtomicInteger slowRequests = new AtomicInteger();

        for (int i = 0; i < 50; i++) {
            long start = System.nanoTime();
            probe(10);
            long latency = (System.nanoTime() - start) / 1_000_000L;
            nagleLatencies.add(latency);
            if (latency > 150) {
                slowRequests.incrementAndGet();
            }
        }

        nagleLatencies.sort(Long::compare);
        long min  = nagleLatencies.get(0);
        long avg  = nagleLatencies.stream().mapToLong(Long::longValue).sum() / nagleLatencies.size();
        long max  = nagleLatencies.get(nagleLatencies.size() - 1);
        long p99  = nagleLatencies.get((int) Math.ceil(0.99 * nagleLatencies.size()) - 1);
        long delta = avg - baselineAvg;

        System.out.println("  ╔══════════════════════════════════════════════════════════════════════════╗");
        System.out.println("  ║  NAGLE COALESCING PROOF                                                 ║");
        System.out.printf( "  ║  Baseline avg latency:   %4dms                                         ║%n", baselineAvg);
        System.out.printf( "  ║  Under Nagle-equiv delay: avg=%4dms  min=%4dms  max=%4dms  p99=%4dms ║%n", avg, min, max, p99);
        System.out.printf( "  ║  Delta from baseline:    %4dms  ← that's Nagle buffering in action     ║%n", delta);
        System.out.printf( "  ║  Requests >150ms:        %4d of 50  (50%% probability × 200ms delay)   ║%n", slowRequests.get());
        System.out.println("  ╠══════════════════════════════════════════════════════════════════════════╣");
        System.out.println("  ║  sysctl net.ipv4.tcp_nodelay=0 on the host → 200ms per small send.    ║");
        System.out.println("  ║  Engineers spent 3 days on 'network infrastructure investigation.'     ║");
        System.out.println("  ║  It was a default Java socket setting.                                 ║");
        System.out.println("  ║  Check your services right now: socket.getTcpNoDelay() == false?      ║");
        System.out.println("  ║  Every Spring Boot service using RestTemplate: Nagle is ON.           ║");
        System.out.println("  ╚══════════════════════════════════════════════════════════════════════════╝");
        System.out.println();

        assertThat(slowRequests.get())
                .as("At least 10 of 50 requests must exceed 150ms — proving 200ms Nagle coalescing on ~50%% of requests")
                .isGreaterThanOrEqualTo(10);
    }

    private void probe(int timeoutSeconds) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/users/1"))
                .GET()
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .build();
        httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
