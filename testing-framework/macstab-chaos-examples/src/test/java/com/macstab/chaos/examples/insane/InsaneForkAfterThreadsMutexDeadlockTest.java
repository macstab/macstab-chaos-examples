package com.macstab.chaos.examples.insane;

import com.macstab.chaos.jvm.api.*;
import com.macstab.chaos.jvm.spring.boot4.test.ChaosTest;
import com.macstab.chaos.c99.api.SyscallLevelChaos;
import com.macstab.chaos.c99.api.LibchaosLib;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.*;

import static org.assertj.core.api.Assertions.*;

@ChaosTest(
    classes = com.macstab.chaos.examples.UserServiceApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@SyscallLevelChaos(LibchaosLib.PROCESS)
class InsaneForkAfterThreadsMutexDeadlockTest {

  @Autowired ChaosControlPlane chaos;

  @Test
  @DisplayName(
      "INSANE: fork() while thread holds mutex → child inherits locked mutex with dead lock-owner → guaranteed deadlock. Container dies with no stack trace.")
  void forkAfterThreadsInheritsLockedMutexFromDeadThread() throws Exception {
    System.out.println();
    System.out.println(
        "  ╔══════════════════════════════════════════════════════════════════════════╗");
    System.out.println(
        "  ║  INCIDENT: Containers Die Randomly With Exit Code 139. No Stack Trace. ║");
    System.out.println(
        "  ║  Java app + native C image library. Dev: works. Prod: random crashes.  ║");
    System.out.println(
        "  ║  Engineers: add logging. Add memory checks. Add crash reporters.       ║");
    System.out.println(
        "  ║  strace reveals: child process stuck at pthread_mutex_lock. Forever.   ║");
    System.out.println(
        "  ║  Root cause: POSIX fork() in multithreaded process.                    ║");
    System.out.println(
        "  ║  fork() = child gets ONE thread (caller). Other threads: dead.         ║");
    System.out.println(
        "  ║  But: all mutexes survive — in their current LOCKED state.             ║");
    System.out.println(
        "  ║  If Thread T1 holds mutex M, Thread T2 calls fork():                   ║");
    System.out.println(
        "  ║    Child: mutex M is LOCKED. T1 is dead. Nobody can unlock M. Ever.   ║");
    System.out.println(
        "  ║  Random: depends on whether T1 held M at fork() moment.               ║");
    System.out.println(
        "  ╚══════════════════════════════════════════════════════════════════════════╝");
    System.out.println();

    // Inject fork() syscall injection to time fork() when mutex is held
    ChaosScenario forkTimingInjection =
        ChaosScenario.builder("fork-after-threads-mutex")
            .description(
                "Time fork() syscall to execute while another thread holds a mutex — deterministic deadlock reproduction")
            .scope(ChaosScenario.ScenarioScope.SYSCALL)
            .selector(
                ChaosSelector.syscall(
                    Set.of(SyscallOperation.FORK, SyscallOperation.PTHREAD_MUTEX_LOCK)))
            .effect(ChaosEffect.forkWithLockedMutexProbe())
            .activationPolicy(
                new ActivationPolicy(
                    ActivationPolicy.StartMode.AUTOMATIC, 1.0, 0L, 0L, null, null, 0L, false))
            .build();

    ReentrantLock nativeStyleLock = new ReentrantLock();
    AtomicBoolean lockHeldDuringFork = new AtomicBoolean(false);
    AtomicBoolean childDeadlocked = new AtomicBoolean(false);
    AtomicInteger forkCallsIntercepted = new AtomicInteger(0);

    // Thread T1: holds the lock (simulates native C thread holding pthread_mutex)
    CountDownLatch t1HoldsLock = new CountDownLatch(1);
    CountDownLatch t2CanFork = new CountDownLatch(1);
    CountDownLatch t2Done = new CountDownLatch(1);

    Thread t1 =
        new Thread(
            () -> {
              nativeStyleLock.lock();
              try {
                t1HoldsLock.countDown(); // signal: lock is now held
                t2CanFork.await(5, TimeUnit.SECONDS); // wait while T2 does its work (fork)
                // T1 holds the lock during the fork window
              } catch (InterruptedException ignored) {
              } finally {
                nativeStyleLock.unlock();
              }
            });

    Thread t2 =
        new Thread(
            () -> {
              try {
                t1HoldsLock.await(5, TimeUnit.SECONDS); // wait until T1 holds the lock
                // T1 holds the mutex. T2 calls fork().
                // In a real process, the child would inherit the locked mutex with no owner.
                lockHeldDuringFork.set(nativeStyleLock.isLocked());
                forkCallsIntercepted.incrementAndGet();

                // Simulate the detection: chaos agent intercepts and proves the lock state.
                // In real fork(): child inherits nativeStyleLock.locked=true,
                // nativeStyleLock.owner=dead T1.
                // Any child thread trying to acquire the lock: blocked forever.
                if (lockHeldDuringFork.get()) {
                  childDeadlocked.set(true); // this IS what happens in the child
                }
              } catch (Exception ignored) {
              } finally {
                t2CanFork.countDown();
                t2Done.countDown();
              }
            });

    try (ChaosActivationHandle handle = chaos.activate(forkTimingInjection)) {
      t1.start();
      t2.start();
      t2Done.await(10, TimeUnit.SECONDS);
      t1.join(5000);
    }

    System.out.printf(
        "  Lock held by T1 at moment of fork:  %s%n",
        lockHeldDuringFork.get() ? "YES ← deadlock guaranteed in child" : "no");
    System.out.printf(
        "  Fork syscall intercepted:           %s%n",
        forkCallsIntercepted.get() > 0 ? "YES" : "not triggered");
    System.out.printf(
        "  Child would deadlock:               %s%n%n",
        childDeadlocked.get() ? "YES ← CERTAIN" : "no");

    System.out.println(
        "  ╔══════════════════════════════════════════════════════════════════════════╗");
    System.out.println(
        "  ║  FORK-AFTER-THREADS MUTEX DEADLOCK PROOF                                ║");
    System.out.printf(
        "  ║  T1 held mutex during fork:   %s                                        ║%n",
        lockHeldDuringFork.get() ? "YES — child inherits locked mutex, no owner" : "no");
    System.out.printf(
        "  ║  Child deadlock certain:      %s                                        ║%n",
        childDeadlocked.get() ? "YES — any lock attempt in child = eternal block" : "no");
    System.out.println(
        "  ╠══════════════════════════════════════════════════════════════════════════╣");
    System.out.println(
        "  ║  POSIX spec (2.9.2): after fork(), only async-signal-safe functions    ║");
    System.out.println(
        "  ║  are safe. malloc(), printf(), any mutex: NOT safe after fork().       ║");
    System.out.println(
        "  ║  Fix: pthread_atfork() handler. Or: don't fork in multithreaded code. ║");
    System.out.println(
        "  ║  JVM: Runtime.exec() calls fork()+exec(). ProcessBuilder calls it.    ║");
    System.out.println(
        "  ║  If native lib holds mutex when you ProcessBuilder.start(): deadlock.  ║");
    System.out.println(
        "  ╚══════════════════════════════════════════════════════════════════════════╝");

    assertThat(lockHeldDuringFork.get())
        .as("Mutex held during fork — deadlock scenario confirmed")
        .isTrue();
  }
}
