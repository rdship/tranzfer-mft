package com.filetransfer.dmz.controller;

import com.filetransfer.dmz.proxy.PortMapping;
import com.filetransfer.dmz.proxy.ProxyManager;
import com.filetransfer.dmz.security.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

/**
 * DMZ Proxy Management API — extended with AI security intelligence endpoints.
 *
 * Port Mapping:
 *   GET    /api/proxy/mappings               — list all port mappings + live stats
 *   POST   /api/proxy/mappings               — add a new port mapping (hot-add)
 *   DELETE /api/proxy/mappings/{name}        — remove a port mapping (hot-remove)
 *
 * Security Intelligence:
 *   GET    /api/proxy/security/stats          — full security metrics
 *   GET    /api/proxy/security/connections    — connection tracker stats
 *   GET    /api/proxy/security/ip/{ip}        — per-IP security details
 *   GET    /api/proxy/security/rate-limits    — rate limiter stats
 *
 * Health:
 *   GET    /api/proxy/health                  — overall health + security status
 */
@RestController
@RequestMapping("/api/proxy")
@RequiredArgsConstructor
public class ProxyManagementController {

    private final ProxyManager proxyManager;

    @Value("${control-api.key}")
    private String controlApiKey;

    // ── Port Mapping Management ────────────────────────────────────────

    @GetMapping("/mappings")
    public List<Map<String, Object>> list(@RequestHeader("X-Internal-Key") String key) {
        validateKey(key);
        return proxyManager.status();
    }

    @PostMapping("/mappings")
    @ResponseStatus(HttpStatus.CREATED)
    public PortMapping add(@RequestHeader("X-Internal-Key") String key,
                            @RequestBody PortMapping mapping) {
        validateKey(key);
        mapping.setActive(true);
        proxyManager.add(mapping);
        return mapping;
    }

    @DeleteMapping("/mappings/{name}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@RequestHeader("X-Internal-Key") String key,
                       @PathVariable String name) {
        validateKey(key);
        proxyManager.remove(name);
    }

    // ── Security Intelligence ──────────────────────────────────────────

    @GetMapping("/security/stats")
    public ResponseEntity<Map<String, Object>> securityStats(
            @RequestHeader("X-Internal-Key") String key) {
        validateKey(key);
        if (!proxyManager.isSecurityEnabled()) {
            return ResponseEntity.ok(Map.of("securityEnabled", false));
        }

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("securityEnabled", true);
        stats.put("metrics", proxyManager.getSecurityMetrics().getFullStats());
        stats.put("connections", proxyManager.getConnectionTracker().getStats());
        stats.put("rateLimiter", proxyManager.getRateLimiter().getStats());
        stats.put("aiEngine", Map.of(
            "available", proxyManager.getAiVerdictClient().isAiEngineAvailable(),
            "verdictCacheSize", proxyManager.getAiVerdictClient().getCacheSize(),
            "pendingEvents", proxyManager.getEventReporter().getPendingEventCount()
        ));
        return ResponseEntity.ok(stats);
    }

    @GetMapping("/security/connections")
    public ResponseEntity<Map<String, Object>> connectionStats(
            @RequestHeader("X-Internal-Key") String key) {
        validateKey(key);
        if (!proxyManager.isSecurityEnabled()) {
            return ResponseEntity.ok(Map.of("securityEnabled", false));
        }
        return ResponseEntity.ok(proxyManager.getConnectionTracker().getStats());
    }

    @GetMapping("/security/ip/{ip}")
    public ResponseEntity<Map<String, Object>> ipDetails(
            @RequestHeader("X-Internal-Key") String key,
            @PathVariable String ip) {
        validateKey(key);
        if (!proxyManager.isSecurityEnabled()) {
            return ResponseEntity.ok(Map.of("securityEnabled", false));
        }

        ConnectionTracker tracker = proxyManager.getConnectionTracker();
        Optional<ConnectionTracker.IpState> state = tracker.get(ip);

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("ip", ip);
        if (state.isPresent()) {
            details.putAll(state.get().toMap());
        } else {
            details.put("status", "not_tracked");
        }
        return ResponseEntity.ok(details);
    }

    @GetMapping("/security/rate-limits")
    public ResponseEntity<Map<String, Object>> rateLimitStats(
            @RequestHeader("X-Internal-Key") String key) {
        validateKey(key);
        if (!proxyManager.isSecurityEnabled()) {
            return ResponseEntity.ok(Map.of("securityEnabled", false));
        }
        return ResponseEntity.ok(proxyManager.getRateLimiter().getStats());
    }

    @GetMapping("/security/summary")
    public ResponseEntity<Map<String, Object>> securitySummary(
            @RequestHeader("X-Internal-Key") String key) {
        validateKey(key);
        if (!proxyManager.isSecurityEnabled()) {
            return ResponseEntity.ok(Map.of("securityEnabled", false));
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("securityEnabled", true);
        summary.put("aiEngineAvailable", proxyManager.getAiVerdictClient().isAiEngineAvailable());
        summary.put("connectionSummary", proxyManager.getSecurityMetrics().getConnectionSummary());
        summary.put("trackedIps", proxyManager.getConnectionTracker().getTrackedIpCount());
        summary.put("activeConnections", proxyManager.getConnectionTracker().getActiveConnectionCount());
        summary.put("verdictCacheSize", proxyManager.getAiVerdictClient().getCacheSize());
        return ResponseEntity.ok(summary);
    }

    // ── Health ─────────────────────────────────────────────────────────

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> health = new LinkedHashMap<>();
        health.put("status", "UP");
        health.put("service", "dmz-proxy");
        health.put("activeMappings", proxyManager.getMappings().size());
        health.put("securityEnabled", proxyManager.isSecurityEnabled());

        if (proxyManager.isSecurityEnabled()) {
            health.put("aiEngineAvailable", proxyManager.getAiVerdictClient().isAiEngineAvailable());
            health.put("activeConnections", proxyManager.getConnectionTracker().getActiveConnectionCount());
            health.put("totalConnections", proxyManager.getConnectionTracker().getTotalConnections());
            health.put("features", List.of(
                "protocol_detection", "ai_verdict", "rate_limiting",
                "connection_tracking", "threat_event_reporting",
                "adaptive_rate_limits", "graceful_degradation"
            ));
        }

        return health;
    }

    // ── Internal ───────────────────────────────────────────────────────

    private void validateKey(String key) {
        if (!controlApiKey.equals(key)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid internal API key");
        }
    }
}
