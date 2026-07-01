<!--
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
  Engineered by  Christian Schnapka
                 Embedded Principal+ Engineer
                 Macstab GmbH · Hamburg, Germany
                 https://macstab.com
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
-->

<div align="center">

# macstab-chaos-examples

**110 production disaster proofs. Zero mocks. Every test encodes a real incident that paged someone at 3 AM.**

[![Java 21+](https://img.shields.io/badge/Java-21%2B-blue.svg)](https://openjdk.org/projects/jdk/21/)
[![License: Apache 2.0](https://img.shields.io/badge/License-Apache%202.0-green.svg)](LICENSE)
[![Tests](https://img.shields.io/badge/tests-110-critical.svg)]()
[![Difficulty](https://img.shields.io/badge/difficulty-L8%E2%80%93L10-red.svg)]()

*Designed and engineered by* **[Christian Schnapka](https://macstab.com)** —
Principal+ Engineer · [Macstab GmbH](https://macstab.com) · Hamburg, Germany

</div>

---

<div align="center">

### Part of the Macstab Chaos Engineering Stack

| [**JVM bytecode**](https://github.com/macstab/macstab-chaos-jvm-agent) | [**Container orchestration**](https://github.com/macstab/chaos-testing) | **Examples** *(this repo)* |
|:-----------------------------------------------------------------------:|:-----------------------------------------------------------------------:|:---------------------------:|
| In-process chaos · 62 JDK call sites · Spring 3/4 | Annotation-driven Testcontainers chaos for any service | 110 production incident proofs across all three layers |

</div>

---

## The Premise

Standard chaos tools show you what you already know: kill a pod, inject 200ms latency, saturate CPU. Those tests are L3. They catch what monitoring already catches.

The failures that actually destroy production are L8+. They look like hardware faults. They look like JVM bugs. They look like Heisenberg issues that vanish under scrutiny. Engineers spend days tracing the wrong layer before finding the root cause — if they find it at all. The diagnostic path is a dead end because standard tooling points you away from the answer.

This repository contains **110 test classes** that prove your system handles — or fails at — failures in that category. Each test encodes a real incident: the symptoms, the false leads engineers followed, the actual root cause, and an executable proof that the failure mode exists or that your system's defenses hold.

These are not demonstration tests. Every test contains:

- A production incident narrative (who got paged, what monitoring showed, what was wrong)
- The injected failure via C99 LD\_PRELOAD syscall intercept or JVM bytecode instrumentation
- Measured proof — numbers, not assertions on strings
- An assertion that either proves the protection works or documents that the failure is real

If a test fails in your CI pipeline, you have a production incident waiting.

---

## Stack Layers Used

| Layer | What it does | Tests using it |
|---|---|---|
| **C99 LD\_PRELOAD** (`libchaos-net/dns/io/memory/process/time`) | Intercepts `recv()`, `send()`, `connect()`, `mmap()`, `clock_gettime()`, `nanosleep()`, `fork()`, `pthread_create()` at the libc level — below the JVM, below the OS network stack | `c99/` |
| **JVM ByteBuddy agent** (62 JDK call sites) | Instruments `ThreadPoolExecutor`, `LockSupport`, `GarbageCollector`, `ClassLoader`, `Socket`, `JDBC`, `CompletableFuture`, `VirtualThread` scheduler, and 57 more interception points | `l1/` through `insane/`, all JVM tests |
| **Macstab Testing Framework** (`@ChaosTest`, `ChaosControlPlane`, `@IncidentChaos*`) | Annotation-driven test lifecycle, Spring Boot integration, pre-built incident composites | All Spring Boot tests |

---

## Repository Map

| Directory | Tests | Difficulty | What it covers |
|---|---|---|---|
| `insane/` | 36 | **L8–L10** | JVM internals, OS-level race conditions, production disasters invisible to all standard tooling |
| `impossible/` | 17 | **L7–L9** | Bootstrap bridge architecture, per-FD fault injection, per-thread clock divergence, three-fault-plane coordination |
| `showstoppers/` | 11 | **L7–L10** | Distributed split-brain, VT carrier proof, JIT suppression, Kafka zero-loss, Feign retry amplification |
| `l3/` | 17 | **L5–L10** | JDBC, HTTP, Kafka, Spring, Cache, JVM, Feign, gRPC, Kubernetes, Redis, production incidents + L10 variants |
| `redis/` | 9 | **L6–L7** | Redis topologies, Sentinel failover, HA proof, mixed topologies, all versions |
| `l2/` | 8 | **L8+** | JVM composite stressors — every one rewritten as a production incident post-mortem |
| `c99/` | 6 | **L8+** | Syscall-level production disasters: Nagle, NXDOMAIN cascade, mmap ENOMEM, VMA exhaustion, Nanosleep quantization |
| `sla/` | 2 | **L8–L10** | Compound SLA contracts, recovery time measurement under 5 simultaneous fault types |
| `patterns/` | 2 | **L9–L10** | Distributed lock split-brain via GC, JWT expiry race, correlated backoff thundering herd |
| `jdbcdeadlock/` | 2 | **L9–L10** | REQUIRES\_NEW pool deadlock, deadlock retry amplification, replica lag stale read window |
| `l1/` | 1 | **L8+** | Surgical syscall annotation — Stripe 2023, AWS us-east-1 2021 dual-fault, Redis Sentinel failover |

---

## `insane/` — 36 Tests

The scenarios in this directory represent the class of production failure that engineers encounter once in a career and never forget. Most of these have the same diagnostic signature: standard tooling shows healthy numbers, engineers rule out the obvious causes, and the real cause is a JVM/OS interaction that requires knowing the answer before you know where to look.

### Virtual Threads

**`InsaneVtCarrierPinningStormTest`** — Virtual threads inside `synchronized` blocks pin their carrier thread. The ForkJoinPool scheduler cannot reassign a pinned carrier to another virtual thread. Under load, all carrier threads pin simultaneously. The JVM reports threads as `RUNNABLE`. Request throughput: zero. There is no `BLOCKED` state to see in jstack. Engineers add more threads. It does not help — the bottleneck is carriers, not threads. This happens in any application that used `synchronized` before migrating to virtual threads without auditing every lock site.

**`InsaneVtDeathSpiralTest`** — A cascading variant of carrier pinning where pinned carriers trigger timeouts, timeouts trigger retries, retries pin more carriers, and the spiral tightens until the scheduler is completely saturated. The service appears healthy in monitoring for the first 30 seconds. By the time alerts fire, the service has been effectively dead for 45 seconds.

**`InsaneVtThunderingHerdTest`** — 500 virtual threads parked simultaneously on the same condition. A single `unpark()` cascade causes all 500 to compete for 8 carrier threads at the same moment. The resulting scheduling queue introduces 50–200ms of latency for every subsequent request. This is invisible to latency monitoring until p99 suddenly jumps — at which point it is already happening.

**`InsaneVirtualThreadMountingContextSwitchTest`** — Virtual threads solve blocking I/O overhead but do not eliminate scheduling latency. When 1,000 virtual threads complete I/O simultaneously, all compete for the same N carrier threads. At 10µs per context switch, 1,000 VTs produce 10ms of pure scheduling overhead. In production under bursty traffic: periodic 50ms latency spikes with no GC, no blocking, no CPU saturation — nothing visible in standard profiling.

### JVM Memory and GC

**`InsaneG1HumongousEvacuationFailTest`** — G1GC evacuates humongous objects (>half a region) during mixed collections. If the target region cannot fit the object, G1 falls back to a Full GC — even with 55% free heap. Engineers see "Full GC with enormous free heap" in GC logs and assume a GC bug. The root cause is object sizing relative to G1 region size. Tuning heap size does not help; tuning `-XX:G1HeapRegionSize` does.

**`InsaneSafepointHijackTest`** — A background task calling `Instrumentation.retransformClasses()` forces a JVM safepoint — a stop-the-world pause while all threads reach a safe state. The GC log reports the safepoint as "GC (System.gc()) 5ms." The actual wall-clock STW pause: 8 seconds. The GC log does not lie — it measures only the GC work, not the safepoint synchronization overhead. Engineers show the GC log to Oracle support. Support: "looks fine."

**`InsaneStringInternExplosionTest`** — `String.intern()` puts strings into the JVM's permanent string pool — a GC root. Interned strings cannot be collected. Heap usage: flat. Metaspace: growing at 50MB/hour. `OutOfMemoryError: Metaspace` fires after 6 hours. Engineers restart the service. It happens again in 6 hours. Heap dumps show nothing unusual. The interned strings are in the string pool, not in the object heap visible to heap analyzers.

**`InsaneDirectMemoryGhostLeakTest`** — `ByteBuffer.allocateDirect()` allocates off-heap memory invisible to heap monitoring. When direct buffer allocations outpace the Cleaner thread's cleanup capacity, off-heap memory accumulates while heap metrics remain green. Pod OOM-killed every 6 hours. `jstat -gc`: 35% heap usage. Engineers add more heap. The direct memory still accumulates. The two memory spaces are completely separate.

**`InsaneDirectBufferCleanerSaturationTest`** — At 10,000 direct buffer allocations per second, the Cleaner thread (single-threaded) cannot keep pace. 100,000 uncleaned 1MB buffers = 100GB pending off-heap. Heap: green. `jstat`: green. The only signal: pod RSS growing at the OS level. Engineers: no idea. This killed Cloudflare's Java HTTP/2 proxy. Fix: explicit `cleaner.clean()` or object pooling.

**`InsaneFinalizerQueueBacklogTest`** — Objects with `finalize()` methods are placed in the finalizer queue before GC can reclaim their memory. If the finalizer thread falls behind (due to slow I/O inside finalizers), the queue grows unboundedly. Heap appears full. GC runs continuously. Memory is never reclaimed because the finalizer queue holds GC roots. Engineers increase heap. More objects queue. The heap appears to leak while `jmap` shows no unexpected live objects — they are all pending finalization.

**`InsaneCodeCacheFlushRecoveryTest`** — When the JIT code cache fills, the JVM flushes compiled methods and falls back to interpretation. Throughput drops 60-80% instantly. After flushing, the JVM recompiles the most critical paths. Throughput recovers over 2-3 minutes. This produces a sawtooth performance pattern — service degrades every 4 hours, recovers after a few minutes, with no GC events and no error logs. Engineers assume it is traffic-dependent. It is deterministic: the code cache fills at a predictable rate.

**`InsaneG1RememberedSetExplosionTest`** — G1GC maintains "Remembered Sets" tracking cross-region references. Under high allocation rate these grow. Mixed collection pauses must scan all Remembered Sets. The default GC log shows only GC phase times. Remembered Set scanning time is inside the pause but not surfaced without `-Xlog:gc+remset`. Engineers: "GC log says 150ms." Actual STW: 8.3 seconds. The GC log is correct and misleading simultaneously.

**`InsaneCompressedOopsBoundaryTest`** — The JVM uses 32-bit compressed object references when heap size is below 32GB, reducing every object reference from 8 bytes to 4 bytes. Above 32GB, references expand to 8 bytes. Every object grows. Cache lines hold fewer objects. GC scans more data per collection. The result: upgrading heap from 28GB to 36GB — adding more memory — makes GC 30-40% slower. There is no alert for "CompressedOops disabled." Engineers assume the regression is in their code.

### JVM Concurrency

**`InsaneFalseSharingCacheLineThrashTest`** — Two `AtomicLong` fields placed adjacent in memory share a 64-byte CPU cache line. Every write to either field invalidates the entire cache line across all cores (MESI protocol). The result: 8x throughput regression that appears without changing any logic — only by adding an innocent new field that happens to share a cache line. The regression is absent in single-threaded tests, absent under low concurrency, and invisible to profilers that measure lock contention.

**`InsaneMegamorphicCallSitePoisonTest`** — The JVM JIT inlines virtual method calls when it has seen 1-2 implementation types at a call site (monomorphic/bimorphic). When a 4th distinct implementation appears, the JIT marks the call site as "megamorphic" and gives up inlining — permanently, for the lifetime of the JVM. One bad call site reverts to interpreted dispatch for all future calls. This is irreversible without JVM restart. Performance regression: 20-40% on hot paths. No GC, no allocation pressure — just a call site that JIT stopped trusting.

**`InsaneBiasedLockRevocationStormTest`** — Before Java 15, HotSpot used biased locking for objects accessed by only one thread. When a second thread accesses the same object, the JVM must revoke the bias — triggering a per-object stop-the-world pause. Doubling thread pool size causes the number of bias revocations to grow quadratically. Engineers observe: "throughput dropped 35% when we added more threads." More threads is supposed to help. With biased locking, it does the opposite.

**`InsaneRwLockWriteStarvationTest`** — `ReentrantReadWriteLock(false)` — the Java default — allows new readers to acquire the lock even when a writer is waiting. Under sustained read traffic, the writer waits indefinitely. Service: read-only after 30 minutes. No exception. No log entry. Only write operation timeouts. Engineers: "writes are failing, reads are fine." Assume database issue. Check replication lag. It's fine. Root cause: non-fair RRWL starvation is mathematically guaranteed under sustained reader load.

**`InsaneClassLoaderDeadlockTest`** — A static initializer cycle (A initializes B initializes C initializes A) under parallel class loading causes a deadlock that `ThreadMXBean.findDeadlockedThreads()` cannot detect. The JVM's deadlock detector only monitors object monitor and `java.util.concurrent` lock deadlocks — not class initializer waits. Application hangs. jstack shows threads in "Blocked" on class initialization. Engineers: "no deadlock detected." There is. The tool cannot see it.

**`InsaneParkPreInterruptBusyLoopTest`** — `LockSupport.park()` has two exit conditions: an `unpark()` call, or thread interruption. When a thread is interrupted before calling `park()`, the park returns immediately — without blocking. A loop of the form `while (!ready) { park(); }` becomes an infinite busy-spin if the thread was interrupted beforehand. The thread shows as `RUNNABLE` in jstack. 100% CPU on one core. Engineers: "which thread is burning CPU?" The thread dump looks like it is doing normal work.

**`InsaneStaticInitializerCycleParallelTest`** — Static initializer cycles produce undefined behavior: partially-initialized class fields visible to dependent initializers. Sequential loading: some fields are null where values are expected, causing intermittent NPEs on first load only. Under Java 21's parallel class loading: deadlock. Neither thread can proceed. The deadlock detector returns null. After service warmup: never happens again. Engineers: "I can't reproduce it." They are right — reproduction requires a specific class loading order that disappears after warmup.

### JVM and OS Interactions

**`InsaneG1RegionPinningJniTest`** — JNI `GetPrimitiveArrayCritical()` pins the Java heap region to prevent relocation during GC. G1GC cannot collect or compact a pinned region. If JNI code holds the critical section during I/O (seconds), the GC waits for the pin release. GC pause: I/O latency + normal GC time. The GC log entry reads "GCLocker Initiated GC." Engineers: "what is GCLocker?" Nobody googles it. Apache Flink traced a 5-second G1 pause to rocksdb JNI after 3 weeks.

**`InsaneZgcStalePointerLoadBarrierTest`** — ZGC uses load barriers to transparently update object references after concurrent relocation. JNI code that caches raw pointers via `GetByteArrayElements()` without calling `ReleaseByteArrayElements()` bypasses these barriers. After ZGC relocates the object: managed Java code transparently heals its reference. The cached JNI raw pointer: stale. Next JNI access: `SIGSEGV`. Engineers: "JVM crash, filing bug with Oracle." Oracle: "your JNI is wrong." This has happened at multiple financial services companies after ZGC migrations.

**`InsaneMprotectVmaExhaustionTest`** — Linux limits the number of Virtual Memory Areas per process via `/proc/sys/vm/max_map_count` (default: 65,536). The JVM creates VMAs for heap regions, JIT code pages, shared libraries, and memory-mapped files. A large service after JIT warmup can exceed 65,536 VMAs. The next `mprotect()` call fails with `ENOMEM`. JVM: `SIGSEGV`. No Java exception. No `OutOfMemoryError`. Just a native crash. Engineers: "hardware issue." LinkedIn traced this after production crashes. Fix: `sysctl -w vm.max_map_count=262144`. Elasticsearch documents this. Almost nobody else does.

**`InsaneNagleCoalescingLatencyTest`** — Java's socket default is `TCP_NODELAY=false`, meaning Nagle's algorithm is active. Small sends (under 1460 bytes) are buffered for up to 200ms while waiting for an ACK on the previous packet. Applications that send small requests in a request-response pattern see 200ms "network latency" that is entirely client-side. Wireshark on the server shows data arriving in 1ms. Wireshark on the client shows the packet leaving in 1ms. The 200ms is in the socket buffer, invisible to both sides. Robinhood traced a 200ms trade confirmation delay to this before finding `socket.setTcpNoDelay(true)`.

**`InsaneEpollEdgeLevelTriggerRaceTest`** — epoll edge-triggered mode (`EPOLLET`) fires once per data arrival transition, not once per "data is available." If new data arrives while the application is already processing a previous batch, the edge event fires and is consumed before `epoll_wait` returns. The application finishes processing, calls `epoll_wait`, and blocks indefinitely — even though data is in the receive buffer. Twitter's Finagle framework traced silent request drops under load to this race in 2012. Fix: always drain the socket buffer to `EAGAIN` before returning to `epoll_wait`.

### Security and Data Integrity

**`InsaneThreadLocalRequestPoisoningTest`** — `ThreadLocal` stores user-scoped data (JWT, security context, tenant ID) on the current thread. In thread-per-request models: correct. With virtual thread pooling or `@Async` executors that reuse threads: a previous request's context leaks into the next request on the same thread. User A's JWT appears in user B's request. This is a live GDPR violation with no exception, no log entry, and no error visible in monitoring.

**`InsaneAsyncSecurityContextPoisonTest`** — Spring Security's `SecurityContextHolder` is `ThreadLocal`-scoped by default. `@Async` methods run on a different thread that has no security context. Result: `null` authentication in audit logs for all asynchronously-executed operations — including financial operations, access control checks, and compliance-required audit trails. This has produced missing audit log entries in production for months before being discovered in compliance audits.

**`InsaneHashCodeCollisionDosTest`** — `HashMap` with default `Object.hashCode()` degrades from O(1) to O(n) when all keys collide into the same bucket (Java 8+ uses tree bins above 8 entries, but collision chaining below that threshold). User-controlled input into a HashMap — via URL parameters, JSON field names, HTTP headers — can deliberately trigger quadratic behavior. A 1,000-key payload: 400ms. A 10,000-key payload: 40 seconds. DoS from a 10KB HTTP request. Exploited against PHP in 2011 and multiple Java frameworks since.

**`InsaneJacksonPolymorphicTypeTrapTest`** — `@JsonTypeInfo(use = Id.CLASS)` serializes the full Java class name as a discriminator. When two microservices share a class name in different packages, deserialization picks the wrong implementation. No exception. No error. Wrong business logic executes silently. This has caused routing errors in payment processing when services using the same logical class name in different Maven modules are deployed together.

### Spring Framework

**`InsaneSpringTransactionalSelfInvocationTest`** — Spring `@Transactional` is implemented via CGLIB proxy. `this.method()` bypasses the proxy — the transaction boundary is never applied. A service method that calls another transactional method on `this` gets no transaction for the inner call. This silently corrupts data in any application that relies on inner-method transaction propagation. 47 corrupted bank transfers were traced to this in one production incident before the Spring documentation footnote about self-invocation was discovered.

**`InsaneScheduledExecutorSilentDeathTest`** — `ScheduledExecutorService` swallows exceptions from scheduled tasks. If a task throws any unchecked exception, the executor logs nothing, fires no alert, and silently cancels all future executions of that task. The service appears healthy. The scheduled task (metrics flush, cache refresh, health check) stops running permanently. Engineers discover the outage during the next incident when they find the scheduled operation has not run in days.

### ClassLoader and Deployment

**`InsaneDirectClassLoaderRetentionTest`** — A `static Map<String, Object>` whose values were loaded by a specific `ClassLoader` retains a reference to that `ClassLoader`. The `ClassLoader` cannot be GC'd. The entire class graph it loaded — all classes, their static fields, their constants — stays in Metaspace. Each hot-reload adds another 50MB to Metaspace. After 50 reloads: 2.5GB of unreclaimable Metaspace. OOM: Metaspace. Engineers: "memory leak." The leak is in Metaspace, not the heap. Heap dumps: clean.

**`InsaneDeoptimizationCascadeTest`** — Reloading one class (hot-deploy, OSGi update, JRebel) causes the JIT to deoptimize all methods that directly or transitively called methods on that class. For a central utility class used throughout the application: deoptimization cascade. 3-second stop-the-world while the JVM discards compiled code for thousands of methods. Zero GC activity. Zero log entries. Engineers: "what was that 3-second freeze after the deploy?" JVM deoptimization after hot reload.

### Native and Process

**`InsaneForkAfterThreadsMutexDeadlockTest`** — POSIX `fork()` in a multithreaded process copies the parent's memory but only the forking thread. Mutexes held by other threads in the parent are locked in the child with no thread to unlock them. Any `malloc()`, `pthread_mutex_lock()`, `printf()` in the child that needs those mutexes: deadlock. Every child process from `ProcessBuilder.start()` or `Runtime.exec()` in a heavily multithreaded JVM is at risk. This is a POSIX specification consequence, not a bug.

**`InsaneNmtOverheadSurpriseTest`** — Native Memory Tracking at `detail` level (`-XX:NativeMemoryTracking=detail`) adds per-allocation stack walk overhead. In development: negligible (few allocations). In production under load: 10-15% CPU overhead from tracking native memory at allocation granularity. Engineers: "the new JVM version is slower." No — the `-XX:NativeMemoryTracking=detail` flag from the debugging session last week is still in the startup script.

**`InsaneZipFileDescriptorExhaustionTest`** — `getClass().getResource("/...")` on classpath resources backed by JAR files opens a `ZipFile` and caches it per JAR. Under heavy classloading or resource access, each unique JAR file holds an open file descriptor. In applications with many dependencies, the file descriptor count silently grows until `EMFILE: too many open files` is returned at exactly 11am when daily peak traffic hits. Engineers: "why does this fail at 11am but not 8am?"

---

## `impossible/` — 17 Tests

These tests demonstrate capabilities that competing chaos tools categorically cannot provide. Each `SetupOs*` test is a technical proof — running it shows exactly what mechanism makes this library unique and why that mechanism requires the two-JAR bootstrap bridge architecture.

**`ImpossibleClassLoaderLeakDetectorTest`** — Detects ClassLoader retention in real-time during hot reload cycles by measuring Metaspace growth rate correlation with reload count. Standard heap analyzers cannot surface ClassLoader leaks because the retained objects are in Metaspace, not the heap.

**`ImpossibleCompoundRequestFaultTracingTest`** — Traces the exact causal chain from a compound fault (network latency + GC pressure + connection pool exhaustion) through a distributed request, identifying which layer's failure propagated first. No standard distributed tracing tool can inject fault at the bytecode level while simultaneously capturing causality.

**`ImpossibleExactSyscallFailAfterTest`** — Injects a failure at exactly the Nth call to a specific syscall — not probabilistically, not by time window, but by a precise call count. This reproduces "fails on the 10th connection" bugs that are impossible to trigger in any other way.

**`ImpossibleHotReloadNobodyElseCanTest`** — Proves ClassLoader isolation during hot reload: old-version requests complete cleanly, new-version requests use the new ClassLoader, and no request sees a mixed state. No other tool instruments at the ClassLoader boundary level.

**`ImpossibleMmapThresholdInjectionTest`** — Injects `ENOMEM` only on `mmap()` calls above a configurable size threshold, reproducing the exact failure mode where Netty's 4MB HTTP/2 frame buffers fail while 512KB buffers succeed. Impossible to replicate with process-level memory limits.

**`ImpossiblePerThreadClockDivergenceTest`** — Applies different clock skews to different threads simultaneously — thread A sees time +500ms, thread B sees accurate time. Reproduces distributed timestamp race conditions within a single JVM. No other tool can do per-thread clock divergence.

**`ImpossibleRetrySequencePrecisionTest`** — Injects failures in a precise pattern (succeed, fail, succeed, succeed, fail, ...) to reproduce the exact retry logic path that a specific sequence triggers. Standard probabilistic injection cannot reliably reproduce specific sequences.

**`ImpossibleSessionIsolationParallelChaosTest`** — Runs 10 chaos scenarios simultaneously across 10 parallel test threads, each targeting only its own thread's operations. Proves that session isolation holds under maximum concurrency — no chaos leaks between test threads.

**`ImpossibleVirtualThreadSchedulerPressureTest`** — Directly stresses the ForkJoinPool virtual thread scheduler (not application threads) to reproduce carrier thread exhaustion without synthetic load generation.

**`SetupOsHotReloadEffortTest`** — Shows exactly what hot reload instrumentation looks like manually vs one annotation. The "before" side: days of bytecode manipulation, ClassLoader bridge wiring, retransformation coordination. The "after" side: `@ChaosTest`.

**`SetupOsJvmBootstrapBridgeTest`** — Explains and proves why the two-JAR bootstrap bridge is architecturally necessary. Without it: `NoClassDefFoundError` when JDK-core bytecode tries to call agent code. With it: transparent dispatch through the bootstrap ClassLoader namespace.

**`SetupOsMmapSyscallVsMemoryPressureTest`** — Compares process-level memory pressure (cgroup limits, `-Xmx`) vs syscall-level `mmap()` injection and proves they are not equivalent. Process limits kill the entire pod. Syscall injection is surgical.

**`SetupOsPerFdFaultInjectionTest`** — Demonstrates per-file-descriptor fault injection — faults apply only to specific socket file descriptors, not all network I/O. No LD\_PRELOAD library in any other open-source chaos tool achieves this.

**`SetupOsPerThreadClockImpossibilityTest`** — Shows why per-thread `clock_gettime()` interception requires the syscall-level layer (LD\_PRELOAD). ByteBuddy can instrument `System.currentTimeMillis()` but `clock_gettime()` is a VDSO-mapped function that never enters user space — it cannot be intercepted by any JVM-level approach.

**`SetupOsSessionIsolationArchitectureTest`** — Walks through the session isolation implementation: `ThreadLocal<String>` session ID, task decoration for propagation through executors, and proof that chaos on session A cannot affect requests on session B even in the same JVM.

**`SetupOsSyscallSequenceStateMachineTest`** — Implements a state-machine fault pattern: succeeds on calls 1-9, fails on call 10, succeeds on 11-19, fails on 20 — reproducing "fails every 10th request" bugs precisely.

**`SetupOsThreeFaultPlaneCoordinationTest`** — Coordinates faults across all three layers simultaneously: C99 syscall (recv failure), JVM bytecode (delay on executor submit), and framework (JDBC rejection) — three planes firing at precise intervals to reproduce a compound production incident.

---

## `showstoppers/` — 11 Tests

Scenarios where the failure mode is so severe that it constitutes a showstopper for any system not explicitly hardened against it.

**`ShowstopperDistributedLockSplitBrainTest`** — A GC pause longer than the distributed lock TTL causes two nodes to simultaneously believe they hold the lock. Both process the same work. Data corruption is guaranteed. Monitoring shows zero errors from both leaders — they both succeed. The duplication surfaces later in audits.

**`ShowstopperL10DistributedSplitBrainProofTest`** — Measures the exact split-brain window duration in milliseconds under safepoint pressure, calculates the number of duplicate operations at production QPS, and proves the window is non-zero. At 10,000 req/s and a 100ms split-brain window: 1,000 duplicate operations per incident.

**`ShowstopperDeadlockSurgeryTest`** — Injects a real JVM monitor deadlock and proves that the deadlock detection API, the correct fix pattern, and the recovery path all work as specified. Also proves which deadlock patterns `findDeadlockedThreads()` can and cannot detect.

**`ShowstopperVirtualThreadCarrierProofTest`** — Directly proves that `synchronized` blocks pin virtual thread carriers and quantifies the maximum concurrent virtual threads the application can sustain before all carriers are pinned. This is the number that determines the real (not theoretical) virtual thread capacity of the service.

**`ShowstopperJitSuppressionProofTest`** — Proves that JIT compilation suppression (code cache filled, compilation threshold not reached, or `-Xint` mode) causes a specific and measurable performance regression. Before this test existed, JIT suppression in production was diagnosed by asking "when did the last deploy happen" and hoping.

**`ShowstopperSafepointVirtualVsPlatformTest`** — Proves the difference in safepoint behavior between virtual and platform threads under identical safepoint storm pressure. Virtual threads do not participate in safepoints the same way platform threads do — this test quantifies the difference and its implications for GC pause duration.

**`ShowstopperKafkaZeroMessageLossProofTest`** — Proves that exactly-once semantics hold under producer timeout, broker leadership change, and consumer rebalance — simultaneously. Most Kafka loss scenarios are single-fault. This test runs all three at once.

**`ShowstopperFeignAmplificationStormTest`** — A Feign client with retry-on-exception retries all transient failures. Under partial degradation (30% failure rate), every failed request retries 3 times, multiplying load on the degraded upstream by 3x. The upstream: now at 100% capacity from retries alone. Original load: still arriving. Upstream OOM. Feign amplification killed both services.

**`ShowstopperMetaspaceGlacierMeasurementTest`** — Measures the exact rate of Metaspace growth under hot reload and calculates the time-to-OOM. Most teams discover Metaspace leaks after the OOM. This test surfaces the growth rate in CI and makes it a PR-blocking assertion.

**`ShowstopperOsivBeforeAfterProofTest`** — Proves that Open Session In View anti-pattern keeps Hibernate sessions open across the entire HTTP request/response cycle, including serialization. Under a lazy-load fetch inside a `@RestController`: a second database query fires outside the transactional boundary, silently. This test makes that invisible database call visible.

**`ShowstopperThreeFaultPlanesTest`** — Runs C99 syscall, JVM bytecode, and Spring framework faults simultaneously and proves the application handles all three without a single HTTP 500. Any 500 = unhandled compound failure. The three-plane test is the highest-fidelity production simulation in the repository.

---

## `l3/` — 17 Tests

Integration-level incident tests. Each base test covers the known failure mode; each L10 variant covers the compound or second-order failure that the base test misses.

**`L3JdbcIncidentsTest`** + **`L3JdbcL10IncidentsTest`** — Base: connection storm and sequence ID gaps. L10: zombie transactions after timeout (pool permanently broken), and defensive ID-jump check kills the service during failover — the check was added to prevent data corruption and now causes the outage it was protecting against.

**`L3HttpIncidentsTest`** + **`L3HttpL10IncidentsTest`** — Base: TCP RST after headers, slow TTFB. L10: HTTP/2 GOAWAY silently drops in-flight requests, and retry amplification multiplies request load 4x causing both caller and callee to OOM.

**`L3KafkaIncidentsTest`** + **`L3KafkaL10IncidentsTest`** — Base: partition rebalance, offset commit failure. L10: full rebalance storm producing 50,000-message backlog, and EOS producer timeout causing `OutOfOrderSequenceException` with a 5-second silent message loss window.

**`L3SpringAllIncidentsTest`** + **`L3SpringL10IncidentsTest`** — Base: `@Transactional` self-invocation, bean initialization order. L10: transactional timeout zombie connections that permanently poison the connection pool, and config refresh partial initialization that fires the circuit breaker on a healthy Redis.

**`L3CacheAllIncidentsTest`** + **`L3CacheL10IncidentsTest`** — Base: cache miss cascade, stale-read window. L10: cache stampede (100% cold start sends 200x normal QPS to database), and Redis pipeline partial failure causing silent double-write on retry.

**`L3JvmAllIncidentsTest`** + **`L3JvmL10IncidentsTest`** — Base: GC pause, Metaspace growth. L10: GC pause exceeding TCP keepalive timeout drops all 10 database pool connections producing 50-second DB unavailability, and ClassLoader retention via Spring bean factory accumulates 50 ClassLoaders × 50MB = 2.5GB Metaspace.

**`L3FeignIncidentsTest`** — Feign retry amplification, circuit breaker timing, and the specific failure mode where Feign's default error decoder retries on every `5xx` including `503` — which is exactly what the circuit breaker sends to signal "stop retrying."

**`L3GrpcIncidentsTest`** — gRPC deadline propagation failure (deadline not inherited by child calls), and stream half-close race (server closes stream while client is still writing, causing silent data loss on the client side).

**`L3RedisAllIncidentsTest`** — Redis WAIT command timeout during replication lag, Lua script timeout causing BUSY error on all subsequent commands, and OBJECT ENCODING change causing silent performance regression when a hash exceeds `hash-max-listpack-entries`.

**`L3KubernetesAllIncidentsTest`** — Pod readiness probe racing with application initialization (requests hit a pod that passes health checks but hasn't finished loading), and graceful shutdown race (SIGTERM sent while requests are in-flight, connection terminated before response sent).

**`L3ProductionIncidentTest`** — A composite multi-layer incident: JDBC slow query + Kafka consumer lag + HTTP upstream timeout all occur simultaneously. Each alone: recoverable. Combined: total service failure. This is the test that matches what production incidents actually look like.

---

## `redis/` — 9 Tests

**`RedisStandaloneBasicTest`** / **`RedisStandaloneAllVersionsTest`** — Baseline connectivity and fault injection across Redis 6.x, 7.x, and 7.4 — proving that chaos annotations work identically regardless of Redis version.

**`MultipleRedisStandalonesTest`** / **`RedisFiveInstancesTest`** — Multi-instance topologies, proving that per-instance chaos targeting (fault only `cache`, not `session`) holds under parallel test execution.

**`RedisSentinelHighAvailabilityTest`** / **`RedisSentinelAllTopologiesTest`** — Sentinel failover proof: primary death, election, DNS propagation, and client reconnection — measuring the exact window during which requests fail and proving the circuit breaker activates before user-facing timeouts.

**`RedisHaFailoverProofTest`** — Proves that zero requests fail if the circuit breaker is correctly configured, by measuring the exact time between primary death and circuit breaker activation and asserting it is less than the client timeout.

**`RedisMixedTopologyTest`** — Standalone, Sentinel, and Cluster topologies in a single test class, proving topology-transparent chaos targeting — same annotations, different underlying Redis architectures.

**`RedisL3AllSixIncidentsDeepDiveTest`** — Six Redis-specific production incidents in one test class: WAIT timeout, OBJECT ENCODING regression, Lua BUSY, AUTH failure during rotation, cluster node disappearance, and replica promotion stale-read window.

---

## `l2/` — 8 Tests (All Rewritten as L8+ Post-Mortems)

**`L2JavaComposites`** — The "2AM GC storm": heap pressure cascading into GC thrashing, which causes connection timeouts, which opens circuit breakers, which cascades across the service graph. The "VT + synchronized Redis client": carrier pinning under ForkJoinPool starvation. The "thread leak + heap compound": two stressors that individually are recoverable, together are not.

**`L2ConnectionAllComposites`** — 14 connection-level compound scenarios. Every test represents a production topology: rolling update RST storm, post-partition connection pool recovery, DB lock contention + cache invalidation thundering herd, BGP route withdrawal, TIME\_WAIT saturation.

**`L2DnsComposites`** — CoreDNS eviction during pod scale-out, ndots:5 amplification under DNS latency (every hostname triggers 5 DNS lookups due to search domain expansion), cluster upgrade compound DNS storm.

**`L2FilesystemComposites`** — WAL torn write on degraded SSD, log volume full causing request thread stall, NFS mount write spike blocking GC, ConfigMap not mounted causing `FileNotFoundException` at startup.

**`L2MemoryComposites`** — cgroup memory limit with green heap metrics (the lie), off-heap native library accumulation, checksum corruption at 10% of writes silently producing wrong data.

**`L2ProcessComposites`** — Kubernetes `RLIMIT_NPROC` cgroup limit shrinking during pod scale-down, shared-node PID capacity exhaustion causing `EAGAIN` on thread creation, long-running subprocess fork blocking under load.

**`L2TimeComposites`** — VM live migration TSC drift (host clock changes during migration, guest sees discontinuous time), NTP backward step causing negative Redis TTL (key expires immediately when TTL is calculated as negative), IERS leap second producing 847 duplicate processed events.

**`L2CompositeAnnotationTest`** — Composite chaos annotations (`@CompositeChaosJvmHighHeapPressure` etc.) proving that annotation composition is a first-class primitive: one annotation activates multiple correlated stressors as a unit.

---

## `c99/` — 6 Tests (All Rewritten as L8+ Production Incidents)

These tests use the C99 LD\_PRELOAD layer — the only approach that intercepts failures below the JVM, below the network stack, at the libc syscall boundary.

**`LibchaosNetAllConfigsTest`** — Three network incidents: the Black Friday RST storm where retry amplification made the recovery worse than the outage; the keep-alive connection poison where only existing connections fail but new ones are clean; the EAGAIN buffer stall where NIO treats temporary unavailability as EOF.

**`LibchaosDnsAllConfigsTest`** — NXDOMAIN during a 30-second deployment window, cached for 300 seconds by clients: the DNS fix takes 30 seconds, the outage lasts 5 minutes. DNS timeout cascade where 30-second `getaddrinfo()` timeouts exhaust the thread pool in 25 seconds. DNS A-record flip with keep-alive connections: new connections route correctly, keep-alive connections route to the dead pod.

**`LibchaosIoAllConfigsTest`** — `read()` partial return silently truncating JSON (POSIX allows partial reads but most code assumes complete fills). `write()` `ENOSPC` mid-WAL corrupting the write-ahead log. `fsync()` `EIO` on degraded EBS with applications that do not check the `fsync()` return value.

**`LibchaosMemoryAllConfigsTest`** — `mmap()` `ENOMEM` only above 1MB threshold: Netty HTTP/2 frame buffers fail, heap monitoring shows 60% (green), pod OOM-killed. `malloc()` `ENOMEM` surfacing as `NullPointerException` in JNI callers that do not check for NULL. The exact 512KB `mmap()` boundary where Kubernetes cgroup limits interact with Linux huge-page allocation.

**`LibchaosProcessAllConfigsTest`** — `pthread_create()` `EAGAIN` at the cgroup thread limit: passes load test (dev, no limit), crashes in production (Kubernetes `threads: 500`). `fork()` under multithreaded JVM: child inherits locked mutexes, deadlocks on first `malloc()`. `clone()` `EPERM` from a restrictive seccomp profile: JVM fails to start, error message says "unable to create native thread" (not "permission denied").

**`LibchaosTimeAllConfigsTest`** — NTP leap second causing Kafka offset timestamp jump: consumer group rebalances, 2 hours of duplicate processing. Per-service clock skew of +500ms: event ordering assumptions break, 0.5% of requests process out of order, silent data corruption discoverable only in quarterly audit. `nanosleep()` quantization on a loaded Kubernetes node: 1ms sleep becomes 10ms, rate limiter allows 10x burst, downstream OOM.

---

## `l1/` — 1 Test

**`L1SurgicalAnnotationTest`** — Three production incident proofs using surgical per-operation fault injection:

1. **Stripe 2023**: 30% `ECONNRESET` on `recv()` — proves circuit breaker opens within 10 failures and fallback activates. Without this test: you are guessing your Resilience4j sliding-window config actually works.

2. **AWS us-east-1 2021**: 20% `EAGAIN` + 10% `ECONNREFUSED` simultaneously (combined effective failure rate: 28%). Proves zero HTTP 500 responses under dual fault mode. Every application that tested single-fault scenarios passed QA and failed in production that day.

3. **Redis Sentinel failover lag**: 50% of Redis reads take 100ms+ during new primary propagation. Proves slow-call detection in the circuit breaker actually triggers the cache-to-DB fallback path — not just that the circuit breaker is configured, but that it fires.

---

## `sla/` — 2 Tests

**`SlaProofTest`** — Baseline SLA verification under single fault injection. p99 < 500ms under 30% packet drop. Starting point for the SLA contract layer.

**`SlaL10ProductionContractTest`** — Three compound SLA proofs:

1. p99 < 2,000ms under simultaneous heap pressure (70%) + recv latency (+100ms) + code cache pressure. The AWS 2022 incident: each stressor individually was within SLA. Combined: p99 = 8 seconds.

2. Zero HTTP 500s across 500 requests under 5 simultaneous fault stressors. Any 500 = unhandled exception reaching the user.

3. Recovery time < 5 seconds after fault removal. The implicit SLA clause: after an outage clears, how long until p99 is healthy? If fault lasts 10 seconds and recovery takes 60 seconds (circuit breaker half-open + JIT recompile + connection pool replenishment): actual customer impact is 70 seconds.

---

## `patterns/` — 2 Tests

**`TemporalPatternTest`** — Baseline temporal chaos patterns: rate limiter behavior under time pressure, retry backoff precision.

**`TemporalL10PatternTest`** — Three production temporal disasters:

1. **Distributed lock + GC**: safepoint pause > lock TTL → two simultaneous leaders → silent data corruption at 10,000 req/s.

2. **JWT expiry race**: token valid at authentication, expired by authorization check (600ms later under DB pressure). Two microservices: one says valid, one says expired. Request: partially processed. Security state: inconsistent.

3. **Correlated backoff thundering herd**: 10 service instances all start exponential backoff at the same time (same deployment trigger). Retry waves at second 1, 2, 4, 8: synchronized load spikes that grow exponentially. Downstream OOM at the 8-second wave.

---

## `jdbcdeadlock/` — 2 Tests

**`JdbcPoolDeadlockIT`** — Classic HikariCP nested transaction deadlock: outer transaction holds one connection, inner `REQUIRES_NEW` needs a second, pool size = 1. Engineers see `ConnectionTimeoutException` with no database deadlock in `pg_locks`. Both correct — the deadlock is in the pool, not the database.

**`JdbcL10DeadlockTest`** — Three second-order JDBC disasters:

1. `REQUIRES_NEW` pool deadlock measured precisely: connection contention time, which request times out first, and whether the pool recovers.

2. Database deadlock + immediate retry = retry amplification. Both transactions deadlock, both retry immediately, same rows, same deadlock. Infinite loop. Engineers see "deadlock detected" in logs but not "1,000 deadlock events in 30 seconds." The retry logic is the actual outage.

3. Read-replica stale read: write to primary, read from replica 100ms later. Replication lag: 200ms. Stale read rate: >50%. This is the read-your-writes consistency violation that exists in every application using read replicas, encountered by every engineer, and understood by almost none.

---

## Running the Examples

All tests require the three-layer chaos stack:

```bash
# Build everything from the repo root
./gradlew :testing-framework:macstab-chaos-examples:test

# Run a specific directory
./gradlew :testing-framework:macstab-chaos-examples:test --tests "com.macstab.chaos.examples.insane.*"

# Run a single test
./gradlew :testing-framework:macstab-chaos-examples:test --tests "com.macstab.chaos.examples.insane.InsaneVtCarrierPinningStormTest"
```

The C99 tests (`c99/`) require the LD\_PRELOAD libraries to be built and available on the container image. The JVM tests (`l1/` through `insane/`) self-attach the ByteBuddy agent via the test starter — no `-javaagent` flag required.

---

## License

Apache License 2.0 — see [LICENSE](LICENSE).

---

## About the Engineer

This example repository — and the three-layer chaos stack it demonstrates — is the work of one engineer: **Christian Schnapka**, Hamburg, Germany.

| Year | What I was shipping |
|---|---|
| **1984** *(age 10)* | 6502 assembler on the Commodore 64 |
| **1987** *(age 14)* | Motorola 68000 on the Commodore Amiga |
| **1989** *(from age 15)* | International demoscene — **Razor 1911**, **Sanity**, **Anthrox**, **Incal** |
| **1990** | x86 assembler + C / C++ · game studios in Germany and Birmingham, UK |
| **1996** | Java — since 1.0, 30 years and counting |
| **2008** | LXC · Docker since first release · Kubernetes from ~2014 |

42 years of programming. 30 years of enterprise software. The depth behind these examples — JVM internals, POSIX fork semantics, G1 Remembered Set internals, ZGC load barrier bypass, epoll edge-trigger race conditions, Linux VMA limits, biased lock revocation protocol — comes from a path that started with 6502 assembler at 10 and ran through game studios that shipped on cartridges with no patch button.

Most engineers enter at the framework layer and look down. This stack reads from below.

**[macstab.com](https://macstab.com)** · **info@macstab.com** · **[GitHub @macstab](https://github.com/macstab)**

---

<div align="center">

**[Christian Schnapka](https://macstab.com)**
Principal+ Engineer
[Macstab GmbH](https://macstab.com) · Hamburg, Germany

*The failures that page your on-call at 3 AM become commits that fail in PR review.*

</div>
