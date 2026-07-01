package com.macstab.chaos.examples.patterns;

import static org.assertj.core.api.Assertions.assertThat;

import com.macstab.chaos.annotation.AppContainer;
import com.macstab.chaos.annotation.SyscallLevelChaos;
import com.macstab.chaos.extension.ChaosTestingExtension;
import com.macstab.chaos.lib.LibchaosLib;
import com.macstab.chaos.patterns.ChaosController;
import com.macstab.chaos.patterns.GaussianNoisePattern;
import com.macstab.chaos.patterns.RampPattern;
import com.macstab.chaos.patterns.WavePattern;
import com.macstab.chaos.redis.annotation.RedisStandalone;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.wiremock.testcontainers.WireMockContainer;

/**
 * Temporal chaos patterns: fault rates that change over time.
 *
 * <p>Static fault annotations ({@code @ChaosRecvEconnreset(probability = 0.30)}) inject a constant
 * fault rate for the duration of the test. Real production incidents rarely work that way:
 * <ul>
 *   <li>Network degradation ramps up gradually (congestion builds).
 *   <li>Flapping networks oscillate between healthy and degraded.
 *   <li>Thunderstorm events cause brief, intense spikes followed by recovery.
 *   <li>Background noise is always present, usually at a low, variable level.
 * </ul>
 *
 * <p>The {@code macstab-chaos-patterns} library provides time-varying fault rate controllers that
 * drive the libchaos injection probability dynamically. Each pattern is injected via
 * {@link ChaosController} – the extension calls {@link ChaosController#tick()} at the configured
 * {@code stepInterval} and updates the active fault probability accordingly.
 */
@Testcontainers
@ExtendWith(ChaosTestingExtension.class)
@SyscallLevelChaos(LibchaosLib.NET)
@RedisStandalone(id = "pattern-cache", version = "7.4")
class TemporalPatternTest {

    private static final Logger log = LoggerFactory.getLogger(TemporalPatternTest.class);

    // ── Containers ────────────────────────────────────────────────────────

    @Container
    @AppContainer
    static GenericContainer<?> app =
            new GenericContainer<>("macstab/user-service:latest")
                    .withExposedPorts(8080)
                    .withEnv("SPRING_REDIS_HOST", "pattern-cache")
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
     * Linearly ramps fault rate from 0% to 100% over 60 seconds in 500 ms steps, detecting the
     * exact fault percentage at which the circuit breaker opens.
     *
     * <p>This is a <em>fault threshold discovery test</em>. Instead of asserting "the circuit
     * breaker opens at 50%", it actively measures the empirical opening threshold for this specific
     * combination of Resilience4j config + application code + Redis timeout settings.
     *
     * <p>The ramping is implemented via {@link RampPattern} which linearly interpolates from
     * {@code from} to {@code to} over {@code durationSeconds}, updating the libchaos injection
     * probability at {@code stepMs} intervals.
     *
     * <p>The measured threshold is expected to be between 30% and 70%:
     * <ul>
     *   <li>Below 30% the retry policy absorbs all failures before they reach the circuit
     *       breaker's sliding window.
     *   <li>Above 70% the circuit breaker would already be open before the ramp reaches that level.
     * </ul>
     */
    @Test
    void rampingFaultRateFindsCircuitBreakerThreshold(ChaosController chaos) throws Exception {
        RampPattern ramp =
                RampPattern.builder()
                        .from(0.0)
                        .to(1.0)
                        .durationSeconds(60)
                        .stepMs(500)
                        .build();

        chaos.applyPattern(ramp);

        Instant testStart = Instant.now();
        double openedAtFaultRate = -1.0;

        // Fire requests continuously and detect when the circuit breaker opens.
        while (Duration.between(testStart, Instant.now()).getSeconds() < 65) {
            double currentFaultRate = ramp.currentProbability();

            HttpRequest request =
                    HttpRequest.newBuilder()
                            .uri(URI.create(appBaseUrl + "/users/1"))
                            .GET()
                            .timeout(Duration.ofSeconds(3))
                            .build();

            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            // Detect circuit breaker open state.
            if (openedAtFaultRate < 0 && response.statusCode() == 200
                    && response.body().contains("\"source\":\"FALLBACK\"")) {
                openedAtFaultRate = currentFaultRate;
                log.info(
                        "Circuit breaker opened at fault rate: {:.2f}%% ({} seconds elapsed)",
                        openedAtFaultRate * 100,
                        Duration.between(testStart, Instant.now()).getSeconds());
            }

            Thread.sleep(200);
        }

        log.info(
                "Ramp pattern test complete. Circuit breaker opened at: {:.1f}%% fault rate",
                openedAtFaultRate * 100);

        assertThat(openedAtFaultRate)
                .as(
                        "circuit breaker must open at some point during the ramp;"
                                + " -1.0 means it never opened")
                .isGreaterThan(0.0);

        assertThat(openedAtFaultRate)
                .as("empirical circuit breaker threshold must be between 30%% and 70%%")
                .isBetween(0.30, 0.70);
    }

