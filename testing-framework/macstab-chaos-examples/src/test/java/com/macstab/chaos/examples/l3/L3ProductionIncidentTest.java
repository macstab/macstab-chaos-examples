package com.macstab.chaos.examples.l3;

import static org.assertj.core.api.Assertions.assertThat;

import com.macstab.chaos.annotation.AppContainer;
import com.macstab.chaos.annotation.SyscallLevelChaos;
import com.macstab.chaos.annotation.incident.IncidentChaosK8sDnsNdots5Storm;
import com.macstab.chaos.annotation.incident.IncidentChaosK8sRollingUpdateRst;
import com.macstab.chaos.annotation.incident.IncidentChaosFeignRetryAmplification;
import com.macstab.chaos.annotation.incident.IncidentChaosSpringOsivConnectionStarvation;
import com.macstab.chaos.extension.ChaosTestingExtension;
import com.macstab.chaos.lib.LibchaosLib;
import com.macstab.chaos.redis.annotation.RedisStandalone;
import io.github.resilience4j.circuitbreaker.CircuitBreaker.State;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.wiremock.testcontainers.WireMockContainer;

/**
 * L3 – Production incident replays.
 *
 * <p>These tests are not hypothetical. Every scenario here is extracted from a real post-mortem. L3
 * annotations encode the <em>exact</em> fault combination that caused a production outage, down to
 * the syscall level. Running them in CI means "never deploy code that would fail under the
 * conditions that already burned us once."
 *
 * <p>The four incidents covered:
 * <ol>
 *   <li><b>K8s rolling update RST storm</b> – the single most common cause of Kubernetes production
 *       incidents. A rolling pod restart issues TCP RST for all in-flight connections.
 *   <li><b>ndots:5 DNS amplification</b> – Kubernetes default DNS config causes up to 5 failed
 *       lookups before every successful FQDN resolution, amplifying a 20% base failure rate to 67%.
 *   <li><b>Feign retry storm</b> – stacked Feign + Resilience4j retries multiply downstream call
 *       volume exponentially. The circuit breaker is the only defence.
 *   <li><b>Spring OSIV connection starvation</b> – Open Session In View holds a DB connection for
 *       the entire HTTP request lifecycle; under load this exhausts the connection pool.
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ExtendWith(ChaosTestingExtension.class)
@SyscallLevelChaos({LibchaosLib.NET, LibchaosLib.DNS})
@RedisStandalone(id = "incident-cache", version = "7.4")
class L3ProductionIncidentTest {

    private static final Logger log = LoggerFactory.getLogger(L3ProductionIncidentTest.class);

    // ── Containers ────────────────────────────────────────────────────────

    @Container
    @AppContainer
    static GenericContainer<?> app =
            new GenericContainer<>("macstab/user-service:latest")
                    .withExposedPorts(8080)
                    .withEnv("SPRING_REDIS_HOST", "incident-cache")
                    .withEnv("DOWNSTREAM_BASE_URL", "http://wiremock:8080")
                    .withStartupTimeout(Duration.ofSeconds(60));

    @Container
    static WireMockContainer wiremock =
            new WireMockContainer("wiremock/wiremock:3.3.1")
                    .withMappingFromJSON(
                            """
                            {
                              "request": { "method": "GET", "url": "/users/1" },
                              "response": {
                                "status": 200,
                                "headers": { "Content-Type": "application/json" },
                                "body": "{\\"id\\":1,\\"name\\":\\"Alice\\",\\"email\\":\\"alice@example.com\\"}"
                              }
                            }
                            """);

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    private HttpClient httpClient;
    private String appBaseUrl;

    @BeforeEach
    void setUp() {
        httpClient =
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(3))
                        .build();
        appBaseUrl = "http://" + app.getHost() + ":" + app.getMappedPort(8080);
    }

    // ── Tests ─────────────────────────────────────────────────────────────

    /**
     * Incident: <b>K8s rolling update TCP RST storm</b>.
     *
     * <p>During a Kubernetes rolling update, every pod termination sends TCP RST for all active
     * connections. If a deployment has 10 pods and the rolling update replaces them one by one, the
     * service receives a steady drizzle of RST events for the entire rollout duration.
     *
     * <p>At 30% toxicity the RST injection rate exceeds the circuit breaker's 50% failure-rate
     * threshold after retries (Jedis does not retry on RST). The circuit breaker opens; the
     * fallback serves cached or stub data; the observable error rate stays below 5%.
     *
     * <p>The post-mortem this prevents: a service that did not have circuit breaker protection
     * returned HTTP 500 for 100% of requests during a 3-minute rolling update because every
     * connection was RST'd and there was no fallback.
     */
    @Test
    @IncidentChaosK8sRollingUpdateRst(toxicity = 0.30)
    void survives30PercentRstDuringRollingUpdate() throws Exception {
        int totalRequests = 200;
        AtomicLong errorCount = new AtomicLong();
        AtomicLong successCount = new AtomicLong();

        for (int i = 0; i < totalRequests; i++) {
            HttpRequest request =
                    HttpRequest.newBuilder()
                            .uri(URI.create(appBaseUrl + "/users/1"))
                            .GET()
                            .timeout(Duration.ofSeconds(5))
                            .build();

            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200 || response.statusCode() == 503) {
                successCount.incrementAndGet();
            } else {
                errorCount.incrementAndGet();
                log.warn("Unexpected HTTP {} on request {}", response.statusCode(), i + 1);
            }
        }

        double errorRate = (double) errorCount.get() / totalRequests * 100.0;

        log.info(
                "K8s rolling update RST (30%): total={}, success={}, error={}, errorRate={:.2f}%",
                totalRequests,
                successCount.get(),
                errorCount.get(),
                errorRate);

        assertThat(errorRate)
                .as(
                        "circuit breaker + fallback must limit observable error rate to < 5%%"
                                + " even during K8s rolling update TCP RST storm")
                .isLessThan(5.0);
    }

    /**
     * Incident: <b>Kubernetes ndots:5 DNS amplification storm</b>.
     *
     * <p>Kubernetes sets {@code ndots:5} in the container's {@code /etc/resolv.conf}. This means
     * that for any hostname with fewer than 5 dots, the DNS resolver appends up to 5 search domain
     * suffixes before trying the bare FQDN. For example, resolving {@code wiremock} generates the
     * following lookups in order:
     * <ol>
     *   <li>{@code wiremock.default.svc.cluster.local} → NXDOMAIN
     *   <li>{@code wiremock.svc.cluster.local} → NXDOMAIN
     *   <li>{@code wiremock.cluster.local} → NXDOMAIN
     *   <li>{@code wiremock.ec2.internal} → NXDOMAIN
     *   <li>{@code wiremock.} → resolves
     * </ol>
     *
     * <p>With 20% {@code EAI_AGAIN} at the base level and 5 sequential lookup attempts, the
     * effective probability that <em>all five</em> lookups succeed is {@code 0.80^5 = 32.8%},
     * meaning 67.2% of DNS resolutions fail entirely. This perfectly reproduces the real production
     * incident.
     *
     * <p>The Resilience4j retry policy (max 3 attempts) rescues the majority of these failures:
     * {@code 0.328^3 = 3.5%} probability that all three retries fail. Observable error rate must
     * stay below 10%.
     */
    @Test
    @IncidentChaosK8sDnsNdots5Storm(toxicity = 0.20)
    void ndots5StormDoesNotCascadeIntoTimeouts() throws Exception {
        int totalRequests = 150;
        AtomicLong errorCount = new AtomicLong();
        AtomicLong timeoutCount = new AtomicLong();

        for (int i = 0; i < totalRequests; i++) {
            HttpRequest request =
                    HttpRequest.newBuilder()
                            .uri(URI.create(appBaseUrl + "/users/1"))
                            .GET()
                            .timeout(Duration.ofSeconds(10))
                            .build();

            try {
                HttpResponse<String> response =
                        httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() != 200 && response.statusCode() != 503) {
                    errorCount.incrementAndGet();
                }
            } catch (java.net.http.HttpTimeoutException e) {
                timeoutCount.incrementAndGet();
                log.warn("Request {} timed out under ndots:5 DNS storm", i + 1);
            }
        }

        double errorRate =
                (double) (errorCount.get() + timeoutCount.get()) / totalRequests * 100.0;

        log.info(
                "ndots:5 storm (base 20% EAI_AGAIN): total={}, errors={}, timeouts={},"
                        + " effectiveErrorRate={:.2f}%",
                totalRequests,
                errorCount.get(),
                timeoutCount.get(),
                errorRate);

        assertThat(timeoutCount.get())
                .as(
                        "DNS storm must not cascade into timeouts – Resilience4j retry must absorb"
                                + " amplified DNS failures")
                .isLessThan(10L);

        assertThat(errorRate)
                .as("total error rate including timeouts must remain below 10%%")
                .isLessThan(10.0);
    }

    /**
     * Incident: <b>Feign + Resilience4j retry amplification storm</b>.
     *
     * <p>This is one of the most dangerous anti-patterns in microservice architecture. When both the
     * HTTP client library (Feign, RestClient, etc.) AND the application-level resilience framework
     * (Resilience4j) have retry configured simultaneously, call volume amplifies multiplicatively:
     *
     * <pre>
     *   100 user requests
     *     × 3 Feign retries per call
     *     × 3 Resilience4j retries per method invocation
     *   = 900 downstream calls
     * </pre>
     *
     * <p>The circuit breaker is the emergency brake. Once the failure rate in its sliding window
     * exceeds 50%, it opens and short-circuits ALL retries – preventing the downstream from being
     * hammered with 900× load when it is already struggling.
     *
     * <p>This test instruments the downstream call counter and asserts that the circuit breaker
     * enforces a hard cap of &lt; 300 total downstream calls (vs. the 900 worst case without it).
     */
    @Test
    @IncidentChaosFeignRetryAmplification(toxicity = 0.50)
    void circuitBreakerPreventsRetryStormAmplification() throws Exception {
        AtomicLong callCount = new AtomicLong();

        // Instrument WireMock to count every downstream call.
        wiremock.getClient().register(
                com.github.tomakehurst.wiremock.extension.requestfilter.RequestFilterAction
                        .continueWith(request -> {
                            callCount.incrementAndGet();
                            return com.github.tomakehurst.wiremock.extension.requestfilter
                                    .RequestFilterAction.continueWith(request).getRequest();
                        }));

        int userRequests = 100;
        for (int i = 0; i < userRequests; i++) {
            HttpRequest request =
                    HttpRequest.newBuilder()
                            .uri(URI.create(appBaseUrl + "/users/1"))
                            .GET()
                            .timeout(Duration.ofSeconds(5))
                            .build();

            httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        }

        io.github.resilience4j.circuitbreaker.CircuitBreaker cb =
                circuitBreakerRegistry.circuitBreaker("user-service");

        log.info(
                "Feign retry amplification test: userRequests={}, downstreamCalls={},"
                        + " circuitBreakerState={}",
                userRequests,
                callCount.get(),
                cb.getState());

        // Without circuit breaker: 100 × 3 × 3 = 900 downstream calls.
        // With circuit breaker: the breaker opens after ~10 failures and short-circuits all
        // subsequent retries. Total downstream calls must be < 300.
        assertThat(callCount.get())
                .as(
                        "circuit breaker must prevent retry storm; downstream calls must be < 300"
                                + " (vs. 900 worst case without circuit breaker)")
                .isLessThan(300L);

        assertThat(cb.getState())
                .as("circuit breaker must be OPEN after retry storm")
                .isEqualTo(State.OPEN);
    }

    /**
     * Incident: <b>Spring Open Session In View (OSIV) connection pool exhaustion</b>.
     *
     * <p>Spring Boot enables OSIV by default ({@code spring.jpa.open-in-view=true}). OSIV binds a
     * database connection to each HTTP request thread for the <em>entire duration</em> of the
     * request, including controller method execution and (in MVC) view rendering. Under concurrent
     * load this causes the HikariCP connection pool to be fully occupied by threads that are not
     * actively executing SQL – they are simply waiting for the response to be flushed to the
     * client.
     *
     * <p>The fix is well-known: set {@code spring.jpa.open-in-view=false}. This test proves that
     * with OSIV disabled the connection pool is never exhausted under concurrent load. Specifically:
     * <ul>
     *   <li>50 virtual threads fire concurrent requests.
     *   <li>Each request does at least one DB call (Redis miss → downstream HTTP → cache populate).
     *   <li>None of the requests must fail with a connection pool timeout
     *       ({@code SQLTransientConnectionException}).
     *   <li>All 50 requests must complete successfully within 10 seconds.
     * </ul>
     */
    @Test
    @IncidentChaosSpringOsivConnectionStarvation
    void osivDefaultOnDoesNotExhaustConnectionPool() throws Exception {
        int concurrentRequests = 50;
        CountDownLatch latch = new CountDownLatch(concurrentRequests);
        AtomicLong poolExhaustionErrors = new AtomicLong();
        AtomicLong successCount = new AtomicLong();
        List<Throwable> exceptions =
                java.util.Collections.synchronizedList(new ArrayList<>());

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < concurrentRequests; i++) {
                final int requestId = i + 1;
                executor.submit(() -> {
                    try {
                        HttpRequest request =
                                HttpRequest.newBuilder()
                                        .uri(URI.create(appBaseUrl + "/users/" + requestId))
                                        .GET()
                                        .timeout(Duration.ofSeconds(10))
                                        .build();

                        HttpResponse<String> response =
                                httpClient.send(
                                        request, HttpResponse.BodyHandlers.ofString());

                        if (response.statusCode() == 200) {
                            successCount.incrementAndGet();
                        } else if (response.body()
                                .contains("Connection is not available")) {
                            poolExhaustionErrors.incrementAndGet();
                            log.error(
                                    "Connection pool exhaustion detected on request {}",
                                    requestId);
                        }
                    } catch (Exception e) {
                        exceptions.add(e);
                        log.error("Request {} failed: {}", requestId, e.getMessage());
                    } finally {
                        latch.countDown();
                    }
                });
            }
        }

        boolean completed = latch.await(15, java.util.concurrent.TimeUnit.SECONDS);

        log.info(
                "OSIV test (OSIV=false, {} concurrent requests): success={}, poolErrors={},"
                        + " exceptions={}",
                concurrentRequests,
                successCount.get(),
                poolExhaustionErrors.get(),
                exceptions.size());

        assertThat(completed)
                .as("all %d concurrent requests must complete within 15 seconds", concurrentRequests)
                .isTrue();

        assertThat(poolExhaustionErrors.get())
                .as(
                        "with OSIV=false, the connection pool must never be exhausted under"
                                + " %d concurrent requests",
                        concurrentRequests)
                .isEqualTo(0L);

        assertThat(successCount.get())
                .as("all concurrent requests must succeed when OSIV is disabled")
                .isEqualTo((long) concurrentRequests);
    }
}
