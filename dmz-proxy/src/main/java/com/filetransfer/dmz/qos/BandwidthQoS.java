package com.filetransfer.dmz.qos;

import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bandwidth Quality of Service — traffic prioritization and bandwidth management
 * per mapping, per connection, and globally.
 *
 * <p>Uses a multi-level token bucket algorithm with weighted fair queuing:</p>
 * <ul>
 *   <li><b>Global bucket:</b> shared across all connections</li>
 *   <li><b>Per-mapping bucket:</b> shared across connections on the same mapping</li>
 *   <li><b>Per-connection bucket:</b> individual connection limit</li>
 * </ul>
 *
 * <p>Priority levels based on security tier:</p>
 * <pre>
 *   RULES tier       → priority 1 (fastest, no AI wait)
 *   AI tier          → priority 3
 *   AI_LLM tier      → priority 5 (may have LLM latency)
 *   Throttled        → priority 8
 *   Challenged       → priority 9
 * </pre>
 *
 * <p>Weight formula: {@code weight = 11 - priority} (priority 1 → weight 10, priority 10 → weight 1).
 * Higher priority connections get proportionally more bandwidth when the global limit is constrained.</p>
 *
 * <p>Burst handling: token bucket capacity = {@code limit * (1 + burstAllowance / 100)},
 * allowing temporary bursts above the sustained rate.</p>
 *
 * <p>Thread-safe: AtomicLong for tokens, ConcurrentHashMap for connection tracking.</p>
 */
@Slf4j
public class BandwidthQoS {

    // ── Nested types ──────────────────────────────────────────────────

    /**
     * QoS configuration.
     */
    public record QoSConfig(
        boolean enabled,
        long globalMaxBytesPerSecond,
        long perMappingMaxBytesPerSecond,
        long perConnectionMaxBytesPerSecond,
        int priority,
        int burstAllowancePercent
    ) {
        public QoSConfig {
            if (burstAllowancePercent < 0) burstAllowancePercent = 0;
            if (priority < 1) priority = 1;
            if (priority > 10) priority = 10;
        }
    }

    /**
     * Bandwidth statistics for a mapping or global view.
     */
    public record BandwidthStats(
        long currentBps,
        long limitBps,
        double utilizationPercent,
        int activeConnections,
        long totalBytes
    ) {}

    // ── Token Bucket ──────────────────────────────────────────────────

    /**
     * Thread-safe token bucket with burst capacity.
     * Capacity = limit * (1 + burstAllowance / 100).
     * Refill rate = limit / 10 per 100ms interval (10 refills/second).
     */
    private static class TokenBucket {
        private final AtomicLong tokens;
        private final long refillAmountPer100ms;
        private final long capacity;
        private final AtomicLong totalConsumed = new AtomicLong();

        // Sliding window for current BPS measurement
        private final AtomicLong windowStart = new AtomicLong(System.currentTimeMillis());
        private final AtomicLong windowBytes = new AtomicLong();

        TokenBucket(long bytesPerSecond, int burstAllowancePercent) {
            if (bytesPerSecond <= 0) {
                // Unlimited
                this.capacity = Long.MAX_VALUE;
                this.tokens = new AtomicLong(Long.MAX_VALUE);
                this.refillAmountPer100ms = 0;
            } else {
                this.capacity = bytesPerSecond + (bytesPerSecond * burstAllowancePercent / 100);
                this.tokens = new AtomicLong(capacity);
                this.refillAmountPer100ms = bytesPerSecond / 10;
            }
        }

        /**
         * Try to consume tokens. Returns true if allowed, false if insufficient tokens.
         */
        boolean tryConsume(long bytes) {
            if (capacity == Long.MAX_VALUE) {
                totalConsumed.addAndGet(bytes);
                trackWindow(bytes);
                return true;
            }

            // CAS loop for lock-free thread safety
            while (true) {
                long current = tokens.get();
                if (current < bytes) return false;
                if (tokens.compareAndSet(current, current - bytes)) {
                    totalConsumed.addAndGet(bytes);
                    trackWindow(bytes);
                    return true;
                }
            }
        }

