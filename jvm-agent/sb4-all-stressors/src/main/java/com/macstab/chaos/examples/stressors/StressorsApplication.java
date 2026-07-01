package com.macstab.chaos.examples.stressors;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot 4 application that hosts a minimal REST surface used by the
 * {@code AllStressorsIT} integration test to exercise every chaos stressor
 * the agent ships: heap, keepalive, metaspace, direct-buffer, GC pressure,
 * finalizer backlog, deadlock, thread leak, ThreadLocal leak, monitor
 * contention, code cache, safepoint storm, string intern pressure, and
 * reference queue flood.
 *
 * <p>Virtual threads are enabled globally via
 * {@code spring.threads.virtual.enabled=true} so that Tomcat's request
 * threads are virtual, giving each stressor a realistic multi-threaded
 * context without a large platform-thread pool.
 */
@SpringBootApplication
public class StressorsApplication {

    public static void main(String[] args) {
        SpringApplication.run(StressorsApplication.class, args);
    }
}