    /**
     * Oscillates fault rate between 0% and 30% in a sine wave pattern to simulate a flapping
     * network, verifying that the circuit breaker both opens AND closes in sync with the wave.
     *
     * <p>A flapping network is one of the hardest fault modes to handle: the circuit breaker
     * opens when the fault rate spikes, but it must also close again when the network recovers so
     * that the service doesn't stay degraded indefinitely.
     *
     * <p>The {@link WavePattern} drives the injection probability as:
     * <pre>
     *   P(t) = amplitude × (1 + sin(2π × t / period)) / 2
     * </pre>
     * resulting in a range of [0, amplitude] oscillating with the given period.
     *
     * <p>Assertions:
     * <ul>
     *   <li>During high-fault phases (fault ≈ 30%) fallbacks must be observed.
     *   <li>During low-fault phases (fault ≈ 0%) zero fallbacks must be observed.
     *   <li>This proves the circuit breaker both opens and closes in response to the wave.
     * </ul>
     */
    @Test
    void wavePatternSimulatesFlappingNetwork(ChaosController chaos) throws Exception {
        WavePattern wave =
                WavePattern.builder()
                        .amplitude(0.30)
                        .periodSeconds(10)
                        .cycles(3)
                        .build();

        chaos.applyPattern(wave);

        // Phase tracking: sample every 500ms over 3 full cycles (30s total).
        List<PhaseObservation> observations = new ArrayList<>();
        Instant testStart = Instant.now();

        while (Duration.between(testStart, Instant.now()).getSeconds() < 32) {
            double currentFault = wave.currentProbability();
            boolean highFaultPhase = currentFault > 0.15;

            HttpRequest request =
                    HttpRequest.newBuilder()
                            .uri(URI.create(appBaseUrl + "/users/1"))
                            .GET()
                            .timeout(Duration.ofSeconds(3))
                            .build();

            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            boolean isFallback =
                    response.statusCode() == 200
                            && response.body().contains("\"source\":\"FALLBACK\"");

            observations.add(new PhaseObservation(highFaultPhase, isFallback));
            Thread.sleep(500);
        }

        long highFaultObservations = observations.stream().filter(o -> o.highFaultPhase).count();
        long lowFaultObservations = observations.stream().filter(o -> !o.highFaultPhase).count();
        long fallbacksDuringHighFault =
                observations.stream()
                        .filter(o -> o.highFaultPhase && o.wasFallback)
                        .count();
        long fallbacksDuringLowFault =
                observations.stream()
                        .filter(o -> !o.highFaultPhase && o.wasFallback)
                        .count();

        log.info(
                "Wave pattern test: highFaultObs={}, lowFaultObs={},"
                        + " fallbacksHighFault={}, fallbacksLowFault={}",
                highFaultObservations,
                lowFaultObservations,
                fallbacksDuringHighFault,
                fallbacksDuringLowFault);

        // During high-fault phases the circuit breaker should open → fallbacks observed.
        assertThat(fallbacksDuringHighFault)
                .as(
                        "fallbacks must be observed during high-fault wave phases"
                                + " (circuit breaker opens when fault > 30%%)")
                .isGreaterThan(0L);

        // During low-fault phases the circuit breaker should close → no fallbacks.
        assertThat(fallbacksDuringLowFault)
                .as(
                        "NO fallbacks must be observed during low-fault wave phases"
                                + " (circuit breaker closes when fault drops to 0%%)")
                .isEqualTo(0L);
    }

