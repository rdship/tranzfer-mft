package com.filetransfer.onboarding.controller;

import com.filetransfer.onboarding.dto.request.LoginRequest;
import com.filetransfer.onboarding.dto.request.RegisterRequest;
import com.filetransfer.onboarding.dto.response.AuthResponse;
import com.filetransfer.onboarding.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
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
public class AuthController {

    private final AuthService authService;

    // --- IP-based rate limiting ---
    private static final int MAX_AUTH_REQUESTS_PER_MINUTE = 20;
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
    public AuthResponse register(@Valid @RequestBody RegisterRequest request,
                                  HttpServletRequest httpRequest) {
        checkIpRateLimit(httpRequest);
        return authService.register(request);
    }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest request,
                               HttpServletRequest httpRequest) {
        checkIpRateLimit(httpRequest);
        return authService.login(request);
    }

    private static class IpWindow {
        final long windowStart;
        final AtomicInteger count = new AtomicInteger(0);
        IpWindow(long windowStart) { this.windowStart = windowStart; }
    }
}
