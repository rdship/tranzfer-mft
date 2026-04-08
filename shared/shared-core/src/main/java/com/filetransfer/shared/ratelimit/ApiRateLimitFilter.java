package com.filetransfer.shared.ratelimit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Token-bucket rate limiter for internal REST APIs.
 *
 * Limits per IP address and per authenticated user (JWT subject).
 * Returns standard rate-limit headers on every response and a 429
 * with {@code Retry-After} when the budget is exhausted.
 *
 * Configuration via {@link RateLimitProperties}.
 */
@Slf4j
public class ApiRateLimitFilter extends OncePerRequestFilter {

    private final RateLimitProperties properties;
    private final Map<String, TokenBucket> ipBuckets = new ConcurrentHashMap<>();
    private final Map<String, TokenBucket> userBuckets = new ConcurrentHashMap<>();

    private static final int MAX_TRACKED_KEYS = 50_000;

    public ApiRateLimitFilter(RateLimitProperties properties) {
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (!properties.isEnabled()) {
            filterChain.doFilter(request, response);
            return;
        }

        // Bypass rate limiting for authenticated internal services (ROLE_INTERNAL).
        // This filter runs AFTER PlatformJwtAuthFilter so ROLE_INTERNAL is already set
        // in the SecurityContext when a valid SPIFFE JWT-SVID was presented.
        Authentication internalAuth = SecurityContextHolder.getContext().getAuthentication();
        if (internalAuth != null && internalAuth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_INTERNAL".equals(a.getAuthority()))) {
            filterChain.doFilter(request, response);
            return;
        }

        String ip = resolveClientIp(request);
        int ipLimit = properties.getDefaultLimit();
        long windowSeconds = properties.getDefaultWindowSeconds();

        TokenBucket ipBucket = ipBuckets.computeIfAbsent(ip,
                k -> new TokenBucket(ipLimit, windowSeconds));
        evictIfNeeded(ipBuckets);

        if (!ipBucket.tryConsume()) {
            writeRateLimitResponse(response, ipBucket, windowSeconds);
            return;
        }

        // Per-user rate limiting (if authenticated)
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && auth.getPrincipal() instanceof String principal) {
            int userLimit = properties.getUserLimit();
            TokenBucket userBucket = userBuckets.computeIfAbsent(principal,
                    k -> new TokenBucket(userLimit, windowSeconds));
            evictIfNeeded(userBuckets);

            if (!userBucket.tryConsume()) {
                writeRateLimitResponse(response, userBucket, windowSeconds);
                return;
            }
            addRateLimitHeaders(response, userBucket, userLimit);
        } else {
            addRateLimitHeaders(response, ipBucket, ipLimit);
        }

        filterChain.doFilter(request, response);
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private String resolveClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private void addRateLimitHeaders(HttpServletResponse response, TokenBucket bucket, int limit) {
        response.setIntHeader("X-RateLimit-Limit", limit);
        response.setIntHeader("X-RateLimit-Remaining", (int) Math.max(0, bucket.remaining()));
        response.setHeader("X-RateLimit-Reset", String.valueOf(bucket.resetEpochSecond()));
    }

    private void writeRateLimitResponse(HttpServletResponse response, TokenBucket bucket,
                                         long windowSeconds) throws IOException {
        long retryAfter = Math.max(1, bucket.resetEpochSecond() - (System.currentTimeMillis() / 1000));
        response.setStatus(429);
        response.setHeader("Retry-After", String.valueOf(retryAfter));
        response.setIntHeader("X-RateLimit-Remaining", 0);
        response.setHeader("X-RateLimit-Reset", String.valueOf(bucket.resetEpochSecond()));
        response.setContentType("application/json");
        response.getWriter().write(
                "{\"error\":\"Too Many Requests\",\"message\":\"Rate limit exceeded. Retry after "
                        + retryAfter + " seconds.\"}");
        log.warn("Rate limit exceeded — responding 429");
    }

    private void evictIfNeeded(Map<String, TokenBucket> map) {
        if (map.size() > MAX_TRACKED_KEYS) {
            long now = System.currentTimeMillis();
            map.entrySet().removeIf(e -> e.getValue().isExpired(now));
        }
    }

    // ── Token Bucket ────────────────────────────────────────────────────

    static class TokenBucket {
        private final int maxTokens;
        private final long windowMs;
        private final AtomicLong tokens;
        private volatile long windowStart;

        TokenBucket(int maxTokens, long windowSeconds) {
            this.maxTokens = maxTokens;
            this.windowMs = windowSeconds * 1000;
            this.tokens = new AtomicLong(maxTokens);
            this.windowStart = System.currentTimeMillis();
        }

        boolean tryConsume() {
            refill();
            long val = tokens.decrementAndGet();
            if (val < 0) {
                tokens.incrementAndGet();
                return false;
            }
            return true;
        }

        long remaining() {
            refill();
            return tokens.get();
        }

        long resetEpochSecond() {
            return (windowStart + windowMs) / 1000;
        }

        boolean isExpired(long now) {
            return now - windowStart > windowMs * 2;
        }

        private void refill() {
            long now = System.currentTimeMillis();
            if (now - windowStart >= windowMs) {
                windowStart = now;
                tokens.set(maxTokens);
            }
        }
    }
}
