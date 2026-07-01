package com.macstab.chaos.examples.jdbcdeadlock;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Demonstrates JDBC connection pool deadlock caused by nested @Transactional(REQUIRES_NEW)
 * when pool-size < 2 × max_concurrent_outer_transactions.
 *
 * With pool-size=5 and 10 concurrent outer transactions, each outer holds C1 and waits
 * for C2 for the inner REQUIRES_NEW. If all 5 pool slots are held by outer transactions,
 * inner transactions starve and the pool times out after 30 seconds.
 */
@SpringBootApplication
public class JdbcPoolDeadlockApplication {

    /**
     * HikariCP pool size, must be >= 2 × max concurrent outer transactions to avoid deadlock.
     * Set to 5 in application.properties to demonstrate the problem with 10 concurrent triggers.
     */
    public static final int POOL_SIZE = 5;

    public static void main(String[] args) {
        SpringApplication.run(JdbcPoolDeadlockApplication.class, args);
    }
}
