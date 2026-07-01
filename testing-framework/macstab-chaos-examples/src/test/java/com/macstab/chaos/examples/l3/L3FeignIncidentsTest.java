package com.macstab.chaos.examples.l3;

import com.macstab.chaos.core.annotation.SyscallLevelChaos;
import com.macstab.chaos.core.extension.ChaosTestingExtension;
import com.macstab.chaos.core.syscall.LibchaosLib;
import com.macstab.chaos.net.annotation.l3.IncidentChaosFeignRetryAmplification;
import com.macstab.chaos.net.annotation.l3.IncidentChaosFeignHystrixThreadLeak;
import com.macstab.chaos.net.annotation.l3.IncidentChaosFeignStaleLoadBalancer;
import com.macstab.chaos.net.annotation.l3.IncidentChaosOkHttpMetastablePool;
import com.github.tomakehurst.wiremock.WireMockServer;
import static com.github.tomakehurst.wiremock.client.WireMock.*;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import com.macstab.chaos.jvm.spring.boot4.test.ChaosTest;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;

@ChaosTest(classes = com.macstab.chaos.examples.UserServiceApplication.class, webEnvironment = org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT)
@SyscallLevelChaos(LibchaosLib.NET)
class L3FeignIncidentsTest {

    @Autowired
    private TestRestTemplate restTemplate;

    private static WireMockServer wireMock;
    private static AtomicLong downstreamCallCount;

    @BeforeAll
    static void startWireMock() {
        downstreamCallCount = new AtomicLong(0);
        wireMock = new WireMockServer(WireMockConfiguration.options().port(18081));
        wireMock.start();
    }

    @AfterAll
    static void stop() {
        if (wireMock != null) wireMock.stop();
    }

    @Test
    @IncidentChaosFeignRetryAmplification(toxicity = 0.5f)
    @DisplayName("INCIDENT Feign/RetryAmplification: 3×Feign × 3×R4J = 9× downstream calls without CB")
    void feignRetryAmplification() throws Exception {
        downstreamCallCount.set(0);
        wireMock.stubFor(get(urlMatching("/.*"))
                .willReturn(aResponse().withStatus(500).withBody("error")));

        int userRequests = 20;
        AtomicInteger userErrors = new AtomicInteger(0);
        for (int i = 0; i < userRequests; i++) {
            ResponseEntity<String> r = restTemplate.getForEntity("/users", String.class);
            if (!r.getStatusCode().is2xxSuccessful()) userErrors.incrementAndGet();
        }

        long downstream = wireMock.countRequestsMatching(getRequestedFor(urlMatching("/.*")).build()).getCount();
        System.out.printf("Feign amplification: %d user requests → %d downstream calls (%.1f× amplification)%n",
                userRequests, downstream, (double) downstream / userRequests);
        assertThat(downstream)
                .as("Circuit breaker limits downstream calls (not 20×9=180)")
                .isLessThan(100);
    }

    @Test
    @IncidentChaosFeignStaleLoadBalancer
    @DisplayName("INCIDENT Feign/StaleLoadBalancer: 30s LB cache — old pod IPs served ECONNREFUSED for 30s")
    void feignStaleLoadBalancer() throws Exception {
        wireMock.stubFor(get(urlMatching("/.*"))
                .willReturn(aResponse().withStatus(200).withBody("fresh-pod")));

        AtomicInteger staleErrors = new AtomicInteger(0);
        AtomicInteger freshSuccess = new AtomicInteger(0);
        long start = System.currentTimeMillis();

        for (int i = 0; i < 60; i++) {
            ResponseEntity<String> r = restTemplate.getForEntity("/users", String.class);
            if (r.getStatusCode().is5xxServerError()) staleErrors.incrementAndGet();
            else freshSuccess.incrementAndGet();
            Thread.sleep(100);
        }

        System.out.printf("Stale LB: %d stale errors, %d fresh success over 6s window%n",
                staleErrors.get(), freshSuccess.get());
        assertThat(freshSuccess.get())
                .as("Eventually resolves to fresh pod")
                .isGreaterThan(10);
    }

    @Test
    @IncidentChaosOkHttpMetastablePool
    @DisplayName("INCIDENT OkHttp/MetastablePool: slow pod attracts more load — positive feedback loop")
    void okHttpMetastablePool() throws Exception {
        wireMock.stubFor(get(urlPathEqualTo("/fast"))
                .willReturn(aResponse().withStatus(200).withFixedDelay(10).withBody("fast")));
        wireMock.stubFor(get(urlPathEqualTo("/slow"))
                .willReturn(aResponse().withStatus(200).withFixedDelay(200).withBody("slow")));

        long fastCalls = 0, slowCalls = 0;
        for (int i = 0; i < 50; i++) {
            restTemplate.getForEntity("/users", String.class);
        }

        fastCalls = wireMock.countRequestsMatching(getRequestedFor(urlPathEqualTo("/fast")).build()).getCount();
        slowCalls = wireMock.countRequestsMatching(getRequestedFor(urlPathEqualTo("/slow")).build()).getCount();
        System.out.printf("OkHttp metastable: fast=%d, slow=%d — ideally balanced 25/25%n", fastCalls, slowCalls);
    }
}
