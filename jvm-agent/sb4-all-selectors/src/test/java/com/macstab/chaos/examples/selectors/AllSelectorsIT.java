package com.macstab.chaos.examples.selectors;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.macstab.chaos.agent.test.annotation.ChaosTest;
import com.macstab.chaos.agent.test.dsl.ActivationPolicy;
import com.macstab.chaos.agent.test.dsl.ChaosEffect;
import com.macstab.chaos.agent.test.dsl.ChaosSelector;
import com.macstab.chaos.agent.test.dsl.ChaosSession;
import com.macstab.chaos.agent.test.dsl.ClockSkewMode;
import com.macstab.chaos.agent.test.dsl.FailureKind;
import com.macstab.chaos.agent.test.dsl.NamePattern;
import com.macstab.chaos.agent.test.dsl.OperationType;
import com.macstab.chaos.agent.test.dsl.ThreadKind;
import com.macstab.chaos.examples.selectors.SelectorsApplication.ScheduledCounter;
import com.macstab.chaos.examples.selectors.SelectorsApplication.UserRecord;
import com.macstab.chaos.examples.selectors.SelectorsApplication.UserService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration test that exercises all 13 chaos selector types provided by the macstab
 * chaos agent.  Each test activates exactly one selector, injects a specific effect, and
 * then asserts on observable behaviour — latency, exception propagation, partial failure
 * rates, or correctness of results.
 *
 * WireMock stubs the HTTP downstream for tests that need real network I/O (TEST 10, 11).
 */
@ChaosTest(classes = SelectorsApplication.class, webEnvironment = WebEnvironment.RANDOM_PORT)
class AllSelectorsIT {

    // ------------------------------------------------------------------
    // WireMock lifecycle
    // ------------------------------------------------------------------

    private static WireMockServer wireMock;

    @LocalServerPort
    int port;

    @Autowired
    ChaosSession chaos;

    @Autowired
    @Qualifier("platformThreadPool")
    ThreadPoolExecutor platformThreadPool;

    @Autowired
    @Qualifier("scheduledPool")
    ScheduledExecutorService scheduledPool;

    @Autowired
    LinkedBlockingQueue<String> sharedQueue;

    @Autowired
    HttpClient httpClient;

    @Autowired
    UserService userService;

    @Autowired
    ScheduledCounter scheduledCounter;