        /**
         * Refund tokens (when a downstream check fails and we need to undo consumption).
         */
        void refund(long bytes) {
            if (capacity == Long.MAX_VALUE) return;
            tokens.addAndGet(bytes);
        }

        /**
         * Refill tokens (called every 100ms by the scheduler).
         */
        void refill() {
            if (capacity == Long.MAX_VALUE) return;
            long current = tokens.get();
            long newVal = Math.min(capacity, current + refillAmountPer100ms);
            tokens.set(newVal);
        }

        /**
         * Get current throughput in bytes per second (sliding 1-second window).
         */
        long getCurrentBps() {
            long now = System.currentTimeMillis();
            long start = windowStart.get();
            long elapsed = now - start;

            if (elapsed >= 1000) {
                long bytes = windowBytes.getAndSet(0);
                windowStart.set(now);
                return elapsed > 0 ? bytes * 1000 / elapsed : 0;
            }

            return elapsed > 0 ? windowBytes.get() * 1000 / elapsed : 0;
        }

        long getLimit() {
            return capacity == Long.MAX_VALUE ? 0 : capacity;
        }

        long getTotalConsumed() {
            return totalConsumed.get();
        }

        private void trackWindow(long bytes) {
            windowBytes.addAndGet(bytes);
            long now = System.currentTimeMillis();
            if (now - windowStart.get() >= 1000) {
                windowBytes.set(bytes);
                windowStart.set(now);
            }
        }
    }

    // ── Per-connection tracking ───────────────────────────────────────

    private record ConnectionInfo(
        String mappingName,
        int priority,
        TokenBucket connectionBucket
    ) {}

    // ── State ─────────────────────────────────────────────────────────

    private final QoSConfig defaultConfig;
    private final TokenBucket globalBucket;

    // Per-mapping buckets: mappingName → TokenBucket
    private final ConcurrentHashMap<String, TokenBucket> mappingBuckets = new ConcurrentHashMap<>();

    // Per-connection info: connectionId → ConnectionInfo
    private final ConcurrentHashMap<String, ConnectionInfo> connections = new ConcurrentHashMap<>();

    // Per-mapping connection counts: mappingName → count
    private final ConcurrentHashMap<String, AtomicLong> mappingConnectionCounts = new ConcurrentHashMap<>();

    // Scheduler for token refill
    private ScheduledExecutorService refillScheduler;

    // ── Constructor ───────────────────────────────────────────────────

    /**
     * Create a new bandwidth QoS manager.
     *
     * @param defaultConfig default QoS configuration
     */
    public BandwidthQoS(QoSConfig defaultConfig) {
        this.defaultConfig = defaultConfig;
        this.globalBucket = new TokenBucket(
            defaultConfig.globalMaxBytesPerSecond(),
            defaultConfig.burstAllowancePercent());
    }

    // ── Lifecycle ─────────────────────────────────────────────────────

