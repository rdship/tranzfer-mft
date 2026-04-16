package com.filetransfer.shared.ratelimit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.lang.Nullable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Redis-backed sliding window rate limiter for REST APIs.
 *
 * <p>Primary: Redis INCR + EXPIRE for distributed rate limiting (works across replicas).
 * <p>Fallback: in-memory ConcurrentHashMap if Redis is unavailable (single-instance only).
 *
 * <p>Limits per IP address and per authenticated user (JWT subject).
 * Returns standard rate-limit headers on every response and a 429
 * with {@code Retry-After} when the budget is exhausted.
 *
 * <p>Internal services (ROLE_INTERNAL via SPIFFE JWT-SVID) bypass rate limiting.
 */
@Slf4j
public class ApiRateLimitFilter extends OncePerRequestFilter {

    private final RateLimitProperties properties;
    private final @Nullable StringRedisTemplate redis;

    // Fallback: in-memory token buckets (used when Redis is unavailable)
    private final Map<String, TokenBucket> ipBuckets = new ConcurrentHashMap<>();
    private final Map<String, TokenBucket> userBuckets = new ConcurrentHashMap<>();
    private static final int MAX_TRACKED_KEYS = 50_000;

    private volatile boolean redisAvailable = true;

    public ApiRateLimitFilter(RateLimitProperties properties, @Nullable StringRedisTemplate redis) {
        this.properties = properties;
        this.redis = redis;
        if (redis == null) {
            log.warn("Rate limiter: Redis not available, using in-memory fallback (single-instance only)");
        }
    }

    /** Backward-compatible constructor for services without Redis. */
    public ApiRateLimitFilter(RateLimitProperties properties) {
        this(properties, null);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (!properties.isEnabled()) {
            filterChain.doFilter(request, response);
            return;
        }

        // Bypass rate limiting for health endpoints and actuator (burst of checks on page load)
        String path = request.getRequestURI();
        if (path != null && (path.endsWith("/health") || path.startsWith("/actuator"))) {
            filterChain.doFilter(request, response);
            return;
        }

        // Bypass rate limiting for SPIFFE-authenticated internal services
        Authentication internalAuth = SecurityContextHolder.getContext().getAuthentication();
        if (internalAuth != null && internalAuth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_INTERNAL".equals(a.getAuthority()))) {
            filterChain.doFilter(request, response);
            return;
        }

        String ip = resolveClientIp(request);
        int ipLimit = properties.getDefaultLimit();
        long windowSeconds = properties.getDefaultWindowSeconds();

        // IP-based rate limiting
        RateLimitResult ipResult = checkRateLimit("rate:ip:" + ip, ipLimit, windowSeconds);
        if (!ipResult.allowed) {
            writeRateLimitResponse(response, ipResult, windowSeconds);
            return;
        }

        // Per-user rate limiting (if authenticated)
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && auth.getPrincipal() instanceof String principal) {
            int userLimit = properties.getUserLimit();
            RateLimitResult userResult = checkRateLimit("rate:user:" + principal, userLimit, windowSeconds);
            if (!userResult.allowed) {
                writeRateLimitResponse(response, userResult, windowSeconds);
                return;
            }
            addRateLimitHeaders(response, userResult, userLimit);
        } else {
            addRateLimitHeaders(response, ipResult, ipLimit);
        }

