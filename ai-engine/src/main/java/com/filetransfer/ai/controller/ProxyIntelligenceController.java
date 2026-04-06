package com.filetransfer.ai.controller;

import com.filetransfer.ai.service.proxy.GeoAnomalyDetector;
import com.filetransfer.ai.service.proxy.IpReputationService;
import com.filetransfer.ai.service.proxy.ProxyIntelligenceService;
import com.filetransfer.ai.service.proxy.ProxyIntelligenceService.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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
@RequiredArgsConstructor
public class ProxyIntelligenceController {

    private final ProxyIntelligenceService intelligenceService;
    private final IpReputationService reputationService;
    private final GeoAnomalyDetector geoDetector;

    // ── Verdict (Hot Path) ─────────────────────────────────────────────

    /**
     * Get a real-time verdict for an incoming connection.
     * Called by the DMZ proxy before allowing a connection through.
     */
    @PostMapping("/verdict")
    public ResponseEntity<Map<String, Object>> getVerdict(@RequestBody Map<String, Object> request) {
        String sourceIp = (String) request.get("sourceIp");
        int targetPort = ((Number) request.getOrDefault("targetPort", 0)).intValue();
        String protocol = (String) request.getOrDefault("detectedProtocol", "TCP");

        if (sourceIp == null || sourceIp.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "sourceIp is required"));
        }

        Verdict verdict = intelligenceService.computeVerdict(sourceIp, targetPort, protocol);

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
    @PostMapping("/verdicts/batch")
    public ResponseEntity<List<Map<String, Object>>> getBatchVerdicts(
            @RequestBody List<Map<String, Object>> requests) {
        List<String[]> parsed = new ArrayList<>();
        for (Map<String, Object> req : requests) {
            String ip = (String) req.get("sourceIp");
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
    public ResponseEntity<Map<String, String>> reportEvent(@RequestBody Map<String, Object> event) {
        try {
            ThreatEvent te = mapToThreatEvent(event);
            intelligenceService.processEvent(te);
            return ResponseEntity.ok(Map.of("status", "accepted"));
        } catch (Exception e) {
            log.warn("Failed to process event: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Report multiple events in a batch.
     * More efficient for high-volume proxies.
     */
    @PostMapping("/events")
    public ResponseEntity<Map<String, Object>> reportEvents(@RequestBody List<Map<String, Object>> events) {
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
    public ResponseEntity<Map<String, Object>> getBlocklist() {
        Set<String> blocked = intelligenceService.getBlocklist();
        return ResponseEntity.ok(Map.of(
            "count", blocked.size(),
            "ips", blocked
        ));
    }

    @PostMapping("/blocklist")
    public ResponseEntity<Map<String, String>> addToBlocklist(@RequestBody Map<String, String> request) {
        String ip = request.get("ip");
        String reason = request.getOrDefault("reason", "manual");
        if (ip == null) return ResponseEntity.badRequest().body(Map.of("error", "ip is required"));

        intelligenceService.blockIp(ip, reason);
        return ResponseEntity.ok(Map.of("status", "blocked", "ip", ip));
    }

    @DeleteMapping("/blocklist/{ip}")
    public ResponseEntity<Map<String, String>> removeFromBlocklist(@PathVariable String ip) {
        intelligenceService.unblockIp(ip);
        return ResponseEntity.ok(Map.of("status", "unblocked", "ip", ip));
    }

    // ── Allowlist Management ───────────────────────────────────────────

    @GetMapping("/allowlist")
    public ResponseEntity<Map<String, Object>> getAllowlist() {
        Set<String> allowed = intelligenceService.getAllowlist();
        return ResponseEntity.ok(Map.of(
            "count", allowed.size(),
            "ips", allowed
        ));
    }

    @PostMapping("/allowlist")
    public ResponseEntity<Map<String, String>> addToAllowlist(@RequestBody Map<String, String> request) {
        String ip = request.get("ip");
        if (ip == null) return ResponseEntity.badRequest().body(Map.of("error", "ip is required"));

        intelligenceService.allowIp(ip);
        return ResponseEntity.ok(Map.of("status", "allowed", "ip", ip));
    }

    // ── IP Intelligence ────────────────────────────────────────────────

    @GetMapping("/ip/{ip}")
    public ResponseEntity<Map<String, Object>> getIpIntelligence(@PathVariable String ip) {
        return ResponseEntity.ok(intelligenceService.getIpIntelligence(ip));
    }

    // ── Dashboard & Audit ──────────────────────────────────────────────

    @GetMapping("/dashboard")
    public ResponseEntity<Map<String, Object>> getDashboard() {
        return ResponseEntity.ok(intelligenceService.getFullDashboard());
    }

    @GetMapping("/verdicts")
    public ResponseEntity<List<Map<String, Object>>> getRecentVerdicts(
            @RequestParam(defaultValue = "50") int limit) {
        return ResponseEntity.ok(intelligenceService.getRecentVerdicts(limit));
    }

    // ── Geo Threat Feed Management ─────────────────────────────────────

    @PostMapping("/geo/high-risk-countries")
    public ResponseEntity<Map<String, Object>> setHighRiskCountries(@RequestBody Map<String, Object> request) {
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
    public ResponseEntity<Map<String, Object>> updateTorExitNodes(@RequestBody Map<String, Object> request) {
        @SuppressWarnings("unchecked")
        List<String> nodes = (List<String>) request.get("nodes");
        if (nodes != null) {
            geoDetector.updateTorExitNodes(new HashSet<>(nodes));
        }
        return ResponseEntity.ok(Map.of("torExitNodes", geoDetector.getTorExitNodeCount()));
    }

    @PostMapping("/geo/vpn-prefixes")
    public ResponseEntity<Map<String, Object>> updateVpnPrefixes(@RequestBody Map<String, Object> request) {
        @SuppressWarnings("unchecked")
        List<String> prefixes = (List<String>) request.get("prefixes");
        if (prefixes != null) {
            geoDetector.updateVpnPrefixes(new HashSet<>(prefixes));
        }
        return ResponseEntity.ok(Map.of("vpnPrefixes", geoDetector.getVpnPrefixCount()));
    }

    @GetMapping("/geo/stats")
    public ResponseEntity<Map<String, Object>> getGeoStats() {
        return ResponseEntity.ok(geoDetector.getStats());
    }

    // ── Health ──────────────────────────────────────────────────────────

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
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
        return new ThreatEvent(
            (String) event.getOrDefault("eventType", "CONNECTION_OPENED"),
            (String) event.get("sourceIp"),
            event.get("sourcePort") != null ? ((Number) event.get("sourcePort")).intValue() : 0,
            event.get("targetPort") != null ? ((Number) event.get("targetPort")).intValue() : 0,
            (String) event.getOrDefault("detectedProtocol", "TCP"),
            event.get("bytesIn") != null ? ((Number) event.get("bytesIn")).longValue() : 0,
            event.get("bytesOut") != null ? ((Number) event.get("bytesOut")).longValue() : 0,
            event.get("durationMs") != null ? ((Number) event.get("durationMs")).longValue() : 0,
            Boolean.TRUE.equals(event.get("blocked")),
            (String) event.getOrDefault("blockReason", ""),
            (String) event.get("account"),
            (String) event.get("country"),
            event.containsKey("metadata") ? safeCastMetadata(event.get("metadata")) : Map.of()
        );
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> safeCastMetadata(Object meta) {
        if (meta instanceof Map) return (Map<String, Object>) meta;
        return Map.of();
    }
}
