package com.filetransfer.ai.controller;

import com.filetransfer.ai.service.proxy.GeoAnomalyDetector;
import com.filetransfer.ai.service.proxy.IpReputationService;
import com.filetransfer.ai.service.proxy.ProxyIntelligenceService;
import com.filetransfer.ai.service.proxy.ProxyIntelligenceService.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/**
 * Proxy Intelligence REST API — the interface between the DMZ reverse proxy
 * and the AI engine brain.
 *
 * Product-agnostic endpoints:
 * - /api/v1/proxy/verdict     — real-time connection verdict (hot path)
 * - /api/v1/proxy/event       — async threat event reporting
 * - /api/v1/proxy/events      — batch event reporting
 * - /api/v1/proxy/blocklist   — manage IP blocklist
 * - /api/v1/proxy/allowlist   — manage IP allowlist
 * - /api/v1/proxy/ip/{ip}     — full intelligence on an IP
 * - /api/v1/proxy/dashboard   — security dashboard
 * - /api/v1/proxy/verdicts    — recent verdict audit trail
 * - /api/v1/proxy/geo/*       — geo threat feed management
 * - /api/v1/proxy/health      — intelligence service health
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/proxy")
public class ProxyIntelligenceController {

    private final ProxyIntelligenceService intelligenceService;
    private final IpReputationService reputationService;
    private final GeoAnomalyDetector geoDetector;

    @Value("${platform.security.control-api-key:internal_control_secret}")
    private String controlApiKey;

    public ProxyIntelligenceController(
            ProxyIntelligenceService intelligenceService,
            IpReputationService reputationService,
            GeoAnomalyDetector geoDetector) {
        this.intelligenceService = intelligenceService;
        this.reputationService = reputationService;
        this.geoDetector = geoDetector;
    }

    /** Validate internal API key — all endpoints require this */
    private void authenticate(String key) {
        if (key == null || !MessageDigest.isEqual(
                controlApiKey.getBytes(StandardCharsets.UTF_8),
                key.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid or missing X-Internal-Key");
        }
    }

    /** Validate IPv4/IPv6 format — rejects log injection payloads, empty strings, and malformed IPs */
    private static String validateIp(String ip) {
        if (ip == null || ip.isEmpty()) return null;
        // Strip control characters (log injection defense)
        String sanitized = ip.replaceAll("[\\r\\n\\t]", "");
        if (sanitized.length() > 45) return null; // max IPv6 length
        // IPv4: 1.2.3.4
        if (sanitized.matches("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$")) {
            for (String octet : sanitized.split("\\.")) {
                int val = Integer.parseInt(octet);
                if (val < 0 || val > 255) return null;
            }
            return sanitized;
        }
        // IPv6: simplified check (hex groups separated by colons)
        if (sanitized.matches("^[0-9a-fA-F:]+$") && sanitized.contains(":")) {
            return sanitized;
        }
        return null;
    }

    /** Validate port range 0-65535 */
    private static int validatePort(Object portObj) {
        if (portObj == null) return 0;
        int port = ((Number) portObj).intValue();
        return (port >= 0 && port <= 65535) ? port : 0;
    }

    /** Cap metadata map size to prevent memory exhaustion */
    @SuppressWarnings("unchecked")
    private Map<String, Object> validateMetadata(Object meta) {
        if (!(meta instanceof Map)) return Map.of();
        Map<String, Object> map = (Map<String, Object>) meta;
        if (map.size() > 50) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "metadata exceeds maximum 50 keys");
        }
        return map;
    }

    /** Validate event type against known types */
    private static final Set<String> VALID_EVENT_TYPES = Set.of(
        "CONNECTION_OPENED", "CONNECTION_CLOSED", "BYTES_TRANSFERRED",
        "RATE_LIMIT_HIT", "REJECTED", "AUTH_FAILURE"
    );

    // ── Verdict (Hot Path) ─────────────────────────────────────────────

    /**
     * Get a real-time verdict for an incoming connection.
     * Called by the DMZ proxy before allowing a connection through.
     */
    @PostMapping("/verdict")
    public ResponseEntity<Map<String, Object>> getVerdict(
            @RequestHeader("X-Internal-Key") String key,
            @RequestBody Map<String, Object> request) {
        authenticate(key);
        String sourceIp = validateIp((String) request.get("sourceIp"));
        int targetPort = validatePort(request.get("targetPort"));
        String protocol = (String) request.getOrDefault("detectedProtocol", "TCP");

        if (sourceIp == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "valid sourceIp is required"));
        }

        // If securityTier is present, use the tier-aware overload (supports LLM escalation)
        String securityTier = (String) request.get("securityTier");
        Verdict verdict = securityTier != null
            ? intelligenceService.computeVerdict(sourceIp, targetPort, protocol, securityTier)
            : intelligenceService.computeVerdict(sourceIp, targetPort, protocol);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("action", verdict.action().name());
        response.put("riskScore", verdict.riskScore());
        response.put("reason", verdict.reason());
        response.put("ttlSeconds", verdict.ttlSeconds());
        response.put("signals", verdict.signals());

        if (verdict.rateLimit() != null) {
            Map<String, Object> rl = new LinkedHashMap<>();
            rl.put("maxConnectionsPerMinute", verdict.rateLimit().maxConnectionsPerMinute());
            rl.put("maxConcurrentConnections", verdict.rateLimit().maxConcurrentConnections());
            rl.put("maxBytesPerMinute", verdict.rateLimit().maxBytesPerMinute());
            response.put("rateLimit", rl);
        }

        response.put("metadata", verdict.metadata());

        return ResponseEntity.ok(response);
    }

    // ── Batch Verdict (Improvement #5) ──────────────────────────────────

    /**
     * Compute verdicts for multiple IPs in a single call.
     * Reduces HTTP round-trip overhead for proxies handling connection bursts.
     */
    private static final int MAX_BATCH_SIZE = 1000;

    @PostMapping("/verdicts/batch")
    public ResponseEntity<List<Map<String, Object>>> getBatchVerdicts(
            @RequestHeader("X-Internal-Key") String key,
            @RequestBody List<Map<String, Object>> requests) {
        authenticate(key);
        if (requests.size() > MAX_BATCH_SIZE) {
            return ResponseEntity.badRequest().body(List.of(
                    Map.of("error", "Batch size " + requests.size() + " exceeds maximum " + MAX_BATCH_SIZE)));
        }
        List<String[]> parsed = new ArrayList<>();
        for (Map<String, Object> req : requests) {
            String ip = validateIp((String) req.get("sourceIp"));
            if (ip == null) continue;
            String port = String.valueOf(req.getOrDefault("targetPort", "0"));
            String protocol = (String) req.getOrDefault("detectedProtocol", "TCP");
            parsed.add(new String[]{ip, port, protocol});
        }

        List<Verdict> verdicts = intelligenceService.computeVerdictBatch(parsed);

        List<Map<String, Object>> results = new ArrayList<>();
        for (Verdict v : verdicts) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("action", v.action().name());
            r.put("riskScore", v.riskScore());
            r.put("reason", v.reason());
            r.put("ttlSeconds", v.ttlSeconds());
            r.put("signals", v.signals());
            results.add(r);
        }
        return ResponseEntity.ok(results);
    }

    // ── Event Reporting (Async) ────────────────────────────────────────

    /**
     * Report a single threat event from the proxy.
     * Fire-and-forget from the proxy's perspective.
     */
    @PostMapping("/event")
    public ResponseEntity<Map<String, String>> reportEvent(
            @RequestHeader("X-Internal-Key") String key,
            @RequestBody Map<String, Object> event) {
        authenticate(key);
        try {
            ThreatEvent te = mapToThreatEvent(event);
            intelligenceService.processEvent(te);
            return ResponseEntity.ok(Map.of("status", "accepted"));
        } catch (Exception e) {
            log.warn("Failed to process event: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", "invalid event format"));
        }
    }

    /**
     * Report multiple events in a batch.
     * More efficient for high-volume proxies.
     */
    @PostMapping("/events")
    public ResponseEntity<Map<String, Object>> reportEvents(
            @RequestHeader("X-Internal-Key") String key,
            @RequestBody List<Map<String, Object>> events) {
        authenticate(key);
        if (events.size() > MAX_BATCH_SIZE) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Batch size " + events.size() + " exceeds maximum " + MAX_BATCH_SIZE));
        }
        int accepted = 0;
        int failed = 0;
        for (Map<String, Object> event : events) {
            try {
                intelligenceService.processEvent(mapToThreatEvent(event));
                accepted++;
            } catch (Exception e) {
                failed++;
            }
        }
        return ResponseEntity.ok(Map.of("accepted", accepted, "failed", failed));
    }

    // ── Blocklist Management ───────────────────────────────────────────

    @GetMapping("/blocklist")
    public ResponseEntity<Map<String, Object>> getBlocklist(@RequestHeader("X-Internal-Key") String key) {
        authenticate(key);
        Set<String> blocked = intelligenceService.getBlocklist();
        return ResponseEntity.ok(Map.of(
            "count", blocked.size(),
            "ips", blocked
        ));
    }

    @PostMapping("/blocklist")
    public ResponseEntity<Map<String, String>> addToBlocklist(
            @RequestHeader("X-Internal-Key") String key,
            @RequestBody Map<String, String> request) {
        authenticate(key);
        String ip = request.get("ip");
        String reason = request.getOrDefault("reason", "manual");
        if (ip == null) return ResponseEntity.badRequest().body(Map.of("error", "ip is required"));

        intelligenceService.blockIp(ip, reason);
        return ResponseEntity.ok(Map.of("status", "blocked", "ip", ip));
    }

    @DeleteMapping("/blocklist/{ip}")
    public ResponseEntity<Map<String, String>> removeFromBlocklist(
            @RequestHeader("X-Internal-Key") String key,
            @PathVariable String ip) {
        authenticate(key);
        intelligenceService.unblockIp(ip);
        return ResponseEntity.ok(Map.of("status", "unblocked", "ip", ip));
    }

    // ── Allowlist Management ───────────────────────────────────────────

    @GetMapping("/allowlist")
    public ResponseEntity<Map<String, Object>> getAllowlist(@RequestHeader("X-Internal-Key") String key) {
        authenticate(key);
        Set<String> allowed = intelligenceService.getAllowlist();
        return ResponseEntity.ok(Map.of(
            "count", allowed.size(),
            "ips", allowed
        ));
    }

    @PostMapping("/allowlist")
    public ResponseEntity<Map<String, String>> addToAllowlist(
            @RequestHeader("X-Internal-Key") String key,
            @RequestBody Map<String, String> request) {
        authenticate(key);
        String ip = request.get("ip");
        if (ip == null) return ResponseEntity.badRequest().body(Map.of("error", "ip is required"));

        intelligenceService.allowIp(ip);
        return ResponseEntity.ok(Map.of("status", "allowed", "ip", ip));
    }

    // ── IP Intelligence ────────────────────────────────────────────────

    @GetMapping("/ip/{ip}")
    public ResponseEntity<Map<String, Object>> getIpIntelligence(
            @RequestHeader("X-Internal-Key") String key,
            @PathVariable String ip) {
        authenticate(key);
        return ResponseEntity.ok(intelligenceService.getIpIntelligence(ip));
    }

    // ── Dashboard & Audit ──────────────────────────────────────────────

    @GetMapping("/dashboard")
    public ResponseEntity<Map<String, Object>> getDashboard(@RequestHeader("X-Internal-Key") String key) {
        authenticate(key);
        return ResponseEntity.ok(intelligenceService.getFullDashboard());
    }

    @GetMapping("/verdicts")
    public ResponseEntity<List<Map<String, Object>>> getRecentVerdicts(
            @RequestHeader("X-Internal-Key") String key,
            @RequestParam(defaultValue = "50") int limit) {
        authenticate(key);
        return ResponseEntity.ok(intelligenceService.getRecentVerdicts(limit));
    }

    // ── Geo Threat Feed Management ─────────────────────────────────────

    @PostMapping("/geo/high-risk-countries")
    public ResponseEntity<Map<String, Object>> setHighRiskCountries(
            @RequestHeader("X-Internal-Key") String key,
            @RequestBody Map<String, Object> request) {
        authenticate(key);
        @SuppressWarnings("unchecked")
        List<String> countries = (List<String>) request.get("countries");
        if (countries != null) {
            countries.forEach(geoDetector::addHighRiskCountry);
        }
        return ResponseEntity.ok(Map.of(
            "highRiskCountries", geoDetector.getHighRiskCountries()
        ));
    }

    @PostMapping("/geo/tor-nodes")
    public ResponseEntity<Map<String, Object>> updateTorExitNodes(
            @RequestHeader("X-Internal-Key") String key,
            @RequestBody Map<String, Object> request) {
        authenticate(key);
        @SuppressWarnings("unchecked")
        List<String> nodes = (List<String>) request.get("nodes");
        if (nodes != null) {
            geoDetector.updateTorExitNodes(new HashSet<>(nodes));
        }
        return ResponseEntity.ok(Map.of("torExitNodes", geoDetector.getTorExitNodeCount()));
    }

    @PostMapping("/geo/vpn-prefixes")
    public ResponseEntity<Map<String, Object>> updateVpnPrefixes(
            @RequestHeader("X-Internal-Key") String key,
            @RequestBody Map<String, Object> request) {
        authenticate(key);
        @SuppressWarnings("unchecked")
        List<String> prefixes = (List<String>) request.get("prefixes");
        if (prefixes != null) {
            geoDetector.updateVpnPrefixes(new HashSet<>(prefixes));
        }
        return ResponseEntity.ok(Map.of("vpnPrefixes", geoDetector.getVpnPrefixCount()));
    }

    @GetMapping("/geo/stats")
    public ResponseEntity<Map<String, Object>> getGeoStats(@RequestHeader("X-Internal-Key") String key) {
        authenticate(key);
        return ResponseEntity.ok(geoDetector.getStats());
    }

    // ── Overhead Estimates (public, no auth) ─────────────────────────────

    /**
     * Static overhead estimates for each security tier.
     * Public endpoint — no auth required, returns static informational data.
     */
    @GetMapping("/overhead-estimates")
    public ResponseEntity<Map<String, Object>> overheadEstimates() {
        return ResponseEntity.ok(Map.of(
            "MANUAL", Map.of("avgMs", 0, "maxMs", 1, "description", "Local checks only, zero network calls"),
            "AI", Map.of("avgMs", 5, "maxMs", 50, "cacheHitRate", "90%+", "description", "Internal AI engine verdict with caching"),
            "AI_LLM", Map.of("avgMs", 50, "maxMs", 2000, "llmTriggerRate", "5-10%", "description", "AI + Claude LLM for borderline cases")
        ));
    }

    // ── Health ──────────────────────────────────────────────────────────

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health(@RequestHeader("X-Internal-Key") String key) {
        authenticate(key);
        Map<String, Object> health = new LinkedHashMap<>();
        health.put("status", "UP");
        health.put("service", "proxy-intelligence");
        health.put("trackedIps", reputationService.getActiveIpCount());
        health.put("blockedIps", intelligenceService.getBlocklist().size());
        health.put("features", List.of(
            "ip_reputation", "protocol_threat_detection",
            "connection_pattern_analysis", "geo_anomaly_detection",
            "adaptive_rate_limiting", "auto_blocklist",
            "brute_force_detection", "ddos_detection",
            "port_scan_detection", "slow_loris_detection"
        ));
        return ResponseEntity.ok(health);
    }

    // ── Helper ─────────────────────────────────────────────────────────

    private ThreatEvent mapToThreatEvent(Map<String, Object> event) {
        String ip = validateIp((String) event.get("sourceIp"));
        if (ip == null) throw new IllegalArgumentException("invalid sourceIp");

        String eventType = (String) event.getOrDefault("eventType", "CONNECTION_OPENED");
        if (!VALID_EVENT_TYPES.contains(eventType)) {
            throw new IllegalArgumentException("invalid eventType");
        }

        int sourcePort = validatePort(event.get("sourcePort"));
        int targetPort = validatePort(event.get("targetPort"));

        String protocol = (String) event.getOrDefault("detectedProtocol", "TCP");
        // Strip control chars from protocol
        protocol = protocol.replaceAll("[\\r\\n\\t]", "");

        long bytesIn = event.get("bytesIn") != null ? Math.max(0, ((Number) event.get("bytesIn")).longValue()) : 0;
        long bytesOut = event.get("bytesOut") != null ? Math.max(0, ((Number) event.get("bytesOut")).longValue()) : 0;
        long durationMs = event.get("durationMs") != null ? Math.max(0, ((Number) event.get("durationMs")).longValue()) : 0;

        String country = (String) event.get("country");
        if (country != null) {
            country = country.replaceAll("[^A-Za-z]", "");
            if (country.length() > 3) country = country.substring(0, 3);
        }

        Map<String, Object> metadata = event.containsKey("metadata") ? validateMetadata(event.get("metadata")) : Map.of();

        return new ThreatEvent(
            eventType, ip, sourcePort, targetPort, protocol,
            bytesIn, bytesOut, durationMs,
            Boolean.TRUE.equals(event.get("blocked")),
            (String) event.getOrDefault("blockReason", ""),
            (String) event.get("account"),
            country, metadata
        );
    }
}
