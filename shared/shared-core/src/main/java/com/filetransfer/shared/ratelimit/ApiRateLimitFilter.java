package com.filetransfer.shared.ratelimit;

import com.filetransfer.shared.spiffe.SpiffeWorkloadClient;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
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
    private final @Nullable PgRateLimitCoordinator pg;
    /**
     * Optional SPIFFE workload client. When present, Bearer tokens carrying a
     * {@code spiffe://} subject are validated inline and bypass rate limiting —
     * belt-and-suspenders for the {@code ROLE_INTERNAL} SecurityContext bypass
     * below, which only works when an upstream auth filter has already run.
     * Without this, services that place {@link ApiRateLimitFilter} before
     * their auth filter (historical mistake on {@code onboarding-api}) would
     * rate-limit internal platform traffic as if it were external.
     */
    private final @Nullable SpiffeWorkloadClient spiffeWorkloadClient;

    // Fallback: in-memory token buckets (used when Redis/PG is unavailable)
    private final Map<String, TokenBucket> ipBuckets = new ConcurrentHashMap<>();
    private final Map<String, TokenBucket> userBuckets = new ConcurrentHashMap<>();
    private static final int MAX_TRACKED_KEYS = 50_000;

    private volatile boolean redisAvailable = true;
    private volatile boolean pgAvailable = true;

    /**
     * Active backend resolved once at filter construction. Values:
     * {@code "redis"} (default, pre-R134w), {@code "pg"} (R134w target,
     * Sprint 2), {@code "memory"} (single-instance fallback). Anything
     * else falls back to {@code "redis"} for safety.
     */
    private final String backend;

    public ApiRateLimitFilter(RateLimitProperties properties,
                              @Nullable StringRedisTemplate redis,
                              @Nullable PgRateLimitCoordinator pg,
                              @Nullable SpiffeWorkloadClient spiffeWorkloadClient) {
        this.properties = properties;
        this.redis = redis;
        this.pg = pg;
        this.spiffeWorkloadClient = spiffeWorkloadClient;
        String requested = properties.getBackend() == null ? "redis" : properties.getBackend().toLowerCase();
        if ("pg".equals(requested) && pg == null) {
            log.warn("Rate limiter: backend=pg requested but PgRateLimitCoordinator not available — falling back to memory");
            this.backend = "memory";
        } else if ("redis".equals(requested) && redis == null) {
            log.warn("Rate limiter: backend=redis requested but StringRedisTemplate not available — falling back to memory");
            this.backend = "memory";
        } else if ("pg".equals(requested) || "redis".equals(requested) || "memory".equals(requested)) {
            this.backend = requested;
        } else {
            log.warn("Rate limiter: unknown backend '{}' — defaulting to redis", requested);
            this.backend = "redis";
        }
        log.info("Rate limiter: backend={} (requested={})", this.backend, requested);
    }

    /** Backward-compatible — three-arg (pre-R134w, before PG backend). */
    public ApiRateLimitFilter(RateLimitProperties properties,
                              @Nullable StringRedisTemplate redis,
                              @Nullable SpiffeWorkloadClient spiffeWorkloadClient) {
        this(properties, redis, null, spiffeWorkloadClient);
    }

    /** Backward-compatible — kept for call sites without a SPIFFE client (e.g. tests). */
    public ApiRateLimitFilter(RateLimitProperties properties, @Nullable StringRedisTemplate redis) {
        this(properties, redis, null, null);
    }

    /** Backward-compatible constructor for services without Redis. */
    public ApiRateLimitFilter(RateLimitProperties properties) {
        this(properties, null, null, null);
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

        // Bypass rate limiting for SPIFFE-authenticated internal services.
        // Two paths — either is sufficient:
        //  (a) An upstream auth filter already put ROLE_INTERNAL in the
        //      SecurityContext (PlatformSecurityConfig / PlatformJwtAuthFilter).
        //  (b) We haven't been given a chance to see the SecurityContext yet
        //      (this filter runs BEFORE auth) but the request carries a
        //      SPIFFE JWT-SVID. Validate inline and exempt. This guards
        //      against filter-ordering regressions like the one the R87-R89
        //      perf run surfaced on config-service + onboarding-api.
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
        if ("redis".equals(backend) && redis != null && redisAvailable) {
            try {
                return checkRedis(key, limit, windowSeconds);
            } catch (Exception e) {
                if (redisAvailable) {
                    log.warn("Rate limiter: Redis unavailable, falling back to in-memory: {}", e.getMessage());
                    redisAvailable = false;
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
     *
     * <p>Reset epoch is the next window boundary. Retry-After in the 429 path
     * uses {@link PgRateLimitCoordinator#retryAfterSeconds} for jitter (R134t).
     */
    private RateLimitResult checkPostgres(String key, int limit, long windowSeconds) {
        Duration window = Duration.ofSeconds(windowSeconds);
        Instant windowStart = PgRateLimitCoordinator.windowStart(window);
        long count = pg.incrementAndGet(key, windowStart, 1);
        long remaining = Math.max(0, limit - count);
        long resetEpoch = (windowStart.toEpochMilli() / 1000) + windowSeconds;
        // Signal health on success
        pgAvailable = true;
        return new RateLimitResult(count <= limit, remaining, resetEpoch);
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

    /**
     * Returns true iff the request carries a Bearer token that cryptographically
     * validates as a SPIFFE JWT-SVID for this service. Peeks at the JWT payload
     * to decide whether a validation attempt is even warranted (avoid calling
     * the SPIRE Workload API for every admin/user JWT).
     *
     * <p>Security note: we deliberately validate via {@link SpiffeWorkloadClient}
     * rather than trusting the {@code sub} claim alone. A forged JWT with
     * {@code sub=spiffe://...} but no valid signature from SPIRE will not be
     * accepted — the bypass is gated on real signature + audience verification.
     */
    private boolean hasValidSpiffeToken(HttpServletRequest request) {
        // Cheap token-shape checks first — avoid touching SpiffeWorkloadClient
        // (which would hit the SPIRE workload API) for the majority of requests
        // that carry a normal platform JWT or no auth at all.
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

    /** Cheap pre-check: does the JWT payload mention a spiffe:// subject? */
    private static boolean looksLikeSpiffeToken(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length < 2) return false;
            String p = parts[1];
            // Base64url may omit padding; re-pad to multiple of 4.
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

    /** Return current status: tracked IPs, tracked users, backend availability. */
    public Map<String, Object> getStatus() {
        return Map.of(
                "enabled", properties.isEnabled(),
                "backend", backend,
                "redisAvailable", redisAvailable,
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
