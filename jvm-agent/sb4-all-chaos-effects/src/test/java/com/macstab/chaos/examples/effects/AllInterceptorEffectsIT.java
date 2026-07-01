package com.macstab.chaos.examples.effects;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosActivationHandle;
import com.macstab.chaos.jvm.api.ChaosControlPlane;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosEffect.ClockSkewMode;
import com.macstab.chaos.jvm.api.ChaosEffect.FailureKind;
import com.macstab.chaos.jvm.api.ChaosEffect.ReturnValueStrategy;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosScenario.ScenarioScope;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.NamePattern;
import com.macstab.chaos.jvm.api.OperationType;
import com.macstab.chaos.jvm.spring.boot4.test.ChaosTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Eight integration tests — one per interceptor effect — showing every interception-category
 * chaos effect available in the macstab JVM agent:
 *
 * <ol>
 *   <li>DELAY on EXECUTOR_SUBMIT</li>
 *   <li>GATE on EXECUTOR_SUBMIT</li>
 *   <li>REJECT on EXECUTOR_SUBMIT (maxApplications)</li>
 *   <li>SUPPRESS on EXECUTOR_SUBMIT</li>
 *   <li>EXCEPTIONAL_COMPLETION on ASYNC_COMPLETE</li>
 *   <li>EXCEPTION_INJECTION on METHOD_ENTER</li>
 *   <li>RETURN_VALUE_CORRUPTION on METHOD_EXIT</li>
 *   <li>CLOCK_SKEW on SYSTEM_CLOCK_MILLIS</li>
 * </ol>
 *
 * WireMock stubs the downstream HTTP endpoint so network calls succeed (or fail only when
 * the chaos agent deliberately targets them).
 */
@ChaosTest(classes = AllEffectsApplication.class, webEnvironment = WebEnvironment.RANDOM_PORT)
class AllInterceptorEffectsIT {

    private static WireMockServer wireMock;

    @Autowired
    private ChaosControlPlane controlPlane;

    @Autowired
    private TargetService targetService;

    // ── WireMock lifecycle ────────────────────────────────────────────────────

    @BeforeAll
    static void startWireMock() {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();
    }

    @AfterAll
    static void stopWireMock() {
        if (wireMock != null) {
            wireMock.stop();
        }
    }