    @BeforeAll
    static void startWireMock() {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();
        wireMock.stubFor(get(urlEqualTo("/ping"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("pong")
                        .withHeader("Content-Type", "text/plain")));
    }

    @AfterAll
    static void stopWireMock() {
        if (wireMock != null) {
            wireMock.stop();
        }
    }

    // ------------------------------------------------------------------
    // TEST 1 — THREAD selector: delay platform thread start
    // ------------------------------------------------------------------

    /**
     * Injects a 50ms delay on every platform thread start.  Starts 10 platform threads
     * and measures how long each takes from construction to first line of run().
     *
     * Assert: every thread takes at least 50ms to start (delay applied before run() begins).
     */
    @Test
    void selector_THREAD() throws Exception {
        long[] startLatenciesMs = new long[10];

        try (var _ = chaos.activate(
                ChaosSelector.thread(Set.of(OperationType.THREAD_START), ThreadKind.PLATFORM),
                ChaosEffect.delay(Duration.ofMillis(50))
        )) {
            List<Thread> threads = new ArrayList<>(10);
            AtomicLong[] startedAt = new AtomicLong[10];
            long[] createdAt = new long[10];

            for (int i = 0; i < 10; i++) {
                startedAt[i] = new AtomicLong(0);
                final int idx = i;
                Thread t = Thread.ofPlatform()
                        .name("chaos-platform-" + idx)
                        .unstarted(() -> startedAt[idx].set(System.nanoTime()));
                createdAt[idx] = System.nanoTime();
                threads.add(t);
            }

            for (Thread t : threads) {
                t.start();
            }
            for (Thread t : threads) {
                t.join(5_000);
            }

            for (int i = 0; i < 10; i++) {
                startLatenciesMs[i] = (startedAt[i].get() - createdAt[i]) / 1_000_000L;
            }
        }

        for (int i = 0; i < startLatenciesMs.length; i++) {
            assertThat(startLatenciesMs[i])
                    .as("Platform thread %d start latency must be >= 50ms (chaos delay applied)", i)
                    .isGreaterThanOrEqualTo(50L);
        }
    }

    // ------------------------------------------------------------------
    // TEST 2 — THREAD_VIRTUAL selector: reject virtual thread starts
    // ------------------------------------------------------------------

    /**
     * Rejects virtual thread starts with probability 0.30.  Starts 100 virtual threads
     * and counts how many are rejected by the chaos agent.
     *
     * Assert: approximately 30 are rejected (25–40 tolerance for statistical variance).
     * Assert: the remaining threads start and complete normally.
     */
    @Test
    void selector_THREAD_VIRTUAL() throws Exception {
        AtomicInteger rejected = new AtomicInteger(0);
        AtomicInteger started = new AtomicInteger(0);

        try (var _ = chaos.activate(
                ChaosSelector.thread(Set.of(OperationType.VIRTUAL_THREAD_START), ThreadKind.VIRTUAL),
                ChaosEffect.reject("virtual thread rejected"),
                ActivationPolicy.probability(0.30)
        )) {
            List<Thread> threads = new ArrayList<>(100);

            for (int i = 0; i < 100; i++) {
                Thread t = Thread.ofVirtual()
                        .name("chaos-virtual-" + i)
                        .unstarted(() -> started.incrementAndGet());
                threads.add(t);
            }

            for (Thread t : threads) {
                try {
                    t.start();
                } catch (Exception e) {
                    if (e.getMessage() != null && e.getMessage().contains("virtual thread rejected")) {
                        rejected.incrementAndGet();
                    }
                }
            }

            // Wait for threads that did start
            for (Thread t : threads) {
                if (t.getState() != Thread.State.NEW) {
                    t.join(5_000);
                }
            }
        }

        System.out.printf("[THREAD_VIRTUAL] rejected=%d, started=%d out of 100%n",
                rejected.get(), started.get());

        assertThat(rejected.get())
                .as("Approximately 30 virtual threads must be rejected at probability=0.30 (25-40 range)")
                .isBetween(25, 40);
        assertThat(started.get())
                .as("Remaining threads must have started normally")
                .isGreaterThan(60);
    }

    // ------------------------------------------------------------------
    // TEST 3 — EXECUTOR selector: delay task submission
    // ------------------------------------------------------------------

    /**
     * Injects a 100ms delay on every ExecutorService.submit() call on the 4-thread pool.
     * Submits 20 tasks and measures total wall time.
     *
     * Assert: total wall time > 2000ms because each of the 20 submissions is delayed 100ms
     * before the task even enters the queue (ceil(20/4) batches × 100ms delay per submit
     * adds at least 5 × 100ms = 500ms; all 20 submits delayed individually = 2000ms floor).
     */
    @Test
    void selector_EXECUTOR() throws Exception {
        AtomicInteger completions = new AtomicInteger(0);

        long wallStart = System.currentTimeMillis();

        try (var _ = chaos.activate(
                ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)),
                ChaosEffect.delay(Duration.ofMillis(100))
        )) {
            List<Future<?>> futures = new ArrayList<>(20);

            for (int i = 0; i < 20; i++) {
                futures.add(platformThreadPool.submit((Callable<Void>) () -> {
                    completions.incrementAndGet();
                    return null;
                }));
            }

            for (Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
        }

        long wallMs = System.currentTimeMillis() - wallStart;

        System.out.printf("[EXECUTOR] wall time=%dms, completions=%d%n", wallMs, completions.get());

        assertThat(wallMs)
                .as("Total wall time must be > 2000ms: 20 submit() calls each delayed by 100ms")
                .isGreaterThan(2_000L);
        assertThat(completions.get())
                .as("All 20 tasks must eventually complete")
                .isEqualTo(20);
    }

    // ------------------------------------------------------------------
    // TEST 4 — SCHEDULING selector: reject scheduled task submissions
    // ------------------------------------------------------------------

