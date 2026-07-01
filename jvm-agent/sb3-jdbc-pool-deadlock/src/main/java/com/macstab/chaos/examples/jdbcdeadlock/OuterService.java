package com.macstab.chaos.examples.jdbcdeadlock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Outer transactional service.  Acquires connection C1 at transaction open, holds it for
 * the full duration including the sleep, then calls InnerService which needs a second
 * connection C2 via REQUIRES_NEW.
 *
 * With pool-size=5 and >= 5 concurrent callers, all 5 pool slots are occupied by outer
 * transactions while each waits for a free slot for the inner — a classic JDBC pool deadlock.
 */
@Service
public class OuterService {

    private static final Logger log = LoggerFactory.getLogger(OuterService.class);

    private static final String INSERT_ORDER =
            "INSERT INTO orders (order_ref, status) VALUES (?, 'PENDING')";

    private static final int HOLD_MILLIS = 200;

    private final JdbcTemplate jdbc;
    private final InnerService innerService;

    public OuterService(JdbcTemplate jdbc, InnerService innerService) {
        this.jdbc = jdbc;
        this.innerService = innerService;
    }

    /**
     * Opens a transaction (acquires C1), inserts into orders, sleeps 200ms while holding C1,
     * then calls innerService which needs a second connection from the pool (REQUIRES_NEW).
     *
     * @param ref arbitrary order reference string
     * @return the generated order id
     */
    @Transactional
    public long processOrder(String ref) {
        // C1 is acquired here by the transaction manager
        jdbc.update(INSERT_ORDER, ref);
        long orderId = jdbc.queryForObject(
                "SELECT id FROM orders WHERE order_ref = ? ORDER BY id DESC LIMIT 1",
                Long.class,
                ref
        );

        log.debug("Order {} inserted with id={}, holding connection for {}ms", ref, orderId, HOLD_MILLIS);

        // Simulate processing time — connection C1 remains checked out the whole time
        try {
            Thread.sleep(HOLD_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while holding connection", e);
        }

        // Needs C2 from pool via REQUIRES_NEW — will block if pool is exhausted
        innerService.saveWithNewTransaction(orderId);

        log.debug("Order {} processing complete", ref);
        return orderId;
    }
}
