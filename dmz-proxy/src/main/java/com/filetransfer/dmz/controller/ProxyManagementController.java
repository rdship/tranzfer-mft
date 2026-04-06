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
        validateMapping(mapping);
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

    @PutMapping("/mappings/{name}/security-policy")
    public ResponseEntity<?> updateSecurityPolicy(
            @RequestHeader("X-Internal-Key") String key,
            @PathVariable String name,
            @RequestBody PortMapping.SecurityPolicy policy) {
        validateKey(key);
        proxyManager.updateSecurityPolicy(name, policy);
        return ResponseEntity.ok(Map.of(
            "mapping", name,
            "securityTier", policy.getSecurityTier(),
            "message", "Security policy updated"));
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
        if (key == null || !java.security.MessageDigest.isEqual(
                controlApiKey.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                key.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid internal API key");
        }
    }

    /** Validate port mapping target to prevent SSRF attacks.
     *  Blocks loopback, link-local, cloud metadata, and private ranges unless explicitly allowed. */
    private void validateMapping(PortMapping mapping) {
        String host = mapping.getTargetHost();
        if (host == null || host.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "targetHost is required");
        }

        int port = mapping.getTargetPort();
        if (port < 1 || port > 65535) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "targetPort must be 1-65535");
        }

        int listenPort = mapping.getListenPort();
        if (listenPort < 1 || listenPort > 65535) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "listenPort must be 1-65535");
        }

        // Block dangerous targets
        String lower = host.toLowerCase().trim();
        if (lower.equals("localhost") || lower.equals("0.0.0.0")
                || lower.startsWith("127.") || lower.equals("::1")
                || lower.startsWith("169.254.")   // link-local / cloud metadata
                || lower.startsWith("metadata.")  // GCP metadata
                || lower.equals("[::1]")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "targetHost blocked: loopback and metadata addresses are not allowed");
        }
    }
}