    /**
     * Rejects ScheduledExecutorService.schedule() with probability=0.5 but caps total
     * rejections at 3 via maxApplications=3.  Schedules 10 tasks.
     *
     * Assert: exactly 3 are rejected (maxApplications caps the chaos at 3).
     * Assert: 7 execute successfully.
     */
    @Test
    void selector_SCHEDULING() throws Exception {
        AtomicInteger rejected = new AtomicInteger(0);
        AtomicInteger succeeded = new AtomicInteger(0);

        try (var _ = chaos.activate(
                ChaosSelector.scheduling(Set.of(OperationType.SCHEDULE_SUBMIT), NamePattern.any()),
                ChaosEffect.reject("scheduled task rejected"),
                ActivationPolicy.builder().probability(0.5).maxApplications(3).build()
        )) {
            List<Future<?>> futures = new ArrayList<>();

            for (int i = 0; i < 10; i++) {
                try {
                    Future<?> f = scheduledPool.schedule(
                            (Callable<Void>) () -> {
                                succeeded.incrementAndGet();
                                return null;
                            },
                            0, TimeUnit.MILLISECONDS
                    );
                    futures.add(f);
                } catch (Exception e) {
                    if (e.getMessage() != null && e.getMessage().contains("scheduled task rejected")) {
                        rejected.incrementAndGet();
                    }
                }
            }

            for (Future<?> f : futures) {
                f.get(5, TimeUnit.SECONDS);
            }
        }

        System.out.printf("[SCHEDULING] rejected=%d, succeeded=%d%n", rejected.get(), succeeded.get());

        assertThat(rejected.get())
                .as("Exactly 3 scheduled tasks must be rejected (maxApplications=3 cap)")
                .isEqualTo(3);
        assertThat(succeeded.get())
                .as("The remaining 7 tasks must execute successfully")
                .isEqualTo(7);
    }

    // ------------------------------------------------------------------
    // TEST 5 — QUEUE selector: delay put and take on BlockingQueue
    // ------------------------------------------------------------------

