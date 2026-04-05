package com.filetransfer.dmz.security;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Centralized security metrics for the DMZ proxy.
 * Thread-safe counters and distributions for monitoring and dashboards.
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

    // Per-protocol counters
    private final ConcurrentHashMap<String, AtomicLong> protocolCounts = new ConcurrentHashMap<>();
    // Per-action counters
    private final ConcurrentHashMap<String, AtomicLong> actionCounts = new ConcurrentHashMap<>();
    // Per-port counters
    private final ConcurrentHashMap<Integer, AtomicLong> portCounts = new ConcurrentHashMap<>();

    private final Instant startedAt = Instant.now();

    // ── Recording ──────────────────────────────────────────────────────

    public void recordConnection() { totalConnections.incrementAndGet(); }
    public void recordAllowed() { allowedConnections.incrementAndGet(); }
    public void recordThrottled() { throttledConnections.incrementAndGet(); }
    public void recordBlocked() { blockedConnections.incrementAndGet(); }
    public void recordBlackholed() { blackholedConnections.incrementAndGet(); }
    public void recordRateLimited() { rateLimitedConnections.incrementAndGet(); }
    public void recordBytesIn(long bytes) { totalBytesIn.addAndGet(bytes); }
    public void recordBytesOut(long bytes) { totalBytesOut.addAndGet(bytes); }
    public void recordAiVerdictRequest() { aiVerdictRequests.incrementAndGet(); }
    public void recordAiVerdictCacheHit() { aiVerdictCacheHits.incrementAndGet(); }
    public void recordAiVerdictFallback() { aiVerdictFallbacks.incrementAndGet(); }

    public void recordProtocol(String protocol) {
        protocolDetections.incrementAndGet();
        if (protocol != null) {
            protocolCounts.computeIfAbsent(protocol, k -> new AtomicLong(0)).incrementAndGet();
        }
    }

    public void recordAction(String action) {
        actionCounts.computeIfAbsent(action, k -> new AtomicLong(0)).incrementAndGet();
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
