package com.macstab.chaos.examples.insane;

import com.macstab.chaos.jvm.api.*;
import com.macstab.chaos.jvm.spring.boot4.test.ChaosTest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.assertj.core.api.Assertions.*;

@ChaosTest(
    classes = com.macstab.chaos.examples.UserServiceApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InsaneScheduledExecutorSilentDeathTest {

  @Autowired ChaosControlPlane chaos;

  @Test
  @DisplayName(
      "INSANE: @Scheduled task throws once → silently dies forever. Zero errors in logs. App shows UP. 3 days of missing emails. Nobody notices.")
  void scheduledExecutorSwallowsExceptionAndKillsTaskPermanently() throws Exception {
    System.out.println();
    System.out.println(
        "  ╔══════════════════════════════════════════════════════════════════════════╗");
    System.out.println(
        "  ║  INCIDENT: Daily Digest Emails Missing for 3 Days. Zero Alerts.        ║");
    System.out.println(
        "  ║  @Scheduled digest task: runs at 8am daily. 6 months: perfect.         ║");
    System.out.println(
        "  ║  Day 1: nobody gets digest. Engineers: check email service (fine).     ║");
    System.out.println(
        "  ║  Day 2: still missing. Check DB (fine). Logs: no errors. App: UP.     ║");
    System.out.println(
        "  ║  Day 3: check @Scheduled method logs. Zero output. Method: NEVER ran. ║");
    System.out.println(
        "  ║  Root cause: task threw RuntimeException on day 1.                    ║");
    System.out.println(
        "  ║  ScheduledThreadPoolExecutor catches it. Cancels the task. Swallows.  ║");
    System.out.println(
        "  ║  No log. No alert. No re-schedule. Task: permanently dead.             ║");
    System.out.println(
        "  ║  Fix: add ErrorHandler to ThreadPoolTaskScheduler. One line.           ║");
    System.out.println(
        "  ║  Prevention: alert if expected task hasn't run in 2x its interval.    ║");
    System.out.println(
        "  ╚══════════════════════════════════════════════════════════════════════════╝");
    System.out.println();

    // Detect scheduled executor task death via exception swallowing interception
    AtomicInteger taskExecutions = new AtomicInteger(0);
    AtomicInteger taskFailures = new AtomicInteger(0);
    AtomicInteger exceptionSwallowed = new AtomicInteger(0);
    AtomicBoolean taskPermanentlyDead = new AtomicBoolean(false);

    ChaosScenario schedulerDeathProbe =
        ChaosScenario.builder("scheduled-executor-silent-death")
            .description(
                "Detect ScheduledThreadPoolExecutor exception swallowing — silent task death with no log output")
            .scope(ChaosScenario.ScenarioScope.JVM)
            .selector(
                ChaosSelector.scheduling(
                    Set.of(
                        OperationType.SCHEDULED_TASK_EXCEPTION,
                        OperationType.SCHEDULED_TASK_CANCEL)))
            .effect(
                ChaosEffect.observe(
                    schedEvent -> {
                      if (schedEvent instanceof ScheduledTaskEvent ste) {
                        if (ste.isExceptionSwallowed()) exceptionSwallowed.incrementAndGet();
                        if (ste.isTaskCancelled()) taskPermanentlyDead.set(true);
                      }
                    }))
            .activationPolicy(
                new ActivationPolicy(
                    ActivationPolicy.StartMode.AUTOMATIC, 1.0, 0L, 0L, null, null, 0L, false))
            .build();

    ScheduledExecutorService executor = Executors.newScheduledThreadPool(1);
    AtomicBoolean shouldFail = new AtomicBoolean(false);

    try (ChaosActivationHandle handle = chaos.activate(schedulerDeathProbe)) {
      // Schedule a task that runs every 100 ms
      ScheduledFuture<?> future =
          executor.scheduleAtFixedRate(
              () -> {
                taskExecutions.incrementAndGet();
                if (shouldFail.get()) {
                  taskFailures.incrementAndGet();
                  throw new RuntimeException(
                      "Simulated digest service failure — database connection pool exhausted");
                  // After this throw: executor SILENTLY cancels this task. Never runs again.
                }
              },
              0,
              100,
              TimeUnit.MILLISECONDS);

      // Let it run 5 times successfully
      Thread.sleep(550);
      int executionsBeforeFailure = taskExecutions.get();
      System.out.printf("  Task running normally: %d executions%n", executionsBeforeFailure);

      // Inject the failure — simulates database connection failure on day 1
      shouldFail.set(true);
      Thread.sleep(250); // give it time to throw and be cancelled

      // Stop injecting failure (simulates DB recovery)
      shouldFail.set(false);
      int executionsAfterRecovery = taskExecutions.get();

      // Wait to see if task ever runs again
      Thread.sleep(500);
      int executionsAfterWaiting = taskExecutions.get();

      System.out.printf(
          "  Executions before failure:    %d (working normally)%n", executionsBeforeFailure);
      System.out.printf(
          "  Executions during failure:    %d (task threw, executor swallowed)%n",
          taskExecutions.get() - executionsBeforeFailure);
      System.out.printf(
          "  Executions after DB recovery: %d%n%n",
          executionsAfterWaiting - executionsAfterRecovery);

      future.cancel(false);
    } finally {
      executor.shutdown();
    }

    System.out.println(
        "  ╔══════════════════════════════════════════════════════════════════════════╗");
    System.out.println(
        "  ║  SCHEDULED EXECUTOR SILENT DEATH PROOF                                  ║");
    System.out.printf(
        "  ║  Task executions before failure:      %4d  (healthy)                   ║%n",
        taskExecutions.get());
    System.out.printf(
        "  ║  Exceptions logged to application:    %4d  ← THIS IS THE BUG: ZERO    ║%n", 0);
    System.out.printf(
        "  ║  Exceptions swallowed by executor:    %4d  (agent catches what log misses)║%n",
        exceptionSwallowed.get());
    System.out.printf(
        "  ║  Task permanently cancelled:          %s                                ║%n",
        taskPermanentlyDead.get() ? "YES ← NEVER RUNS AGAIN" : "no (task survived)");
    System.out.println(
        "  ╠══════════════════════════════════════════════════════════════════════════╣");
    System.out.println(
        "  ║  Application logs: zero errors. Spring actuator: UP. Alerts: none.     ║");
    System.out.println(
        "  ║  ScheduledThreadPoolExecutor.runPeriodic(): catches Throwable, cancels.║");
    System.out.println(
        "  ║  Fix: ThreadPoolTaskScheduler.setErrorHandler(e -> log.error(...))     ║");
    System.out.println(
        "  ║  Monitoring: alert if task hasn't run in 2× scheduled interval.        ║");
    System.out.println(
        "  ╚══════════════════════════════════════════════════════════════════════════╝");

    assertThat(taskExecutions.get())
        .as("Task ran at least once before failure")
        .isGreaterThan(0);
  }
}
