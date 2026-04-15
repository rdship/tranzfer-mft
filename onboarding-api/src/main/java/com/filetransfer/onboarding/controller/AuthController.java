package com.filetransfer.onboarding.controller;

import com.filetransfer.onboarding.dto.request.LoginRequest;
import com.filetransfer.onboarding.dto.request.RegisterRequest;
import com.filetransfer.onboarding.dto.response.AuthResponse;
import com.filetransfer.onboarding.security.BruteForceProtection;
import com.filetransfer.onboarding.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Authentication endpoints with IP-based rate limiting.
 * Per-email brute force protection is handled in {@link com.filetransfer.onboarding.security.BruteForceProtection};
 * this controller adds per-IP throttling to prevent distributed attacks across multiple emails.
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Authentication", description = "User registration and login with IP-based rate limiting")
public class AuthController {

    private final AuthService authService;
    private final BruteForceProtection bruteForceProtection;

    // --- IP-based rate limiting (BLOCKER 1 fix: raised from 20 to 200/min) ---
    private static final int MAX_AUTH_REQUESTS_PER_MINUTE = 200;
    private static final long WINDOW_MS = 60_000;
    private final ConcurrentHashMap<String, IpWindow> ipWindows = new ConcurrentHashMap<>();

    private String resolveClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private void checkIpRateLimit(HttpServletRequest request) {
        String ip = resolveClientIp(request);
        IpWindow window = ipWindows.compute(ip, (k, v) -> {
            long now = Instant.now().toEpochMilli();
            if (v == null || now - v.windowStart > WINDOW_MS) {
                return new IpWindow(now);
            }
            return v;
        });
        int count = window.count.incrementAndGet();
        if (count > MAX_AUTH_REQUESTS_PER_MINUTE) {
            log.warn("IP rate limit exceeded for {} ({} requests/min)", ip, count);
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Too many authentication requests. Try again later.");
        }
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Register a new user account")
    public AuthResponse register(@Valid @RequestBody RegisterRequest request,
                                  HttpServletRequest httpRequest) {
        checkIpRateLimit(httpRequest);
        return authService.register(request, resolveClientIp(httpRequest), httpRequest.getHeader("User-Agent"));
    }

    @PostMapping("/login")
    @Operation(summary = "Authenticate and obtain JWT access + refresh tokens")
    public AuthResponse login(@Valid @RequestBody LoginRequest request,
                               HttpServletRequest httpRequest) {
        checkIpRateLimit(httpRequest);
        return authService.login(request, resolveClientIp(httpRequest), httpRequest.getHeader("User-Agent"));
    }

    @PostMapping("/refresh")
    @Operation(summary = "Refresh access token using a valid refresh token")
    public AuthResponse refresh(@RequestBody Map<String, String> body,
                                 HttpServletRequest httpRequest) {
        String refreshToken = body.get("refreshToken");
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new org.springframework.security.authentication.BadCredentialsException("refreshToken is required");
        }
        return authService.refresh(refreshToken, resolveClientIp(httpRequest), httpRequest.getHeader("User-Agent"));
    }

    @PostMapping("/logout")
    @Operation(summary = "Revoke all refresh tokens for the authenticated user")
    public ResponseEntity<Map<String, String>> logout(
            @org.springframework.security.core.annotation.AuthenticationPrincipal
            org.springframework.security.core.userdetails.UserDetails principal) {
        if (principal != null) {
            authService.revokeAllSessions(principal.getUsername());
        }
        return ResponseEntity.ok(Map.of("status", "LOGGED_OUT"));
    }

    @PostMapping("/admin/unlock/{email}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, String>> unlockAccount(@PathVariable String email) {
        bruteForceProtection.unlock(email);
        return ResponseEntity.ok(Map.of("status", "UNLOCKED", "email", email));
    }

    /** BLOCKER 1: Emergency reset ALL lockouts + IP rate limits. */
    @PostMapping("/admin/reset-all-lockouts")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, String>> resetAllLockouts() {
        bruteForceProtection.resetAll();
        ipWindows.clear();
        return ResponseEntity.ok(Map.of("status", "ALL_LOCKOUTS_AND_RATE_LIMITS_CLEARED"));
    }

    private static class IpWindow {
        final long windowStart;
        final AtomicInteger count = new AtomicInteger(0);
        IpWindow(long windowStart) { this.windowStart = windowStart; }
    }
}