        filterChain.doFilter(request, response);
    }

    // ── Rate Limit Check ────────────────────────────────────────────────

    private RateLimitResult checkRateLimit(String key, int limit, long windowSeconds) {
        if (redis != null && redisAvailable) {
            try {
                return checkRedis(key, limit, windowSeconds);
            } catch (Exception e) {
                // Redis failed — fall back to in-memory for this request cycle
                if (redisAvailable) {
                    log.warn("Rate limiter: Redis unavailable, falling back to in-memory: {}", e.getMessage());
                    redisAvailable = false;
                }
            }
        }
        return checkInMemory(key, limit, windowSeconds);
    }

    private RateLimitResult checkRedis(String key, int limit, long windowSeconds) {
        // Sliding window counter: INCR + EXPIRE
        Long count = redis.opsForValue().increment(key);
        if (count == null) count = 1L;

        if (count == 1) {
            // First request in this window — set TTL
            redis.expire(key, Duration.ofSeconds(windowSeconds));
        }

        long remaining = Math.max(0, limit - count);
        Long ttl = redis.getExpire(key);
        long resetEpoch = System.currentTimeMillis() / 1000 + (ttl != null ? ttl : windowSeconds);

        // Re-enable Redis check on successful operation
        redisAvailable = true;

        return new RateLimitResult(count <= limit, remaining, resetEpoch);
    }

    private RateLimitResult checkInMemory(String key, int limit, long windowSeconds) {
        Map<String, TokenBucket> map = key.startsWith("rate:user:") ? userBuckets : ipBuckets;
        TokenBucket bucket = map.computeIfAbsent(key, k -> new TokenBucket(limit, windowSeconds));
        evictIfNeeded(map);

        boolean allowed = bucket.tryConsume();
        return new RateLimitResult(allowed, bucket.remaining(), bucket.resetEpochSecond());
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private String resolveClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private void addRateLimitHeaders(HttpServletResponse response, RateLimitResult result, int limit) {
        response.setIntHeader("X-RateLimit-Limit", limit);
        response.setIntHeader("X-RateLimit-Remaining", (int) result.remaining);
        response.setHeader("X-RateLimit-Reset", String.valueOf(result.resetEpoch));
    }

    private void writeRateLimitResponse(HttpServletResponse response, RateLimitResult result,
                                         long windowSeconds) throws IOException {
        long retryAfter = Math.max(1, result.resetEpoch - (System.currentTimeMillis() / 1000));
        response.setStatus(429);
        response.setHeader("Retry-After", String.valueOf(retryAfter));
        response.setIntHeader("X-RateLimit-Remaining", 0);
        response.setHeader("X-RateLimit-Reset", String.valueOf(result.resetEpoch));
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

    // ── Admin operations (N9 fix) ────────────────────────────────────────

    /** Clear rate limit for a specific user (by email/principal). */
    public void clearUser(String principal) {
        userBuckets.remove(principal);
        if (redis != null && redisAvailable) {
            try { redis.delete("rate:user:" + principal); } catch (Exception ignored) {}
        }
    }

    /** Clear rate limit for a specific IP. */
    public void clearIp(String ip) {
        ipBuckets.remove(ip);
        if (redis != null && redisAvailable) {
            try { redis.delete("rate:ip:" + ip); } catch (Exception ignored) {}
        }
    }

    /** Clear all rate limit buckets (in-memory + Redis). */
    public void clearAll() {
        ipBuckets.clear();
        userBuckets.clear();
        if (redis != null && redisAvailable) {
            try {
                var ipKeys = redis.keys("rate:ip:*");
                var userKeys = redis.keys("rate:user:*");
                if (ipKeys != null && !ipKeys.isEmpty()) redis.delete(ipKeys);
                if (userKeys != null && !userKeys.isEmpty()) redis.delete(userKeys);
            } catch (Exception ignored) {}
        }
    }

    /** Return current status: tracked IPs, tracked users, Redis availability. */
    public Map<String, Object> getStatus() {
        return Map.of(
                "enabled", properties.isEnabled(),
                "redisAvailable", redisAvailable,
                "trackedIps", ipBuckets.size(),
                "trackedUsers", userBuckets.size(),
                "defaultLimit", properties.getDefaultLimit(),
                "userLimit", properties.getUserLimit(),
                "windowSeconds", properties.getDefaultWindowSeconds()
        );
    }

    // ── Data Classes ────────────────────────────────────────────────────

    record RateLimitResult(boolean allowed, long remaining, long resetEpoch) {}

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
            refillIfNeeded();
            return tokens.getAndDecrement() > 0;
        }

        long remaining() {
            refillIfNeeded();
            return Math.max(0, tokens.get());
        }

        long resetEpochSecond() {
            return (windowStart + windowMs) / 1000;
        }

        boolean isExpired(long now) {
            return now - windowStart > windowMs * 2;
        }

        private void refillIfNeeded() {
            long now = System.currentTimeMillis();
            if (now - windowStart >= windowMs) {
                windowStart = now;
                tokens.set(maxTokens);
            }
        }
    }
}