    /**
     * Start the QoS engine (begins token refill scheduler).
     * Must be called before {@link #tryConsume} or {@link #registerConnection}.
     */
    public void start() {
        if (!defaultConfig.enabled()) {
            log.info("BandwidthQoS is disabled — all traffic unlimited");
            return;
        }

        refillScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "qos-refill");
            t.setDaemon(true);
            return t;
        });

        // Refill every 100ms for smooth throughput
        refillScheduler.scheduleAtFixedRate(this::refillAll, 100, 100, TimeUnit.MILLISECONDS);

        log.info("BandwidthQoS started — global: {} B/s, per-mapping: {} B/s, per-connection: {} B/s, burst: {}%",
            defaultConfig.globalMaxBytesPerSecond(),
            defaultConfig.perMappingMaxBytesPerSecond(),
            defaultConfig.perConnectionMaxBytesPerSecond(),
            defaultConfig.burstAllowancePercent());
    }

    /**
     * Shutdown the QoS engine. Stops the refill scheduler and clears all tracked state.
     */
    public void shutdown() {
        if (refillScheduler != null) {
            refillScheduler.shutdownNow();
            try {
                if (!refillScheduler.awaitTermination(2, TimeUnit.SECONDS)) {
                    log.warn("QoS refill scheduler did not terminate within 2 seconds");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        connections.clear();
        mappingBuckets.clear();
        mappingConnectionCounts.clear();
        log.info("BandwidthQoS shutdown complete");
    }

    // ── Connection Management ─────────────────────────────────────────

    /**
     * Register a new connection for bandwidth tracking.
     *
     * @param connectionId unique connection identifier
     * @param mappingName  the port mapping name
     * @param priority     priority level (1 = highest, 10 = lowest)
     */
    public void registerConnection(String connectionId, String mappingName, int priority) {
        if (!defaultConfig.enabled()) return;

        int clampedPriority = Math.max(1, Math.min(10, priority));

        // Ensure per-mapping bucket exists
        mappingBuckets.computeIfAbsent(mappingName, k ->
            new TokenBucket(defaultConfig.perMappingMaxBytesPerSecond(),
                defaultConfig.burstAllowancePercent()));

        // Create per-connection bucket
        TokenBucket connBucket = new TokenBucket(
            defaultConfig.perConnectionMaxBytesPerSecond(),
            defaultConfig.burstAllowancePercent());

        connections.put(connectionId, new ConnectionInfo(mappingName, clampedPriority, connBucket));
        mappingConnectionCounts.computeIfAbsent(mappingName, k -> new AtomicLong())
            .incrementAndGet();

        log.debug("QoS: registered connection {} on mapping {} with priority {}",
            connectionId, mappingName, clampedPriority);
    }

    /**
     * Unregister a connection (on close). Releases the per-connection bucket.
     *
     * @param connectionId unique connection identifier
     */
    public void unregisterConnection(String connectionId) {
        ConnectionInfo info = connections.remove(connectionId);
        if (info != null) {
            AtomicLong count = mappingConnectionCounts.get(info.mappingName());
            if (count != null) {
                count.decrementAndGet();
            }
            log.debug("QoS: unregistered connection {} from mapping {}", connectionId, info.mappingName());
        }
    }

    // ── Bandwidth Control ─────────────────────────────────────────────

    /**
     * Attempt to send bytes for a connection. Uses weighted fair queuing when
     * global bandwidth is constrained.
     *
     * <p>Checks three levels in order: global → per-mapping → per-connection.
     * If any level rejects, previously consumed tokens are refunded and the caller
     * should apply backpressure (delay the write).</p>
     *
     * <p>Weighted fair queuing: higher priority connections effectively cost fewer
     * tokens from the global bucket. Weight = {@code 11 - priority}, so priority 1
     * gets 4x the bandwidth of priority 5 under contention:</p>
     * <pre>
     *   weightedBytes = bytes * 5 / weight
     *   priority 1 → weight 10 → weightedBytes = bytes * 0.5
     *   priority 5 → weight  6 → weightedBytes = bytes * 0.83
     *   priority 10 → weight  1 → weightedBytes = bytes * 5.0
     * </pre>
     *
     * @param connectionId unique connection identifier
     * @param mappingName  the port mapping name
     * @param priority     priority level (1 = highest, 10 = lowest)
     * @param bytes        number of bytes to send
     * @return true if the bytes may be sent, false if the caller should backpressure
     */
    public boolean tryConsume(String connectionId, String mappingName, int priority, long bytes) {
        if (!defaultConfig.enabled()) return true;
        if (bytes <= 0) return true;

        int clampedPriority = Math.max(1, Math.min(10, priority));
        int weight = 11 - clampedPriority;
        // Base weight = 5 (for priority 5). Priority 1 pays half, priority 10 pays 5x.
        long weightedBytes = Math.max(1, bytes * 5 / weight);

        // 1. Global bucket (weighted)
        if (defaultConfig.globalMaxBytesPerSecond() > 0) {
            if (!globalBucket.tryConsume(weightedBytes)) {
                log.trace("QoS: global limit hit for connection {} (priority {})", connectionId, priority);
                return false;
            }
        }

        // 2. Per-mapping bucket
        if (defaultConfig.perMappingMaxBytesPerSecond() > 0) {
            TokenBucket mappingBucket = mappingBuckets.get(mappingName);
            if (mappingBucket != null && !mappingBucket.tryConsume(bytes)) {
                // Refund global tokens
                if (defaultConfig.globalMaxBytesPerSecond() > 0) {
                    globalBucket.refund(weightedBytes);
                }
                log.trace("QoS: mapping {} limit hit for connection {}", mappingName, connectionId);
                return false;
            }
        }

        // 3. Per-connection bucket
        if (defaultConfig.perConnectionMaxBytesPerSecond() > 0) {
            ConnectionInfo info = connections.get(connectionId);
            if (info != null && !info.connectionBucket().tryConsume(bytes)) {
                // Refund mapping and global tokens
                if (defaultConfig.perMappingMaxBytesPerSecond() > 0) {
                    TokenBucket mappingBucket = mappingBuckets.get(mappingName);
                    if (mappingBucket != null) mappingBucket.refund(bytes);
                }
                if (defaultConfig.globalMaxBytesPerSecond() > 0) {
                    globalBucket.refund(weightedBytes);
                }
                log.trace("QoS: connection {} limit hit", connectionId);
                return false;
            }
        }

        return true;
    }

    // ── Stats ─────────────────────────────────────────────────────────

    /**
     * Get bandwidth statistics for a specific mapping.
     *
     * @param mappingName the port mapping name
     * @return bandwidth stats, or null if mapping not tracked
     */
    public BandwidthStats getStats(String mappingName) {
        TokenBucket bucket = mappingBuckets.get(mappingName);
        if (bucket == null) return null;

        AtomicLong connCount = mappingConnectionCounts.get(mappingName);
        long currentBps = bucket.getCurrentBps();
        long limitBps = bucket.getLimit();
        double utilization = limitBps > 0 ? (double) currentBps / limitBps * 100.0 : 0.0;

        int activeConns = 0;
        if (connCount != null) {
            long count = connCount.get();
            activeConns = count > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) count;
        }

        return new BandwidthStats(
            currentBps,
            limitBps,
            Math.min(utilization, 100.0),
            activeConns,
            bucket.getTotalConsumed()
        );
    }

    /**
     * Get bandwidth statistics for all active mappings.
     *
     * @return map of mapping name to stats
     */
    public Map<String, BandwidthStats> getAllStats() {
        Map<String, BandwidthStats> allStats = new LinkedHashMap<>();
        for (String mappingName : mappingBuckets.keySet()) {
            BandwidthStats stats = getStats(mappingName);
            if (stats != null) {
                allStats.put(mappingName, stats);
            }
        }
        return allStats;
    }

    /**
     * Get global QoS statistics including all mappings and the global bucket.
     *
     * @return map of stat name to value
     */
    public Map<String, Object> getGlobalStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("enabled", defaultConfig.enabled());
        stats.put("globalCurrentBps", globalBucket.getCurrentBps());
        stats.put("globalLimitBps", globalBucket.getLimit());
        stats.put("globalTotalBytes", globalBucket.getTotalConsumed());
        stats.put("totalConnections", connections.size());
        stats.put("totalMappings", mappingBuckets.size());
        stats.put("mappings", getAllStats());
        return stats;
    }

    /**
     * Map security tier to QoS priority.
     *
     * @param securityTier the security tier string (RULES, AI, AI_LLM)
     * @return priority level (1 = highest, 10 = lowest)
     */
    public static int tierToPriority(String securityTier) {
        if (securityTier == null) return 5;
        return switch (securityTier) {
            case "RULES"  -> 1;
            case "AI"     -> 3;
            case "AI_LLM" -> 5;
            default       -> 5;
        };
    }

    // ── Private ───────────────────────────────────────────────────────

    /**
     * Refill all token buckets. Called every 100ms by the scheduler for smooth throughput.
     */
    private void refillAll() {
        try {
            // Refill global bucket
            globalBucket.refill();

            // Refill per-mapping buckets
            for (TokenBucket bucket : mappingBuckets.values()) {
                bucket.refill();
            }

            // Refill per-connection buckets
            for (ConnectionInfo info : connections.values()) {
                info.connectionBucket().refill();
            }
        } catch (Exception e) {
            log.warn("Error during QoS token refill: {}", e.getMessage());
        }
    }
}
