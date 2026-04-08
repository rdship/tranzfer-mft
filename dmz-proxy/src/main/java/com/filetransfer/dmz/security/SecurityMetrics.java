package com.filetransfer.dmz.security;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Centralized security metrics for the DMZ proxy.
 * Thread-safe counters and distributions for monitoring and dashboards.
 *
 * Dual-mode: maintains internal AtomicLong counters (for getFullStats() REST API)
 * AND registers Micrometer metrics (for Prometheus scraping) when a MeterRegistry is provided.
 *
 * Product-agnostic: reports generic proxy security metrics.
 */
public class SecurityMetrics {

    // ── Counters ───────────────────────────────────────────────────────

    private final AtomicLong totalConnections = new AtomicLong(0);
    private final AtomicLong allowedConnections = new AtomicLong(0);
    private final AtomicLong throttledConnections = new AtomicLong(0);
    private final AtomicLong blockedConnections = new AtomicLong(0);
    private final AtomicLong blackholedConnections = new AtomicLong(0);
    private final AtomicLong rateLimitedConnections = new AtomicLong(0);
    private final AtomicLong totalBytesIn = new AtomicLong(0);
    private final AtomicLong totalBytesOut = new AtomicLong(0);
    private final AtomicLong aiVerdictRequests = new AtomicLong(0);
    private final AtomicLong aiVerdictCacheHits = new AtomicLong(0);
    private final AtomicLong aiVerdictFallbacks = new AtomicLong(0);
    private final AtomicLong protocolDetections = new AtomicLong(0);
    private final AtomicLong dpiBlocks = new AtomicLong(0);

    // Per-protocol counters
    private final ConcurrentHashMap<String, AtomicLong> protocolCounts = new ConcurrentHashMap<>();
    // Per-action counters
    private final ConcurrentHashMap<String, AtomicLong> actionCounts = new ConcurrentHashMap<>();
    // Per-port counters
    private final ConcurrentHashMap<Integer, AtomicLong> portCounts = new ConcurrentHashMap<>();

    private final Instant startedAt = Instant.now();

    // ── Micrometer counters (null when no registry is provided) ──
    private Counter mAllowed;
    private Counter mBlocked;
    private Counter mThrottled;
    private Counter mBytesIn;
    private Counter mBytesOut;
    private Counter mAiAllow;
    private Counter mAiBlock;
    private Counter mAiThrottle;
    private Counter mAiFallback;
    private Counter mRateLimited;
    private Counter mDpiBlocks;

    // ── Micrometer registration ────────────────────────────────────────

    /**
     * Register Micrometer metrics with the given registry.
     * Call once after construction; safe to call before any recording.
     *
     * Note: dmz_connections_active and dmz_backend_health gauges are registered
     * externally by ProxyManager (they depend on ConnectionTracker / BackendHealthChecker).
     *
     * @param registry the MeterRegistry (typically PrometheusMeterRegistry)
     */
    public void registerMicrometer(MeterRegistry registry) {
        // dmz_connections_total (counter, tags: action=allowed|blocked|throttled)
        mAllowed = Counter.builder("dmz_connections_total")
                .description("Total DMZ proxy connections by action")
                .tag("action", "allowed")
                .register(registry);
        mBlocked = Counter.builder("dmz_connections_total")
                .description("Total DMZ proxy connections by action")
                .tag("action", "blocked")
                .register(registry);
        mThrottled = Counter.builder("dmz_connections_total")
                .description("Total DMZ proxy connections by action")
                .tag("action", "throttled")
                .register(registry);

        // dmz_bytes_transferred_total (counter, tags: direction=inbound|outbound)
        mBytesIn = Counter.builder("dmz_bytes_transferred_total")
                .description("Total bytes transferred through DMZ proxy")
                .tag("direction", "inbound")
                .register(registry);
        mBytesOut = Counter.builder("dmz_bytes_transferred_total")
                .description("Total bytes transferred through DMZ proxy")
                .tag("direction", "outbound")
                .register(registry);

        // dmz_ai_verdicts_total (counter, tags: action=allow|block|throttle)
        mAiAllow = Counter.builder("dmz_ai_verdicts_total")
                .description("AI verdict decisions")
                .tag("action", "allow")
                .register(registry);
        mAiBlock = Counter.builder("dmz_ai_verdicts_total")
                .description("AI verdict decisions")
                .tag("action", "block")
                .register(registry);
        mAiThrottle = Counter.builder("dmz_ai_verdicts_total")
                .description("AI verdict decisions")
                .tag("action", "throttle")
                .register(registry);

        // dmz_ai_fallback_total (counter)
        mAiFallback = Counter.builder("dmz_ai_fallback_total")
                .description("AI verdict fallbacks (AI engine unavailable)")
                .register(registry);

        // dmz_rate_limited_total (counter)
        mRateLimited = Counter.builder("dmz_rate_limited_total")
                .description("Connections rejected by rate limiter")
                .register(registry);

        // dmz_dpi_blocks_total (counter)
        mDpiBlocks = Counter.builder("dmz_dpi_blocks_total")
                .description("Connections blocked by deep packet inspection")
                .register(registry);
    }

    /**
     * Register backend health gauges. Call after health checker is initialized.
     *
     * @param registry the MeterRegistry
     * @param healthChecker the backend health checker to read status from
     * @param backendNames the names of backends to register gauges for
     */
    public void registerBackendHealthGauges(MeterRegistry registry,
            com.filetransfer.dmz.health.BackendHealthChecker healthChecker,
            Collection<String> backendNames) {
        for (String name : backendNames) {
            registry.gauge("dmz_backend_health", Tags.of("backend", name),
                    healthChecker, hc -> hc.isHealthy(name) ? 1.0 : 0.0);
        }
    }

