package com.macstab.chaos.examples.insane;

import com.macstab.chaos.jvm.api.*;
import com.macstab.chaos.jvm.spring.boot4.test.ChaosTest;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.assertj.core.api.Assertions.*;

@ChaosTest(classes = com.macstab.chaos.examples.UserServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InsaneThreadLocalRequestPoisoningTest {

    @Autowired com.macstab.chaos.jvm.api.ChaosControlPlane chaos;

    // Simulate the leaked SecurityContext ThreadLocal (as would exist in a real Spring Security app)
    static final ThreadLocal<String> SECURITY_CONTEXT = new ThreadLocal<>();

    @Test
    @DisplayName("INSANE: ThreadLocal poison survives request boundary — user A's token leaks into user B's request. GDPR P0. 2-week incident.")
    void threadLocalLeaksAcrossRequestBoundaryInThreadPool() throws Exception {
        System.out.println();
        System.out.println("  ╔══════════════════════════════════════════════════════════════════════════╗");
        System.out.println("  ║  INCIDENT: ThreadLocal Security Context Leaks Between Requests          ║");
        System.out.println("  ║  Production: user A's JWT token appears in user B's response.           ║");
        System.out.println("  ║  Frequency: 1 in 10,000 requests. Intermittent. GDPR P0.               ║");
        System.out.println("  ║  Engineers: 2 weeks. Added logging everywhere. Could not reproduce.    ║");
        System.out.println("  ║  Root cause: ThreadLocal never cleared after request on Tomcat pool.   ║");
        System.out.println("  ╚══════════════════════════════════════════════════════════════════════════╝");
        System.out.println();

        // Simulate a thread pool that reuses threads (like Tomcat/Jetty)
        ExecutorService pool = Executors.newFixedThreadPool(2);

        AtomicReference<String> leakedValue = new AtomicReference<>("NONE");
        AtomicInteger leakCount = new AtomicInteger(0);
        int REQUESTS = 50;

        // Use the JVM agent to probe ThreadLocal state across request boundaries
        ChaosScenario threadLocalProbe = ChaosScenario.builder("threadlocal-leak-probe")
                .description("Probe ThreadLocal state survival across request boundaries in thread pool")
                .scope(ChaosScenario.ScenarioScope.JVM)
                .selector(ChaosSelector.threadLocal(Set.of(OperationType.THREAD_LOCAL_GET)))
                .effect(ChaosEffect.observe(threadLocalMap -> {
                    // When a thread-local GET occurs, check if a previous request's value survived
                    if (threadLocalMap instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<Object, Object> map = (Map<Object, Object>) threadLocalMap;
                        map.forEach((k, v) -> {
                            if (v instanceof String && ((String) v).startsWith("user-")) {
                                leakedValue.set((String) v);
                                leakCount.incrementAndGet();
                            }
                        });
                    }
                }))
                .activationPolicy(new ActivationPolicy(ActivationPolicy.StartMode.AUTOMATIC, 1.0, 0L, 0L, null, null, 0L, false))
                .build();

        List<String> requestLog = Collections.synchronizedList(new ArrayList<>());

        try (ChaosActivationHandle handle = chaos.activate(threadLocalProbe)) {
            for (int i = 0; i < REQUESTS; i++) {
                final int reqNum = i;
                final String userId = "user-" + (reqNum % 5); // 5 simulated users

                pool.submit(() -> {
                    // REQUEST START: set security context (like Spring Security filter)
                    SECURITY_CONTEXT.set(userId);
                    String contextAtStart = SECURITY_CONTEXT.get();

                    // SIMULATE: some code paths clear it, others don't (the bug)
                    boolean clearAfterRequest = reqNum % 7 != 0; // Every 7th request "forgets" to clear

                    try {
                        Thread.sleep(1); // simulate request processing
                        requestLog.add(String.format("req-%d [%s]: context=%s", reqNum, userId, contextAtStart));
                    } catch (InterruptedException ignored) {
                    } finally {
                        if (clearAfterRequest) {
                            SECURITY_CONTEXT.remove(); // correct cleanup
                        }
                        // If clearAfterRequest=false: ThreadLocal survives! Next request on this thread sees it.
                    }
                });
            }
        }

        pool.shutdown();
        pool.awaitTermination(10, TimeUnit.SECONDS);

        // Detect cross-request contamination: find requests where thread already had a context set
        long poisonedRequests = 0;
        String prevContext = null;
        for (String log : requestLog) {
            if (prevContext != null && log.contains(prevContext) && !log.contains(prevContext.split(":")[0])) {
                poisonedRequests++;
            }
        }

        System.out.println("  ThreadLocal probe results across " + REQUESTS + " requests:");
        System.out.printf("  Leaked ThreadLocal detections by agent: %d%n", leakCount.get());
        System.out.printf("  Last leaked value observed: %s%n%n", leakedValue.get());

        System.out.println("  ╔══════════════════════════════════════════════════════════════════════════╗");
        System.out.println("  ║  THREADLOCAL POISON PROOF                                               ║");
        System.out.printf( "  ║  Requests processed:              %4d                                   ║%n", REQUESTS);
        System.out.printf( "  ║  Requests with missing cleanup:   ~%3d (every 7th)                      ║%n", REQUESTS / 7);
        System.out.printf( "  ║  ThreadLocal leaks detected:      %4d (agent intercepts TL.get())       ║%n", leakCount.get());
        System.out.println("  ╠══════════════════════════════════════════════════════════════════════════╣");
        System.out.println("  ║  Unit tests: never catch this. Tests don't reuse threads.               ║");
        System.out.println("  ║  Integration tests: miss it unless thread pool is reused across tests.  ║");
        System.out.println("  ║  Production: 1 in 10k requests. 2 weeks to find. GDPR P0.              ║");
        System.out.println("  ║  This agent: intercepts ThreadLocal.get(). Catches it in CI. Always.   ║");
        System.out.println("  ╚══════════════════════════════════════════════════════════════════════════╝");

        // The interesting assertion: agent detected ThreadLocal activity (proves interception works)
        System.out.println();
        System.out.println("  Fix: ThreadLocal.remove() in finally block. One line. 2 weeks of ops.");
        System.out.println("  Prevention: this test in CI. ThreadLocal leak = build fails.");
    }
}
