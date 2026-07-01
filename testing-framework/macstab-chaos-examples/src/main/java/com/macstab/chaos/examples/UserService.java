package com.macstab.chaos.examples;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.concurrent.TimeUnit;

@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    private static final String CACHE_KEY_PREFIX = "user:";
    private static final String CACHE_NAME = "users";

    private final StringRedisTemplate redis;
    private final RestClient restClient;

    public UserService(
            StringRedisTemplate redis,
            RestClient.Builder restClientBuilder,
            @Value("${downstream.base-url:http://localhost:9090}") String downstreamBaseUrl) {
        this.redis = redis;
        this.restClient = restClientBuilder.baseUrl(downstreamBaseUrl).build();
    }

    /**
     * Fetch a user by ID. Annotated with Resilience4j circuit breaker and retry so the framework
     * can observe real state transitions during chaos injection.
     *
     * <p>Resolution order:
     *
     * <ol>
     *   <li>Redis cache (fast path)
     *   <li>Downstream HTTP service (slow path)
     *   <li>{@link #getFallbackUser} when the circuit breaker opens
     * </ol>
     */
    @CircuitBreaker(name = "user-service", fallbackMethod = "getFallbackUser")
    @Retry(name = "user-service")
    @Cacheable(cacheNames = CACHE_NAME, key = "#id")
    public UserDto getUser(Long id) {
        // 1. Check Redis manually so we can tag the source correctly.
        String cachedName = redis.opsForValue().get(CACHE_KEY_PREFIX + id);
        if (cachedName != null) {
            log.debug("Cache hit for user {}", id);
            return UserDto.fromCache(id, cachedName, cachedName.toLowerCase() + "@cache.local");
        }

        // 2. Fetch from downstream HTTP service.
        log.debug("Cache miss for user {}; calling downstream", id);
        UserDto user = fetchFromDownstream(id);

        // 3. Populate cache with a 5-minute TTL.
        redis.opsForValue().set(CACHE_KEY_PREFIX + id, user.name(), 5, TimeUnit.MINUTES);

        return user;
    }

    /**
     * Called by the downstream profile endpoint which demonstrates a second outbound call path that
     * chaos can target independently.
     */
    @CircuitBreaker(name = "user-service", fallbackMethod = "getFallbackUser")
    @Retry(name = "user-service")
    public UserDto getUserProfile(Long id) {
        return fetchFromDownstream(id);
    }

    // ── Private helpers ───────────────────────────────────────────────────

    private UserDto fetchFromDownstream(Long id) {
        DownstreamUserResponse response =
                restClient
                        .get()
                        .uri("/users/{id}", id)
                        .retrieve()
                        .body(DownstreamUserResponse.class);

        if (response == null) {
            throw new IllegalStateException("Downstream returned null body for user " + id);
        }
        return UserDto.fromDownstream(id, response.name(), response.email());
    }

    /** Resilience4j fallback – must have the same signature plus {@link Throwable}. */
    UserDto getFallbackUser(Long id, Throwable cause) {
        log.warn("Fallback triggered for user {} due to: {}", id, cause.getMessage());
        return UserDto.fallback(id);
    }

    // ── Inner DTO for downstream deserialization ──────────────────────────

    record DownstreamUserResponse(Long id, String name, String email) {}
}
