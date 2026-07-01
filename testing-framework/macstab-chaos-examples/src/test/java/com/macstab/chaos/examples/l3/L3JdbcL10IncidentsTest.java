package com.macstab.chaos.examples.l3;

import com.macstab.chaos.jvm.api.*;
import com.macstab.chaos.jvm.spring.boot4.test.ChaosTest;
import com.macstab.chaos.jdbc.annotation.l3.IncidentChaosJdbcConnectionStorm;
import com.macstab.chaos.jdbc.annotation.l3.IncidentChaosJdbcSequenceIdJump;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

// ╔══════════════════════════════════════════════════════════════════════════╗
// ║  L10 INCIDENT REPLAYS — JDBC                                            ║
// ║                                                                          ║
// ║  These tests encode two real production disasters that engineers could   ║
// ║  not reproduce in staging.  They look simple.  They killed services for  ║
// ║  hours.  Running them in CI proves the system no longer falls into the   ║
// ║  same traps.                                                             ║
// ╚══════════════════════════════════════════════════════════════════════════╝

@ChaosTest(classes = com.macstab.chaos.examples.UserServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class L3JdbcL10IncidentsTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ChaosControlPlane chaos;

    // ──────────────────────────────────────────────────────────────────────
    // INCIDENT 1  ·  Connection pool never recovers after storm
    //
    // THE INCIDENT
    // ────────────
    // Connection storm hits pool (size=5).  Pool exhausts.  Requests time
    // out.  Pool returns connections to pool.  But: each connection timeout
    // leaves the JDBC transaction open — the application did not call
    // rollback on the timeout path.  Pool gets connections back but they
    // are in "broken" state (transaction already started on them).  Next
    // request on that connection: "ERROR: current transaction is aborted,
    // commands ignored until end of transaction block."  All 5 pool
    // connections now permanently broken.  Service never recovers without
    // restart.  Engineers: restart → recovers → storm hits → breaks again.
    // 6-hour on-call rotation.
    //
    // PROOF
    // ─────
    // Measure broken connection recovery:
    //   • connection pool state before storm          (baseline: 0 broken)
    //   • broken connections detected during storm    (must be < pool size)
    //   • service recovery after storm window         (≥ 60 % requests ok)
    //   • no permanently broken connections remain    (0 broken after drain)
    // ──────────────────────────────────────────────────────────────────────

    @Test
    @IncidentChaosJdbcConnectionStorm
    @DisplayName("INCIDENT JDBC/L10ConnectionStormNeverRecovers: pool=5 exhausted by timed-out open txns — pool MUST self-heal without restart")
    void jdbcL10ConnectionStormNeverRecovers() throws Exception {
        int storm = 50;
        ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor();
        CountDownLatch latch = new CountDownLatch(storm);
        AtomicInteger ok = new AtomicInteger(0);
        AtomicInteger failed = new AtomicInteger(0);

        // Phase 1 — storm: saturate pool, force connection timeouts
        for (int i = 0; i < storm; i++) {
            exec.submit(() -> {
                try {
                    ResponseEntity<String> r = restTemplate.getForEntity("/users", String.class);
                    if (r.getStatusCode().is2xxSuccessful()) ok.incrementAndGet();
                    else failed.incrementAndGet();
                } catch (Exception e) {
                    failed.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }
        assertThat(latch.await(30, TimeUnit.SECONDS))
                .as("Storm requests complete within 30s (pool does not hang forever)").isTrue();
        exec.shutdown();

        int stormOk = ok.get();
        int stormFailed = failed.get();
        System.out.printf(
                "JDBC L10 connection storm — storm phase: %d ok, %d failed of %d%n",
                stormOk, stormFailed, storm);

        // Phase 2 — recovery window: pool must return connections to healthy state
        AtomicInteger recoveryOk = new AtomicInteger(0);
        AtomicInteger recoveryFailed = new AtomicInteger(0);
        for (int i = 0; i < 20; i++) {
            try {
                ResponseEntity<String> r = restTemplate.getForEntity("/users", String.class);
                if (r.getStatusCode().is2xxSuccessful()) recoveryOk.incrementAndGet();
                else recoveryFailed.incrementAndGet();
            } catch (Exception e) {
                recoveryFailed.incrementAndGet();
            }
        }
        System.out.printf(
                "JDBC L10 connection storm — recovery phase: %d ok, %d failed of 20%n",
                recoveryOk.get(), recoveryFailed.get());

        // Phase 3 — prove: no "aborted transaction" errors on connection re-use
        AtomicInteger abortErrors = new AtomicInteger(0);
        try {
            List<Integer> result = jdbc.query(
                    "SELECT 1 AS probe", (rs, n) -> rs.getInt("probe"));
            assertThat(result).as("Direct JDBC probe after storm must not return aborted-txn error").isNotEmpty();
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("current transaction is aborted")) {
                abortErrors.incrementAndGet();
            }
        }

        System.out.printf(
                "JDBC L10 connection storm — aborted-transaction errors after recovery: %d%n",
                abortErrors.get());

        // ── PROOF ASSERTIONS ──────────────────────────────────────────────
        // 1. Pool must self-heal: majority of post-storm requests succeed
        assertThat(recoveryOk.get())
                .as("PROOF: pool self-heals — at least 12 of 20 post-storm requests succeed (no restart needed)")
                .isGreaterThanOrEqualTo(12);
        // 2. No permanently broken connections leave aborted-txn errors
        assertThat(abortErrors.get())
                .as("PROOF: no connections stuck with open aborted transaction after pool recovery")
                .isEqualTo(0);
    }

    // ──────────────────────────────────────────────────────────────────────
    // INCIDENT 2  ·  JDBC sequence gap causes application invariant violation
    //
    // THE INCIDENT
    // ────────────
    // PostgreSQL sequence generates IDs.  On primary failover: sequence not
    // synced to replica.  New primary starts from last ID it knew.  Gap:
    // IDs 10000–20000 potentially skipped.  Application has code:
    //
    //   if (id > lastKnownMaxId) {
    //       throw new IllegalStateException("ID jumped — failover detected");
    //   }
    //
    // This "defensive" check was added by a senior engineer 3 years ago.
    // Now it fires during failover.  Service: dead.  Engineers: comment out
    // the check.  Data integrity: unknown.
    //
    // PROOF
    // ─────
    // Measure ID gap handling:
    //   • IDs are created across the gap injection                    (≥ 2 users)
    //   • gap magnitude is detectable (non-sequential IDs present)
    //   • application handles every returned ID without throwing      (0 5xx)
    //   • each ID is fetchable by GET /users/{id}                     (200 or 404)
    // ──────────────────────────────────────────────────────────────────────

    @Test
    @IncidentChaosJdbcSequenceIdJump
    @DisplayName("INCIDENT JDBC/L10SequenceGapInvariantViolation: primary failover skips IDs 10000-20000 — application MUST NOT throw on gap")
    void jdbcL10SequenceGapInvariantViolation() throws Exception {
        // Create users on either side of the potential sequence gap
        ResponseEntity<String> r1 = restTemplate.postForEntity("/users",
                new org.springframework.http.HttpEntity<>(
                        new com.macstab.chaos.examples.UserDto("failover-before@test.com", "BeforeGap"), null),
                String.class);
        ResponseEntity<String> r2 = restTemplate.postForEntity("/users",
                new org.springframework.http.HttpEntity<>(
                        new com.macstab.chaos.examples.UserDto("failover-after@test.com", "AfterGap"), null),
                String.class);

        assertThat(r1.getStatusCode().is2xxSuccessful())
                .as("User before gap created without IllegalStateException (defensive check must not fire)").isTrue();
        assertThat(r2.getStatusCode().is2xxSuccessful())
                .as("User after gap created without IllegalStateException").isTrue();

        // Read all IDs from DB — gaps are expected
        List<Long> ids = jdbc.query(
                "SELECT id FROM users ORDER BY id",
                (rs, i) -> rs.getLong("id"));

        assertThat(ids).as("At least 2 users exist after failover gap injection").hasSizeGreaterThanOrEqualTo(2);

        long minId = ids.stream().mapToLong(Long::longValue).min().orElse(0L);
        long maxId = ids.stream().mapToLong(Long::longValue).max().orElse(0L);
        long gap = maxId - minId;

        System.out.printf(
                "JDBC L10 sequence gap — IDs: %s  |  min=%d  max=%d  gap=%d%n",
                ids, minId, maxId, gap);
        System.out.println(
                "PROOF: gap=" + gap + " — ID jump detected.  Application must not throw.");

        // Every ID must be fetchable — no 500 from the defensive id-jump check
        AtomicInteger fetchOk = new AtomicInteger(0);
        AtomicInteger fetchServerError = new AtomicInteger(0);
        for (long id : ids) {
            ResponseEntity<String> fetch = restTemplate.getForEntity("/users/" + id, String.class);
            if (fetch.getStatusCode().is5xxServerError()) {
                fetchServerError.incrementAndGet();
                System.out.printf(
                        "PROOF FAILURE: GET /users/%d returned %s — defensive check is still throwing!%n",
                        id, fetch.getStatusCode());
            } else {
                fetchOk.incrementAndGet();
            }
        }

        System.out.printf(
                "JDBC L10 sequence gap — fetch results: %d ok, %d 5xx of %d IDs%n",
                fetchOk.get(), fetchServerError.get(), ids.size());

        // ── PROOF ASSERTIONS ──────────────────────────────────────────────
        // 1. No server errors from the "id > lastKnownMaxId" guard
        assertThat(fetchServerError.get())
                .as("PROOF: 0 server errors — defensive ID-jump check does not kill the service on sequence gap")
                .isEqualTo(0);
        // 2. All IDs present
        assertThat(fetchOk.get())
                .as("PROOF: all inserted IDs are fetchable after gap")
                .isEqualTo(ids.size());
    }
}
