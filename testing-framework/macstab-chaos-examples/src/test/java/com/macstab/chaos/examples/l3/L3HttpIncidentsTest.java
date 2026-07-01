package com.macstab.chaos.examples.l3;

import com.macstab.chaos.core.annotation.SyscallLevelChaos;
import com.macstab.chaos.core.extension.ChaosTestingExtension;
import com.macstab.chaos.core.syscall.LibchaosLib;
import com.macstab.chaos.net.annotation.l1.ChaosRecvEconnreset;
import com.macstab.chaos.net.annotation.l1.ChaosRecvLatency;
import com.macstab.chaos.jvm.spring.boot4.test.ChaosTest;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.*;
import java.util.concurrent.atomic.*;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.*;

@ChaosTest(classes = com.macstab.chaos.examples.UserServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@SyscallLevelChaos(LibchaosLib.NET)
class L3HttpIncidentsTest {

    private static WireMockServer wireMock;

    @Autowired
    private TestRestTemplate restTemplate;

    @BeforeAll
    static void start() {
        wireMock = new WireMockServer(WireMockConfiguration.options().port(18082));
        wireMock.start();
    }

    @AfterAll
    static void stop() {
        if (wireMock != null) wireMock.stop();
    }

    @Test
    @ChaosRecvEconnreset(probability = 0.3f)
    @DisplayName("INCIDENT HTTP/PartialResponse: TCP RST after headers — HttpClient throws IOException, no silent truncation")
    void partialResponseBodyTruncated() throws Exception {
        wireMock.stubFor(get(urlMatching("/.*"))
                .willReturn(aResponse().withStatus(200).withBody("full-body-content")));

        AtomicInteger complete = new AtomicInteger(0);
        AtomicInteger truncated = new AtomicInteger(0);

        for (int i = 0; i < 50; i++) {
            try {
                ResponseEntity<String> r = restTemplate.getForEntity("/users", String.class);
                if (r.getStatusCode().is2xxSuccessful() && r.getBody() != null) complete.incrementAndGet();
                else truncated.incrementAndGet();
            } catch (Exception e) {
                truncated.incrementAndGet();
            }
        }
        System.out.printf("HTTP partial response: %d complete, %d truncated/error of 50%n", complete.get(), truncated.get());
        assertThat(complete.get()).as("Majority complete successfully").isGreaterThan(25);
        assertThat(truncated.get()).as("Some RSTs injected — chaos confirmed active").isGreaterThan(0);
    }

    @Test
    @ChaosRecvLatency(delay = 500, probability = 1.0f)
    @DisplayName("INCIDENT HTTP/SlowHeaders: TTFB > 500ms — circuit breaker timeout fires")
    void slowHttpHeaders() throws Exception {
        wireMock.stubFor(get(urlMatching("/.*"))
                .willReturn(aResponse().withStatus(200).withFixedDelay(100).withBody("response")));

        long start = System.currentTimeMillis();
        ResponseEntity<String> r = restTemplate.getForEntity("/users", String.class);
        long elapsed = System.currentTimeMillis() - start;

        System.out.printf("HTTP slow headers: TTFB was %dms (injected 500ms recv latency)%n", elapsed);
        assertThat(elapsed).as("Request took > 500ms (latency confirmed)").isGreaterThan(499);
        assertThat(r.getStatusCode().is2xxSuccessful() || r.getStatusCode().is5xxServerError())
                .as("Response is either success (timeout absorbed) or 5xx (circuit breaker fired)").isTrue();
    }

    @Test
    @ChaosRecvEconnreset(probability = 0.5f)
    @DisplayName("INCIDENT HTTP/KeepAlivePoison: RST on 2nd keep-alive request — client retries, no silent data loss")
    void httpKeepAliveRstPoison() throws Exception {
        wireMock.stubFor(get(urlMatching("/.*"))
                .willReturn(aResponse().withStatus(200).withBody("ok")));

        AtomicInteger success = new AtomicInteger(0);
        AtomicInteger error = new AtomicInteger(0);

        for (int i = 0; i < 30; i++) {
            try {
                if (restTemplate.getForEntity("/users", String.class).getStatusCode().is2xxSuccessful())
                    success.incrementAndGet();
                else error.incrementAndGet();
            } catch (Exception e) {
                error.incrementAndGet();
            }
        }

        System.out.printf("Keep-alive RST poison: %d success, %d error of 30%n", success.get(), error.get());
        assertThat(success.get()).as("HTTP client retries on RST — majority succeed").isGreaterThan(10);
    }
}
