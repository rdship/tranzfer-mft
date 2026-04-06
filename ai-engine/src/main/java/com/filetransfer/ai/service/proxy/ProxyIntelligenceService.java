package com.filetransfer.ai.service.proxy;

import com.filetransfer.ai.service.proxy.ProtocolThreatDetector.ConnectionEvent;
import com.filetransfer.ai.service.proxy.ProtocolThreatDetector.ThreatSignal;
import com.filetransfer.ai.service.proxy.ConnectionPatternAnalyzer.PatternAnalysis;
import com.filetransfer.ai.service.proxy.GeoAnomalyDetector.GeoAnomaly;
import com.filetransfer.ai.service.intelligence.ThreatIntelligenceStore;
import com.filetransfer.ai.service.intelligence.MitreAttackMapper;
import com.filetransfer.ai.service.detection.NetworkBehaviorAnalyzer;
import com.filetransfer.ai.service.detection.AttackChainDetector;
import com.filetransfer.ai.service.detection.ExplainabilityEngine;
import com.filetransfer.ai.service.response.PlaybookEngine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
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
public class ProxyIntelligenceService {

    private final IpReputationService reputationService;
    private final ProtocolThreatDetector threatDetector;
    private final ConnectionPatternAnalyzer patternAnalyzer;
    private final GeoAnomalyDetector geoDetector;

    // ── Enhanced AI capabilities (optional — gracefully degrade if absent) ──
    private final ThreatIntelligenceStore threatIntelStore;
    private final MitreAttackMapper mitreMapper;
    private final NetworkBehaviorAnalyzer networkAnalyzer;
    private final AttackChainDetector attackChainDetector;
    private final ExplainabilityEngine explainabilityEngine;
    private final PlaybookEngine playbookEngine;
    private final LlmSecurityEscalation llmEscalation;

    @Autowired
    public ProxyIntelligenceService(
            IpReputationService reputationService,
            ProtocolThreatDetector threatDetector,
            ConnectionPatternAnalyzer patternAnalyzer,
            GeoAnomalyDetector geoDetector,
            LlmSecurityEscalation llmEscalation,
            @Autowired(required = false) ThreatIntelligenceStore threatIntelStore,
            @Autowired(required = false) MitreAttackMapper mitreMapper,
            @Autowired(required = false) NetworkBehaviorAnalyzer networkAnalyzer,
            @Autowired(required = false) AttackChainDetector attackChainDetector,
            @Autowired(required = false) ExplainabilityEngine explainabilityEngine,
            @Autowired(required = false) PlaybookEngine playbookEngine) {
        this.reputationService = reputationService;
        this.threatDetector = threatDetector;
        this.patternAnalyzer = patternAnalyzer;
        this.geoDetector = geoDetector;
        this.llmEscalation = llmEscalation;
        this.threatIntelStore = threatIntelStore;
        this.mitreMapper = mitreMapper;
        this.networkAnalyzer = networkAnalyzer;
        this.attackChainDetector = attackChainDetector;
        this.explainabilityEngine = explainabilityEngine;
        this.playbookEngine = playbookEngine;
    }

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
    private final AtomicLong cacheHits = new AtomicLong(0);

    // ── Lock-free ring buffer for verdict audit trail (Improvement #3) ──
    private static final int MAX_RECENT_VERDICTS = 512; // power of 2 for fast modulo
    @SuppressWarnings("unchecked")
    private final Map<String, Object>[] verdictRing = new Map[MAX_RECENT_VERDICTS];
    private final AtomicInteger verdictRingHead = new AtomicInteger(0);

    // ── Verdict cache: avoids recomputing for same IP+port+protocol within TTL ──
    // Security: asymmetric TTL — BLOCK/BLACKHOLE cached longer (conservative), ALLOW cached short
    // Security: borderline verdicts (risk 30-59) are NEVER cached (too close to action threshold)
    // Security: composite key prevents cross-port/protocol verdict reuse
    private static final long CACHE_TTL_BLOCK_MS = 300_000;    // 5 min — safe to cache denials
    private static final long CACHE_TTL_THROTTLE_MS = 30_000;  // 30s — re-evaluate throttled IPs quickly
    private static final long CACHE_TTL_ALLOW_MS = 10_000;     // 10s max for allows — short window
    private static final long CACHE_TTL_ALLOW_TRUSTED_MS = 30_000; // 30s for very low risk (score < 10)
    private static final int VERDICT_CACHE_MAX_SIZE = 50_000;
    private final ConcurrentHashMap<String, CachedVerdict> verdictCache = new ConcurrentHashMap<>();

