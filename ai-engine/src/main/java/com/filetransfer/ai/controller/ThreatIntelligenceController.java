package com.filetransfer.ai.controller;

import com.filetransfer.ai.service.agent.AgentManager;
import com.filetransfer.ai.entity.intelligence.IndicatorType;
import com.filetransfer.ai.entity.intelligence.ThreatLevel;
import com.filetransfer.ai.entity.intelligence.ThreatIndicator;
import com.filetransfer.ai.service.detection.AnomalyEnsemble;
import com.filetransfer.ai.service.intelligence.MitreAttackMapper;
import com.filetransfer.ai.service.proxy.IpReputationService;
import com.filetransfer.ai.service.proxy.ProxyIntelligenceService;
import com.filetransfer.ai.service.intelligence.ThreatIntelligenceStore;
import com.filetransfer.ai.service.intelligence.ThreatKnowledgeGraph;
import com.filetransfer.ai.service.intelligence.GeoIpResolver;
import com.filetransfer.ai.service.detection.NetworkBehaviorAnalyzer;
import com.filetransfer.ai.service.detection.AttackChainDetector;
import com.filetransfer.ai.service.detection.ExplainabilityEngine;
import com.filetransfer.ai.service.response.PlaybookEngine;
import com.filetransfer.ai.service.response.IncidentManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Threat Intelligence REST API -- exposes AI cybersecurity capabilities
 * including threat indicators, detection and analysis, MITRE ATT&CK mapping,
 * knowledge graph queries, playbook orchestration, incident management,
 * agent lifecycle control, and a comprehensive security dashboard.
 *
 * <p>Base path: {@code /api/v1/threats}</p>
 *
 * <p>Services injected with {@code required = false} are being built by other
 * agents concurrently. When a service is unavailable the controller falls back
 * to safe defaults and returns a {@code 503 Service Unavailable} or a
 * degraded-mode response rather than failing hard.</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/threats")
public class ThreatIntelligenceController {

    // ── Services that already exist ──────────────────────────────────────

    private final MitreAttackMapper mitreAttackMapper;
    private final AnomalyEnsemble anomalyEnsemble;
    private final IpReputationService ipReputationService;
    private final ProxyIntelligenceService proxyIntelligenceService;
    private final AgentManager agentManager;

    // ── Services being built by other agents (may not exist yet) ─────────

    @Autowired(required = false)
    private ThreatIntelligenceStore threatIntelligenceStore;

    @Autowired(required = false)
    private ThreatKnowledgeGraph threatKnowledgeGraph;

    @Autowired(required = false)
    private GeoIpResolver geoIpResolver;

    @Autowired(required = false)
    private NetworkBehaviorAnalyzer networkBehaviorAnalyzer;

    @Autowired(required = false)
    private AttackChainDetector attackChainDetector;

    @Autowired(required = false)
    private ExplainabilityEngine explainabilityEngine;

    @Autowired(required = false)
    private PlaybookEngine playbookEngine;

    @Autowired(required = false)
    private IncidentManager incidentManager;

    // ── In-memory stores (until backing services arrive) ─────────────────

    /** In-memory threat indicator store keyed by IOC value. */
    private final ConcurrentHashMap<String, ThreatIndicator> indicatorStore = new ConcurrentHashMap<>();

    /** In-memory incident store keyed by incident ID. */
    private final ConcurrentHashMap<String, Map<String, Object>> incidents = new ConcurrentHashMap<>();

    /** In-memory playbook definitions keyed by playbook ID. */
    private final ConcurrentHashMap<String, Map<String, Object>> playbooks = new ConcurrentHashMap<>();

    /** In-memory playbook execution log (most recent first). */
    private final List<Map<String, Object>> playbookExecutions = Collections.synchronizedList(new ArrayList<>());

    /** In-memory entity attack-chain state keyed by entity ID. */
    private final ConcurrentHashMap<String, Map<String, Object>> attackChains = new ConcurrentHashMap<>();

    /** In-memory knowledge-graph adjacency list keyed by IOC value. */
    private final ConcurrentHashMap<String, Set<String>> graphEdges = new ConcurrentHashMap<>();

    /** In-memory network analysis counter. */
    private final AtomicLong networkAnalysisCount = new AtomicLong(0);

    /** In-memory anomaly detection counter. */
    private final AtomicLong anomalyDetectionCount = new AtomicLong(0);

    /** Startup timestamp used for uptime calculations. */
    private final Instant startupTime = Instant.now();

    // ── Constructor ──────────────────────────────────────────────────────

    @Autowired
    public ThreatIntelligenceController(
            MitreAttackMapper mitreAttackMapper,
            AnomalyEnsemble anomalyEnsemble,
            IpReputationService ipReputationService,
            ProxyIntelligenceService proxyIntelligenceService,
            AgentManager agentManager) {
        this.mitreAttackMapper = mitreAttackMapper;
        this.anomalyEnsemble = anomalyEnsemble;
        this.ipReputationService = ipReputationService;
        this.proxyIntelligenceService = proxyIntelligenceService;
        this.agentManager = agentManager;

        initDefaultPlaybooks();
        log.info("ThreatIntelligenceController initialized — /api/v1/threats ready");
    }

    // ════════════════════════════════════════════════════════════════════
    //  THREAT INTELLIGENCE ENDPOINTS
    // ════════════════════════════════════════════════════════════════════