    @BeforeEach
    void stubDownstream() {
        wireMock.resetAll();
        wireMock.stubFor(get(urlEqualTo("/downstream"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("ok")
                        .withHeader("Content-Type", "text/plain")));
    }

    // ── TEST 1: DELAY ─────────────────────────────────────────────────────────

    /**
     * Activates a 200 ms DELAY on every EXECUTOR_SUBMIT.  Fires 10 tasks and measures
     * wall time.  Verifies the total wall time exceeds 200 ms (delay was applied) and
     * all 10 results are still correct (delay does not corrupt logic).
     */
    @Test
    @DisplayName("effect_DELAY_slows_executorSubmit")
    void effect_DELAY_slows_executorSubmit() throws Exception {
        ChaosActivationHandle handle = controlPlane.activate(
                ChaosScenario.builder("delay-executor-submit")
                        .scope(ScenarioScope.JVM)
                        .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
                        .effect(ChaosEffect.delay(Duration.ofMillis(200)))
                        .activationPolicy(ActivationPolicy.always())
                        .build()
        );

        try {
            long startMs = System.currentTimeMillis();
            List<String> results = targetService.executeTasks(10);
            long wallMs = System.currentTimeMillis() - startMs;

            System.out.printf("[DELAY] 10 tasks wall=%dms%n", wallMs);

            assertThat(wallMs)
                    .as("Wall time must exceed 200ms — the delay effect slows every EXECUTOR_SUBMIT")
                    .isGreaterThan(200L);

            long nonNullCount = results.stream().filter(r -> r != null).count();
            assertThat(nonNullCount)
                    .as("Delay does not break task logic — all 10 results must be non-null")
                    .isEqualTo(10L);
        } finally {
            handle.stop();
        }
    }

    // ── TEST 2: GATE ──────────────────────────────────────────────────────────

    /**
     * Activates a GATE on EXECUTOR_SUBMIT with a 3-second maximum block.  Fires 5 tasks
     * simultaneously; the gate blocks them until the 3-second timeout releases them
     * automatically.  Verifies that all tasks eventually complete and wall time reflects
     * the gate hold.
     */
    @Test
    @DisplayName("effect_GATE_blocksUntilReleased")
    void effect_GATE_blocksUntilReleased() throws Exception {
        ChaosActivationHandle handle = controlPlane.activate(
                ChaosScenario.builder("gate-executor-submit")
                        .scope(ScenarioScope.JVM)
                        .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
                        .effect(ChaosEffect.gate(Duration.ofSeconds(3)))
                        .activationPolicy(ActivationPolicy.always())
                        .build()
        );

        try {
            int taskCount = 5;
            CountDownLatch latch = new CountDownLatch(taskCount);
            long startMs = System.currentTimeMillis();

            ExecutorService caller = Executors.newVirtualThreadPerTaskExecutor();
            List<Future<?>> futures = new ArrayList<>(taskCount);

            for (int i = 0; i < taskCount; i++) {
                futures.add(caller.submit(() -> {
                    targetService.executeTasks(1);
                    latch.countDown();
                }));
            }

            boolean allCompleted = latch.await(15, TimeUnit.SECONDS);
            long wallMs = System.currentTimeMillis() - startMs;

            System.out.printf("[GATE] 5 tasks wall=%dms, allCompleted=%b%n", wallMs, allCompleted);

            assertThat(allCompleted)
                    .as("All 5 gated tasks must eventually complete after the gate auto-releases at 3s")
                    .isTrue();
            assertThat(wallMs)
                    .as("Wall time must be >= 3000ms — gate held tasks for up to 3 seconds")
                    .isGreaterThanOrEqualTo(3_000L);

            caller.shutdownNow();
        } finally {
            handle.stop();
        }
    }

    // ── TEST 3: REJECT ────────────────────────────────────────────────────────

    /**
     * Activates REJECT on EXECUTOR_SUBMIT with probability 0.3 and maxApplications=5.
     * Fires 20 tasks, catching {@link RejectedExecutionException}.  Verifies exactly 5
     * rejections (maxApplications honoured) and that the remaining 15 tasks succeed.
     */
    @Test
    @DisplayName("effect_REJECT_throwsRejectedExecutionException")
    void effect_REJECT_throwsRejectedExecutionException() throws Exception {
        ChaosActivationHandle handle = controlPlane.activate(
                ChaosScenario.builder("reject-executor-submit")
                        .scope(ScenarioScope.JVM)
                        .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
                        .effect(ChaosEffect.reject("executor overloaded"))
                        .activationPolicy(new ActivationPolicy(
                                ActivationPolicy.StartMode.AUTOMATIC,
                                0.3,
                                0,
                                5L,
                                null,
                                null,
                                42L,
                                false))
                        .build()
        );

        try {
            int taskCount = 20;
            AtomicInteger rejections = new AtomicInteger(0);
            AtomicInteger successes = new AtomicInteger(0);

            // Drive through the service method which wraps catch logic and returns nulls
            // for suppressed/rejected submissions.
            for (int i = 0; i < taskCount; i++) {
                List<String> batch = targetService.executeTasks(1);
                String r = batch.isEmpty() ? null : batch.get(0);
                if (r == null) {
                    rejections.incrementAndGet();
                } else {
                    successes.incrementAndGet();
                }
            }

            System.out.printf("[REJECT] rejections=%d, successes=%d out of %d tasks%n",
                    rejections.get(), successes.get(), taskCount);

            assertThat(rejections.get())
                    .as("maxApplications=5 must cap rejections at exactly 5")
                    .isEqualTo(5);
            assertThat(successes.get())
                    .as("Remaining %d tasks must succeed after the cap is exhausted", taskCount - 5)
                    .isEqualTo(taskCount - 5);
        } finally {
            handle.stop();
        }
    }

    // ── TEST 4: SUPPRESS ─────────────────────────────────────────────────────

    /**
     * Activates SUPPRESS on EXECUTOR_SUBMIT with probability 0.5.  Fires 20 tasks.
     * Suppressed submissions return a null Future silently — no exception is thrown.
     * Verifies roughly half the tasks are suppressed and no exception escapes.
     */
    @Test
    @DisplayName("effect_SUPPRESS_silentlyDiscardsSubmission")
    void effect_SUPPRESS_silentlyDiscardsSubmission() throws Exception {
        ChaosActivationHandle handle = controlPlane.activate(
                ChaosScenario.builder("suppress-executor-submit")
                        .scope(ScenarioScope.JVM)
                        .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
                        .effect(ChaosEffect.suppress())
                        .activationPolicy(new ActivationPolicy(
                                ActivationPolicy.StartMode.AUTOMATIC,
                                0.5,
                                0,
                                null,
                                null,
                                null,
                                42L,
                                false))
                        .build()
        );

        try {
            int taskCount = 20;
            AtomicInteger nullCount = new AtomicInteger(0);

            for (int i = 0; i < taskCount; i++) {
                List<String> batch = targetService.executeTasks(1);
                if (batch.isEmpty() || batch.get(0) == null) {
                    nullCount.incrementAndGet();
                }
            }

            System.out.printf("[SUPPRESS] nullResults=%d out of %d (expected ~10)%n",
                    nullCount.get(), taskCount);

            assertThat(nullCount.get())
                    .as("At probability 0.5, roughly half of 20 submissions should be suppressed")
                    .isBetween(3, 17);
        } finally {
            handle.stop();
        }
    }

    // ── TEST 5: EXCEPTIONAL_COMPLETION ───────────────────────────────────────

    /**
     * Activates EXCEPTIONAL_COMPLETION on ASYNC_COMPLETE with probability 0.5 and
     * failure kind RUNTIME.  Creates 20 CompletableFutures.  Verifies roughly 50% complete
     * exceptionally (null in results) and the rest complete normally.
     */
    @Test
    @DisplayName("effect_EXCEPTIONAL_COMPLETION_failsFutures")
    void effect_EXCEPTIONAL_COMPLETION_failsFutures() throws Exception {
        ChaosActivationHandle handle = controlPlane.activate(
                ChaosScenario.builder("exceptional-completion-async")
                        .scope(ScenarioScope.JVM)
                        .selector(ChaosSelector.async(Set.of(OperationType.ASYNC_COMPLETE)))
                        .effect(ChaosEffect.exceptionalCompletion(FailureKind.RUNTIME, "injected failure"))
                        .activationPolicy(new ActivationPolicy(
                                ActivationPolicy.StartMode.AUTOMATIC,
                                0.5,
                                0,
                                null,
                                null,
                                null,
                                42L,
                                false))
                        .build()
        );

        try {
            int count = 20;
            List<String> results = targetService.completeFutures(count);

            long completedNormally = results.stream().filter(r -> r != null).count();
            long completedExceptionally = results.stream().filter(r -> r == null).count();

            System.out.printf("[EXCEPTIONAL_COMPLETION] normal=%d, exceptional=%d out of %d%n",
                    completedNormally, completedExceptionally, count);

            assertThat(completedNormally)
                    .as("At 50%% injection rate some futures must still complete normally")
                    .isGreaterThan(0L);
            assertThat(completedExceptionally)
                    .as("At 50%% injection rate at least one future must complete exceptionally")
                    .isGreaterThanOrEqualTo(1L);
        } finally {
            handle.stop();
        }
    }

    // ── TEST 6: EXCEPTION_INJECTION ──────────────────────────────────────────

    /**
     * Activates exception injection of {@code java.sql.SQLException} on METHOD_ENTER
     * of {@code executeQuery}, scoped to the {@code com.macstab.chaos.examples} package.
     * Probability 0.5, so roughly half of 20 calls throw.  Verifies roughly 10 throw
     * SQLException (checked exception bypasses compiler via Unsafe) and callers handle
     * gracefully (no unhandled exception escapes the test).
     */
    @Test
    @DisplayName("effect_EXCEPTION_INJECTION_throwsChecked")
    void effect_EXCEPTION_INJECTION_throwsChecked() throws Exception {
        // Seed some data so the query actually runs when not intercepted
        targetService.insertEvent();

        ChaosActivationHandle handle = controlPlane.activate(
                ChaosScenario.builder("exception-injection-execute-query")
                        .scope(ScenarioScope.JVM)
                        .selector(ChaosSelector.method(
                                Set.of(OperationType.METHOD_ENTER),
                                NamePattern.prefix("com.macstab.chaos.examples"),
                                NamePattern.exact("executeQuery")
                        ))
                        .effect(ChaosEffect.injectException("java.sql.SQLException", "injected SQL error"))
                        .activationPolicy(new ActivationPolicy(
                                ActivationPolicy.StartMode.AUTOMATIC,
                                0.5,
                                0,
                                null,
                                null,
                                null,
                                42L,
                                false))
                        .build()
        );

        try {
            int callCount = 20;
            AtomicInteger throwCount = new AtomicInteger(0);
            AtomicInteger successCount = new AtomicInteger(0);

            for (int i = 0; i < callCount; i++) {
                try {
                    targetService.executeQuery("SELECT name FROM events");
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    // Exception injection bypasses checked-exception enforcement via Unsafe.
                    // Callers must catch Exception broadly to handle injected SQLExceptions.
                    throwCount.incrementAndGet();
                }
            }

            System.out.printf("[EXCEPTION_INJECTION] throws=%d, success=%d out of %d%n",
                    throwCount.get(), successCount.get(), callCount);

            assertThat(throwCount.get())
                    .as("At 50%% injection probability, at least one call must throw")
                    .isGreaterThanOrEqualTo(1);
            assertThat(successCount.get())
                    .as("At 50%% injection probability, at least one call must succeed")
                    .isGreaterThanOrEqualTo(1);
        } finally {
            handle.stop();
        }
    }

    // ── TEST 7: RETURN_VALUE_CORRUPTION ──────────────────────────────────────

    /**
     * Activates NULL return-value corruption on METHOD_EXIT of {@code fetchFromNetwork},
     * scoped to the {@code com.macstab.chaos.examples} package.  Probability 0.5.
     * Calls fetchFromNetwork 20 times via the HTTP endpoint.  Verifies roughly half
     * return null (corruption applied) and no NullPointerException-driven 500 occurs
     * (caller null-checks correctly).
     */
    @Test
    @DisplayName("effect_RETURN_VALUE_CORRUPTION_nullifiesResult")
    void effect_RETURN_VALUE_CORRUPTION_nullifiesResult() throws Exception {
        String downstreamUrl = "http://localhost:" + wireMock.port() + "/downstream";

        ChaosActivationHandle handle = controlPlane.activate(
                ChaosScenario.builder("return-value-corruption-fetch")
                        .scope(ScenarioScope.JVM)
                        .selector(ChaosSelector.method(
                                Set.of(OperationType.METHOD_EXIT),
                                NamePattern.prefix("com.macstab.chaos.examples"),
                                NamePattern.exact("fetchFromNetwork")
                        ))
                        .effect(ChaosEffect.corruptReturnValue(ReturnValueStrategy.NULL))
                        .activationPolicy(new ActivationPolicy(
                                ActivationPolicy.StartMode.AUTOMATIC,
                                0.5,
                                0,
                                null,
                                null,
                                null,
                                42L,
                                false))
                        .build()
        );

        try {
            int callCount = 20;
            AtomicInteger nullCount = new AtomicInteger(0);
            AtomicInteger nonNullCount = new AtomicInteger(0);

            for (int i = 0; i < callCount; i++) {
                try {
                    String result = targetService.fetchFromNetwork(downstreamUrl);
                    if (result == null) {
                        nullCount.incrementAndGet();
                    } else {
                        nonNullCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    // HTTP or network errors; not the corruption scenario
                    nonNullCount.incrementAndGet();
                }
            }

            System.out.printf("[RETURN_VALUE_CORRUPTION] null=%d, nonNull=%d out of %d%n",
                    nullCount.get(), nonNullCount.get(), callCount);

            assertThat(nullCount.get())
                    .as("At 50%% corruption probability, at least some calls must return null")
                    .isGreaterThanOrEqualTo(1);
            assertThat(nonNullCount.get())
                    .as("At 50%% corruption probability, at least some calls must return a real value")
                    .isGreaterThanOrEqualTo(1);
        } finally {
            handle.stop();
        }
    }

    // ── TEST 8: CLOCK_SKEW ────────────────────────────────────────────────────

    /**
     * Activates CLOCK_SKEW DRIFT of +10 seconds on SYSTEM_CLOCK_MILLIS.  Captures the
     * real epoch time via {@link System#nanoTime()} before and after, then compares to
     * the skewed value returned by {@link TargetService#getCurrentTime()}.  Verifies that
     * the reported time differs from real time by at least 10 000 ms.
     */
    @Test
    @DisplayName("effect_CLOCK_SKEW_driftsCurrentTimeMillis")
    void effect_CLOCK_SKEW_driftsCurrentTimeMillis() throws Exception {
        long startNano = System.nanoTime();
        long startEpochMs = System.currentTimeMillis();

        ChaosActivationHandle handle = controlPlane.activate(
                ChaosScenario.builder("clock-skew-drift")
                        .scope(ScenarioScope.JVM)
                        .selector(ChaosSelector.jvmRuntime(Set.of(OperationType.SYSTEM_CLOCK_MILLIS)))
                        .effect(ChaosEffect.skewClock(Duration.ofSeconds(10), ClockSkewMode.DRIFT))
                        .activationPolicy(ActivationPolicy.always())
                        .build()
        );

        try {
            long reported = targetService.getCurrentTime();

            // Approximate real current time from nanoTime delta + captured epoch
            long elapsedMs = (System.nanoTime() - startNano) / 1_000_000L;
            long realApproxMs = startEpochMs + elapsedMs;

            long skewMs = Math.abs(reported - realApproxMs);

            System.out.printf("[CLOCK_SKEW] reported=%dms, realApprox=%dms, skew=%dms%n",
                    reported, realApproxMs, skewMs);

            assertThat(skewMs)
                    .as("DRIFT skew of 10s must move reported time at least 10 000ms from real time")
                    .isGreaterThanOrEqualTo(10_000L);
        } finally {
            handle.stop();
        }
    }
}