    private record CachedVerdict(Verdict verdict, long expiresAt) {
        boolean isExpired() { return System.currentTimeMillis() > expiresAt; }
    }

    /** Composite cache key — prevents cross-port/protocol verdict reuse */
    private static String verdictCacheKey(String ip, int port, String protocol) {
        return ip + ":" + port + ":" + (protocol != null ? protocol.toUpperCase() : "TCP");
    }

    /** Risk-based TTL — conservative: denials cached long, allows cached short, borderline never cached */
    private static long computeCacheTtlMs(Action action, int riskScore) {
        // NEVER cache borderline verdicts — too close to action thresholds
        if (riskScore >= 30 && riskScore < 60) return 0;
        return switch (action) {
            case BLACKHOLE, BLOCK -> CACHE_TTL_BLOCK_MS;
            case THROTTLE -> CACHE_TTL_THROTTLE_MS;
            case ALLOW -> riskScore < 10 ? CACHE_TTL_ALLOW_TRUSTED_MS : CACHE_TTL_ALLOW_MS;
            case CHALLENGE -> CACHE_TTL_THROTTLE_MS;
        };
    }

    // ── Async alert enrichment thread pool (Improvement #1) ──
    private final ExecutorService alertExecutor = Executors.newFixedThreadPool(2,
        r -> { Thread t = new Thread(r, "alert-enrichment"); t.setDaemon(true); return t; });

    // Active threat alerts
    private final ConcurrentHashMap<String, Map<String, Object>> activeAlerts = new ConcurrentHashMap<>();

    // ── Event rate limiting: prevents reputation manipulation via fake event flooding ──
    private static final int MAX_EVENTS_PER_IP_PER_MINUTE = 30;
    private final ConcurrentHashMap<String, long[]> eventRateTracker = new ConcurrentHashMap<>();

    // ── Verdict Computation (hot path) ─────────────────────────────────