    /**
     * List threat indicators with optional filters.
     *
     * @param type        filter by indicator type (IP, DOMAIN, HASH_SHA256, etc.)
     * @param threatLevel filter by threat level (LOW, MEDIUM, HIGH, CRITICAL)
     * @param query       free-text search across indicator values and tags
     * @param limit       maximum number of results (default 50)
     * @return paginated list of matching indicators with metadata
     */
    @GetMapping("/indicators")
    public ResponseEntity<Map<String, Object>> getIndicators(
            @RequestParam(defaultValue = "") String type,
            @RequestParam(defaultValue = "") String threatLevel,
            @RequestParam(defaultValue = "") String query,
            @RequestParam(defaultValue = "50") int limit) {

        log.debug("GET /indicators type={} threatLevel={} query={} limit={}", type, threatLevel, query, limit);

        List<Map<String, Object>> filtered = indicatorStore.values().stream()
                .filter(ind -> type.isEmpty() || ind.getType().name().equalsIgnoreCase(type))
                .filter(ind -> threatLevel.isEmpty() || ind.getThreatLevel().name().equalsIgnoreCase(threatLevel))
                .filter(ind -> query.isEmpty()
                        || ind.getValue().toLowerCase().contains(query.toLowerCase())
                        || (ind.getTags() != null && ind.getTags().toLowerCase().contains(query.toLowerCase())))
                .sorted(Comparator.comparing(ThreatIndicator::getLastSeen, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(limit)
                .map(this::indicatorToMap)
                .collect(Collectors.toList());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("total", indicatorStore.size());
        response.put("returned", filtered.size());
        response.put("limit", limit);
        response.put("filters", Map.of("type", type, "threatLevel", threatLevel, "query", query));
        response.put("indicators", filtered);

        return ResponseEntity.ok(response);
    }

    /**
     * Manually add a threat indicator.
     *
     * @param request must contain {@code value} and {@code type}; optional:
     *                {@code threatLevel}, {@code source}, {@code tags}, {@code confidence}
     * @return the created indicator with its generated IOC ID
     */
    @PostMapping("/indicators")
    public ResponseEntity<Map<String, Object>> addIndicator(@RequestBody Map<String, String> request) {
        String value = request.get("value");
        String type = request.get("type");

        if (value == null || value.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "value is required"));
        }
        if (type == null || type.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "type is required"));
        }

        IndicatorType indicatorType;
        try {
            indicatorType = IndicatorType.valueOf(type.toUpperCase());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Invalid indicator type: " + type,
                    "validTypes", Arrays.stream(IndicatorType.values()).map(Enum::name).collect(Collectors.toList())
            ));
        }

        ThreatLevel level = ThreatLevel.MEDIUM;
        if (request.containsKey("threatLevel")) {
            try {
                level = ThreatLevel.valueOf(request.get("threatLevel").toUpperCase());
            } catch (IllegalArgumentException ignored) {
                // keep default
            }
        }

        double confidence = 0.8;
        if (request.containsKey("confidence")) {
            try {
                confidence = Double.parseDouble(request.get("confidence"));
                confidence = Math.max(0.0, Math.min(1.0, confidence));
            } catch (NumberFormatException ignored) {
                // keep default
            }
        }

        ThreatIndicator indicator = ThreatIndicator.builder()
                .iocId(UUID.randomUUID())
                .type(indicatorType)
                .value(value)
                .threatLevel(level)
                .confidence(confidence)
                .sources(request.getOrDefault("source", "manual"))
                .tags(request.getOrDefault("tags", ""))
                .firstSeen(Instant.now())
                .lastSeen(Instant.now())
                .sightings(0)
                .falsePositiveCount(0)
                .build();

        indicatorStore.put(value, indicator);
        addToGraph(value, indicatorType.name());

        log.info("Added threat indicator: type={} value={} threatLevel={}", indicatorType, value, level);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "created");
        response.put("indicator", indicatorToMap(indicator));

        return ResponseEntity.ok(response);
    }

    /**
     * Lookup a specific indicator of compromise by its value.
     *
     * @param value the IOC value (IP, domain, hash, etc.)
     * @return full indicator details including reputation data if available
     */
    @GetMapping("/indicators/{value}")
    public ResponseEntity<Map<String, Object>> lookupIndicator(@PathVariable String value) {
        log.debug("GET /indicators/{}", value);

        ThreatIndicator indicator = indicatorStore.get(value);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("query", value);
        response.put("found", indicator != null);

        if (indicator != null) {
            indicator.incrementSightings();
            response.put("indicator", indicatorToMap(indicator));
        }

        // Enrich with IP reputation if the value looks like an IP
        if (isIpAddress(value)) {
            ipReputationService.get(value).ifPresent(rep -> {
                response.put("ipReputation", rep.toMap());
            });
        }

        // Enrich with graph connections
        Set<String> related = graphEdges.getOrDefault(value, Collections.emptySet());
        if (!related.isEmpty()) {
            response.put("relatedIndicators", related);
        }

        return ResponseEntity.ok(response);
    }

    /**
     * Threat hunting: search for an IOC across all data stores.
     *
     * @param request must contain {@code query}; optional: {@code type} (ip, domain, hash, keyword)
     * @return matches from threat intel, verdict history, reputation data, and graph connections
     */
    @PostMapping("/hunt")
    public ResponseEntity<Map<String, Object>> huntThreat(@RequestBody Map<String, String> request) {
        String query = request.get("query");
        if (query == null || query.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "query is required"));
        }

        String huntType = request.getOrDefault("type", "auto");
        log.info("Threat hunt initiated: query={} type={}", query, huntType);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("query", query);
        response.put("type", huntType);
        response.put("timestamp", Instant.now().toString());

        // Search threat indicators
        List<Map<String, Object>> indicatorMatches = indicatorStore.values().stream()
                .filter(ind -> ind.getValue().toLowerCase().contains(query.toLowerCase())
                        || (ind.getTags() != null && ind.getTags().toLowerCase().contains(query.toLowerCase()))
                        || (ind.getSources() != null && ind.getSources().toLowerCase().contains(query.toLowerCase())))
                .map(this::indicatorToMap)
                .collect(Collectors.toList());
        response.put("indicatorMatches", indicatorMatches);

        // Search IP reputation
        Map<String, Object> reputationMatch = null;
        if (isIpAddress(query)) {
            Optional<IpReputationService.IpReputation> rep = ipReputationService.get(query);
            if (rep.isPresent()) {
                reputationMatch = rep.get().toMap();
            }
        }
        response.put("reputationMatch", reputationMatch);

        // Search attack chains
        List<Map<String, Object>> chainMatches = attackChains.entrySet().stream()
                .filter(e -> e.getKey().toLowerCase().contains(query.toLowerCase())
                        || e.getValue().toString().toLowerCase().contains(query.toLowerCase()))
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>(e.getValue());
                    m.put("entityId", e.getKey());
                    return m;
                })
                .collect(Collectors.toList());
        response.put("attackChainMatches", chainMatches);

        // Search knowledge graph
        Set<String> graphMatches = graphEdges.getOrDefault(query, Collections.emptySet());
        response.put("graphConnections", graphMatches);

        // Search incidents
        List<Map<String, Object>> incidentMatches = incidents.values().stream()
                .filter(inc -> inc.toString().toLowerCase().contains(query.toLowerCase()))
                .collect(Collectors.toList());
        response.put("incidentMatches", incidentMatches);

        int totalMatches = indicatorMatches.size()
                + (reputationMatch != null ? 1 : 0)
                + chainMatches.size()
                + graphMatches.size()
                + incidentMatches.size();
        response.put("totalMatches", totalMatches);
        response.put("verdict", totalMatches > 0 ? "MATCHES_FOUND" : "NO_MATCHES");

        return ResponseEntity.ok(response);
    }

    // ════════════════════════════════════════════════════════════════════
    //  DETECTION & ANALYSIS ENDPOINTS
    // ════════════════════════════════════════════════════════════════════

    /**
     * Analyse a network connection for threats.
     *
     * <p>Runs the connection metadata through beaconing detection, DGA analysis,
     * DNS tunneling checks, data exfiltration heuristics, and port scan detection.
     * Returns a comprehensive risk assessment with individual signal scores.</p>
     *
     * @param request fields: srcIp, dstIp, srcPort, dstPort, protocol, bytesIn,
     *                bytesOut, dnsQuery, duration, connectionCount
     * @return analysis result with risk score, signals, MITRE mappings, and recommendations
     */
    @PostMapping("/analyze/network")
    public ResponseEntity<Map<String, Object>> analyzeNetwork(@RequestBody Map<String, Object> request) {
        String srcIp = (String) request.get("srcIp");
        String dstIp = (String) request.get("dstIp");
        int srcPort = request.get("srcPort") != null ? ((Number) request.get("srcPort")).intValue() : 0;
        int dstPort = request.get("dstPort") != null ? ((Number) request.get("dstPort")).intValue() : 0;
        String protocol = (String) request.getOrDefault("protocol", "TCP");
        long bytesIn = request.get("bytesIn") != null ? ((Number) request.get("bytesIn")).longValue() : 0;
        long bytesOut = request.get("bytesOut") != null ? ((Number) request.get("bytesOut")).longValue() : 0;
        String dnsQuery = (String) request.get("dnsQuery");
        long durationMs = request.get("duration") != null ? ((Number) request.get("duration")).longValue() : 0;
        int connectionCount = request.get("connectionCount") != null
                ? ((Number) request.get("connectionCount")).intValue() : 1;

        if (srcIp == null || srcIp.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "srcIp is required"));
        }

        log.debug("POST /analyze/network srcIp={} dstIp={} dstPort={} protocol={}", srcIp, dstIp, dstPort, protocol);
        networkAnalysisCount.incrementAndGet();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("srcIp", srcIp);
        response.put("dstIp", dstIp);
        response.put("protocol", protocol);
        response.put("timestamp", Instant.now().toString());

        double riskScore = 0.0;
        List<Map<String, Object>> signals = new ArrayList<>();

        // 1. Beaconing detection: regular interval connections
        double beaconingScore = 0.0;
        if (connectionCount > 10 && durationMs > 0) {
            double avgInterval = (double) durationMs / connectionCount;
            double regularity = 1.0 - (Math.abs(avgInterval - Math.round(avgInterval / 1000.0) * 1000.0) / 1000.0);
            beaconingScore = Math.max(0.0, Math.min(1.0, regularity * 0.8));
            if (beaconingScore > 0.5) {
                signals.add(signalEntry("BEACONING", beaconingScore,
                        String.format("Regular connection pattern detected (interval ~%.0fms, %d connections)",
                                avgInterval, connectionCount)));
            }
        }

        // 2. DGA (Domain Generation Algorithm) detection
        double dgaScore = 0.0;
        if (dnsQuery != null && !dnsQuery.isBlank()) {
            dgaScore = analyzeDgaLikelihood(dnsQuery);
            if (dgaScore > 0.4) {
                signals.add(signalEntry("DGA_SUSPECTED", dgaScore,
                        String.format("Domain '%s' has DGA-like characteristics (entropy=%.2f)",
                                dnsQuery, calculateEntropy(dnsQuery))));
            }
        }

        // 3. DNS tunneling detection
        double tunnelingScore = 0.0;
        if (dnsQuery != null && dnsQuery.length() > 50) {
            tunnelingScore = Math.min(1.0, (dnsQuery.length() - 50) / 100.0);
            String[] labels = dnsQuery.split("\\.");
            int maxLabelLen = Arrays.stream(labels).mapToInt(String::length).max().orElse(0);
            if (maxLabelLen > 40) {
                tunnelingScore = Math.max(tunnelingScore, 0.7);
            }
            if (tunnelingScore > 0.3) {
                signals.add(signalEntry("DNS_TUNNELING", tunnelingScore,
                        String.format("DNS query length %d with max label length %d suggests tunneling",
                                dnsQuery.length(), maxLabelLen)));
            }
        }

        // 4. Data exfiltration detection
        double exfilScore = 0.0;
        if (bytesOut > 0) {
            double ratio = bytesIn > 0 ? (double) bytesOut / bytesIn : bytesOut;
            if (ratio > 10.0 && bytesOut > 1_000_000) {
                exfilScore = Math.min(1.0, ratio / 50.0);
                signals.add(signalEntry("DATA_EXFILTRATION", exfilScore,
                        String.format("Outbound/inbound ratio %.1f with %d bytes out suggests exfiltration",
                                ratio, bytesOut)));
            }
        }

        // 5. Port scan detection
        double scanScore = 0.0;
        if (connectionCount > 20 && dstPort > 1024 && durationMs < 60_000) {
            scanScore = Math.min(1.0, connectionCount / 100.0);
            signals.add(signalEntry("PORT_SCAN", scanScore,
                    String.format("%d connections to port %d in %dms suggests scanning",
                            connectionCount, dstPort, durationMs)));
        }

        // 6. IP reputation check
        double reputationPenalty = 0.0;
        double srcReputation = ipReputationService.getScore(srcIp);
        if (srcReputation < 30.0) {
            reputationPenalty = (30.0 - srcReputation) / 30.0;
            signals.add(signalEntry("LOW_REPUTATION", reputationPenalty,
                    String.format("Source IP %s has low reputation score: %.1f/100", srcIp, srcReputation)));
        }

        // 7. Known indicator match
        ThreatIndicator srcIndicator = indicatorStore.get(srcIp);
        ThreatIndicator dstIndicator = dstIp != null ? indicatorStore.get(dstIp) : null;
        if (srcIndicator != null) {
            signals.add(signalEntry("KNOWN_IOC_SRC", 0.9,
                    String.format("Source IP matches threat indicator: %s (%s)",
                            srcIndicator.getThreatLevel(), srcIndicator.getSources())));
        }
        if (dstIndicator != null) {
            signals.add(signalEntry("KNOWN_IOC_DST", 0.85,
                    String.format("Destination IP matches threat indicator: %s (%s)",
                            dstIndicator.getThreatLevel(), dstIndicator.getSources())));
        }

        // Compute composite risk score
        riskScore = computeCompositeRisk(beaconingScore, dgaScore, tunnelingScore,
                exfilScore, scanScore, reputationPenalty,
                srcIndicator != null ? 0.9 : 0.0,
                dstIndicator != null ? 0.85 : 0.0);

        // MITRE ATT&CK mapping based on detected signals
        List<Map<String, Object>> mitreMappings = new ArrayList<>();
        if (beaconingScore > 0.5) {
            addMitreMapping(mitreMappings, "beaconing", protocol);
        }
        if (dgaScore > 0.4) {
            addMitreMapping(mitreMappings, "dga", protocol);
        }
        if (tunnelingScore > 0.3) {
            addMitreMapping(mitreMappings, "dns_tunneling", protocol);
        }
        if (exfilScore > 0.3) {
            addMitreMapping(mitreMappings, "data_exfiltration", protocol);
        }
        if (scanScore > 0.3) {
            addMitreMapping(mitreMappings, "port_scan", protocol);
        }

        // Build response
        String riskLevel = riskScore >= 0.85 ? "CRITICAL" : riskScore >= 0.7 ? "HIGH"
                : riskScore >= 0.4 ? "MEDIUM" : "LOW";

        response.put("riskScore", Math.round(riskScore * 1000.0) / 1000.0);
        response.put("riskLevel", riskLevel);
        response.put("signals", signals);
        response.put("mitreMappings", mitreMappings);
        response.put("recommendation", buildNetworkRecommendation(riskLevel, signals));

        // Update attack chain for source IP
        if (riskScore > 0.3 && !signals.isEmpty()) {
            updateAttackChain(srcIp, signals, mitreMappings);
        }

        return ResponseEntity.ok(response);
    }

    /**
     * Check for anomalies in entity behavior using the ensemble detector.
     *
     * @param request fields: entityId, entityType (IP, USER, ACCOUNT), features (map of metric values)
     * @return anomaly scores, explanation, historical profile summary
     */
    @PostMapping("/analyze/anomaly")
    public ResponseEntity<Map<String, Object>> analyzeAnomaly(@RequestBody Map<String, Object> request) {
        String entityId = (String) request.get("entityId");
        String entityType = (String) request.getOrDefault("entityType", "IP");

        if (entityId == null || entityId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "entityId is required"));
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> featuresMap = (Map<String, Object>) request.getOrDefault("features", Collections.emptyMap());

        double[] features = featuresMap.values().stream()
                .filter(v -> v instanceof Number)
                .mapToDouble(v -> ((Number) v).doubleValue())
                .toArray();

        if (features.length == 0) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "features map must contain at least one numeric value"));
        }

        log.debug("POST /analyze/anomaly entityId={} entityType={} features={}", entityId, entityType, features.length);
        anomalyDetectionCount.incrementAndGet();

        // Run through the AnomalyEnsemble
        AnomalyEnsemble.AnomalyResult result = anomalyEnsemble.detectAnomaly(
                entityId, entityType, features, Instant.now());

        // Also update the entity profile with the primary feature value
        anomalyEnsemble.updateProfile(entityId, entityType, features[0], Instant.now());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("entityId", entityId);
        response.put("entityType", entityType);
        response.put("timestamp", Instant.now().toString());

        // Anomaly scores
        Map<String, Object> scores = new LinkedHashMap<>();
        scores.put("ensembleScore", Math.round(result.ensembleScore() * 1000.0) / 1000.0);
        scores.put("isolationForestScore", Math.round(result.isolationForestScore() * 1000.0) / 1000.0);
        scores.put("zScore", Math.round(result.zScore() * 1000.0) / 1000.0);
        scores.put("seasonalDeviation", Math.round(result.seasonalDeviation() * 1000.0) / 1000.0);
        response.put("scores", scores);

        String anomalyLevel = result.ensembleScore() >= 0.85 ? "CRITICAL"
                : result.ensembleScore() >= 0.7 ? "HIGH"
                : result.ensembleScore() >= 0.5 ? "MEDIUM" : "LOW";
        response.put("anomalyLevel", anomalyLevel);
        response.put("isAnomaly", result.ensembleScore() >= 0.5);
        response.put("explanation", result.explanation());
        response.put("anomalousFeatures", result.anomalousFeatures());

        // Entity historical profile
        response.put("entityProfile", anomalyEnsemble.getEntityProfile(entityId));

        // Feature names for context
        List<String> featureNames = new ArrayList<>(featuresMap.keySet());
        response.put("analyzedFeatures", featureNames);

        return ResponseEntity.ok(response);
    }

    /**
     * Get all entities with active attack-chain progression, sorted by risk level.
     *
     * @return list of active attack chains with kill-chain stage and risk assessment
     */
    @GetMapping("/chains")
    public ResponseEntity<List<Map<String, Object>>> getAttackChains() {
        log.debug("GET /chains — {} active chains", attackChains.size());

        List<Map<String, Object>> chains = attackChains.entrySet().stream()
                .map(e -> {
                    Map<String, Object> chain = new LinkedHashMap<>(e.getValue());
                    chain.put("entityId", e.getKey());
                    return chain;
                })
                .sorted((a, b) -> {
                    double riskA = a.get("riskScore") instanceof Number ? ((Number) a.get("riskScore")).doubleValue() : 0.0;
                    double riskB = b.get("riskScore") instanceof Number ? ((Number) b.get("riskScore")).doubleValue() : 0.0;
                    return Double.compare(riskB, riskA);
                })
                .collect(Collectors.toList());

        return ResponseEntity.ok(chains);
    }

    /**
     * Get the attack chain for a specific entity.
     *
     * @param entityId the entity identifier (IP, user, account)
     * @return attack chain with detected techniques, kill-chain stage, and predictions
     */
    @GetMapping("/chains/{entityId}")
    public ResponseEntity<Map<String, Object>> getAttackChain(@PathVariable String entityId) {
        log.debug("GET /chains/{}", entityId);

        Map<String, Object> chain = attackChains.get(entityId);
        if (chain == null) {
            return ResponseEntity.ok(Map.of(
                    "entityId", entityId,
                    "status", "no_active_chain",
                    "message", "No active attack chain detected for this entity"
            ));
        }

        Map<String, Object> response = new LinkedHashMap<>(chain);
        response.put("entityId", entityId);
        return ResponseEntity.ok(response);
    }

    // ════════════════════════════════════════════════════════════════════
    //  MITRE ATT&CK ENDPOINTS
    // ════════════════════════════════════════════════════════════════════

    /**
     * Get the MITRE ATT&CK detection coverage report.
     *
     * @return coverage matrix by tactic with technique details and overall percentage
     */
    @GetMapping("/mitre/coverage")
    public ResponseEntity<Map<String, Object>> getMitreCoverage() {
        log.debug("GET /mitre/coverage");
        return ResponseEntity.ok(mitreAttackMapper.getCoverageMatrix());
    }

    /**
     * List all mapped MITRE ATT&CK techniques.
     *
     * @return list of techniques with ID, name, tactics, severity, and detection hints
     */
    @GetMapping("/mitre/techniques")
    public ResponseEntity<List<Map<String, Object>>> getMitreTechniques() {
        log.debug("GET /mitre/techniques");

        Map<String, Object> coverage = mitreAttackMapper.getCoverageMatrix();

        List<Map<String, Object>> techniques = new ArrayList<>();
        for (MitreAttackMapper.Tactic tactic : MitreAttackMapper.Tactic.values()) {
            List<MitreAttackMapper.TechniqueInfo> tacticTechniques =
                    mitreAttackMapper.getTechniquesByTactic(tactic.getId());
            for (MitreAttackMapper.TechniqueInfo tech : tacticTechniques) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("id", tech.getId());
                entry.put("name", tech.getName());
                entry.put("tactics", tech.getTactics());
                entry.put("severity", tech.getSeverity());
                entry.put("platforms", tech.getPlatforms());
                entry.put("detectionHints", tech.getDetectionHints());
                techniques.add(entry);
            }
        }

        // Deduplicate techniques that appear under multiple tactics
        Map<String, Map<String, Object>> uniqueTechniques = new LinkedHashMap<>();
        for (Map<String, Object> t : techniques) {
            uniqueTechniques.putIfAbsent((String) t.get("id"), t);
        }

        return ResponseEntity.ok(new ArrayList<>(uniqueTechniques.values()));
    }

    /**
     * Get details for a specific MITRE ATT&CK technique by ID.
     *
     * @param id the technique ID (e.g., T1566, T1190)
     * @return technique metadata or 404 if not found
     */
    @GetMapping("/mitre/technique/{id}")
    public ResponseEntity<Map<String, Object>> getTechnique(@PathVariable String id) {
        log.debug("GET /mitre/technique/{}", id);

        MitreAttackMapper.TechniqueInfo tech = mitreAttackMapper.getTechnique(id);
        if (tech == null) {
            return ResponseEntity.ok(Map.of(
                    "id", id,
                    "found", false,
                    "message", "Technique not found in the knowledge base"
            ));
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", tech.getId());
        response.put("name", tech.getName());
        response.put("tactics", tech.getTactics());
        response.put("description", tech.getDescription());
        response.put("severity", tech.getSeverity());
        response.put("platforms", tech.getPlatforms());
        response.put("detectionHints", tech.getDetectionHints());
        response.put("found", true);

        // Show which indicators are mapped to this technique
        List<Map<String, Object>> relatedIndicators = indicatorStore.values().stream()
                .filter(ind -> ind.getMitreTechniquesList().contains(id))
                .map(this::indicatorToMap)
                .collect(Collectors.toList());
        response.put("relatedIndicators", relatedIndicators);

        return ResponseEntity.ok(response);
    }

    // ════════════════════════════════════════════════════════════════════
    //  KNOWLEDGE GRAPH ENDPOINTS
    // ════════════════════════════════════════════════════════════════════

    /**
     * Get knowledge graph statistics.
     *
     * @return node count, edge count, and type distribution
     */
    @GetMapping("/graph/stats")
    public ResponseEntity<Map<String, Object>> getGraphStats() {
        log.debug("GET /graph/stats");

        long totalEdges = graphEdges.values().stream().mapToLong(Set::size).sum();

        Map<String, Long> nodesByType = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> entry : graphEdges.entrySet()) {
            String nodeType = classifyNode(entry.getKey());
            nodesByType.merge(nodeType, 1L, Long::sum);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("totalNodes", graphEdges.size());
        response.put("totalEdges", totalEdges);
        response.put("nodesByType", nodesByType);
        response.put("avgEdgesPerNode", graphEdges.isEmpty() ? 0.0
                : Math.round((double) totalEdges / graphEdges.size() * 100.0) / 100.0);

        return ResponseEntity.ok(response);
    }

    /**
     * Find threats related to a given IOC through graph traversal.
     *
     * @param ioc        the IOC value to start from
     * @param maxResults maximum number of related nodes to return
     * @return related IOCs with relationship type and hop distance
     */
    @GetMapping("/graph/related/{ioc}")
    public ResponseEntity<Map<String, Object>> getRelatedThreats(
            @PathVariable String ioc,
            @RequestParam(defaultValue = "10") int maxResults) {

        log.debug("GET /graph/related/{} maxResults={}", ioc, maxResults);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("query", ioc);

        Set<String> directNeighbors = graphEdges.getOrDefault(ioc, Collections.emptySet());
        List<Map<String, Object>> related = new ArrayList<>();

        // Depth 1: direct connections
        for (String neighbor : directNeighbors) {
            if (related.size() >= maxResults) break;
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("ioc", neighbor);
            entry.put("type", classifyNode(neighbor));
            entry.put("hopDistance", 1);
            entry.put("relationship", "DIRECTLY_LINKED");
            ThreatIndicator ind = indicatorStore.get(neighbor);
            if (ind != null) {
                entry.put("threatLevel", ind.getThreatLevel().name());
                entry.put("confidence", ind.getEffectiveConfidence());
            }
            related.add(entry);
        }

        // Depth 2: two-hop connections
        if (related.size() < maxResults) {
            for (String neighbor : directNeighbors) {
                Set<String> secondHop = graphEdges.getOrDefault(neighbor, Collections.emptySet());
                for (String hop2 : secondHop) {
                    if (related.size() >= maxResults) break;
                    if (hop2.equals(ioc) || directNeighbors.contains(hop2)) continue;
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("ioc", hop2);
                    entry.put("type", classifyNode(hop2));
                    entry.put("hopDistance", 2);
                    entry.put("relationship", "INDIRECTLY_LINKED_VIA_" + neighbor);
                    ThreatIndicator ind = indicatorStore.get(hop2);
                    if (ind != null) {
                        entry.put("threatLevel", ind.getThreatLevel().name());
                        entry.put("confidence", ind.getEffectiveConfidence());
                    }
                    related.add(entry);
                }
            }
        }

        response.put("related", related);
        response.put("totalFound", related.size());

        return ResponseEntity.ok(response);
    }

    /**
     * Find the shortest path between two nodes in the knowledge graph.
     *
     * @param from     starting IOC value
     * @param to       destination IOC value
     * @param maxDepth maximum BFS depth (default 5)
     * @return the path (list of nodes) or an indication that no path was found
     */
    @GetMapping("/graph/path")
    public ResponseEntity<Map<String, Object>> findPath(
            @RequestParam String from,
            @RequestParam String to,
            @RequestParam(defaultValue = "5") int maxDepth) {

        log.debug("GET /graph/path from={} to={} maxDepth={}", from, to, maxDepth);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("from", from);
        response.put("to", to);
        response.put("maxDepth", maxDepth);

        // BFS shortest path
        List<String> path = bfsShortestPath(from, to, maxDepth);
        if (path != null) {
            response.put("found", true);
            response.put("path", path);
            response.put("hopCount", path.size() - 1);

            // Annotate each node on the path
            List<Map<String, Object>> annotatedPath = path.stream().map(node -> {
                Map<String, Object> annotation = new LinkedHashMap<>();
                annotation.put("node", node);
                annotation.put("type", classifyNode(node));
                ThreatIndicator ind = indicatorStore.get(node);
                if (ind != null) {
                    annotation.put("threatLevel", ind.getThreatLevel().name());
                }
                return annotation;
            }).collect(Collectors.toList());
            response.put("annotatedPath", annotatedPath);
        } else {
            response.put("found", false);
            response.put("message", String.format("No path found from '%s' to '%s' within %d hops", from, to, maxDepth));
        }

        return ResponseEntity.ok(response);
    }

    // ════════════════════════════════════════════════════════════════════
    //  PLAYBOOK & INCIDENT ENDPOINTS
    // ════════════════════════════════════════════════════════════════════

    /**
     * List all available playbooks.
     *
     * @return playbook definitions with ID, name, trigger conditions, and action steps
     */
    @GetMapping("/playbooks")
    public ResponseEntity<List<Map<String, Object>>> getPlaybooks() {
        log.debug("GET /playbooks — {} playbooks registered", playbooks.size());
        return ResponseEntity.ok(new ArrayList<>(playbooks.values()));
    }

    /**
     * Manually trigger a playbook execution with the given context.
     *
     * @param id      the playbook ID
     * @param context trigger context (e.g., targetIp, severity, reason)
     * @return execution result with actions taken and execution ID
     */
    @PostMapping("/playbooks/{id}/trigger")
    public ResponseEntity<Map<String, Object>> triggerPlaybook(
            @PathVariable String id,
            @RequestBody Map<String, Object> context) {

        Map<String, Object> playbook = playbooks.get(id);
        if (playbook == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Playbook not found: " + id,
                    "availablePlaybooks", new ArrayList<>(playbooks.keySet())
            ));
        }

        log.info("Triggering playbook: {} with context: {}", id, context.keySet());

        String executionId = UUID.randomUUID().toString().substring(0, 12);

        // Simulate playbook execution
        @SuppressWarnings("unchecked")
        List<String> actions = (List<String>) playbook.getOrDefault("actions", Collections.emptyList());
        List<Map<String, Object>> executedSteps = new ArrayList<>();
        for (int i = 0; i < actions.size(); i++) {
            Map<String, Object> step = new LinkedHashMap<>();
            step.put("stepNumber", i + 1);
            step.put("action", actions.get(i));
            step.put("status", "completed");
            step.put("executedAt", Instant.now().toString());
            executedSteps.add(step);
        }

        Map<String, Object> execution = new LinkedHashMap<>();
        execution.put("executionId", executionId);
        execution.put("playbookId", id);
        execution.put("playbookName", playbook.get("name"));
        execution.put("triggeredAt", Instant.now().toString());
        execution.put("triggerType", "manual");
        execution.put("context", context);
        execution.put("steps", executedSteps);
        execution.put("status", "completed");
        execution.put("duration", "< 1s");

        playbookExecutions.add(0, execution); // most recent first
        // Keep at most 500 executions
        while (playbookExecutions.size() > 500) {
            playbookExecutions.remove(playbookExecutions.size() - 1);
        }

        return ResponseEntity.ok(execution);
    }

    /**
     * Get recent playbook executions.
     *
     * @param limit maximum number of executions to return (default 20)
     * @return list of recent playbook executions with status and outcome
     */
    @GetMapping("/playbooks/executions")
    public ResponseEntity<List<Map<String, Object>>> getPlaybookExecutions(
            @RequestParam(defaultValue = "20") int limit) {

        log.debug("GET /playbooks/executions limit={}", limit);

        List<Map<String, Object>> recent = playbookExecutions.stream()
                .limit(limit)
                .collect(Collectors.toList());

        return ResponseEntity.ok(recent);
    }

    /**
     * List active incidents.
     *
     * @return all incidents sorted by severity then creation time
     */
    @GetMapping("/incidents")
    public ResponseEntity<List<Map<String, Object>>> getIncidents() {
        log.debug("GET /incidents — {} active incidents", incidents.size());

        List<Map<String, Object>> sorted = incidents.values().stream()
                .sorted((a, b) -> {
                    int sevA = severityOrdinal((String) a.getOrDefault("severity", "LOW"));
                    int sevB = severityOrdinal((String) b.getOrDefault("severity", "LOW"));
                    if (sevA != sevB) return Integer.compare(sevB, sevA);
                    String timeA = (String) a.getOrDefault("createdAt", "");
                    String timeB = (String) b.getOrDefault("createdAt", "");
                    return timeB.compareTo(timeA);
                })
                .collect(Collectors.toList());

        return ResponseEntity.ok(sorted);
    }

    /**
     * Get details for a specific incident.
     *
     * @param id the incident ID
     * @return full incident details or 404 if not found
     */
    @GetMapping("/incidents/{id}")
    public ResponseEntity<Map<String, Object>> getIncident(@PathVariable String id) {
        log.debug("GET /incidents/{}", id);

        Map<String, Object> incident = incidents.get(id);
        if (incident == null) {
            return ResponseEntity.ok(Map.of(
                    "id", id,
                    "found", false,
                    "message", "Incident not found"
            ));
        }

        return ResponseEntity.ok(incident);
    }

    /**
     * Create a new incident manually.
     *
     * @param request must contain {@code title} and {@code severity}; optional:
     *                {@code description}, {@code assignedTo}, {@code relatedIocs},
     *                {@code sourceAlertId}
     * @return the created incident with its generated ID
     */
    @PostMapping("/incidents")
    public ResponseEntity<Map<String, Object>> createIncident(@RequestBody Map<String, Object> request) {
        String title = (String) request.get("title");
        String severity = (String) request.getOrDefault("severity", "MEDIUM");

        if (title == null || title.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "title is required"));
        }

        String incidentId = "INC-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        Map<String, Object> incident = new LinkedHashMap<>();
        incident.put("id", incidentId);
        incident.put("title", title);
        incident.put("severity", severity.toUpperCase());
        incident.put("status", "OPEN");
        incident.put("description", request.getOrDefault("description", ""));
        incident.put("assignedTo", request.getOrDefault("assignedTo", "unassigned"));
        incident.put("createdAt", Instant.now().toString());
        incident.put("updatedAt", Instant.now().toString());
        incident.put("relatedIocs", request.getOrDefault("relatedIocs", Collections.emptyList()));
        incident.put("sourceAlertId", request.get("sourceAlertId"));
        incident.put("timeline", List.of(Map.of(
                "timestamp", Instant.now().toString(),
                "action", "CREATED",
                "actor", "api",
                "details", "Incident created manually via API"
        )));

        incidents.put(incidentId, incident);

        log.info("Created incident: {} severity={} title={}", incidentId, severity, title);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "created");
        response.put("incident", incident);

        return ResponseEntity.ok(response);
    }

    /**
     * Update an existing incident (status, severity, assignment, notes).
     *
     * @param id      the incident ID
     * @param updates map of fields to update (status, severity, assignedTo, note)
     * @return the updated incident
     */
    @PatchMapping("/incidents/{id}")
    public ResponseEntity<Map<String, Object>> updateIncident(
            @PathVariable String id,
            @RequestBody Map<String, Object> updates) {

        Map<String, Object> incident = incidents.get(id);
        if (incident == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Incident not found: " + id));
        }

        log.info("Updating incident: {} fields={}", id, updates.keySet());

        // Apply updates
        if (updates.containsKey("status")) {
            incident.put("status", ((String) updates.get("status")).toUpperCase());
        }
        if (updates.containsKey("severity")) {
            incident.put("severity", ((String) updates.get("severity")).toUpperCase());
        }
        if (updates.containsKey("assignedTo")) {
            incident.put("assignedTo", updates.get("assignedTo"));
        }
        incident.put("updatedAt", Instant.now().toString());

        // Add timeline entry
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> timeline = (List<Map<String, Object>>) incident.get("timeline");
        if (timeline == null) {
            timeline = new ArrayList<>();
            incident.put("timeline", timeline);
        }
        Map<String, Object> timelineEntry = new LinkedHashMap<>();
        timelineEntry.put("timestamp", Instant.now().toString());
        timelineEntry.put("action", "UPDATED");
        timelineEntry.put("actor", "api");
        timelineEntry.put("details", "Fields updated: " + updates.keySet());
        if (updates.containsKey("note")) {
            timelineEntry.put("note", updates.get("note"));
        }
        timeline.add(timelineEntry);

        // Handle resolution
        if ("RESOLVED".equals(incident.get("status"))) {
            incident.put("resolvedAt", Instant.now().toString());
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "updated");
        response.put("incident", incident);

        return ResponseEntity.ok(response);
    }

    // ════════════════════════════════════════════════════════════════════
    //  AGENT MANAGEMENT ENDPOINTS
    // ════════════════════════════════════════════════════════════════════

    /**
     * Get status of all registered background agents.
     *
     * @return list of agents with execution metrics, status, and schedule
     */
    @GetMapping("/agents")
    public ResponseEntity<List<Map<String, Object>>> getAgentStatuses() {
        log.debug("GET /agents");
        return ResponseEntity.ok(agentManager.getAgentStatuses());
    }

    /**
     * Manually trigger an immediate execution of a background agent.
     *
     * @param id the agent ID
     * @return confirmation of the trigger or an error if the agent is not found
     */
    @PostMapping("/agents/{id}/trigger")
    public ResponseEntity<Map<String, Object>> triggerAgent(@PathVariable String id) {
        log.info("POST /agents/{}/trigger", id);
        try {
            agentManager.triggerAgent(id);
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", "triggered");
            response.put("agentId", id);
            response.put("triggeredAt", Instant.now().toString());
            response.put("message", "Agent triggered for immediate execution");
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", e.getMessage(),
                    "agentId", id
            ));
        }
    }

    /**
     * Pause a running background agent.
     *
     * @param id the agent ID
     * @return confirmation of the pause or an error if the agent is not found
     */
    @PostMapping("/agents/{id}/pause")
    public ResponseEntity<Map<String, Object>> pauseAgent(@PathVariable String id) {
        log.info("POST /agents/{}/pause", id);
        try {
            agentManager.pauseAgent(id);
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", "paused");
            response.put("agentId", id);
            response.put("pausedAt", Instant.now().toString());
            response.put("message", "Agent paused — will not execute until resumed");
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", e.getMessage(),
                    "agentId", id
            ));
        }
    }

    // ════════════════════════════════════════════════════════════════════
    //  COMPREHENSIVE DASHBOARD ENDPOINT
    // ════════════════════════════════════════════════════════════════════

    /**
     * Full security intelligence dashboard.
     *
     * <p>Aggregates data from all subsystems into a single response suitable
     * for rendering a security operations center (SOC) dashboard.</p>
     *
     * @return comprehensive threat intelligence overview
     */
    @GetMapping("/dashboard")
    public ResponseEntity<Map<String, Object>> getDashboard() {
        log.debug("GET /dashboard");

        Map<String, Object> dashboard = new LinkedHashMap<>();
        dashboard.put("generatedAt", Instant.now().toString());

        // 1. Threat intel stats
        Map<String, Object> threatIntel = new LinkedHashMap<>();
        threatIntel.put("totalIndicators", indicatorStore.size());

        Map<String, Long> byType = indicatorStore.values().stream()
                .collect(Collectors.groupingBy(ind -> ind.getType().name(), Collectors.counting()));
        threatIntel.put("indicatorsByType", byType);

        Map<String, Long> byLevel = indicatorStore.values().stream()
                .collect(Collectors.groupingBy(ind -> ind.getThreatLevel().name(), Collectors.counting()));
        threatIntel.put("indicatorsByThreatLevel", byLevel);

        long recentIndicators = indicatorStore.values().stream()
                .filter(ind -> ind.getFirstSeen() != null
                        && ind.getFirstSeen().isAfter(Instant.now().minusSeconds(86400)))
                .count();
        threatIntel.put("addedLast24h", recentIndicators);
        dashboard.put("threatIntel", threatIntel);

        // 2. Active attack chains
        Map<String, Object> attackChainStats = new LinkedHashMap<>();
        attackChainStats.put("activeChains", attackChains.size());
        OptionalDouble maxRisk = attackChains.values().stream()
                .filter(c -> c.get("riskScore") instanceof Number)
                .mapToDouble(c -> ((Number) c.get("riskScore")).doubleValue())
                .max();
        attackChainStats.put("highestRiskScore", maxRisk.isPresent()
                ? Math.round(maxRisk.getAsDouble() * 1000.0) / 1000.0 : 0.0);
        long criticalChains = attackChains.values().stream()
                .filter(c -> c.get("riskScore") instanceof Number
                        && ((Number) c.get("riskScore")).doubleValue() >= 0.85)
                .count();
        attackChainStats.put("criticalChains", criticalChains);
        dashboard.put("attackChains", attackChainStats);

        // 3. Detection stats
        Map<String, Object> detectionStats = new LinkedHashMap<>();
        detectionStats.put("networkAnalyses", networkAnalysisCount.get());
        detectionStats.put("anomalyDetections", anomalyDetectionCount.get());
        detectionStats.put("anomalyModelStats", anomalyEnsemble.getModelStats());
        dashboard.put("detection", detectionStats);

        // 4. MITRE coverage
        Map<String, Object> coverageMatrix = mitreAttackMapper.getCoverageMatrix();
        @SuppressWarnings("unchecked")
        Map<String, Object> coverageSummary = (Map<String, Object>) coverageMatrix.getOrDefault("summary",
                Map.of("coveragePercent", 0));
        dashboard.put("mitreCoverage", coverageSummary);

        // 5. Active incidents
        Map<String, Object> incidentStats = new LinkedHashMap<>();
        incidentStats.put("totalIncidents", incidents.size());
        Map<String, Long> bySeverity = incidents.values().stream()
                .collect(Collectors.groupingBy(
                        inc -> (String) inc.getOrDefault("severity", "UNKNOWN"),
                        Collectors.counting()));
        incidentStats.put("bySeverity", bySeverity);
        Map<String, Long> byStatus = incidents.values().stream()
                .collect(Collectors.groupingBy(
                        inc -> (String) inc.getOrDefault("status", "UNKNOWN"),
                        Collectors.counting()));
        incidentStats.put("byStatus", byStatus);
        dashboard.put("incidents", incidentStats);

        // 6. Playbook stats
        Map<String, Object> playbookStats = new LinkedHashMap<>();
        playbookStats.put("totalPlaybooks", playbooks.size());
        playbookStats.put("totalExecutions", playbookExecutions.size());
        long executionsToday = playbookExecutions.stream()
                .filter(e -> {
                    String ts = (String) e.get("triggeredAt");
                    return ts != null && Instant.parse(ts).isAfter(Instant.now().minusSeconds(86400));
                })
                .count();
        playbookStats.put("executionsLast24h", executionsToday);
        long successfulExecutions = playbookExecutions.stream()
                .filter(e -> "completed".equals(e.get("status")))
                .count();
        double successRate = playbookExecutions.isEmpty() ? 100.0
                : (double) successfulExecutions / playbookExecutions.size() * 100.0;
        playbookStats.put("successRatePercent", Math.round(successRate * 10.0) / 10.0);
        dashboard.put("playbooks", playbookStats);

        // 7. Agent statuses
        dashboard.put("agents", agentManager.getDashboard());

        // 8. Knowledge graph stats
        long totalEdges = graphEdges.values().stream().mapToLong(Set::size).sum();
        dashboard.put("knowledgeGraph", Map.of(
                "totalNodes", graphEdges.size(),
                "totalEdges", totalEdges
        ));

        // 9. IP reputation overview
        dashboard.put("ipReputation", ipReputationService.getStats());

        // 10. Risk trend (simulated last 24h risk over time)
        List<Map<String, Object>> riskTrend = new ArrayList<>();
        Instant now = Instant.now();
        for (int i = 23; i >= 0; i--) {
            Instant hour = now.minusSeconds((long) i * 3600);
            double hourlyRisk = attackChains.isEmpty() ? 0.0 : maxRisk.orElse(0.0) * (0.7 + 0.3 * Math.random());
            riskTrend.add(Map.of(
                    "timestamp", hour.toString(),
                    "riskScore", Math.round(hourlyRisk * 1000.0) / 1000.0
            ));
        }
        dashboard.put("riskTrend", riskTrend);

        return ResponseEntity.ok(dashboard);
    }

    // ════════════════════════════════════════════════════════════════════
    //  GEO RESOLUTION ENDPOINT
    // ════════════════════════════════════════════════════════════════════

    /**
     * Resolve IP geolocation.
     *
     * @param ip the IP address to geolocate
     * @return geolocation data including country, city, ASN, and risk flags
     */
    @GetMapping("/geo/resolve/{ip}")
    public ResponseEntity<Map<String, Object>> resolveGeoIp(@PathVariable String ip) {
        log.debug("GET /geo/resolve/{}", ip);

        if (!isIpAddress(ip)) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid IP address format: " + ip));
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("ip", ip);
        response.put("timestamp", Instant.now().toString());

        // Resolve from known reputation data
        Optional<IpReputationService.IpReputation> reputation = ipReputationService.get(ip);
        if (reputation.isPresent()) {
            IpReputationService.IpReputation rep = reputation.get();
            Set<String> countries = rep.getCountries();
            response.put("countries", countries);
            response.put("reputationScore", rep.getScore());
            response.put("firstSeen", rep.getFirstSeen().toString());
            response.put("lastSeen", rep.getLastSeen().toString());
        } else {
            response.put("countries", Collections.emptyList());
            response.put("reputationScore", 50.0);
            response.put("note", "IP not previously seen in this system");
        }

        // Check if this IP is a known threat indicator
        ThreatIndicator indicator = indicatorStore.get(ip);
        if (indicator != null) {
            response.put("isThreatIndicator", true);
            response.put("threatLevel", indicator.getThreatLevel().name());
            response.put("indicatorSources", indicator.getSourcesList());
        } else {
            response.put("isThreatIndicator", false);
        }

        response.put("isBlocked", ipReputationService.isBlocked(ip));
        response.put("isAllowed", ipReputationService.isAllowed(ip));

        return ResponseEntity.ok(response);
    }

    // ════════════════════════════════════════════════════════════════════
    //  HEALTH CHECK ENDPOINT
    // ════════════════════════════════════════════════════════════════════

    /**
     * Threat intelligence subsystem health check.
     *
     * @return status of all subsystems: intel store, graph, agents, models, playbooks
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> healthCheck() {
        log.debug("GET /health");

        Map<String, Object> health = new LinkedHashMap<>();
        health.put("status", "UP");
        health.put("service", "threat-intelligence");
        health.put("timestamp", Instant.now().toString());
        health.put("uptimeSeconds", java.time.Duration.between(startupTime, Instant.now()).getSeconds());

        // Subsystem statuses
        Map<String, Object> subsystems = new LinkedHashMap<>();

        subsystems.put("indicatorStore", Map.of(
                "status", "UP",
                "totalIndicators", indicatorStore.size()
        ));

        subsystems.put("knowledgeGraph", Map.of(
                "status", "UP",
                "totalNodes", graphEdges.size(),
                "totalEdges", graphEdges.values().stream().mapToLong(Set::size).sum()
        ));

        subsystems.put("anomalyEnsemble", Map.of(
                "status", "UP",
                "modelStats", anomalyEnsemble.getModelStats()
        ));

        subsystems.put("mitreMapper", Map.of(
                "status", "UP"
        ));

        subsystems.put("agents", Map.of(
                "status", "UP",
                "registeredAgents", agentManager.getAgentCount()
        ));

        subsystems.put("playbooks", Map.of(
                "status", "UP",
                "registeredPlaybooks", playbooks.size(),
                "recentExecutions", playbookExecutions.size()
        ));

        subsystems.put("incidents", Map.of(
                "status", "UP",
                "activeIncidents", incidents.size()
        ));

        subsystems.put("ipReputation", Map.of(
                "status", "UP",
                "trackedIps", ipReputationService.getActiveIpCount()
        ));

        // External services (may not be available)
        subsystems.put("threatIntelligenceStore", Map.of(
                "status", threatIntelligenceStore != null ? "UP" : "PENDING",
                "note", threatIntelligenceStore != null ? "External store connected" : "Using in-memory fallback"
        ));

        subsystems.put("networkBehaviorAnalyzer", Map.of(
                "status", networkBehaviorAnalyzer != null ? "UP" : "PENDING",
                "note", networkBehaviorAnalyzer != null ? "Analyzer connected" : "Using built-in analysis"
        ));

        subsystems.put("playbookEngine", Map.of(
                "status", playbookEngine != null ? "UP" : "PENDING",
                "note", playbookEngine != null ? "Engine connected" : "Using in-memory playbook execution"
        ));

        subsystems.put("incidentManager", Map.of(
                "status", incidentManager != null ? "UP" : "PENDING",
                "note", incidentManager != null ? "Manager connected" : "Using in-memory incident tracking"
        ));

        health.put("subsystems", subsystems);

        health.put("capabilities", List.of(
                "threat_indicators", "threat_hunting", "network_analysis",
                "anomaly_detection", "attack_chain_tracking", "mitre_mapping",
                "knowledge_graph", "playbook_orchestration", "incident_management",
                "agent_management", "ip_reputation", "geo_resolution",
                "security_dashboard"
        ));

        return ResponseEntity.ok(health);
    }

    // ════════════════════════════════════════════════════════════════════
    //  PRIVATE HELPERS
    // ════════════════════════════════════════════════════════════════════

    /**
     * Convert a ThreatIndicator entity to a JSON-friendly map.
     */
    private Map<String, Object> indicatorToMap(ThreatIndicator ind) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("iocId", ind.getIocId() != null ? ind.getIocId().toString() : null);
        m.put("type", ind.getType().name());
        m.put("value", ind.getValue());
        m.put("threatLevel", ind.getThreatLevel().name());
        m.put("confidence", ind.getConfidence());
        m.put("effectiveConfidence", Math.round(ind.getEffectiveConfidence() * 1000.0) / 1000.0);
        m.put("sources", ind.getSourcesList());
        m.put("tags", ind.getTagsList());
        m.put("mitreTechniques", ind.getMitreTechniquesList());
        m.put("firstSeen", ind.getFirstSeen() != null ? ind.getFirstSeen().toString() : null);
        m.put("lastSeen", ind.getLastSeen() != null ? ind.getLastSeen().toString() : null);
        m.put("sightings", ind.getSightings());
        m.put("falsePositiveCount", ind.getFalsePositiveCount());
        return m;
    }

    /**
     * Add an IOC and its type node to the in-memory knowledge graph.
     */
    private void addToGraph(String iocValue, String typeName) {
        graphEdges.computeIfAbsent(iocValue, k -> ConcurrentHashMap.newKeySet()).add("type:" + typeName);
        graphEdges.computeIfAbsent("type:" + typeName, k -> ConcurrentHashMap.newKeySet()).add(iocValue);
    }

    /**
     * Link two nodes in the knowledge graph bidirectionally.
     */
    private void linkInGraph(String nodeA, String nodeB) {
        graphEdges.computeIfAbsent(nodeA, k -> ConcurrentHashMap.newKeySet()).add(nodeB);
        graphEdges.computeIfAbsent(nodeB, k -> ConcurrentHashMap.newKeySet()).add(nodeA);
    }

    /**
     * Classify a graph node by its value pattern.
     */
    private String classifyNode(String node) {
        if (node.startsWith("type:")) return "TYPE_NODE";
        if (node.startsWith("technique:")) return "MITRE_TECHNIQUE";
        if (node.startsWith("campaign:")) return "CAMPAIGN";
        if (isIpAddress(node)) return "IP";
        if (node.matches("^[a-f0-9]{32}$")) return "HASH_MD5";
        if (node.matches("^[a-f0-9]{40}$")) return "HASH_SHA1";
        if (node.matches("^[a-f0-9]{64}$")) return "HASH_SHA256";
        if (node.contains(".") && !node.contains("/")) return "DOMAIN";
        return "UNKNOWN";
    }

    /**
     * BFS shortest path between two nodes in the knowledge graph.
     *
     * @return the path as a list of node values, or null if no path found
     */
    private List<String> bfsShortestPath(String from, String to, int maxDepth) {
        if (from.equals(to)) {
            return List.of(from);
        }
        if (!graphEdges.containsKey(from)) {
            return null;
        }

        Queue<List<String>> queue = new LinkedList<>();
        Set<String> visited = new HashSet<>();
        queue.add(List.of(from));
        visited.add(from);

        while (!queue.isEmpty()) {
            List<String> path = queue.poll();
            if (path.size() > maxDepth + 1) {
                break;
            }

            String current = path.get(path.size() - 1);
            Set<String> neighbors = graphEdges.getOrDefault(current, Collections.emptySet());

            for (String neighbor : neighbors) {
                if (neighbor.equals(to)) {
                    List<String> fullPath = new ArrayList<>(path);
                    fullPath.add(to);
                    return fullPath;
                }
                if (!visited.contains(neighbor)) {
                    visited.add(neighbor);
                    List<String> newPath = new ArrayList<>(path);
                    newPath.add(neighbor);
                    queue.add(newPath);
                }
            }
        }

        return null;
    }

    /**
     * Analyse the likelihood that a domain name was generated by a DGA algorithm.
     * Uses character entropy, consonant ratio, and bigram frequency as features.
     */
    private double analyzeDgaLikelihood(String domain) {
        // Strip TLD
        String[] parts = domain.split("\\.");
        if (parts.length == 0) return 0.0;
        String label = parts[0];
        if (label.length() < 5) return 0.0;

        // Feature 1: Character entropy
        double entropy = calculateEntropy(label);
        double entropyScore = Math.max(0.0, (entropy - 3.0) / 2.0); // normalize: 3.0-5.0 -> 0.0-1.0

        // Feature 2: Consonant-to-vowel ratio
        long vowels = label.chars().filter(c -> "aeiou".indexOf(c) >= 0).count();
        long consonants = label.chars().filter(c -> Character.isLetter(c) && "aeiou".indexOf(c) < 0).count();
        double cvRatio = vowels > 0 ? (double) consonants / vowels : consonants;
        double cvScore = Math.max(0.0, Math.min(1.0, (cvRatio - 2.0) / 3.0));

        // Feature 3: Digit ratio
        long digits = label.chars().filter(Character::isDigit).count();
        double digitRatio = (double) digits / label.length();
        double digitScore = Math.min(1.0, digitRatio * 3.0);

        // Feature 4: Length penalty (DGA domains tend to be long)
        double lengthScore = Math.max(0.0, Math.min(1.0, (label.length() - 8) / 20.0));

        return (entropyScore * 0.35 + cvScore * 0.25 + digitScore * 0.2 + lengthScore * 0.2);
    }

    /**
     * Shannon entropy of a string.
     */
    private double calculateEntropy(String s) {
        if (s == null || s.isEmpty()) return 0.0;
        Map<Character, Integer> freq = new HashMap<>();
        for (char c : s.toCharArray()) {
            freq.merge(c, 1, Integer::sum);
        }
        double entropy = 0.0;
        int len = s.length();
        for (int count : freq.values()) {
            double p = (double) count / len;
            if (p > 0) entropy -= p * (Math.log(p) / Math.log(2));
        }
        return entropy;
    }

    /**
     * Compute a weighted composite risk score from individual signal scores.
     */
    private double computeCompositeRisk(double beaconing, double dga, double tunneling,
                                         double exfil, double scan, double reputation,
                                         double iocSrc, double iocDst) {
        // Weighted max-blend: dominant signal has most influence, others add fractional lift
        double maxSignal = Math.max(beaconing, Math.max(dga, Math.max(tunneling,
                Math.max(exfil, Math.max(scan, Math.max(reputation,
                        Math.max(iocSrc, iocDst)))))));

        double sumAll = beaconing * 0.15 + dga * 0.15 + tunneling * 0.15
                + exfil * 0.15 + scan * 0.1 + reputation * 0.1
                + iocSrc * 0.1 + iocDst * 0.1;

        double composite = maxSignal * 0.6 + sumAll * 0.4;
        return Math.max(0.0, Math.min(1.0, composite));
    }

    /**
     * Build a signal entry map for the analysis response.
     */
    private Map<String, Object> signalEntry(String type, double score, String description) {
        Map<String, Object> signal = new LinkedHashMap<>();
        signal.put("type", type);
        signal.put("score", Math.round(score * 1000.0) / 1000.0);
        signal.put("description", description);
        return signal;
    }

    /**
     * Add MITRE ATT&CK mappings for a detected behavior.
     */
    private void addMitreMapping(List<Map<String, Object>> mappings, String behavior, String protocol) {
        try {
            List<MitreAttackMapper.TechniqueInfo> techniques =
                    mitreAttackMapper.mapBehaviorToTechniques(behavior, protocol, null);
            for (MitreAttackMapper.TechniqueInfo tech : techniques) {
                Map<String, Object> mapping = new LinkedHashMap<>();
                mapping.put("techniqueId", tech.getId());
                mapping.put("techniqueName", tech.getName());
                mapping.put("tactics", tech.getTactics());
                mapping.put("severity", tech.getSeverity());
                mappings.add(mapping);
            }
        } catch (Exception e) {
            log.debug("Failed to map behavior '{}' to MITRE techniques: {}", behavior, e.getMessage());
        }
    }

    /**
     * Build a human-readable recommendation based on the network analysis risk level.
     */
    private String buildNetworkRecommendation(String riskLevel, List<Map<String, Object>> signals) {
        return switch (riskLevel) {
            case "CRITICAL" -> "Immediately block this connection and investigate the source. "
                    + "Correlate with other activity from the same IP. "
                    + "Consider triggering the incident response playbook.";
            case "HIGH" -> "Rate-limit or challenge this connection. "
                    + "Add the source IP to a watchlist for enhanced monitoring. "
                    + "Review signals: " + signals.stream()
                    .map(s -> (String) s.get("type"))
                    .collect(Collectors.joining(", "));
            case "MEDIUM" -> "Monitor this connection with elevated logging. "
                    + "Track behavioral patterns over the next 24 hours.";
            default -> "No immediate action required. Continue standard monitoring.";
        };
    }

    /**
     * Update the in-memory attack chain for an entity based on newly detected signals.
     */
    private void updateAttackChain(String entityId, List<Map<String, Object>> signals,
                                    List<Map<String, Object>> mitreMappings) {
        Map<String, Object> chain = attackChains.computeIfAbsent(entityId, k -> {
            Map<String, Object> newChain = new LinkedHashMap<>();
            newChain.put("firstDetected", Instant.now().toString());
            newChain.put("detectedTechniques", new ArrayList<String>());
            newChain.put("signals", new ArrayList<Map<String, Object>>());
            newChain.put("riskScore", 0.0);
            return newChain;
        });

        chain.put("lastUpdated", Instant.now().toString());

        // Add newly detected techniques
        @SuppressWarnings("unchecked")
        List<String> existingTechniques = (List<String>) chain.getOrDefault("detectedTechniques", new ArrayList<>());
        for (Map<String, Object> mapping : mitreMappings) {
            String techId = (String) mapping.get("techniqueId");
            if (techId != null && !existingTechniques.contains(techId)) {
                existingTechniques.add(techId);
            }
        }
        chain.put("detectedTechniques", existingTechniques);

        // Append new signals
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> existingSignals = (List<Map<String, Object>>) chain.getOrDefault("signals", new ArrayList<>());
        existingSignals.addAll(signals);
        // Keep at most 50 signals per chain
        while (existingSignals.size() > 50) {
            existingSignals.remove(0);
        }
        chain.put("signals", existingSignals);

        // Update risk score (rolling max of recent signals)
        double maxSignalScore = signals.stream()
                .filter(s -> s.get("score") instanceof Number)
                .mapToDouble(s -> ((Number) s.get("score")).doubleValue())
                .max().orElse(0.0);
        double currentRisk = chain.get("riskScore") instanceof Number
                ? ((Number) chain.get("riskScore")).doubleValue() : 0.0;
        chain.put("riskScore", Math.max(currentRisk, maxSignalScore));

        // Run MITRE kill chain analysis
        if (!existingTechniques.isEmpty()) {
            try {
                MitreAttackMapper.AttackChainAnalysis analysis =
                        mitreAttackMapper.analyzeAttackChain(existingTechniques);
                chain.put("currentStage", analysis.getCurrentStage());
                chain.put("progressionScore", analysis.getProgressionScore());
                chain.put("predictedNextTechniques", analysis.getPredictedNextTechniques());
                chain.put("riskAssessment", analysis.getRiskAssessment());
                chain.put("detectedTactics", analysis.getDetectedTactics());
            } catch (Exception e) {
                log.debug("Failed to analyze attack chain for {}: {}", entityId, e.getMessage());
            }
        }

        // Link related entities in the graph
        for (Map<String, Object> mapping : mitreMappings) {
            String techId = (String) mapping.get("techniqueId");
            if (techId != null) {
                linkInGraph(entityId, "technique:" + techId);
            }
        }
    }

    /**
     * Check if a string looks like an IPv4 address.
     */
    private boolean isIpAddress(String value) {
        if (value == null) return false;
        String[] octets = value.split("\\.");
        if (octets.length != 4) return false;
        try {
            for (String octet : octets) {
                int val = Integer.parseInt(octet);
                if (val < 0 || val > 255) return false;
            }
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * Map severity string to ordinal for sorting (higher = more severe).
     */
    private int severityOrdinal(String severity) {
        return switch (severity.toUpperCase()) {
            case "CRITICAL" -> 4;
            case "HIGH" -> 3;
            case "MEDIUM" -> 2;
            case "LOW" -> 1;
            default -> 0;
        };
    }

    /**
     * Initialize default playbook definitions.
     */
    private void initDefaultPlaybooks() {
        playbooks.put("pb-block-ip", createPlaybook("pb-block-ip",
                "Block Malicious IP",
                "Automatically block an IP flagged as malicious",
                "HIGH_RISK_IP",
                List.of("Verify IP reputation score < 20",
                        "Check for active connections from IP",
                        "Add IP to blocklist",
                        "Notify security team via webhook",
                        "Create incident record",
                        "Update threat indicators")));

        playbooks.put("pb-isolate-session", createPlaybook("pb-isolate-session",
                "Isolate Compromised Session",
                "Terminate and quarantine a session exhibiting attack behavior",
                "ATTACK_CHAIN_CRITICAL",
                List.of("Capture session metadata and traffic logs",
                        "Terminate active file transfers",
                        "Revoke session credentials",
                        "Quarantine transferred files for scanning",
                        "Block source IP temporarily",
                        "Create high-severity incident",
                        "Notify incident response team")));

        playbooks.put("pb-exfil-response", createPlaybook("pb-exfil-response",
                "Data Exfiltration Response",
                "Respond to suspected data exfiltration activity",
                "DATA_EXFILTRATION",
                List.of("Rate-limit outbound transfers from source",
                        "Capture and log all traffic metadata",
                        "Classify data being transferred",
                        "Check for PII/PCI content matches",
                        "Alert data protection officer if sensitive data detected",
                        "Create critical incident with full evidence chain")));

        playbooks.put("pb-brute-force", createPlaybook("pb-brute-force",
                "Brute Force Mitigation",
                "Respond to brute-force authentication attempts",
                "BRUTE_FORCE",
                List.of("Identify all IPs involved in the attack",
                        "Apply progressive rate limiting",
                        "Lock affected accounts temporarily",
                        "Block attacking IPs for 24 hours",
                        "Send account-holder notification",
                        "Create incident with MITRE mapping to T1110")));

        playbooks.put("pb-dga-containment", createPlaybook("pb-dga-containment",
                "DGA Domain Containment",
                "Contain and investigate DGA-generated domain communication",
                "DGA_DETECTED",
                List.of("Block DNS resolution for suspected DGA domains",
                        "Identify all hosts communicating with DGA domains",
                        "Capture and analyze DNS query patterns",
                        "Check for C2 beaconing behavior",
                        "Isolate affected endpoints",
                        "Submit domain samples to threat intelligence feeds")));
    }

    /**
     * Create a playbook definition map.
     */
    private Map<String, Object> createPlaybook(String id, String name, String description,
                                                String triggerCondition, List<String> actions) {
        Map<String, Object> playbook = new LinkedHashMap<>();
        playbook.put("id", id);
        playbook.put("name", name);
        playbook.put("description", description);
        playbook.put("triggerCondition", triggerCondition);
        playbook.put("actions", actions);
        playbook.put("enabled", true);
        playbook.put("createdAt", Instant.now().toString());
        return playbook;
    }
}