    // ── Recording ──────────────────────────────────────────────────────

    public void recordConnection() { totalConnections.incrementAndGet(); }

    public void recordAllowed() {
        allowedConnections.incrementAndGet();
        if (mAllowed != null) mAllowed.increment();
    }

    public void recordThrottled() {
        throttledConnections.incrementAndGet();
        if (mThrottled != null) mThrottled.increment();
    }

    public void recordBlocked() {
        blockedConnections.incrementAndGet();
        if (mBlocked != null) mBlocked.increment();
    }

    public void recordBlackholed() {
        blackholedConnections.incrementAndGet();
        if (mBlocked != null) mBlocked.increment(); // blackhole counts as blocked for Prometheus
    }

    public void recordRateLimited() {
        rateLimitedConnections.incrementAndGet();
        if (mRateLimited != null) mRateLimited.increment();
    }

    public void recordBytesIn(long bytes) {
        totalBytesIn.addAndGet(bytes);
        if (mBytesIn != null) mBytesIn.increment(bytes);
    }

    public void recordBytesOut(long bytes) {
        totalBytesOut.addAndGet(bytes);
        if (mBytesOut != null) mBytesOut.increment(bytes);
    }

    public void recordAiVerdictRequest() { aiVerdictRequests.incrementAndGet(); }
    public void recordAiVerdictCacheHit() { aiVerdictCacheHits.incrementAndGet(); }

    public void recordAiVerdictFallback() {
        aiVerdictFallbacks.incrementAndGet();
        if (mAiFallback != null) mAiFallback.increment();
    }

    public void recordProtocol(String protocol) {
        protocolDetections.incrementAndGet();
        if (protocol != null) {
            protocolCounts.computeIfAbsent(protocol, k -> new AtomicLong(0)).incrementAndGet();
        }
    }

    public void recordAction(String action) {
        actionCounts.computeIfAbsent(action, k -> new AtomicLong(0)).incrementAndGet();
        // Map action strings to Micrometer AI verdict counters
        if (action != null) {
            switch (action) {
                case "ALLOW" -> { if (mAiAllow != null) mAiAllow.increment(); }
                case "BLOCK", "MANUAL_BLOCK" -> { if (mAiBlock != null) mAiBlock.increment(); }
                case "THROTTLE" -> { if (mAiThrottle != null) mAiThrottle.increment(); }
                case "DPI_BLOCK" -> {
                    dpiBlocks.incrementAndGet();
                    if (mDpiBlocks != null) mDpiBlocks.increment();
                }
                default -> { /* other actions tracked only in actionCounts */ }
            }
        }
    }

    public void recordPort(int port) {
        portCounts.computeIfAbsent(port, k -> new AtomicLong(0)).incrementAndGet();
    }

    // ── Stats ──────────────────────────────────────────────────────────

    public Map<String, Object> getFullStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("upSince", startedAt.toString());
        stats.put("uptimeSeconds", java.time.Duration.between(startedAt, Instant.now()).getSeconds());

        // Connection stats
        Map<String, Object> connections = new LinkedHashMap<>();
        connections.put("total", totalConnections.get());
        connections.put("allowed", allowedConnections.get());
        connections.put("throttled", throttledConnections.get());
        connections.put("blocked", blockedConnections.get());
        connections.put("blackholed", blackholedConnections.get());
        connections.put("rateLimited", rateLimitedConnections.get());
        long total = totalConnections.get();
        if (total > 0) {
            connections.put("blockRate", String.format("%.2f%%",
                (blockedConnections.get() + blackholedConnections.get()) * 100.0 / total));
        }
        stats.put("connections", connections);

        // Throughput
        Map<String, Object> throughput = new LinkedHashMap<>();
        throughput.put("totalBytesIn", totalBytesIn.get());
        throughput.put("totalBytesOut", totalBytesOut.get());
        throughput.put("totalBytes", totalBytesIn.get() + totalBytesOut.get());
        stats.put("throughput", throughput);

        // AI engine
        Map<String, Object> ai = new LinkedHashMap<>();
        ai.put("verdictRequests", aiVerdictRequests.get());
        ai.put("cacheHits", aiVerdictCacheHits.get());
        ai.put("fallbacks", aiVerdictFallbacks.get());
        long aiTotal = aiVerdictRequests.get();
        if (aiTotal > 0) {
            ai.put("cacheHitRate", String.format("%.2f%%",
                aiVerdictCacheHits.get() * 100.0 / aiTotal));
        }
        stats.put("aiEngine", ai);

        // Protocol distribution
        Map<String, Long> protocols = new LinkedHashMap<>();
        protocolCounts.forEach((k, v) -> protocols.put(k, v.get()));
        stats.put("protocols", protocols);

        // Port distribution
        Map<Integer, Long> ports = new LinkedHashMap<>();
        portCounts.forEach((k, v) -> ports.put(k, v.get()));
        stats.put("ports", ports);

        return stats;
    }

    public Map<String, Long> getConnectionSummary() {
        Map<String, Long> summary = new LinkedHashMap<>();
        summary.put("total", totalConnections.get());
        summary.put("allowed", allowedConnections.get());
        summary.put("blocked", blockedConnections.get() + blackholedConnections.get());
        summary.put("throttled", throttledConnections.get());
        summary.put("rateLimited", rateLimitedConnections.get());
        return summary;
    }
}