    /**
     * Compute a verdict for an incoming connection.
     * This is the hot path — must be fast.
     */
    public Verdict computeVerdict(String sourceIp, int targetPort, String protocol) {
        totalVerdicts.incrementAndGet();

        // ── Verdict cache: return cached result if still valid ──
        String cacheKey = verdictCacheKey(sourceIp, targetPort, protocol);
        CachedVerdict cached = verdictCache.get(cacheKey);
        if (cached != null && !cached.isExpired()) {
            cacheHits.incrementAndGet();
            return cached.verdict();
        }

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

        // ── Gather intelligence from all analyzers (all in-memory, sub-ms) ──

        // 1. IP Reputation (in-memory ConcurrentHashMap — O(1))
        double reputationScore = reputationService.getScore(sourceIp);
        boolean isNew = reputationService.isNewIp(sourceIp);
        reputationService.recordConnection(sourceIp, protocol);

        if (isNew) signals.add("NEW_IP");
        if (reputationScore < 20) signals.add("LOW_REPUTATION:" + Math.round(reputationScore));
        metadata.put("reputationScore", Math.round(reputationScore * 10.0) / 10.0);
        metadata.put("isNewIp", isNew);

        // 2. Connection Patterns (in-memory — O(1))
        patternAnalyzer.recordConnectionOpen(sourceIp, targetPort);
        PatternAnalysis patterns = patternAnalyzer.analyzeIp(sourceIp);
        signals.addAll(patterns.patterns());
        metadata.put("patternRisk", patterns.riskScore());

        // 3. Geo Analysis (cached in-memory — O(1))
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

        // 4. Threat Intelligence lookup (in-memory ConcurrentHashMap — O(1), zero latency impact)
        double threatIntelRisk = 0;
        if (threatIntelStore != null) {
            int threatScore = threatIntelStore.getThreatScore(sourceIp);
            if (threatScore > 0) {
                threatIntelRisk = threatScore;
                signals.add("THREAT_INTEL_MATCH:" + threatScore);
                metadata.put("threatIntelScore", threatScore);
                log.info("Threat intel match for {}: score={}", maskIp(sourceIp), threatScore);
            }
        }

        // 5. Network behavior analysis (in-memory — O(1), only adds signals)
        double networkRisk = 0;
        if (networkAnalyzer != null) {
            var scanResult = networkAnalyzer.detectPortScan(sourceIp, targetPort, Instant.now());
            if (scanResult.detected()) {
                networkRisk = scanResult.confidence() * 100;
                signals.add("PORT_SCAN:" + scanResult.scanType());
                metadata.put("portScan", Map.of("type", scanResult.scanType(),
                    "uniquePorts", scanResult.uniquePorts()));
            }
        }

        // ── Compute composite risk score ──
        // Enhanced weighted formula with threat intel + network behavior:
        //   - IP reputation inverted (100 - score): weight 0.25 (reduced from 0.35)
        //   - Connection pattern risk: weight 0.20 (reduced from 0.30)
        //   - Threat intel feed match: weight 0.20 (NEW — high-confidence external data)
        //   - Geo risk: weight 0.10 (reduced from 0.15)
        //   - Network behavior risk: weight 0.10 (NEW)
        //   - New IP penalty: weight 0.05 (reduced from 0.10)
        //   - Protocol risk: weight 0.10

        double reputationRisk = 100.0 - reputationScore;
        double protocolRisk = getProtocolBaselineRisk(protocol);
        double newIpPenalty = isNew ? 15.0 : 0.0;

        double compositeRisk =
            (reputationRisk * 0.25) +
            (patterns.riskScore() * 0.20) +
            (threatIntelRisk * 0.20) +
            (geoRisk * 0.10) +
            (networkRisk * 0.10) +
            (newIpPenalty * 0.05) +
            (protocolRisk * 0.10);

        int riskScore = (int) Math.min(100, Math.max(0, compositeRisk));
        metadata.put("compositeBreakdown", Map.of(
            "reputation", Math.round(reputationRisk * 0.25),
            "pattern", Math.round(patterns.riskScore() * 0.20),
            "threatIntel", Math.round(threatIntelRisk * 0.20),
            "geo", Math.round(geoRisk * 0.10),
            "network", Math.round(networkRisk * 0.10),
            "newIp", Math.round(newIpPenalty * 0.05),
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

        // Generate alert if high risk — async to keep verdict path fast (Improvement #1)
        if (riskScore >= 60) {
            final List<String> alertSignals = List.copyOf(signals);
            final Action alertAction = action;
            final int alertRisk = riskScore;
            alertExecutor.execute(() -> raiseAlert(sourceIp, alertRisk, alertSignals, alertAction));
        }

        Verdict verdict = new Verdict(action, riskScore, reason, ttl, rateLimit, signals, metadata);

        // Cache the verdict — risk-based TTL, borderline verdicts never cached
        long cacheTtlMs = computeCacheTtlMs(action, riskScore);
        if (cacheTtlMs > 0 && verdictCache.size() < VERDICT_CACHE_MAX_SIZE) {
            verdictCache.put(cacheKey, new CachedVerdict(verdict, System.currentTimeMillis() + cacheTtlMs));
        }

        recordVerdict(sourceIp, action, riskScore, reason);

        return verdict;
    }

    // ── Tier-Aware Verdict (with optional LLM escalation) ────────────────

    /**
     * Tier-aware verdict: computes a standard verdict, then optionally escalates
     * to the Claude LLM for borderline cases when securityTier is AI_LLM.
     *
     * LLM is only invoked when:
     *   - securityTier == "AI_LLM"
     *   - Risk score is borderline (30-70)
     *   - LLM is enabled and API key is configured
     *
     * On LLM failure/timeout, falls through to the existing rule-based verdict.
     */
    @SuppressWarnings("unchecked")
    public Verdict computeVerdict(String sourceIp, int targetPort, String protocol, String securityTier) {
        // Call existing rule-based verdict first
        Verdict baseVerdict = computeVerdict(sourceIp, targetPort, protocol);

        // If AI_LLM tier and risk is borderline (30-70), escalate to LLM
        if ("AI_LLM".equals(securityTier)) {
            int riskScore = baseVerdict.riskScore();
            if (riskScore >= 30 && riskScore <= 70 && llmEscalation.isAvailable()) {
                List<String> signals = baseVerdict.signals();
                Map<String, Object> llmMetadata = new LinkedHashMap<>();
                llmMetadata.put("riskScore", riskScore);
                llmMetadata.put("originalAction", baseVerdict.action().name());
                llmMetadata.putAll(baseVerdict.metadata());

                Optional<LlmSecurityEscalation.LlmVerdictResult> llmResult =
                    llmEscalation.evaluate(sourceIp, targetPort, protocol, riskScore, signals, llmMetadata);

                if (llmResult.isPresent()) {
                    var lr = llmResult.get();

                    // Map LLM action string to Action enum
                    Action llmAction = switch (lr.action()) {
                        case "BLOCK" -> Action.BLOCK;
                        case "THROTTLE" -> Action.THROTTLE;
                        default -> Action.ALLOW;
                    };

                    // Adjust risk score based on LLM action
                    int adjustedRisk;
                    if ("BLOCK".equals(lr.action())) adjustedRisk = Math.max(riskScore, 85);
                    else if ("THROTTLE".equals(lr.action())) adjustedRisk = Math.max(riskScore, 60);
                    else adjustedRisk = Math.min(riskScore, 30);

                    // Compute rate limit for throttle
                    RateLimit rateLimit = llmAction == Action.THROTTLE
                        ? computeRateLimit(adjustedRisk, protocol) : baseVerdict.rateLimit();

                    // Build enriched metadata
                    Map<String, Object> enrichedMeta = new LinkedHashMap<>(baseVerdict.metadata());
                    enrichedMeta.put("llmUsed", true);
                    enrichedMeta.put("llmLatencyMs", lr.latencyMs());
                    enrichedMeta.put("llmConfidence", lr.confidence());

                    return new Verdict(
                        llmAction, adjustedRisk,
                        "LLM: " + lr.reasoning(),
                        baseVerdict.ttlSeconds(),
                        rateLimit,
                        baseVerdict.signals(),
                        enrichedMeta
                    );
                } else {
                    // LLM unavailable/failed — return base verdict with llmUsed=false
                    Map<String, Object> enrichedMeta = new LinkedHashMap<>(baseVerdict.metadata());
                    enrichedMeta.put("llmUsed", false);
                    return new Verdict(
                        baseVerdict.action(), baseVerdict.riskScore(),
                        baseVerdict.reason(), baseVerdict.ttlSeconds(),
                        baseVerdict.rateLimit(), baseVerdict.signals(),
                        enrichedMeta
                    );
                }
            }
        }

        // Non-AI_LLM tier or risk outside borderline range — return base verdict with llmUsed=false
        Map<String, Object> enrichedMeta = new LinkedHashMap<>(baseVerdict.metadata());
        enrichedMeta.put("llmUsed", false);
        return new Verdict(
            baseVerdict.action(), baseVerdict.riskScore(),
            baseVerdict.reason(), baseVerdict.ttlSeconds(),
            baseVerdict.rateLimit(), baseVerdict.signals(),
            enrichedMeta
        );
    }

    // ── Batch Verdict (Improvement #5) ──────────────────────────────────

    /**
     * Compute verdicts for multiple IPs in a single call.
     * Reduces HTTP overhead for proxies handling connection bursts.
     */
    public List<Verdict> computeVerdictBatch(List<String[]> requests) {
        List<Verdict> results = new ArrayList<>(requests.size());
        for (String[] req : requests) {
            String ip = req[0];
            int port = req.length > 1 ? Integer.parseInt(req[1]) : 0;
            String protocol = req.length > 2 ? req[2] : "TCP";
            results.add(computeVerdict(ip, port, protocol));
        }
        return results;
    }

    // ── Event Processing (async, from proxy) ───────────────────────────

    /**
     * Process a threat event reported by the proxy.
     * Updates all intelligence models. Fire-and-forget from proxy's perspective.
     */
    public void processEvent(ThreatEvent event) {
        totalEvents.incrementAndGet();
        String ip = event.sourceIp();

        // Rate-limit events per IP to prevent reputation manipulation attacks
        if (!checkEventRate(ip)) {
            log.debug("Event rate limit exceeded for IP: {}", maskIp(ip));
            return;
        }

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
                            maskIp(ip), reasons, maxSeverity);
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
                    log.warn("Auto-blocked {} for brute force on {}", maskIp(ip), event.detectedProtocol());
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
        invalidateVerdictCache(ip);
    }

    public void unblockIp(String ip) {
        reputationService.unblockIp(ip);
        invalidateVerdictCache(ip);
    }

    public void allowIp(String ip) {
        reputationService.allowIp(ip);
        invalidateVerdictCache(ip);
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
        verdicts.put("cacheHits", cacheHits.get());
        verdicts.put("cacheSize", verdictCache.size());
        double hitRate = totalVerdicts.get() > 0
            ? (cacheHits.get() * 100.0 / totalVerdicts.get()) : 0;
        verdicts.put("cacheHitRate", Math.round(hitRate * 10.0) / 10.0 + "%");
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
        List<Map<String, Object>> result = new ArrayList<>();
        int head = verdictRingHead.get();
        int count = Math.min(limit, MAX_RECENT_VERDICTS);
        for (int i = 0; i < count; i++) {
            int idx = (head - 1 - i + MAX_RECENT_VERDICTS) & (MAX_RECENT_VERDICTS - 1);
            Map<String, Object> entry = verdictRing[idx];
            if (entry != null) result.add(entry);
        }
        return result;
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

    /** Lock-free ring buffer write — no synchronization on the verdict hot path.
     *  Security: IPs are masked in the audit trail to prevent intelligence leakage via /verdicts endpoint. */
    private void recordVerdict(String ip, Action action, int risk, String reason) {
        Map<String, Object> record = Map.of(
            "ip", maskIp(ip),
            "action", action.name(),
            "riskScore", risk,
            "reason", reason,
            "timestamp", Instant.now().toString()
        );
        int idx = verdictRingHead.getAndIncrement() & (MAX_RECENT_VERDICTS - 1);
        verdictRing[idx] = record;
    }

    /** Mask last octet of IPv4 (e.g. 192.168.1.100 → 192.168.1.***) or last group of IPv6.
     *  Allows pattern analysis without exposing exact IPs in audit endpoints. */
    private static String maskIp(String ip) {
        if (ip == null) return "unknown";
        int lastDot = ip.lastIndexOf('.');
        int lastColon = ip.lastIndexOf(':');
        if (lastDot > 0) return ip.substring(0, lastDot + 1) + "***";       // IPv4
        if (lastColon > 0) return ip.substring(0, lastColon + 1) + "****";  // IPv6
        return "***";
    }

    private void raiseAlert(String ip, int riskScore, List<String> signals, Action action) {
        Map<String, Object> alert = new LinkedHashMap<>();
        alert.put("ip", ip);
        alert.put("riskScore", riskScore);
        alert.put("signals", signals);
        alert.put("action", action.name());
        alert.put("timestamp", Instant.now().toString());

        // Enhanced: Map signals to MITRE ATT&CK techniques (in-memory, sub-ms)
        if (mitreMapper != null) {
            List<String> techniques = new ArrayList<>();
            for (String signal : signals) {
                String base = signal.contains(":") ? signal.substring(0, signal.indexOf(':')) : signal;
                List<MitreAttackMapper.TechniqueInfo> matches =
                    mitreMapper.mapBehaviorToTechniques(base, null, Map.of("ip", ip));
                for (MitreAttackMapper.TechniqueInfo match : matches) {
                    techniques.add(match.getId() + ":" + match.getName());
                }
            }
            if (!techniques.isEmpty()) {
                alert.put("mitreTechniques", techniques);
            }
        }

        // Enhanced: Track attack chain progression (in-memory, sub-ms)
        if (attackChainDetector != null && mitreMapper != null) {
            for (String signal : signals) {
                String base = signal.contains(":") ? signal.substring(0, signal.indexOf(':')) : signal;
                List<MitreAttackMapper.TechniqueInfo> matches =
                    mitreMapper.mapBehaviorToTechniques(base, null, Map.of("ip", ip));
                for (MitreAttackMapper.TechniqueInfo match : matches) {
                    for (String tacticId : match.getTactics()) {
                        attackChainDetector.recordTactic(ip, tacticId, match.getId(),
                            riskScore / 100.0, signal);
                    }
                }
            }
            // Check if kill chain is progressing
            var chain = attackChainDetector.analyzeChain(ip);
            if (chain != null && chain.stagesReached() >= 2) {
                alert.put("attackChain", Map.of(
                    "stages", chain.stagesReached(),
                    "riskLevel", chain.riskLevel(),
                    "currentStage", chain.currentStage(),
                    "narrative", chain.narrative()
                ));
                log.warn("Attack chain detected for {}: {} stages, risk={}",
                    maskIp(ip), chain.stagesReached(), chain.riskLevel());
            }
        }

        // Enhanced: Generate human-readable explanation (in-memory, sub-ms)
        if (explainabilityEngine != null) {
            String explanation = explainabilityEngine.explainVerdict(
                ip, 0, "TCP", riskScore, action.name(),
                Map.of("signals", signals));
            alert.put("explanation", explanation);
        }

        activeAlerts.put(ip, alert);

        // Enhanced: Trigger automated response playbooks (fire-and-forget, async)
        if (playbookEngine != null && riskScore >= 60) {
            try {
                String alertType = inferAlertType(signals);
                Map<String, Object> context = new LinkedHashMap<>(alert);
                context.put("sourceIp", ip);
                playbookEngine.triggerForDetection(alertType, riskScore, riskScore / 100.0, context);
            } catch (Exception e) {
                log.debug("Playbook trigger failed (non-critical): {}", e.getMessage());
            }
        }
    }

    /** Infer the primary alert type from signals for playbook matching */
    private String inferAlertType(List<String> signals) {
        for (String signal : signals) {
            String s = signal.toUpperCase();
            if (s.contains("BRUTE_FORCE")) return "BRUTE_FORCE";
            if (s.contains("PORT_SCAN") || s.contains("SCAN")) return "PORT_SCAN";
            if (s.contains("DGA")) return "DGA";
            if (s.contains("EXFIL")) return "DATA_EXFIL";
            if (s.contains("BEACON")) return "C2_BEACONING";
            if (s.contains("THREAT_INTEL")) return "THREAT_INTEL_MATCH";
            if (s.contains("DDOS") || s.contains("FLOOD")) return "DDOS";
        }
        return "GENERIC_THREAT";
    }

    /** Expire alerts older than 30 minutes + evict stale verdict cache entries */
    @Scheduled(fixedRate = 60_000)
    public void expireAlerts() {
        Instant cutoff = Instant.now().minusSeconds(1800);
        activeAlerts.entrySet().removeIf(e -> {
            String ts = (String) e.getValue().get("timestamp");
            return ts != null && Instant.parse(ts).isBefore(cutoff);
        });
        // Evict expired verdict cache entries (Improvement #2)
        verdictCache.entrySet().removeIf(e -> e.getValue().isExpired());
        // Evict stale event rate trackers (older than 2 minutes)
        long twoMinutesAgo = System.currentTimeMillis() / 60_000 - 2;
        eventRateTracker.entrySet().removeIf(e -> e.getValue()[0] < twoMinutesAgo);
    }

    /** Periodic cleanup of stale connection profiles */
    @Scheduled(fixedRate = 3600_000)
    public void cleanup() {
        patternAnalyzer.evictStaleProfiles(24);
    }

    /** Rate-limit events per source IP — returns true if event should be processed, false if throttled.
     *  Uses a simple sliding window counter (current minute). Prevents reputation manipulation attacks. */
    private boolean checkEventRate(String ip) {
        long currentMinute = System.currentTimeMillis() / 60_000;
        long[] tracker = eventRateTracker.computeIfAbsent(ip, k -> new long[]{currentMinute, 0});
        synchronized (tracker) {
            if (tracker[0] != currentMinute) {
                tracker[0] = currentMinute;
                tracker[1] = 0;
            }
            tracker[1]++;
            return tracker[1] <= MAX_EVENTS_PER_IP_PER_MINUTE;
        }
    }

    /** Invalidate ALL verdict cache entries for a specific IP (called on block/allow changes).
     *  Must scan all composite keys since cache key is ip:port:protocol. */
    private void invalidateVerdictCache(String ip) {
        String prefix = ip + ":";
        verdictCache.keySet().removeIf(k -> k.startsWith(prefix));
    }
}
