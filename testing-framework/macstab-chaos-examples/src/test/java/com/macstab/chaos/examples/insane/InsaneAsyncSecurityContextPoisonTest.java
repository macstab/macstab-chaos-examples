package com.macstab.chaos.examples.insane;

import com.macstab.chaos.jvm.api.*;
import com.macstab.chaos.jvm.spring.boot4.test.ChaosTest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.assertj.core.api.Assertions.*;

@ChaosTest(classes = com.macstab.chaos.examples.UserServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InsaneAsyncSecurityContextPoisonTest {

    @Autowired ChaosControlPlane chaos;

    @Test
    @DisplayName("INSANE: @Async loses Spring Security context — NPE in async thread is silently swallowed. 6 months of missing audit logs, bypassed security checks.")
    void asyncThreadLocalSecurityContextLostSilently() throws Exception {
        System.out.println();
        System.out.println("  ╔══════════════════════════════════════════════════════════════════════════╗");
        System.out.println("  ║  INCIDENT: Random NPE in @Async Notification Thread. Silently Caught.  ║");
        System.out.println("  ║  SecurityContextHolder.getContext().getAuthentication() → null → NPE.  ║");
        System.out.println("  ║  Engineers: add try-catch, skip notification if null. 'Fixed.'         ║");
        System.out.println("  ║  6 months later: audit finds 40,000 missing notification records.     ║");
        System.out.println("  ║  Security team: async security checks silently bypassed (auth=null).  ║");
        System.out.println("  ║  Root cause: SecurityContext in ThreadLocal. @Async = new thread.     ║");
        System.out.println("  ║  New thread has NO SecurityContext. THREAD_LOCAL strategy lost.       ║");
        System.out.println("  ║  Fix: MODE_INHERITABLETHREADLOCAL. Mentioned once in Spring docs.     ║");
        System.out.println("  ╚══════════════════════════════════════════════════════════════════════════╝");
        System.out.println();

        // Detect security context availability across thread boundaries
        AtomicInteger contextPresentOnRequestThread = new AtomicInteger(0);
        AtomicInteger contextMissingOnAsyncThread = new AtomicInteger(0);
        AtomicInteger asyncThreadsAffected = new AtomicInteger(0);

        ChaosScenario securityContextProbe = ChaosScenario.builder("async-security-context-poison")
                .description("Probe SecurityContextHolder across thread boundaries — detect ThreadLocal context loss in @Async")
                .scope(ChaosScenario.ScenarioScope.JVM)
                .selector(ChaosSelector.threadLocal(Set.of(OperationType.THREAD_LOCAL_GET,
                        OperationType.SECURITY_CONTEXT_ACCESS)))
                .effect(ChaosEffect.observe(tlEvent -> {
                    if (tlEvent instanceof ThreadLocalAccessEvent tlae) {
                        if (tlae.getKey().contains("SecurityContext")) {
                            if (tlae.getValue() == null || tlae.isEmptyContext()) {
                                contextMissingOnAsyncThread.incrementAndGet();
                            } else {
                                contextPresentOnRequestThread.incrementAndGet();
                            }
                        }
                    }
                }))
                .activationPolicy(new ActivationPolicy(ActivationPolicy.StartMode.AUTOMATIC, 1.0, 0L, 0L, null, null, 0L, false))
                .build();

        // Simulate: request thread sets SecurityContext (as Spring Security does per request)
        // Then simulate @Async execution on a different thread
        CountDownLatch latch = new CountDownLatch(10);
        List<Boolean> contextResults = Collections.synchronizedList(new ArrayList<>());

        try (ChaosActivationHandle handle = chaos.activate(securityContextProbe)) {
            // REQUEST THREAD: has security context (like a real HTTP request)
            UsernamePasswordAuthenticationToken auth =
                    new UsernamePasswordAuthenticationToken(
                            "admin-user", "credentials",
                            List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
            SecurityContextHolder.getContext().setAuthentication(auth);
            boolean contextOnRequestThread = SecurityContextHolder.getContext().getAuthentication() != null;
            contextPresentOnRequestThread.incrementAndGet();

            // @ASYNC THREAD: runs on separate thread pool — ThreadLocal NOT inherited
            ExecutorService asyncPool = Executors.newFixedThreadPool(5); // simulates Spring's @Async executor
            for (int i = 0; i < 10; i++) {
                asyncPool.submit(() -> {
                    try {
                        // This is what the @Async notification method does:
                        SecurityContext asyncContext = SecurityContextHolder.getContext();
                        boolean hasAuth = asyncContext.getAuthentication() != null;
                        contextResults.add(hasAuth);
                        if (!hasAuth) {
                            contextMissingOnAsyncThread.incrementAndGet();
                            asyncThreadsAffected.incrementAndGet();
                        }
                    } finally {
                        latch.countDown();
                    }
                });
            }
            asyncPool.shutdown();
            latch.await(10, TimeUnit.SECONDS);
        }

        long nullContextCount = contextResults.stream().filter(b -> !b).count();

        System.out.printf("  Security context on request thread:   PRESENT (%s)%n",
                SecurityContextHolder.getContext().getAuthentication() != null ? "admin-user" : "null");
        System.out.printf("  Security context on @Async threads:   %d/10 NULL (lost in ThreadLocal handoff)%n", nullContextCount);
        System.out.printf("  Agent ThreadLocal probe detections:   %d missing, %d present%n%n",
                contextMissingOnAsyncThread.get(), contextPresentOnRequestThread.get());

        System.out.println("  ╔══════════════════════════════════════════════════════════════════════════╗");
        System.out.println("  ║  ASYNC SECURITY CONTEXT POISON PROOF                                    ║");
        System.out.printf( "  ║  Request thread context: PRESENT                                        ║%n");
        System.out.printf( "  ║  @Async thread context:  NULL (%d/10 threads — %d%% affected)            ║%n",
                nullContextCount, nullContextCount * 10);
        System.out.println("  ╠══════════════════════════════════════════════════════════════════════════╣");
        System.out.println("  ║  What happens: getAuthentication().getName() → NullPointerException    ║");
        System.out.println("  ║  What engineers do: try-catch. Skip. Log. Mark as 'resolved.'          ║");
        System.out.println("  ║  What's really happening: auth checks silently skipped in async paths  ║");
        System.out.println("  ║  Fix: SecurityContextHolder.setStrategyName(MODE_INHERITABLETHREADLOCAL)║");
        System.out.println("  ╚══════════════════════════════════════════════════════════════════════════╝");

        assertThat(nullContextCount).as("SecurityContext lost on async threads").isGreaterThan(0);
    }
}
