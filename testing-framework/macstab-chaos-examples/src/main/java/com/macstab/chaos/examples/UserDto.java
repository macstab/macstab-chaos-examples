package com.macstab.chaos.examples;

/**
 * Represents a user response. The {@code source} field tells callers where the data came from:
 *
 * <ul>
 *   <li>{@code CACHE} – served from Redis
 *   <li>{@code DB} – fetched from the downstream HTTP service
 *   <li>{@code FALLBACK} – circuit breaker opened; stub data returned
 * </ul>
 */
public record UserDto(Long id, String name, String email, String source) {

    public static UserDto fromCache(Long id, String name, String email) {
        return new UserDto(id, name, email, "CACHE");
    }

    public static UserDto fromDownstream(Long id, String name, String email) {
        return new UserDto(id, name, email, "DB");
    }

    public static UserDto fallback(Long id) {
        return new UserDto(id, "Unknown", "unknown@fallback.local", "FALLBACK");
    }
}
