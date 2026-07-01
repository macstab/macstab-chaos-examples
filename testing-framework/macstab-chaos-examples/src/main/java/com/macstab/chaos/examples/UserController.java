package com.macstab.chaos.examples;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    /**
     * Primary user endpoint. Served from Redis cache when possible, falls back to downstream HTTP,
     * and ultimately to the Resilience4j fallback stub when the circuit breaker is open.
     */
    @GetMapping("/{id}")
    public ResponseEntity<UserDto> getUser(@PathVariable Long id) {
        return ResponseEntity.ok(userService.getUser(id));
    }

    /**
     * Profile endpoint – always calls the downstream HTTP service (no cache). Used by chaos tests
     * that need to exercise the downstream call path in isolation.
     */
    @GetMapping("/{id}/profile")
    public ResponseEntity<UserDto> getUserProfile(@PathVariable Long id) {
        return ResponseEntity.ok(userService.getUserProfile(id));
    }
}
