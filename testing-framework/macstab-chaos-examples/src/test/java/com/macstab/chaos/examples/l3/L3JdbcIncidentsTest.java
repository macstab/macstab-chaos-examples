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

@ChaosTest(classes = com.macstab.chaos.examples.UserServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class L3JdbcIncidentsTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ChaosControlPlane chaos;

    @Test
    @IncidentChaosJdbcConnectionStorm
    @DisplayName("INCIDENT JDBC/ConnectionStorm: pool=5, 50 concurrent requests, 20% JDBC reject — pool exhaustion with fast fail")
    void jdbcConnectionStorm() throws Exception {
        int threads = 50;
        ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor();
        AtomicInteger success = new AtomicInteger(0);
        AtomicInteger failed = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            exec.submit(() -> {
                try {
                    ResponseEntity<String> r = restTemplate.getForEntity("/users", String.class);
                    if (r.getStatusCode().is2xxSuccessful()) success.incrementAndGet();
                    else failed.incrementAndGet();
                } catch (Exception e) {
                    failed.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }
        assertThat(latch.await(30, TimeUnit.SECONDS)).as("All requests complete within 30s").isTrue();
        System.out.printf("JDBC connection storm: %d success, %d failed of %d total%n", success.get(), failed.get(), threads);
        assertThat(success.get()).as("At least 60%% succeed despite connection storm").isGreaterThan(30);
        exec.shutdown();
    }

    @Test
    @IncidentChaosJdbcSequenceIdJump
    @DisplayName("INCIDENT JDBC/SequenceIdJump: sequence gaps on failover — application handles non-sequential IDs")
    void jdbcSequenceIdJump() throws Exception {
        // Create two users and get their IDs
        ResponseEntity<String> r1 = restTemplate.postForEntity("/users",
            new org.springframework.http.HttpEntity<>(new com.macstab.chaos.examples.UserDto("alice@test.com", "Alice"), null), String.class);
        ResponseEntity<String> r2 = restTemplate.postForEntity("/users",
            new org.springframework.http.HttpEntity<>(new com.macstab.chaos.examples.UserDto("bob@test.com", "Bob"), null), String.class);
        assertThat(r1.getStatusCode().is2xxSuccessful()).as("First user created").isTrue();
        assertThat(r2.getStatusCode().is2xxSuccessful()).as("Second user created").isTrue();
        // Check that sequence IDs are not necessarily sequential (gaps are OK)
        List<Long> ids = jdbc.query("SELECT id FROM users ORDER BY id", (rs, i) -> rs.getLong("id"));
        assertThat(ids).as("Users exist in DB").hasSizeGreaterThanOrEqualTo(2);
        // With sequence gaps, IDs may jump by 32+ — app must not assume contiguous IDs
        System.out.printf("JDBC sequence IDs: %s — gaps are expected and handled%n", ids);
        // Verify application can still fetch by any ID
        ids.forEach(id -> {
            ResponseEntity<String> fetch = restTemplate.getForEntity("/users/" + id, String.class);
            assertThat(fetch.getStatusCode()).isNotEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        });
    }
}
