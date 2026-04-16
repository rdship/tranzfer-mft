package com.filetransfer.onboarding.controller;

import com.filetransfer.shared.ratelimit.ApiRateLimitFilter;
import com.filetransfer.shared.security.Roles;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Admin endpoints for managing the platform rate limiter (N9 fix).
 *
 * <p>Allows admins to view rate limit status, clear buckets for a specific
 * user or IP, or reset all buckets. Useful when a legitimate user gets
 * locked out by rate limiting.
 */
@RestController
@RequestMapping("/api/auth/rate-limit")
@PreAuthorize(Roles.ADMIN)
@RequiredArgsConstructor
public class RateLimitAdminController {

    @Autowired(required = false)
    private ApiRateLimitFilter rateLimitFilter;

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        if (rateLimitFilter == null) {
            return ResponseEntity.ok(Map.of("enabled", false, "reason", "Rate limiter not configured"));
        }
        return ResponseEntity.ok(rateLimitFilter.getStatus());
    }

    @DeleteMapping("/user/{principal}")
    public ResponseEntity<Map<String, String>> clearUser(@PathVariable String principal) {
        if (rateLimitFilter == null) return ResponseEntity.ok(Map.of("status", "noop"));
        rateLimitFilter.clearUser(principal);
        return ResponseEntity.ok(Map.of("status", "cleared", "principal", principal));
    }

    @DeleteMapping("/ip/{ip}")
    public ResponseEntity<Map<String, String>> clearIp(@PathVariable String ip) {
        if (rateLimitFilter == null) return ResponseEntity.ok(Map.of("status", "noop"));
        rateLimitFilter.clearIp(ip);
        return ResponseEntity.ok(Map.of("status", "cleared", "ip", ip));
    }

    @DeleteMapping("/all")
    public ResponseEntity<Map<String, String>> clearAll() {
        if (rateLimitFilter == null) return ResponseEntity.ok(Map.of("status", "noop"));
        rateLimitFilter.clearAll();
        return ResponseEntity.ok(Map.of("status", "all_cleared"));
    }
}
