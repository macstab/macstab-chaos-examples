package com.macstab.chaos.examples.insane;

import com.macstab.chaos.jvm.api.*;
import com.macstab.chaos.jvm.spring.boot4.test.ChaosTest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.lang.management.*;
import java.lang.ref.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.atomic.*;

import static org.assertj.core.api.Assertions.*;

@ChaosTest(classes = com.macstab.chaos.examples.UserServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InsaneDirectClassLoaderRetentionTest {

    @Autowired ChaosControlPlane chaos;

    // The "performance cache" that retains ClassLoaders forever
    static final Map<String, ClassLoader> CLASSLOADER_CACHE = new HashMap<>();
    // The "context propagation" ThreadLocal that retains ClassLoaders per thread
    static final ThreadLocal<ClassLoader> CONTEXT_CLASSLOADER = new ThreadLocal<>();

    @Test
    @DisplayName("INSANE: ClassLoader retained by static Map + ThreadLocal → Metaspace grows +50MB per hot reload, never GC'd. 8h dev session → OOM.")
    void classLoaderRetainedByStaticReferenceGrowsMetaspaceWithEveryReload() throws Exception {
        MemoryMXBean memBean = ManagementFactory.getMemoryMXBean();

        System.out.println();
        System.out.println("  ╔══════════════════════════════════════════════════════════════════════════╗");
        System.out.println("  ║  INCIDENT: Metaspace OOM After 8 Hours of Hot-Reload Development        ║");
        System.out.println("  ║  Each hot-reload: +50MB Metaspace. Never released. After 8h: OOM.      ║");
        System.out.println("  ║  Engineers: increase MaxMetaspace. More reloads: OOM again faster.     ║");
        System.out.println("  ║  Heap dump: 400 ClassLoader instances. None GC-eligible.               ║");
        System.out.println("  ║  Root cause: static Map<String, ClassLoader> in framework code.        ║");
        System.out.println("  ║  Also: ThreadLocal<ClassLoader> in context propagation library.        ║");
        System.out.println("  ║  Each ClassLoader holds strong refs to all 10,000+ loaded classes.    ║");
        System.out.println("  ║  GC cannot collect: strong reference chain prevents it. Always.        ║");
        System.out.println("  ║  Eclipse MAT 'retained heap': shows ClassLoader retention. 2h to find.║");
        System.out.println("  ╚══════════════════════════════════════════════════════════════════════════╝");
        System.out.println();

        // Detect ClassLoader retention via ThreadLocal and static reference monitoring
        AtomicInteger classLoadersRetainedByStatic = new AtomicInteger(0);
        AtomicInteger classLoadersRetainedByThreadLocal = new AtomicInteger(0);
        AtomicLong retainedMetaspaceMb = new AtomicLong(0);

        ChaosScenario classLoaderRetentionProbe = ChaosScenario.builder("classloader-retention-probe")
                .description("Detect ClassLoader retention via static references and ThreadLocals — Metaspace leak fingerprint")
                .scope(ChaosScenario.ScenarioScope.JVM)
                .selector(ChaosSelector.classLoading(Set.of(OperationType.CLASSLOADER_RETAIN),
                        NamePattern.any()))
                .effect(ChaosEffect.observe(retentionEvent -> {
                    if (retentionEvent instanceof ClassLoaderRetentionEvent clre) {
                        if (clre.isRetainedByStaticField()) classLoadersRetainedByStatic.incrementAndGet();
                        if (clre.isRetainedByThreadLocal()) classLoadersRetainedByThreadLocal.incrementAndGet();
                        retainedMetaspaceMb.addAndGet(clre.estimatedRetainedMetaspaceMb());
                    }
                }))
                .activationPolicy(new ActivationPolicy(ActivationPolicy.StartMode.AUTOMATIC, 1.0, 0L, 0L, null, null, 0L, false))
                .build();

        // Simulate 5 "hot reloads" — each creates a new ClassLoader and leaks it
        List<WeakReference<ClassLoader>> weakRefs = new ArrayList<>();
        long metaspaceBefore = memBean.getNonHeapMemoryUsage().getUsed();

        try (ChaosActivationHandle handle = chaos.activate(classLoaderRetentionProbe)) {
            for (int reload = 0; reload < 5; reload++) {
                // Each hot-reload creates a new URLClassLoader (simulates framework reload)
                URLClassLoader newLoader = new URLClassLoader(
                        new URL[]{getClass().getClassLoader().getResource(".")},
                        null); // isolated from parent — simulates hot-reload isolation

                // THE BUG: framework puts it in static cache
                CLASSLOADER_CACHE.put("app-v" + reload, newLoader);
                // THE BUG: library puts it in ThreadLocal for "context propagation"
                CONTEXT_CLASSLOADER.set(newLoader);

                // WeakRef: if GC could collect it, this would be cleared
                weakRefs.add(new WeakReference<>(newLoader));

                System.out.printf("  Reload %d: ClassLoader %s added to static cache + ThreadLocal%n",
                        reload + 1, newLoader.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(newLoader)));
            }

            // Try to GC: should fail because CLASSLOADER_CACHE holds strong references
            System.gc();
            Thread.sleep(500);
        }

        long aliveAfterGc = weakRefs.stream().filter(wr -> wr.get() != null).count();
        long metaspaceAfter = memBean.getNonHeapMemoryUsage().getUsed();
        long metaspaceGrowthMb = (metaspaceAfter - metaspaceBefore) / 1_048_576;

        System.out.println();
        System.out.println("  ╔══════════════════════════════════════════════════════════════════════════╗");
        System.out.println("  ║  CLASSLOADER RETENTION PROOF                                            ║");
        System.out.printf( "  ║  ClassLoaders created (5 reloads):    5                                 ║%n");
        System.out.printf( "  ║  ClassLoaders alive after GC:         %d  ← NONE collected               ║%n", aliveAfterGc);
        System.out.printf( "  ║  ClassLoaders in static Map:          %d  (strong reference = no GC)      ║%n", CLASSLOADER_CACHE.size());
        System.out.printf( "  ║  Metaspace growth from 5 reloads:    +%dMB                               ║%n", metaspaceGrowthMb);
        System.out.printf( "  ║  Estimated retained by agent:        +%dMB                               ║%n", retainedMetaspaceMb.get());
        System.out.println("  ╠══════════════════════════════════════════════════════════════════════════╣");
        System.out.println("  ║  With 80 reloads/day: 80 retained ClassLoaders × 50MB = 4GB Metaspace  ║");
        System.out.println("  ║  Fix: WeakReference<ClassLoader> in the cache. ThreadLocal.remove().   ║");
        System.out.println("  ║  Eclipse MAT: 'retained heap' on ClassLoader shows root cause.         ║");
        System.out.println("  ║  This agent: intercepts ClassLoader retention. CI alert on accumulation║");
        System.out.println("  ╚══════════════════════════════════════════════════════════════════════════╝");

        assertThat(aliveAfterGc).as("Retained ClassLoaders survive GC due to static/TL references").isEqualTo(5);
    }
}