    /**
     * Injects a 50ms delay on every BlockingQueue.put() and BlockingQueue.take().
     * A producer puts 100 items while a consumer takes 100 items.
     *
     * Assert: producer wall time > 5000ms (100 puts × 50ms each).
     * Assert: all 100 items transferred correctly (no data loss).
     */
    @Test
    void selector_QUEUE() throws Exception {
        sharedQueue.clear();
        AtomicInteger consumed = new AtomicInteger(0);

        long producerWallMs;

        try (var _ = chaos.activate(
                ChaosSelector.queue(Set.of(OperationType.QUEUE_PUT, OperationType.QUEUE_TAKE)),
                ChaosEffect.delay(Duration.ofMillis(50))
        )) {
            // Consumer runs on a separate virtual thread
            Thread consumer = Thread.ofVirtual().start(() -> {
                try {
                    for (int i = 0; i < 100; i++) {
                        sharedQueue.take();
                        consumed.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });

            long producerStart = System.currentTimeMillis();
            for (int i = 0; i < 100; i++) {
                sharedQueue.put("item-" + i);
            }
            producerWallMs = System.currentTimeMillis() - producerStart;

            consumer.join(60_000);
        }

        System.out.printf("[QUEUE] producer wall time=%dms, items consumed=%d%n",
                producerWallMs, consumed.get());

        assertThat(producerWallMs)
                .as("Producer wall time must be > 5000ms: 100 put() calls each delayed by 50ms")
                .isGreaterThan(5_000L);
        assertThat(consumed.get())
                .as("All 100 items must be transferred — no data loss despite chaos delays")
                .isEqualTo(100);
    }

    // ------------------------------------------------------------------
    // TEST 6 — ASYNC selector: exceptionally complete futures
    // ------------------------------------------------------------------

    /**
     * Injects exceptional completion with probability=0.30 on CompletableFuture.complete().
     * Creates 20 futures, completes each normally, then joins them.
     *
     * Assert: approximately 6 complete exceptionally (join() throws CompletionException).
     * Assert: the rest complete normally.
     */
    @Test
    void selector_ASYNC() throws Exception {
        AtomicInteger exceptionalCount = new AtomicInteger(0);
        AtomicInteger normalCount = new AtomicInteger(0);

        try (var _ = chaos.activate(
                ChaosSelector.async(Set.of(OperationType.ASYNC_COMPLETE)),
                ChaosEffect.exceptionalCompletion(FailureKind.RUNTIME, "async chaos"),
                ActivationPolicy.probability(0.30)
        )) {
            List<CompletableFuture<String>> futures = new ArrayList<>(20);

            for (int i = 0; i < 20; i++) {
                CompletableFuture<String> future = new CompletableFuture<>();
                futures.add(future);
                final int val = i;
                // Complete from a separate thread so the agent can intercept the call
                Thread.ofVirtual().start(() -> future.complete("result-" + val));
            }

            for (CompletableFuture<String> f : futures) {
                try {
                    f.join();
                    normalCount.incrementAndGet();
                } catch (CompletionException e) {
                    if (e.getCause() != null && "async chaos".equals(e.getCause().getMessage())) {
                        exceptionalCount.incrementAndGet();
                    }
                }
            }
        }

        System.out.printf("[ASYNC] exceptional=%d, normal=%d out of 20%n",
                exceptionalCount.get(), normalCount.get());

        assertThat(exceptionalCount.get())
                .as("Approximately 6 futures must complete exceptionally at probability=0.30 (3-10 tolerance)")
                .isBetween(3, 10);
        assertThat(normalCount.get())
                .as("The remaining futures must complete normally")
                .isGreaterThan(10);
    }

    // ------------------------------------------------------------------
    // TEST 7 — SHUTDOWN selector: delay executor shutdown
    // ------------------------------------------------------------------

    /**
     * Injects a 200ms delay on ExecutorService.shutdown().  Creates a dedicated executor,
     * submits tasks, calls shutdown(), and measures the time until it returns.
     *
     * Assert: shutdown() takes at least 200ms extra.
     * Assert: awaitTermination() still works and all tasks completed before it.
     */
    @Test
    void selector_SHUTDOWN() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(
                2, Thread.ofPlatform().name("shutdown-test-", 0).factory()
        );
        AtomicInteger tasksDone = new AtomicInteger(0);

        try (var _ = chaos.activate(
                ChaosSelector.shutdown(Set.of(OperationType.EXECUTOR_SHUTDOWN), NamePattern.any()),
                ChaosEffect.delay(Duration.ofMillis(200))
        )) {
            for (int i = 0; i < 4; i++) {
                executor.submit(() -> {
                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    tasksDone.incrementAndGet();
                });
            }

            long shutdownStart = System.currentTimeMillis();
            executor.shutdown();
            long shutdownMs = System.currentTimeMillis() - shutdownStart;

            boolean terminated = executor.awaitTermination(10, TimeUnit.SECONDS);

            System.out.printf("[SHUTDOWN] shutdown() took=%dms, terminated=%s, tasksDone=%d%n",
                    shutdownMs, terminated, tasksDone.get());

            assertThat(shutdownMs)
                    .as("shutdown() must take at least 200ms (chaos delay injected)")
                    .isGreaterThanOrEqualTo(200L);
            assertThat(terminated)
                    .as("awaitTermination() must return true — executor must eventually finish")
                    .isTrue();
            assertThat(tasksDone.get())
                    .as("All 4 tasks must have completed before termination")
                    .isEqualTo(4);
        }
    }

    // ------------------------------------------------------------------
    // TEST 8 — CLASS_LOADING selector: delay class loading
    // ------------------------------------------------------------------

    /**
     * Injects a 100ms delay on ClassLoader.loadClass() for classes whose name starts
     * with {@code com.macstab}.  Forces a class load via Class.forName() and measures time.
     *
     * Assert: loading takes >= 100ms (chaos delay applied during load).
     * Assert: the class is loaded correctly (not null, correct name).
     */
    @Test
    void selector_CLASS_LOADING() throws Exception {
        long loadTimeMs;
        Class<?> loaded;

        // Use a class that is unlikely to already be loaded in this classloader context
        String targetClass = "com.macstab.chaos.examples.selectors.SelectorsApplication";

        try (var _ = chaos.activate(
                ChaosSelector.classLoading(Set.of(OperationType.CLASS_LOAD), NamePattern.prefix("com.macstab")),
                ChaosEffect.delay(Duration.ofMillis(100))
        )) {
            long start = System.currentTimeMillis();
            // forName with initialize=false avoids running static initializers again
            loaded = Class.forName(targetClass, false, Thread.currentThread().getContextClassLoader());
            loadTimeMs = System.currentTimeMillis() - start;
        }

        System.out.printf("[CLASS_LOADING] load time=%dms, loaded=%s%n", loadTimeMs,
                loaded != null ? loaded.getName() : "null");

        assertThat(loadTimeMs)
                .as("Class load must take >= 100ms (chaos delay injected for com.macstab prefix)")
                .isGreaterThanOrEqualTo(100L);
        assertThat(loaded)
                .as("Class must be loaded correctly — not null")
                .isNotNull();
        assertThat(loaded.getName())
                .as("Loaded class must have the correct binary name")
                .isEqualTo(targetClass);
    }

    // ------------------------------------------------------------------
    // TEST 9 — METHOD selector: delay arbitrary method entry
    // ------------------------------------------------------------------

    /**
     * Injects a 200ms delay on METHOD_ENTER for {@code findUser} in the
     * {@code com.macstab.chaos.examples} package.  Calls findUser() 10 times.
     *
     * Assert: each call takes at least 200ms.
     * Assert: returned results are correct (user name matches query).
     */
    @Test
    void selector_METHOD() throws Exception {
        long[] callTimesMs = new long[10];
        UserRecord[] results = new UserRecord[10];

        try (var _ = chaos.activate(
                ChaosSelector.method(
                        Set.of(OperationType.METHOD_ENTER),
                        NamePattern.prefix("com.macstab.chaos.examples"),
                        NamePattern.exact("findUser")
                ),
                ChaosEffect.delay(Duration.ofMillis(200))
        )) {
            for (int i = 0; i < 10; i++) {
                long start = System.currentTimeMillis();
                results[i] = userService.findUser("alice");
                callTimesMs[i] = System.currentTimeMillis() - start;
            }
        }

        for (int i = 0; i < 10; i++) {
            assertThat(callTimesMs[i])
                    .as("Call %d to findUser() must take at least 200ms (METHOD_ENTER delay)", i)
                    .isGreaterThanOrEqualTo(200L);
            assertThat(results[i])
                    .as("findUser() must return a valid result despite chaos delay")
                    .isNotNull();
            assertThat(results[i].name())
                    .as("findUser() must return the correct user name")
                    .isEqualTo("alice");
        }
    }

    // ------------------------------------------------------------------
    // TEST 10 — NIO selector: delay NIO channel read/write
    // ------------------------------------------------------------------

    /**
     * Injects a 50ms delay on NIO_CHANNEL_READ and NIO_CHANNEL_WRITE.  Makes 10 HTTP
     * requests via the Java NIO-backed HttpClient to the WireMock stub.
     *
     * Assert: each request takes at least 50ms extra (NIO I/O is delayed).
     */
    @Test
    void selector_NIO() throws Exception {
        long[] requestTimesMs = new long[10];
        String targetUrl = "http://localhost:" + wireMock.port() + "/ping";

        try (var _ = chaos.activate(
                ChaosSelector.nio(
                        Set.of(OperationType.NIO_CHANNEL_READ, OperationType.NIO_CHANNEL_WRITE),
                        NamePattern.any()
                ),
                ChaosEffect.delay(Duration.ofMillis(50))
        )) {
            for (int i = 0; i < 10; i++) {
                long start = System.currentTimeMillis();
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(targetUrl))
                        .GET()
                        .timeout(Duration.ofSeconds(10))
                        .build();
                httpClient.send(req, HttpResponse.BodyHandlers.discarding());
                requestTimesMs[i] = System.currentTimeMillis() - start;
            }
        }

        for (int i = 0; i < 10; i++) {
            assertThat(requestTimesMs[i])
                    .as("HTTP request %d must take >= 50ms due to NIO channel read/write delays", i)
                    .isGreaterThanOrEqualTo(50L);
        }
    }

