package com.macstab.chaos.examples.effects;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot 4 application hosting the service endpoints used to demonstrate all 22
 * chaos effects available in the macstab JVM agent.
 *
 * <p>Configuration highlights:
 * <ul>
 *   <li>Virtual threads enabled via {@code spring.threads.virtual.enabled=true}</li>
 *   <li>HikariCP pool capped at 20 connections</li>
 *   <li>H2 in-memory database for JDBC exercises</li>
 * </ul>
 */
@SpringBootApplication
public class AllEffectsApplication {

    /** Entry point — delegates to Spring Boot. */
    public static void main(String[] args) {
        SpringApplication.run(AllEffectsApplication.class, args);
    }
}
