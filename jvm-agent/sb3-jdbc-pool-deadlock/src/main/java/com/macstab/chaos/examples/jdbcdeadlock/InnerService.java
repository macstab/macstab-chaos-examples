package com.macstab.chaos.examples.jdbcdeadlock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Inner transactional service that runs in its own transaction (REQUIRES_NEW).
 *
 * Because REQUIRES_NEW suspends the caller's transaction and opens a brand-new one,
 * it requires a second, independent JDBC connection C2 from the pool at the same time
 * the caller still holds C1.  This is the root cause of the pool deadlock.
 */
@Service
public class InnerService {

    private static final Logger log = LoggerFactory.getLogger(InnerService.class);

    private static final String INSERT_EVENT =
            "INSERT INTO order_events (order_id, event_type) VALUES (?, 'ORDER_CREATED')";

    private final JdbcTemplate jdbc;

    public InnerService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Runs in a new, independent transaction — acquires connection C2 from the pool.
     * If the pool is fully occupied by outer transactions waiting for C2, this call
     * will block until the pool times out and throws a {@link java.sql.SQLTimeoutException}.
     *
     * @param orderId the order to record the event for
     * @return the generated event id
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public long saveWithNewTransaction(long orderId) {
        jdbc.update(INSERT_EVENT, orderId);
        Long eventId = jdbc.queryForObject(
                "SELECT id FROM order_events WHERE order_id = ? ORDER BY id DESC LIMIT 1",
                Long.class,
                orderId
        );
        log.debug("Event recorded for order {}, eventId={}", orderId, eventId);
        return eventId != null ? eventId : -1L;
    }
}
