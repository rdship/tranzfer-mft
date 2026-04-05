package com.filetransfer.ai.service.proxy;

import com.filetransfer.ai.service.proxy.ProtocolThreatDetector.ConnectionEvent;
import com.filetransfer.ai.service.proxy.ProtocolThreatDetector.ThreatSignal;
import com.filetransfer.ai.service.proxy.ConnectionPatternAnalyzer.PatternAnalysis;
import com.filetransfer.ai.service.proxy.GeoAnomalyDetector.GeoAnomaly;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Proxy Intelligence Service — the central brain that orchestrates all proxy security
 * analyzers and computes real-time verdicts for incoming connections.
 *
 * Product-agnostic: any TCP reverse proxy can use this as its security backend.
 *
 * Verdict flow:
 * 1. Proxy sends connection request (IP, port, protocol)
 * 2. This service queries all analyzers in parallel
 * 3. Composite risk score computed with weighted factors
 * 4. Verdict returned: ALLOW, THROTTLE, CHALLENGE, BLOCK, BLACKHOLE
 * 5. Async events update the intelligence model continuously
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProxyIntelligenceService {

    private final IpReputationService reputationService;
    private final ProtocolThreatDetector threatDetector;
    private final ConnectionPatternAnalyzer patternAnalyzer;
    private final GeoAnomalyDetector geoDetector;

    // ── Verdict Model ──────────────────────────────────────────────────

    public enum Action {
        ALLOW,      // Connection permitted
        THROTTLE,   // Allowed but rate-limited
        CHALLENGE,  // Require additional auth verification
        BLOCK,      // Connection refused (RST)
        BLACKHOLE   // Silently drop (no response)
    }

    public record Verdict(
        Action action,
        int riskScore,          // 0-100 composite
        String reason,
        int ttlSeconds,         // how long proxy should cache this verdict
        RateLimit rateLimit,    // rate limit to apply (null if no throttle)
        List<String> signals,   // contributing threat signals
        Map<String, Object> metadata
    ) {}

    public record RateLimit(
        int maxConnectionsPerMinute,
        int maxConcurrentConnections,
        long maxBytesPerMinute
    ) {}

    // ── Threat Event from Proxy ────────────────────────────────────────

    public record ThreatEvent(
        String eventType,       // CONNECTION_OPENED, CONNECTION_CLOSED, BYTES_TRANSFERRED, RATE_LIMIT_HIT, REJECTED
        String sourceIp,
        int sourcePort,
        int targetPort,
        String detectedProtocol,
        long bytesIn,
        long bytesOut,
        long durationMs,
        boolean blocked,
        String blockReason,
        String account,         // optional: resolved account/username
        String country,         // optional: resolved country
        Map<String, Object> metadata
    ) {}

    // ── Counters ───────────────────────────────────────────────────────

    private final AtomicLong totalVerdicts = new AtomicLong(0);
    private final AtomicLong totalAllowed = new AtomicLong(0);
    private final AtomicLong totalThrottled = new AtomicLong(0);
    private final AtomicLong totalBlocked = new AtomicLong(0);
    private final AtomicLong totalBlackholed = new AtomicLong(0);
    private final AtomicLong totalEvents = new AtomicLong(0);

    // Recent verdicts ring buffer for audit trail
    private final Deque<Map<String, Object>> recentVerdicts = new ArrayDeque<>();
    private static final int MAX_RECENT_VERDICTS = 500;

    // Active threat alerts
    private final ConcurrentHashMap<String, Map<String, Object>> activeAlerts = new ConcurrentHashMap<>();

    // ── Verdict Computation (hot path) ─────────────────────────────────

    /**
     * Compute a verdict for an incoming connection.
     * This is the hot path — must be fast.
     */
    public Verdict computeVerdict(String sourceIp, int targetPort, String protocol) {
        totalVerdicts.incrementAndGet();
        List<String> signals = new ArrayList<>();
        Map<String, Object> metadata = new LinkedHashMap<>();

        // ── Fast path: check blocklist/allowlist first ──
        if (reputationService.isBlocked(sourceIp)) {
            totalBlackholed.incrementAndGet();
            recordVerdict(sourceIp, Action.BLACKHOLE, 100, "blocklisted");
            return new Verdict(Action.BLACKHOLE, 100, "IP is blocklisted",
                600, null, List.of("BLOCKLISTED"), Map.of());
        }

        if (reputationService.isAllowed(sourceIp)) {
            totalAllowed.incrementAndGet();
            recordVerdict(sourceIp, Action.ALLOW, 0, "allowlisted");
            return new Verdict(Action.ALLOW, 0, "IP is allowlisted",
                300, null, List.of(), Map.of());
        }

        // ── Gather intelligence from all analyzers ──

        // 1. IP Reputation
        double reputationScore = reputationService.getScore(sourceIp);
        boolean isNew = reputationService.isNewIp(sourceIp);
        reputationService.recordConnection(sourceIp, protocol);

        if (isNew) signals.add("NEW_IP");
        if (reputationScore < 20) signals.add("LOW_REPUTATION:" + Math.round(reputationScore));
        metadata.put("reputationScore", Math.round(reputationScore * 10.0) / 10.0);
        metadata.put("isNewIp", isNew);

        // 2. Connection Patterns
        patternAnalyzer.recordConnectionOpen(sourceIp, targetPort);
        PatternAnalysis patterns = patternAnalyzer.analyzeIp(sourceIp);
        signals.addAll(patterns.patterns());
        metadata.put("patternRisk", patterns.riskScore());

        // 3. Geo Analysis (if country is cached)
        Optional<String> country = geoDetector.getCachedCountry(sourceIp);
        int geoRisk = 0;
        if (country.isPresent()) {
            List<GeoAnomaly> geoAnomalies = geoDetector.analyze(sourceIp, country.get(), null);
            for (GeoAnomaly anomaly : geoAnomalies) {
                signals.add(anomaly.type());
                geoRisk = Math.max(geoRisk, anomaly.severity());
            }
            metadata.put("country", country.get());
        }
        metadata.put("geoRisk", geoRisk);

        // ── Compute composite risk score ──
        // Weighted formula:
        //   - IP reputation inverted (100 - score): weight 0.35
        //   - Connection pattern risk: weight 0.30
        //   - Geo risk: weight 0.15
        //   - New IP penalty: weight 0.10
        //   - Protocol risk (SSH/FTP higher baseline): weight 0.10

        double reputationRisk = 100.0 - reputationScore;
        double protocolRisk = getProtocolBaselineRisk(protocol);
        double newIpPenalty = isNew ? 15.0 : 0.0;

        double compositeRisk =
            (reputationRisk * 0.35) +
            (patterns.riskScore() * 0.30) +
            (geoRisk * 0.15) +
            (newIpPenalty * 0.10) +
            (protocolRisk * 0.10);

        int riskScore = (int) Math.min(100, Math.max(0, compositeRisk));
        metadata.put("compositeBreakdown", Map.of(
            "reputation", Math.round(reputationRisk * 0.35),
            "pattern", Math.round(patterns.riskScore() * 0.30),
            "geo", Math.round(geoRisk * 0.15),
            "newIp", Math.round(newIpPenalty * 0.10),
            "protocol", Math.round(protocolRisk * 0.10)
        ));

        // ── Map risk score to action ──
        Action action;
        String reason;
        RateLimit rateLimit = null;
        int ttl;

        if (riskScore >= 85) {
            action = Action.BLOCK;
            reason = "Risk score " + riskScore + " exceeds BLOCK threshold (85)";
            ttl = 300;  // cache block for 5 min
            totalBlocked.incrementAndGet();

            // Auto-blocklist if extremely risky
            if (riskScore >= 95) {
                reputationService.blockIp(sourceIp, "auto:risk_" + riskScore);
                action = Action.BLACKHOLE;
                totalBlackholed.incrementAndGet();
                totalBlocked.decrementAndGet();
            }
        } else if (riskScore >= 60) {
            action = Action.THROTTLE;
            reason = "Risk score " + riskScore + " — throttling applied";
            rateLimit = computeRateLimit(riskScore, protocol);
            ttl = 120;  // cache throttle for 2 min
            totalThrottled.incrementAndGet();
        } else if (riskScore >= 40 && isNew) {
            action = Action.THROTTLE;
            reason = "New IP with moderate risk — conservative throttle";
            rateLimit = new RateLimit(10, 3, 50_000_000L);
            ttl = 60;
            totalThrottled.incrementAndGet();
        } else {
            action = Action.ALLOW;
            reason = "Risk score " + riskScore + " within acceptable range";
            ttl = riskScore < 10 ? 300 : 60;  // trusted IPs cached longer
            totalAllowed.incrementAndGet();
        }

        // Generate alert if high risk
        if (riskScore >= 60) {
            raiseAlert(sourceIp, riskScore, signals, action);
        }

        recordVerdict(sourceIp, action, riskScore, reason);

        return new Verdict(action, riskScore, reason, ttl, rateLimit, signals, metadata);
    }

    // ── Event Processing (async, from proxy) ───────────────────────────

    /**
     * Process a threat event reported by the proxy.
     * Updates all intelligence models. Fire-and-forget from proxy's perspective.
     */
    public void processEvent(ThreatEvent event) {
        totalEvents.incrementAndGet();
        String ip = event.sourceIp();

        switch (event.eventType()) {
            case "CONNECTION_OPENED" -> {
                reputationService.recordConnection(ip, event.detectedProtocol());
                if (event.country() != null) {
                    reputationService.recordCountry(ip, event.country());
                    geoDetector.cacheIpCountry(ip, event.country());
                }
            }

            case "CONNECTION_CLOSED" -> {
                patternAnalyzer.recordConnectionClose(ip, event.durationMs(),
                    event.bytesIn() + event.bytesOut());
                reputationService.recordBytes(ip, event.bytesIn() + event.bytesOut());

                // Run protocol threat analysis on completed connections
                ConnectionEvent connEvent = new ConnectionEvent(
                    ip, event.targetPort(), event.detectedProtocol(),
                    event.bytesIn(), event.bytesOut(), event.durationMs(),
                    event.metadata() != null ? event.metadata() : Map.of());
                List<ThreatSignal> threats = threatDetector.analyze(connEvent);

                if (!threats.isEmpty()) {
                    int maxSeverity = threats.stream().mapToInt(ThreatSignal::severity).max().orElse(0);
                    String reasons = threats.stream().map(ThreatSignal::threatType)
                        .distinct().reduce((a, b) -> a + "," + b).orElse("");
                    reputationService.recordFailure(ip, reasons);

                    if (maxSeverity >= 70) {
                        log.warn("High-severity threat from {}: {} (severity={})",
                            ip, reasons, maxSeverity);
                    }
                } else {
                    reputationService.recordSuccess(ip);
                }
            }

            case "RATE_LIMIT_HIT" -> {
                reputationService.recordFailure(ip, "rate_limit");
            }

            case "REJECTED" -> {
                reputationService.recordRejection(ip);
                reputationService.recordFailure(ip, event.blockReason());
            }

            case "AUTH_FAILURE" -> {
                reputationService.recordFailure(ip, "auth_failure");
                // Check for brute force
                IpReputationService.IpReputation rep = reputationService.getOrCreate(ip);
                ThreatSignal bruteForce = threatDetector.detectBruteForce(
                    ip, event.detectedProtocol() != null ? event.detectedProtocol() : "TCP",
                    rep.getFailuresInWindow(5), 5);
                if (bruteForce != null && bruteForce.severity() >= 80) {
                    reputationService.blockIp(ip, "auto:brute_force");
                    log.warn("Auto-blocked {} for brute force on {}", ip, event.detectedProtocol());
                }
            }

            case "BYTES_TRANSFERRED" -> {
                reputationService.recordBytes(ip, event.bytesIn() + event.bytesOut());
            }
        }

        // Geo analysis on all events with country data
        if (event.country() != null && !event.country().isEmpty()) {
            List<GeoAnomaly> geoAnomalies = geoDetector.analyze(
                ip, event.country(), event.account());
            for (GeoAnomaly anomaly : geoAnomalies) {
                if (anomaly.severity() >= 70) {
                    reputationService.recordFailure(ip, "geo:" + anomaly.type());
                }
            }
        }
    }

    // ── Blocklist Operations ───────────────────────────────────────────

    public void blockIp(String ip, String reason) {
        reputationService.blockIp(ip, reason);
    }

    public void unblockIp(String ip) {
        reputationService.unblockIp(ip);
    }

    public void allowIp(String ip) {
        reputationService.allowIp(ip);
    }

    public Set<String> getBlocklist() {
        return reputationService.getBlocklist();
    }

    public Set<String> getAllowlist() {
        return reputationService.getAllowlist();
    }

    // ── Dashboard & Stats ──────────────────────────────────────────────

    public Map<String, Object> getFullDashboard() {
        Map<String, Object> dashboard = new LinkedHashMap<>();

        // Verdict counters
        Map<String, Object> verdicts = new LinkedHashMap<>();
        verdicts.put("total", totalVerdicts.get());
        verdicts.put("allowed", totalAllowed.get());
        verdicts.put("throttled", totalThrottled.get());
        verdicts.put("blocked", totalBlocked.get());
        verdicts.put("blackholed", totalBlackholed.get());
        verdicts.put("eventsProcessed", totalEvents.get());
        dashboard.put("verdicts", verdicts);

        // Sub-service stats
        dashboard.put("ipReputation", reputationService.getStats());
        dashboard.put("connectionPatterns", patternAnalyzer.getStats());
        dashboard.put("geoIntelligence", geoDetector.getStats());

        // Global traffic
        dashboard.put("traffic", patternAnalyzer.analyzeGlobalTraffic());

        // Active alerts
        dashboard.put("activeAlerts", activeAlerts.size());
        dashboard.put("alerts", new ArrayList<>(activeAlerts.values()));

        // Top threats
        dashboard.put("topThreats", reputationService.getTopThreats(10));

        return dashboard;
    }

    public List<Map<String, Object>> getRecentVerdicts(int limit) {
        synchronized (recentVerdicts) {
            return recentVerdicts.stream()
                .limit(Math.min(limit, MAX_RECENT_VERDICTS))
                .toList();
        }
    }

    public Map<String, Object> getIpIntelligence(String ip) {
        Map<String, Object> intel = new LinkedHashMap<>();
        intel.put("reputation", reputationService.get(ip).map(IpReputationService.IpReputation::toMap)
            .orElse(Map.of("status", "unknown")));
        intel.put("connectionPattern", patternAnalyzer.analyzeIp(ip));
        intel.put("geo", geoDetector.getCachedCountry(ip).orElse("unknown"));
        intel.put("blocked", reputationService.isBlocked(ip));
        intel.put("allowed", reputationService.isAllowed(ip));
        return intel;
    }

    // ── Private Helpers ────────────────────────────────────────────────

    private double getProtocolBaselineRisk(String protocol) {
        if (protocol == null) return 20.0;
        return switch (protocol.toUpperCase()) {
            case "SSH" -> 15.0;     // SSH is commonly targeted
            case "FTP" -> 20.0;     // FTP inherently less secure
            case "HTTP" -> 10.0;
            case "TLS", "HTTPS", "FTPS" -> 5.0;  // encrypted = lower baseline
            default -> 10.0;
        };
    }

    private RateLimit computeRateLimit(int riskScore, String protocol) {
        // Higher risk = stricter limits
        int maxConn = riskScore >= 80 ? 2 : riskScore >= 70 ? 5 : 15;
        int maxConcurrent = riskScore >= 80 ? 1 : riskScore >= 70 ? 2 : 5;
        long maxBytes = riskScore >= 80 ? 1_000_000L : riskScore >= 70 ? 10_000_000L : 100_000_000L;

        return new RateLimit(maxConn, maxConcurrent, maxBytes);
    }

    private void recordVerdict(String ip, Action action, int risk, String reason) {
        Map<String, Object> record = Map.of(
            "ip", ip,
            "action", action.name(),
            "riskScore", risk,
            "reason", reason,
            "timestamp", Instant.now().toString()
        );
        synchronized (recentVerdicts) {
            recentVerdicts.addFirst(record);
            while (recentVerdicts.size() > MAX_RECENT_VERDICTS) recentVerdicts.pollLast();
        }
    }

    private void raiseAlert(String ip, int riskScore, List<String> signals, Action action) {
        Map<String, Object> alert = new LinkedHashMap<>();
        alert.put("ip", ip);
        alert.put("riskScore", riskScore);
        alert.put("signals", signals);
        alert.put("action", action.name());
        alert.put("timestamp", Instant.now().toString());
        activeAlerts.put(ip, alert);
    }

    /** Expire alerts older than 30 minutes */
    @Scheduled(fixedRate = 60_000)
    public void expireAlerts() {
        Instant cutoff = Instant.now().minusSeconds(1800);
        activeAlerts.entrySet().removeIf(e -> {
            String ts = (String) e.getValue().get("timestamp");
            return ts != null && Instant.parse(ts).isBefore(cutoff);
        });
    }

    /** Periodic cleanup of stale connection profiles */
    @Scheduled(fixedRate = 3600_000)
    public void cleanup() {
        patternAnalyzer.evictStaleProfiles(24);
    }
}
