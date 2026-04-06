package com.filetransfer.ai.service.proxy;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Connection Pattern Analyzer — detects DDoS, connection floods, slow-loris attacks,
 * scanning patterns, and abnormal connection behavior at the network level.
 *
 * Product-agnostic: analyzes raw connection patterns without protocol assumptions.
 *
 * Tracks patterns across:
 * - Per-IP connection rates
 * - Global connection rates (DDoS detection)
 * - Connection duration distributions (slow-loris)
 * - Port distribution per IP (scanning)
 * - Data volume anomalies
 */
@Slf4j
@Service
public class ConnectionPatternAnalyzer {

    /** Analysis result for a given IP */
    public record PatternAnalysis(
        String ip,
        List<String> patterns,       // detected pattern names
        int riskScore,               // 0-100
        Map<String, Object> details
    ) {}

    /** Global traffic snapshot */
    public record TrafficSnapshot(
        long connectionsPerMinute,
        long activeConnections,
        long uniqueIpsLast5Min,
        boolean ddosLikely,
        int globalRiskScore,
        List<String> alerts
    ) {}

    // ── Per-IP Tracking ────────────────────────────────────────────────

    private static class IpConnectionProfile {
        final Deque<Instant> connectionTimestamps = new ArrayDeque<>();
        final Deque<Long> connectionDurations = new ArrayDeque<>(); // ms
        final Set<Integer> portsAttempted = ConcurrentHashMap.newKeySet();
        final AtomicLong activeConnections = new AtomicLong(0);
        final AtomicLong totalBytes = new AtomicLong(0);
        volatile Instant firstSeen = Instant.now();
        volatile Instant lastSeen = Instant.now();

        synchronized void recordConnection(int port) {
            Instant now = Instant.now();
            connectionTimestamps.addLast(now);
            portsAttempted.add(port);
            activeConnections.incrementAndGet();
            lastSeen = now;
            // Keep last 2000 timestamps
            while (connectionTimestamps.size() > 2000) connectionTimestamps.pollFirst();
        }

        synchronized void connectionClosed(long durationMs) {
            activeConnections.decrementAndGet();
            connectionDurations.addLast(durationMs);
            while (connectionDurations.size() > 500) connectionDurations.pollFirst();
        }

        synchronized int connectionsInWindow(int minutes) {
            Instant cutoff = Instant.now().minus(minutes, ChronoUnit.MINUTES);
            return (int) connectionTimestamps.stream().filter(t -> t.isAfter(cutoff)).count();
        }

        synchronized double avgDurationMs() {
            return connectionDurations.stream().mapToLong(Long::longValue).average().orElse(0.0);
        }

        synchronized long minDurationMs() {
            return connectionDurations.stream().mapToLong(Long::longValue).min().orElse(0L);
        }

        synchronized int countShortConnections(long thresholdMs) {
            return (int) connectionDurations.stream()
                .filter(d -> d < thresholdMs).count();
        }

        synchronized int countLongConnections(long thresholdMs) {
            return (int) connectionDurations.stream()
                .filter(d -> d > thresholdMs).count();
        }
    }

    private final ConcurrentHashMap<String, IpConnectionProfile> profiles = new ConcurrentHashMap<>();
    private static final int MAX_TRACKED_IPS = 100_000;

    // ── Global Tracking ────────────────────────────────────────────────

    private final Deque<Instant> globalConnections = new ArrayDeque<>();
    private final AtomicLong globalActiveConnections = new AtomicLong(0);
    private static final int GLOBAL_HISTORY_SIZE = 50_000;

    // ── Thresholds (configurable) ──────────────────────────────────────

    private int maxConnectionsPerIpPerMinute = 60;
    private int maxActiveConnectionsPerIp = 50;
    private int ddosGlobalThresholdPerMinute = 5000;
    private int portScanThreshold = 5;           // distinct ports in 10 min
    private long slowLorisThresholdMs = 300_000;  // 5 min idle connection
    private long bannerGrabThresholdMs = 500;     // < 500ms = banner grab