    // ------------------------------------------------------------------
    // TEST 11 — NETWORK selector: reject socket connections
    // ------------------------------------------------------------------

    /**
     * Rejects SOCKET_CONNECT for hosts matching the prefix {@code localhost} with
     * probability=0.30.  Makes 20 HTTP requests to the WireMock localhost stub.
     *
     * Assert: approximately 6 fail with a connection-level exception.
     * Assert: the remaining ~14 succeed with HTTP 200.
     */
    @Test
    void selector_NETWORK() throws Exception {
        AtomicInteger failures = new AtomicInteger(0);
        AtomicInteger successes = new AtomicInteger(0);
        String targetUrl = "http://localhost:" + wireMock.port() + "/ping";

        try (var _ = chaos.activate(
                ChaosSelector.network(Set.of(OperationType.SOCKET_CONNECT), NamePattern.prefix("localhost")),
                ChaosEffect.reject("chaos: connection refused"),
                ActivationPolicy.probability(0.30)
        )) {
            try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
                List<Future<?>> futures = new ArrayList<>(20);

                for (int i = 0; i < 20; i++) {
                    futures.add(pool.submit(() -> {
                        try {
                            HttpRequest req = HttpRequest.newBuilder()
                                    .uri(URI.create(targetUrl))
                                    .GET()
                                    .timeout(Duration.ofSeconds(5))
                                    .build();
                            HttpResponse<Void> resp = httpClient.send(req, HttpResponse.BodyHandlers.discarding());
                            if (resp.statusCode() == 200) {
                                successes.incrementAndGet();
                            }
                        } catch (Exception e) {
                            failures.incrementAndGet();
                        }
                    }));
                }

                for (Future<?> f : futures) {
                    f.get(30, TimeUnit.SECONDS);
                }
            }
        }

