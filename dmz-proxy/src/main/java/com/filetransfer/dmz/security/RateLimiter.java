package com.filetransfer.dmz.security;

import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Token Bucket Rate Limiter — per-IP and global rate limiting.
 * Lightweight, lock-free implementation using atomic operations.
 *
 * Supports:
 * - Per-IP connection rate limiting (connections per minute)
 * - Per-IP concurrent connection limiting
 * - Per-IP byte rate limiting
 * - Global connection rate limiting
 * - Adaptive limits (AI engine can set per-IP overrides)
 *
 * Product-agnostic: works with any TCP proxy.
 */
@Slf4j
public class RateLimiter {

    // ── Per-IP Bucket ──────────────────────────────────────────────────

    static class IpBucket {
        final AtomicLong tokens;           // current tokens
        final AtomicLong lastRefill;       // last refill timestamp (ms)
        final AtomicInteger concurrent;    // current concurrent connections
        final AtomicLong bytesThisMinute;  // bytes in current minute window
        final AtomicLong minuteStart;      // start of current minute window

        // Limits (may be overridden by AI engine)
        volatile int maxPerMinute;
        volatile int maxConcurrent;
        volatile long maxBytesPerMinute;

        IpBucket(int maxPerMinute, int maxConcurrent, long maxBytesPerMinute) {
            this.tokens = new AtomicLong(maxPerMinute);
            this.lastRefill = new AtomicLong(System.currentTimeMillis());
            this.concurrent = new AtomicInteger(0);
            this.bytesThisMinute = new AtomicLong(0);
            this.minuteStart = new AtomicLong(System.currentTimeMillis());
            this.maxPerMinute = maxPerMinute;
            this.maxConcurrent = maxConcurrent;
            this.maxBytesPerMinute = maxBytesPerMinute;
        }

        void refill() {
            long now = System.currentTimeMillis();
            long last = lastRefill.get();
            long elapsed = now - last;
            if (elapsed >= 1000) { // refill every second
                long tokensToAdd = (elapsed / 1000) * maxPerMinute / 60;
                if (tokensToAdd > 0 && lastRefill.compareAndSet(last, now)) {
                    long current = tokens.get();
                    tokens.set(Math.min(maxPerMinute, current + tokensToAdd));
                }
            }

            // Reset byte counter every minute
            long minStart = minuteStart.get();
            if (now - minStart >= 60_000) {
                if (minuteStart.compareAndSet(minStart, now)) {
                    bytesThisMinute.set(0);
                }
            }
        }

        /**
         * Try to acquire a connection token.
         * @return true if allowed, false if rate limited
         */
        boolean tryAcquire() {
            refill();

            // Check concurrent limit
            if (concurrent.get() >= maxConcurrent) {
                return false;
            }

            // Check rate limit
            long remaining = tokens.decrementAndGet();
            if (remaining < 0) {
                tokens.incrementAndGet(); // put it back
                return false;
            }

            concurrent.incrementAndGet();
            return true;
        }

        void release() {
            concurrent.decrementAndGet();
        }

        boolean checkBytes(long additionalBytes) {
            return bytesThisMinute.addAndGet(additionalBytes) <= maxBytesPerMinute;
        }

        void updateLimits(int maxPerMin, int maxConc, long maxBytes) {
            this.maxPerMinute = maxPerMin;
            this.maxConcurrent = maxConc;
            this.maxBytesPerMinute = maxBytes;
        }
    }

    // ── State ──────────────────────────────────────────────────────────

    private final ConcurrentHashMap<String, IpBucket> buckets = new ConcurrentHashMap<>();
    private final AtomicLong globalTokens = new AtomicLong(10_000);
    private final AtomicLong globalLastRefill = new AtomicLong(System.currentTimeMillis());

    // Default limits
    private volatile int defaultMaxPerMinute = 60;
    private volatile int defaultMaxConcurrent = 20;
    private volatile long defaultMaxBytesPerMinute = 500_000_000L; // 500 MB/min
    private volatile int globalMaxPerMinute = 10_000;