    // ── Event Recording ────────────────────────────────────────────────

    public void recordConnectionOpen(String ip, int port) {
        // Evict oldest profiles if at capacity (Improvement #4: LRU cap)
        if (profiles.size() >= MAX_TRACKED_IPS) {
            evictOldestProfiles();
        }
        IpConnectionProfile profile = profiles.computeIfAbsent(ip, k -> new IpConnectionProfile());
        profile.recordConnection(port);
        synchronized (globalConnections) {
            globalConnections.addLast(Instant.now());
            while (globalConnections.size() > GLOBAL_HISTORY_SIZE) globalConnections.pollFirst();
        }
        globalActiveConnections.incrementAndGet();
    }

    public void recordConnectionClose(String ip, long durationMs, long bytes) {
        IpConnectionProfile profile = profiles.get(ip);
        if (profile != null) {
            profile.connectionClosed(durationMs);
            profile.totalBytes.addAndGet(bytes);
        }
        globalActiveConnections.decrementAndGet();
    }

    // ── Per-IP Analysis ────────────────────────────────────────────────

    public PatternAnalysis analyzeIp(String ip) {
        IpConnectionProfile profile = profiles.get(ip);
        if (profile == null) {
            return new PatternAnalysis(ip, List.of(), 0, Map.of("status", "unknown"));
        }

        List<String> patterns = new ArrayList<>();
        int riskScore = 0;
        Map<String, Object> details = new LinkedHashMap<>();

        // 1. Connection flood
        int connPerMin = profile.connectionsInWindow(1);
        if (connPerMin > maxConnectionsPerIpPerMinute) {
            patterns.add("CONNECTION_FLOOD");
            riskScore += 40 + Math.min(40, (connPerMin - maxConnectionsPerIpPerMinute));
            details.put("connectionsPerMinute", connPerMin);
            details.put("threshold", maxConnectionsPerIpPerMinute);
        }

        // 2. Active connection saturation
        long activeConns = profile.activeConnections.get();
        if (activeConns > maxActiveConnectionsPerIp) {
            patterns.add("CONNECTION_SATURATION");
            riskScore += 30;
            details.put("activeConnections", activeConns);
        }

        // 3. Port scanning
        int distinctPorts = profile.portsAttempted.size();
        if (distinctPorts >= portScanThreshold) {
            patterns.add("PORT_SCAN");
            riskScore += 20 + Math.min(30, distinctPorts * 3);
            details.put("portsAttempted", new ArrayList<>(profile.portsAttempted));
        }

        // 4. Slow-loris pattern (many long-lived connections)
        int longConns = profile.countLongConnections(slowLorisThresholdMs);
        if (longConns > 3) {
            patterns.add("SLOW_LORIS");
            riskScore += 35;
            details.put("longLivedConnections", longConns);
        }

        // 5. Banner grabbing pattern (many very short connections)
        int shortConns = profile.countShortConnections(bannerGrabThresholdMs);
        int totalConns = profile.connectionsInWindow(10);
        if (shortConns > 5 && totalConns > 0 && (double) shortConns / totalConns > 0.7) {
            patterns.add("BANNER_GRAB");
            riskScore += 25;
            details.put("shortConnections", shortConns);
            details.put("shortConnectionRatio", Math.round((double) shortConns / totalConns * 100) + "%");
        }

        // 6. Connection recycling (rapid connect/disconnect)
        int conn5Min = profile.connectionsInWindow(5);
        double avgDur = profile.avgDurationMs();
        if (conn5Min > 20 && avgDur < 2000) {
            patterns.add("CONNECTION_RECYCLING");
            riskScore += 20;
            details.put("connectionsLast5Min", conn5Min);
            details.put("avgDurationMs", Math.round(avgDur));
        }

        riskScore = Math.min(100, riskScore);
        details.put("totalConnectionsLast10Min", profile.connectionsInWindow(10));
        details.put("activeConnections", activeConns);
        details.put("distinctPorts", distinctPorts);

        return new PatternAnalysis(ip, patterns, riskScore, details);
    }