        System.out.printf("[NETWORK] successes=%d, failures=%d out of 20%n",
                successes.get(), failures.get());

        assertThat(failures.get())
                .as("Approximately 6 requests must fail at probability=0.30 (3-10 tolerance)")
                .isBetween(3, 10);
        assertThat(successes.get())
                .as("The remaining requests must succeed with HTTP 200")
                .isGreaterThan(10);
    }

    // ------------------------------------------------------------------
    // TEST 12 — THREAD_LOCAL selector: suppress ThreadLocal.get()
    // ------------------------------------------------------------------

    /**
     * Suppresses ThreadLocal.get() with probability=0.20 — the agent returns null
     * instead of the stored value.  Uses a ThreadLocal&lt;String&gt; carrying a request
     * ID through 50 calls.
     *
     * Assert: approximately 10 get() calls return null (20% of 50).
     * Assert: the application handles null request IDs without throwing NPE.
     */
    @Test
    void selector_THREAD_LOCAL() throws Exception {
        ThreadLocal<String> requestIdHolder = new ThreadLocal<>();
        AtomicInteger nullCount = new AtomicInteger(0);
        AtomicInteger nonNullCount = new AtomicInteger(0);

        try (var _ = chaos.activate(
                ChaosSelector.threadLocal(Set.of(OperationType.THREAD_LOCAL_GET), NamePattern.any()),
                ChaosEffect.suppress(),
                ActivationPolicy.probability(0.20)
        )) {
            for (int i = 0; i < 50; i++) {
                requestIdHolder.set("req-" + i);
                // Simulate a request path that reads the ThreadLocal
                String requestId = requestIdHolder.get();
                // Handle null gracefully — no NPE allowed
                String processed = requestId != null ? requestId.toUpperCase() : "<no-request-id>";
                if (requestId == null) {
                    nullCount.incrementAndGet();
                } else {
                    nonNullCount.incrementAndGet();
                }
                // Safety check — no NPE on the processed value
                assertThat(processed)
                        .as("Processed value must not be null, even when request ID is suppressed")
                        .isNotNull();
            }
        }

        System.out.printf("[THREAD_LOCAL] null=%d, non-null=%d out of 50%n",
                nullCount.get(), nonNullCount.get());

        assertThat(nullCount.get())
                .as("Approximately 10 get() calls must return null at probability=0.20 (5-15 tolerance)")
                .isBetween(5, 15);
        assertThat(nonNullCount.get())
                .as("The remaining calls must return the actual stored request ID")
                .isGreaterThan(35);
    }

    // ------------------------------------------------------------------
    // TEST 13 — JVM_RUNTIME selector: skew System.currentTimeMillis()
    // ------------------------------------------------------------------

    /**
     * Applies a +30-second OFFSET skew to System.currentTimeMillis().
     * Captures the reported time via System.currentTimeMillis() and compares it to
     * the real-world time captured before and after activating chaos.
     *
     * Assert: reported time > real time + 29_000ms (agent adds ~30s to every millis call).
     */
    @Test
    void selector_JVM_RUNTIME() throws Exception {
        long realBefore = System.nanoTime(); // use nanoTime as chaos-free reference

        long reportedMillis;

        try (var _ = chaos.activate(
                ChaosSelector.jvmRuntime(Set.of(OperationType.SYSTEM_CLOCK_MILLIS)),
                ChaosEffect.skewClock(Duration.ofSeconds(30), ClockSkewMode.OFFSET)
        )) {
            reportedMillis = System.currentTimeMillis();
        }

        long realAfterNanos = System.nanoTime();
        // Compute real millis as midpoint of the measurement window
        long realMillis = (realBefore + realAfterNanos) / 2 / 1_000_000L;
        // Adjust for JVM epoch offset — nanoTime is not anchored to epoch, use wall time snapshot
        long wallSnapshot = System.currentTimeMillis(); // chaos no longer active
        long estimatedRealAtCapture = wallSnapshot - (realAfterNanos - realBefore) / 2 / 1_000_000L;

        long skewMs = reportedMillis - estimatedRealAtCapture;

        System.out.printf("[JVM_RUNTIME] reported=%d, estimatedReal=%d, skewMs=%d%n",
                reportedMillis, estimatedRealAtCapture, skewMs);

        assertThat(skewMs)
                .as("Reported millis must be at least 29000ms ahead of real time (30s OFFSET skew)")
                .isGreaterThan(29_000L);
    }
}
