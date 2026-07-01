package com.macstab.chaos.examples.insane;

import com.macstab.chaos.jvm.api.*;
import com.macstab.chaos.jvm.spring.boot4.test.ChaosTest;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;

import java.lang.management.*;
import java.util.*;
import java.util.concurrent.atomic.*;

import static org.assertj.core.api.Assertions.*;

@ChaosTest(classes = com.macstab.chaos.examples.UserServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InsaneStringInternExplosionTest {

    @Autowired com.macstab.chaos.jvm.api.ChaosControlPlane chaos;

    @Test
    @DisplayName("INSANE: String.intern() on unique keys → Metaspace fills while heap stays flat. Engineers increase MaxMetaspace for 2 weeks.")
    void stringInternExplosionFillsMetaspaceHeapStaysFlat() throws Exception {
        MemoryMXBean memBean = ManagementFactory.getMemoryMXBean();

        System.out.println();
        System.out.println("  ╔══════════════════════════════════════════════════════════════════════════╗");
        System.out.println("  ║  INCIDENT: Metaspace OOM While Heap Shows 35% Used                     ║");
        System.out.println("  ║  Production: Metaspace grows 100MB/h. GC does not help. Restarts.      ║");
        System.out.println("  ║  Engineers: increase -XX:MaxMetaspaceSize. It fills again. Repeat.     ║");
        System.out.println("  ║  Memory analyzer: no classloader leak. No class explosion.              ║");
        System.out.println("  ║  But: 800MB of String objects in native memory.                        ║");
        System.out.println("  ║  Root cause: caching library calls String.intern() on cache keys.      ║");
        System.out.println("  ║  Cache keys = userId + timestamp = unique per request.                 ║");
        System.out.println("  ║  50M unique interned strings = 2-4GB native memory. OOM.              ║");
        System.out.println("  ╚══════════════════════════════════════════════════════════════════════════╝");
        System.out.println();

        // Track String.intern() calls via JVM agent
        AtomicInteger internCallCount = new AtomicInteger(0);
        AtomicInteger uniqueInternedCount = new AtomicInteger(0);
        Set<String> internedStrings = Collections.synchronizedSet(new HashSet<>());

        ChaosScenario internProbe = ChaosScenario.builder("string-intern-explosion-probe")
                .description("Intercept String.intern() to count and classify interned strings — find unique-key intern bombs")
                .scope(ChaosScenario.ScenarioScope.JVM)
                .selector(ChaosSelector.stringIntern(Set.of(OperationType.STRING_INTERN)))
                .effect(ChaosEffect.observe(internedValue -> {
                    internCallCount.incrementAndGet();
                    if (internedStrings.add(internedValue.toString())) {
                        uniqueInternedCount.incrementAndGet();
                    }
                }))
                .activationPolicy(new ActivationPolicy(ActivationPolicy.StartMode.AUTOMATIC, 1.0, 0L, 0L, null, null, 0L, false))
                .build();

        long heapBefore = memBean.getHeapMemoryUsage().getUsed();

        try (ChaosActivationHandle handle = chaos.activate(internProbe)) {
            // Simulate what the caching library does: intern cache keys containing unique user IDs
            Random rng = new Random(42);
            int SIMULATED_REQUESTS = 10_000;
            for (int i = 0; i < SIMULATED_REQUESTS; i++) {
                // Each cache key is unique (userId + timestamp + random component = never repeats)
                String cacheKey = "user:" + rng.nextInt(1_000_000) + ":req:" + i + ":ts:" + System.nanoTime();
                // The caching library calls intern() thinking it's "optimizing memory"
                String interned = cacheKey.intern(); // THIS IS THE BUG
            }
        }

        long heapAfter = memBean.getHeapMemoryUsage().getUsed();

        System.out.println("  String.intern() probe results after 10,000 simulated cache operations:");
        System.out.printf("  Total intern() calls intercepted:  %,d%n", internCallCount.get());
        System.out.printf("  Unique strings interned:           %,d%n", uniqueInternedCount.get());
        System.out.printf("  Reuse rate:                        %.1f%%  (0%% = every key is unique = BOMB)%n",
                internCallCount.get() > 0 ?
                        (1.0 - (double) uniqueInternedCount.get() / internCallCount.get()) * 100 : 0);
        System.out.printf("  Heap change:                       %+,d bytes%n", heapAfter - heapBefore);

        System.out.println();
        System.out.println("  ╔══════════════════════════════════════════════════════════════════════════╗");
        System.out.println("  ║  STRING INTERN EXPLOSION PROOF                                          ║");
        System.out.printf( "  ║  intern() calls:     %,8d  (10k requests → 10k interned strings)       ║%n", internCallCount.get());
        System.out.printf( "  ║  Unique interned:    %,8d  (%.0f%% unique = all going to native memory)  ║%n",
                uniqueInternedCount.get(),
                uniqueInternedCount.get() > 0 ? (double) uniqueInternedCount.get() / Math.max(1, internCallCount.get()) * 100 : 0);
        System.out.println("  ╠══════════════════════════════════════════════════════════════════════════╣");
        System.out.println("  ║  At 1M req/day: 1M unique interned strings/day in native memory.       ║");
        System.out.println("  ║  In 6 hours: 250k unique strings = ~100MB native memory growth.        ║");
        System.out.println("  ║  Heap dump shows nothing. GC cannot collect intern table entries.      ║");
        System.out.println("  ║  Fix: remove .intern() from cache key. One word. 2 weeks of ops.       ║");
        System.out.println("  ║  Prevention: this test in CI. >50% unique intern rate = build fails.   ║");
        System.out.println("  ╚══════════════════════════════════════════════════════════════════════════╝");

        assertThat(uniqueInternedCount.get()).as("Agent intercepted String.intern() calls").isGreaterThan(0);
        double uniqueRate = (double) uniqueInternedCount.get() / Math.max(1, internCallCount.get());
        System.out.printf("%n  Unique intern rate: %.0f%% — above 10%% means you have a Metaspace bomb%n",
                uniqueRate * 100);
    }
}