    // ── Global Traffic Analysis ────────────────────────────────────────

    public TrafficSnapshot analyzeGlobalTraffic() {
        long connPerMin;
        long uniqueIps5Min;
        synchronized (globalConnections) {
            Instant oneMinAgo = Instant.now().minus(1, ChronoUnit.MINUTES);
            connPerMin = globalConnections.stream().filter(t -> t.isAfter(oneMinAgo)).count();
        }

        Instant fiveMinAgo = Instant.now().minus(5, ChronoUnit.MINUTES);
        uniqueIps5Min = profiles.entrySet().stream()
            .filter(e -> e.getValue().lastSeen.isAfter(fiveMinAgo))
            .count();

        long active = globalActiveConnections.get();
        boolean ddosLikely = connPerMin > ddosGlobalThresholdPerMinute;
        List<String> alerts = new ArrayList<>();
        int globalRisk = 0;

        if (ddosLikely) {
            alerts.add("DDoS: " + connPerMin + " conn/min exceeds threshold " + ddosGlobalThresholdPerMinute);
            globalRisk = 80;
        }

        if (uniqueIps5Min > 1000 && connPerMin > ddosGlobalThresholdPerMinute / 2) {
            alerts.add("Distributed attack: " + uniqueIps5Min + " unique IPs in 5 min with high conn rate");
            globalRisk = Math.max(globalRisk, 70);
        }

        if (active > 10_000) {
            alerts.add("Connection saturation: " + active + " active connections");
            globalRisk = Math.max(globalRisk, 60);
        }

        return new TrafficSnapshot(connPerMin, active, uniqueIps5Min, ddosLikely,
            Math.min(100, globalRisk), alerts);
    }

    // ── Stats ──────────────────────────────────────────────────────────

    public Map<String, Object> getStats() {
        TrafficSnapshot traffic = analyzeGlobalTraffic();
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("trackedIps", profiles.size());
        stats.put("globalActiveConnections", globalActiveConnections.get());
        stats.put("connectionsPerMinute", traffic.connectionsPerMinute());
        stats.put("uniqueIpsLast5Min", traffic.uniqueIpsLast5Min());
        stats.put("ddosLikely", traffic.ddosLikely());
        stats.put("globalRiskScore", traffic.globalRiskScore());
        if (!traffic.alerts().isEmpty()) {
            stats.put("alerts", traffic.alerts());
        }
        return stats;
    }

    // ── Configuration ──────────────────────────────────────────────────

    public void setMaxConnectionsPerIpPerMinute(int max) { this.maxConnectionsPerIpPerMinute = max; }
    public void setMaxActiveConnectionsPerIp(int max) { this.maxActiveConnectionsPerIp = max; }
    public void setDdosGlobalThresholdPerMinute(int threshold) { this.ddosGlobalThresholdPerMinute = threshold; }

    // ── Cleanup ────────────────────────────────────────────────────────

    public void evictStaleProfiles(int hoursOld) {
        Instant cutoff = Instant.now().minus(hoursOld, ChronoUnit.HOURS);
        profiles.entrySet().removeIf(e -> e.getValue().lastSeen.isBefore(cutoff));
    }

    /** Evict the 10% oldest profiles when at capacity (Improvement #4) */
    private void evictOldestProfiles() {
        int toEvict = MAX_TRACKED_IPS / 10;
        profiles.entrySet().stream()
            .sorted(Comparator.comparing(e -> e.getValue().lastSeen))
            .limit(toEvict)
            .map(Map.Entry::getKey)
            .toList()
            .forEach(profiles::remove);
        log.info("Evicted {} stale IP profiles, remaining: {}", toEvict, profiles.size());
    }
}
