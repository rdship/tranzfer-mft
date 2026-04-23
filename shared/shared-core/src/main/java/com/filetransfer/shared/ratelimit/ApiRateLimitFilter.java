package com.filetransfer.shared.ratelimit;

import com.filetransfer.shared.spiffe.SpiffeWorkloadClient;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.Nullable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Sliding-window rate limiter for REST APIs.
 *
 * <p>Primary: PostgreSQL {@link PgRateLimitCoordinator} — monthly-partitioned
 * {@code rate_limit_buckets} table with atomic UPSERT+RETURNING. Works across
 * replicas without requiring Redis.
 *
 * <p>Fallback: in-memory {@link TokenBucket}s when PG is unavailable or the
 * backend is configured to {@code memory} (single-instance deployments).
 *
 * <p><b>R134AH — Redis backend retired.</b> Pre-R134w, Redis INCR+EXPIRE was
 * the default and PG was opt-in. R134w flipped the default to PG in compose.
 * R134AH now deletes the Redis code path entirely so Redis can be removed
 * from the default stack (R134AJ). Callers that specified
 * {@code platform.rate-limit.backend=redis} fall through to memory with a
 * one-line warn at boot.
 *
 * <p>Internal services (ROLE_INTERNAL via SPIFFE JWT-SVID) bypass rate limiting.
 */
@Slf4j
public class ApiRateLimitFilter extends OncePerRequestFilter {

    private final RateLimitProperties properties;
    private final @Nullable PgRateLimitCoordinator pg;
    /**
     * Optional SPIFFE workload client. When present, Bearer tokens carrying a
     * {@code spiffe://} subject are validated inline and bypass rate limiting —
     * belt-and-suspenders for the {@code ROLE_INTERNAL} SecurityContext bypass
     * below, which only works when an upstream auth filter has already run.
     */
    private final @Nullable SpiffeWorkloadClient spiffeWorkloadClient;

    // Fallback: in-memory token buckets (used when PG is unavailable or backend=memory)
    private final Map<String, TokenBucket> ipBuckets = new ConcurrentHashMap<>();
    private final Map<String, TokenBucket> userBuckets = new ConcurrentHashMap<>();
    private static final int MAX_TRACKED_KEYS = 50_000;

    private volatile boolean pgAvailable = true;

    /**
     * Active backend resolved once at filter construction. Values:
     * {@code "pg"} (default post-R134AH), {@code "memory"} (single-instance).
     * Any other requested value (including the legacy {@code "redis"})
     * falls back to {@code "memory"} with a warn.
     */
    private final String backend;

    public ApiRateLimitFilter(RateLimitProperties properties,
                              @Nullable PgRateLimitCoordinator pg,
                              @Nullable SpiffeWorkloadClient spiffeWorkloadClient) {
        this.properties = properties;
        this.pg = pg;
        this.spiffeWorkloadClient = spiffeWorkloadClient;
        String requested = properties.getBackend() == null ? "pg" : properties.getBackend().toLowerCase();
        if ("pg".equals(requested) && pg == null) {
            log.warn("Rate limiter: backend=pg requested but PgRateLimitCoordinator not available — falling back to memory");
            this.backend = "memory";
        } else if ("memory".equals(requested) || "pg".equals(requested)) {
            this.backend = requested;
        } else {
            log.warn("Rate limiter: unknown or retired backend '{}' (Redis retired at R134AH) — falling back to memory", requested);
            this.backend = "memory";
        }
        log.info("Rate limiter: backend={} (requested={})", this.backend, requested);
    }

    /** Test helper — no PG coordinator, no SPIFFE. */
    public ApiRateLimitFilter(RateLimitProperties properties) {
        this(properties, null, null);
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

        // Bypass for internal S2S — either SecurityContext ROLE_INTERNAL or inline SPIFFE validation.
        Authentication internalAuth = SecurityContextHolder.getContext().getAuthentication();
        if (internalAuth != null && internalAuth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_INTERNAL".equals(a.getAuthority()))) {
            filterChain.doFilter(request, response);
            return;
        }
        if (hasValidSpiffeToken(request)) {
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
        if ("pg".equals(backend) && pg != null && pgAvailable) {
            try {
                return checkPostgres(key, limit, windowSeconds);
            } catch (Exception e) {
                if (pgAvailable) {
                    log.warn("Rate limiter: Postgres unavailable, falling back to in-memory: {}", e.getMessage());
                    pgAvailable = false;
                }
            }
        }
        return checkInMemory(key, limit, windowSeconds);
    }

    /**
     * Postgres-backed sliding window counter via {@link PgRateLimitCoordinator}.
     * Window boundary is truncated to {@code windowSeconds} so all requests in
     * the same window share one row. The UPSERT+RETURNING round-trip returns
     * the new count atomically.
     */
    private RateLimitResult checkPostgres(String key, int limit, long windowSeconds) {
        Duration window = Duration.ofSeconds(windowSeconds);
        Instant windowStart = PgRateLimitCoordinator.windowStart(window);
        long count = pg.incrementAndGet(key, windowStart, 1);
        long remaining = Math.max(0, limit - count);
        long resetEpoch = (windowStart.toEpochMilli() / 1000) + windowSeconds;
        pgAvailable = true;
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

    private boolean hasValidSpiffeToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (!StringUtils.hasText(header) || !header.startsWith("Bearer ")) {
            return false;
        }
        String token = header.substring(7);
        if (!token.startsWith("eyJ") || !looksLikeSpiffeToken(token)) {
            return false;
        }
        if (spiffeWorkloadClient == null || !spiffeWorkloadClient.isAvailable()) {
            return false;
        }
        try {
            String selfId = spiffeWorkloadClient.getSelfSpiffeId();
            return StringUtils.hasText(selfId) && spiffeWorkloadClient.validate(token, selfId);
        } catch (Exception e) {
            log.debug("SPIFFE token validation failed, treating as non-internal: {}", e.getMessage());
            return false;
        }
    }

    private static boolean looksLikeSpiffeToken(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length < 2) return false;
            String p = parts[1];
            String padded = p + "=".repeat((4 - p.length() % 4) % 4);
            String payload = new String(Base64.getUrlDecoder().decode(padded));
            return payload.contains("\"spiffe://");
        } catch (Exception e) {
            return false;
        }
    }

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
    }

    /** Clear rate limit for a specific IP. */
    public void clearIp(String ip) {
        ipBuckets.remove(ip);
    }

    /** Clear all rate limit buckets. */
    public void clearAll() {
        ipBuckets.clear();
        userBuckets.clear();
    }

    /** Return current status: tracked IPs, tracked users, backend availability. */
    public Map<String, Object> getStatus() {
        return Map.of(
                "enabled", properties.isEnabled(),
                "backend", backend,
                "pgAvailable", pgAvailable,
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