    /**
     * Triggers an instantaneous 100% fault spike lasting 5 seconds, then verifies that the circuit
     * breaker recovers after the spike ends.
     *
     * <p>This models a brief but total network outage – for example, a network switch reboot or a
     * BGP route flap. The service must:
     * <ol>
     *   <li>Open the circuit breaker during the spike.
     *   <li>After the spike ends, wait for the configured {@code wait-duration-in-open-state} (10s).
     *   <li>Transition to HALF_OPEN and allow probe calls.
     *   <li>Close the circuit if probe calls succeed.
     * </ol>
     */
    @Test
    void burstSpikeAndRecovery(ChaosController chaos) throws Exception {
        AtomicLong fallbacksDuringSpike = new AtomicLong();
        AtomicLong successesAfterRecovery = new AtomicLong();
        AtomicLong fallbacksAfterRecovery = new AtomicLong();

        // ── Phase 1: baseline (no chaos) ──────────────────────────────────
        for (int i = 0; i < 5; i++) {
            sendRequest(appBaseUrl + "/users/1", 3);
        }

        // ── Phase 2: 100% fault spike for 5 seconds ───────────────────────
        chaos.setFaultProbability(1.0);
        Instant spikeStart = Instant.now();

        while (Duration.between(spikeStart, Instant.now()).getSeconds() < 5) {
            HttpResponse<String> response = sendRequest(appBaseUrl + "/users/1", 3);
            if (response != null
                    && response.statusCode() == 200
                    && response.body().contains("\"source\":\"FALLBACK\"")) {
                fallbacksDuringSpike.incrementAndGet();
            }
            Thread.sleep(200);
        }

        // ── Phase 3: fault cleared ─────────────────────────────────────────
        chaos.setFaultProbability(0.0);
        log.info(
                "Spike ended. Fallbacks during spike: {}. Waiting for circuit breaker to"
                        + " recover (10s wait-duration)...",
                fallbacksDuringSpike.get());

        // Wait for circuit breaker wait-duration (10s) + some buffer.
        Thread.sleep(15_000);

        // ── Phase 4: verify recovery ───────────────────────────────────────
        for (int i = 0; i < 20; i++) {
            HttpResponse<String> response = sendRequest(appBaseUrl + "/users/1", 3);
            if (response != null && response.statusCode() == 200) {
                if (response.body().contains("\"source\":\"FALLBACK\"")) {
                    fallbacksAfterRecovery.incrementAndGet();
                } else {
                    successesAfterRecovery.incrementAndGet();
                }
            }
            Thread.sleep(100);
        }

        log.info(
                "Recovery phase: successes={}, fallbacks={}",
                successesAfterRecovery.get(),
                fallbacksAfterRecovery.get());

        // The spike must have caused circuit breaker to open.
        assertThat(fallbacksDuringSpike.get())
                .as("circuit breaker must have opened during the 100%% fault spike")
                .isGreaterThan(0L);

        // After recovery, the circuit breaker must close.
        assertThat(successesAfterRecovery.get())
                .as(
                        "after 15s (wait-duration + buffer), circuit breaker must have closed"
                                + " and returned to serving real data")
                .isGreaterThan(0L);

        assertThat(fallbacksAfterRecovery.get())
                .as(
                        "no fallbacks expected after circuit breaker closes and fault is"
                                + " cleared")
                .isEqualTo(0L);
    }

    /**
     * Applies Gaussian noise to the fault rate (mean=5%, σ=2%) for 30 seconds and verifies the
     * service's steady-state resilience to low-level ambient noise.
     *
     * <p>This models the background error rate every internet-facing service experiences: dropped
     * packets, transient DNS hiccups, brief TCP resets. The Resilience4j retry policy should
     * absorb this entirely, keeping the observable error rate below 15%.
     *
     * <p>The key insight: at a 5% mean fault rate with 3 retry attempts, the probability that all
     * three retries fail is {@code 0.05^3 = 0.0125%}. The Gaussian noise occasionally spikes to
     * 11% (mean + 3σ), but even then {@code 0.11^3 = 0.13%} – well within the 15% error budget.
     */
    @Test
    void gaussianNoiseSimulatesRealWorldJitter(ChaosController chaos) throws Exception {
        GaussianNoisePattern noise =
                GaussianNoisePattern.builder()
                        .mean(0.05)
                        .stdDev(0.02)
                        .minProbability(0.0)
                        .maxProbability(0.15)
                        .build();

        chaos.applyPattern(noise);

        int totalRequests = 0;
        AtomicLong errorCount = new AtomicLong();
        Instant testStart = Instant.now();

        while (Duration.between(testStart, Instant.now()).getSeconds() < 30) {
            HttpResponse<String> response = sendRequest(appBaseUrl + "/users/1", 3);
            totalRequests++;

            if (response == null || (response.statusCode() != 200 && response.statusCode() != 503)) {
                errorCount.incrementAndGet();
            }

            Thread.sleep(150);
        }

        double errorRate = (double) errorCount.get() / totalRequests * 100.0;

        log.info(
                "Gaussian noise (mean=5%%, σ=2%%): total={}, errors={}, errorRate={:.2f}%%"
                        + " over 30s",
                totalRequests,
                errorCount.get(),
                errorRate);

        assertThat(errorRate)
                .as(
                        "with 5%% mean fault rate and Resilience4j retry (3 attempts),"
                                + " observable error rate must remain below 15%%")
                .isLessThan(15.0);
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private HttpResponse<String> sendRequest(String url, int timeoutSeconds) {
        try {
            HttpRequest request =
                    HttpRequest.newBuilder()
                            .uri(URI.create(url))
                            .GET()
                            .timeout(Duration.ofSeconds(timeoutSeconds))
                            .build();
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            log.debug("Request to {} failed: {}", url, e.getMessage());
            return null;
        }
    }

    /** Snapshot of a single observation period. */
    private record PhaseObservation(boolean highFaultPhase, boolean wasFallback) {}
}