    private static final int MAX_TRACKED_IPS = 50_000;

    // ── Core Operations ────────────────────────────────────────────────

    /**
     * Try to allow a connection from this IP.
     * @return true if allowed, false if rate limited
     */
    public boolean tryAcquire(String ip) {
        // Global rate check
        if (!tryGlobalAcquire()) {
            log.warn("Global rate limit hit");
            return false;
        }

        IpBucket bucket = buckets.computeIfAbsent(ip,
            k -> new IpBucket(defaultMaxPerMinute, defaultMaxConcurrent, defaultMaxBytesPerMinute));

        if (buckets.size() > MAX_TRACKED_IPS) {
            evictStale();
        }

        return bucket.tryAcquire();
    }

    /**
     * Release a connection slot for this IP (called on close).
     */
    public void release(String ip) {
        IpBucket bucket = buckets.get(ip);
        if (bucket != null) {
            bucket.release();
        }
    }

    /**
     * Check if byte transfer is within limit.
     */
    public boolean checkBytes(String ip, long bytes) {
        IpBucket bucket = buckets.get(ip);
        if (bucket == null) return true;
        return bucket.checkBytes(bytes);
    }

    // ── AI Engine Overrides ────────────────────────────────────────────

    /**
     * Set custom rate limits for a specific IP (called when AI engine returns a verdict).
     */
    public void setIpLimits(String ip, int maxPerMinute, int maxConcurrent, long maxBytesPerMinute) {
        IpBucket bucket = buckets.computeIfAbsent(ip,
            k -> new IpBucket(maxPerMinute, maxConcurrent, maxBytesPerMinute));
        bucket.updateLimits(maxPerMinute, maxConcurrent, maxBytesPerMinute);
    }

    /**
     * Reset an IP's limits back to defaults.
     */
    public void resetIpLimits(String ip) {
        IpBucket bucket = buckets.get(ip);
        if (bucket != null) {
            bucket.updateLimits(defaultMaxPerMinute, defaultMaxConcurrent, defaultMaxBytesPerMinute);
        }
    }

    // ── Global Defaults ────────────────────────────────────────────────

    public void setDefaultMaxPerMinute(int max) { this.defaultMaxPerMinute = max; }
    public void setDefaultMaxConcurrent(int max) { this.defaultMaxConcurrent = max; }
    public void setDefaultMaxBytesPerMinute(long max) { this.defaultMaxBytesPerMinute = max; }
    public void setGlobalMaxPerMinute(int max) { this.globalMaxPerMinute = max; }

    public int getDefaultMaxPerMinute() { return defaultMaxPerMinute; }
    public int getDefaultMaxConcurrent() { return defaultMaxConcurrent; }

    // ── Stats ──────────────────────────────────────────────────────────

    public Map<String, Object> getStats() {
        return Map.of(
            "trackedIps", buckets.size(),
            "defaultMaxPerMinute", defaultMaxPerMinute,
            "defaultMaxConcurrent", defaultMaxConcurrent,
            "defaultMaxBytesPerMinute", defaultMaxBytesPerMinute,
            "globalMaxPerMinute", globalMaxPerMinute,
            "globalTokensRemaining", globalTokens.get()
        );
    }

    // ── Private ────────────────────────────────────────────────────────

    private boolean tryGlobalAcquire() {
        long now = System.currentTimeMillis();
        long last = globalLastRefill.get();
        if (now - last >= 1000) {
            long tokensToAdd = ((now - last) / 1000) * globalMaxPerMinute / 60;
            if (globalLastRefill.compareAndSet(last, now)) {
                globalTokens.set(Math.min(globalMaxPerMinute, globalTokens.get() + tokensToAdd));
            }
        }
        return globalTokens.decrementAndGet() >= 0;
    }

    private void evictStale() {
        // Remove IPs with no active connections and full tokens (inactive)
        buckets.entrySet().removeIf(e ->
            e.getValue().concurrent.get() == 0
            && e.getValue().tokens.get() >= e.getValue().maxPerMinute);
    }
}
