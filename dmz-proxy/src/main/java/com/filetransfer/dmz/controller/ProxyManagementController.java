package com.filetransfer.dmz.controller;

import com.filetransfer.dmz.audit.AuditLogger;
import com.filetransfer.dmz.health.BackendHealthChecker;
import com.filetransfer.dmz.proxy.PortMapping;
import com.filetransfer.dmz.proxy.ProxyManager;
import com.filetransfer.dmz.qos.BandwidthQoS;
import com.filetransfer.dmz.security.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

/**
 * DMZ Proxy Management API — enterprise security endpoints.
 *
 * Port Mapping:
 *   GET    /api/proxy/mappings                          — list all mappings + live stats
 *   POST   /api/proxy/mappings                          — add new mapping (hot-add)
 *   DELETE /api/proxy/mappings/{name}                   — remove mapping (graceful drain)
 *   PUT    /api/proxy/mappings/{name}/security-policy   — hot-update security tier + rules
 *
 * Security Intelligence:
 *   GET    /api/proxy/security/stats                    — full security metrics
 *   GET    /api/proxy/security/connections              — connection tracker stats
 *   GET    /api/proxy/security/ip/{ip}                  — per-IP security details
 *   GET    /api/proxy/security/rate-limits              — rate limiter stats
 *   GET    /api/proxy/security/summary                  — quick security overview
 *
 * Enterprise Security:
 *   GET    /api/proxy/backends/health                   — backend health status
 *   GET    /api/proxy/audit/stats                       — audit logger stats
 *   GET    /api/proxy/zones/rules                       — zone enforcement rules
 *   GET    /api/proxy/zones/check                       — test a zone transition
 *   GET    /api/proxy/egress/stats                      — egress filter stats
 *
 * Prometheus:
 *   GET    /api/proxy/metrics                           — Prometheus scrape endpoint (text/plain)
 *
 * Health:
 *   GET    /api/proxy/health                            — overall health + all features
 */
@RestController
@RequestMapping("/api/proxy")
@RequiredArgsConstructor
public class ProxyManagementController {

    private final ProxyManager proxyManager;

    @Value("${platform.security.jwt-secret:changeme_32char_secret_here_!!!!}")
    private String platformJwtSecret;

    /** Optional SPIFFE validator — present when {@code spiffe.enabled=true}. */
    @Autowired(required = false)
    @Nullable
    private SpiffeProxyAuth spiffeProxyAuth;

    // ── Port Mapping Management ────────────────────────────────────────

    @GetMapping("/mappings")
    public List<Map<String, Object>> list() {
        validateAccess();
        return proxyManager.status();
    }

    @PostMapping("/mappings")
    @ResponseStatus(HttpStatus.CREATED)
    public PortMapping add(@RequestBody PortMapping mapping) {
        validateAccess();
        validateMapping(mapping);
        mapping.setActive(true);
        proxyManager.add(mapping);
        return mapping;
    }

    @DeleteMapping("/mappings/{name}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@PathVariable String name) {
        validateAccess();
        proxyManager.remove(name);
    }

    @PutMapping("/mappings/{name}/security-policy")
    public ResponseEntity<?> updateSecurityPolicy(            @PathVariable String name,
            @RequestBody PortMapping.SecurityPolicy policy) {
        validateAccess();
        proxyManager.updateSecurityPolicy(name, policy);
        return ResponseEntity.ok(Map.of(
            "mapping", name,
            "securityTier", policy.getSecurityTier(),
            "message", "Security policy updated"));
    }

    // ── Security Intelligence ──────────────────────────────────────────

    @GetMapping("/security/stats")
    public ResponseEntity<Map<String, Object>> securityStats() {
        validateAccess();
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

        // Enterprise component stats
        if (proxyManager.getAuditLogger() != null) {
            stats.put("audit", proxyManager.getAuditLogger().getStats());
        }
        if (proxyManager.getEgressFilter() != null) {
            stats.put("egress", proxyManager.getEgressFilter().getStats());
        }
        if (proxyManager.getZoneEnforcer() != null) {
            stats.put("zones", proxyManager.getZoneEnforcer().getStats());
        }

        return ResponseEntity.ok(stats);
    }

    @GetMapping("/security/connections")
    public ResponseEntity<Map<String, Object>> connectionStats() {
        validateAccess();
        if (!proxyManager.isSecurityEnabled()) {
            return ResponseEntity.ok(Map.of("securityEnabled", false));
        }
        return ResponseEntity.ok(proxyManager.getConnectionTracker().getStats());
    }

    @GetMapping("/security/ip/{ip}")
    public ResponseEntity<Map<String, Object>> ipDetails(            @PathVariable String ip) {
        validateAccess();
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
    public ResponseEntity<Map<String, Object>> rateLimitStats() {
        validateAccess();
        if (!proxyManager.isSecurityEnabled()) {
            return ResponseEntity.ok(Map.of("securityEnabled", false));
        }
        return ResponseEntity.ok(proxyManager.getRateLimiter().getStats());
    }

    @GetMapping("/security/summary")
    public ResponseEntity<Map<String, Object>> securitySummary() {
        validateAccess();
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

    // ── Backend Health ─────────────────────────────────────────────────

    @GetMapping("/backends/health")
    public ResponseEntity<?> backendHealth() {
        validateAccess();
        BackendHealthChecker hc = proxyManager.getHealthChecker();
        if (hc == null) {
            return ResponseEntity.ok(Map.of("healthCheckEnabled", false));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("healthCheckEnabled", true);
        result.put("backends", hc.getAllHealth());
        result.put("unhealthy", hc.getUnhealthyBackends());
        return ResponseEntity.ok(result);
    }

    // ── Audit ──────────────────────────────────────────────────────────

    @GetMapping("/audit/stats")
    public ResponseEntity<?> auditStats() {
        validateAccess();
        AuditLogger audit = proxyManager.getAuditLogger();
        if (audit == null) {
            return ResponseEntity.ok(Map.of("auditEnabled", false));
        }
        return ResponseEntity.ok(audit.getStats());
    }

    @PostMapping("/audit/flush")
    public ResponseEntity<?> auditFlush() {
        validateAccess();
        AuditLogger audit = proxyManager.getAuditLogger();
        if (audit == null) {
            return ResponseEntity.ok(Map.of("auditEnabled", false));
        }
        audit.flush();
        return ResponseEntity.ok(Map.of("message", "Audit log flushed"));
    }

    // ── Zone Enforcement ───────────────────────────────────────────────

    @GetMapping("/zones/rules")
    public ResponseEntity<?> zoneRules() {
        validateAccess();
        ZoneEnforcer ze = proxyManager.getZoneEnforcer();
        if (ze == null) {
            return ResponseEntity.ok(Map.of("zoneEnforcementEnabled", false));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("zoneEnforcementEnabled", true);
        result.put("rules", ze.getRules());
        result.put("stats", ze.getStats());
        return ResponseEntity.ok(result);
    }

    @GetMapping("/zones/check")
    public ResponseEntity<?> zoneCheck(            @RequestParam String sourceIp,
            @RequestParam String targetHost,
            @RequestParam int targetPort) {
        validateAccess();
        ZoneEnforcer ze = proxyManager.getZoneEnforcer();
        if (ze == null) {
            return ResponseEntity.ok(Map.of("zoneEnforcementEnabled", false));
        }
        ZoneEnforcer.ZoneCheckResult result = ze.checkTransition(sourceIp, targetHost, targetPort);
        return ResponseEntity.ok(Map.of(
            "sourceIp", sourceIp,
            "targetHost", targetHost,
            "targetPort", targetPort,
            "sourceZone", result.sourceZone().name(),
            "targetZone", result.targetZone().name(),
            "allowed", result.allowed(),
            "reason", result.reason()));
    }

    // ── Egress Filter ──────────────────────────────────────────────────

    @GetMapping("/egress/stats")
    public ResponseEntity<?> egressStats() {
        validateAccess();
        EgressFilter ef = proxyManager.getEgressFilter();
        if (ef == null) {
            return ResponseEntity.ok(Map.of("egressFilterEnabled", false));
        }
        return ResponseEntity.ok(ef.getStats());
    }

    @GetMapping("/egress/check")
    public ResponseEntity<?> egressCheck(            @RequestParam String host,
            @RequestParam int port) {
        validateAccess();
        EgressFilter ef = proxyManager.getEgressFilter();
        if (ef == null) {
            return ResponseEntity.ok(Map.of("egressFilterEnabled", false));
        }
        EgressFilter.EgressCheckResult result = ef.checkDestination(host, port);
        return ResponseEntity.ok(Map.of(
            "host", host,
            "port", port,
            "allowed", result.allowed(),
            "resolvedIp", result.resolvedIp() != null ? result.resolvedIp() : "N/A",
            "reason", result.reason()));
    }

    // ── Listener Status ──────────────────────────────────────────────────

    @GetMapping("/listeners")
    public ResponseEntity<?> listeners() {
        validateAccess();
        return ResponseEntity.ok(proxyManager.getListenerStatus());
    }

    // ── IP Blacklist/Whitelist Management ──────────────────────────────

    @PutMapping("/mappings/{name}/security/blacklist")
    public ResponseEntity<?> addBlacklistIp(            @PathVariable String name,
            @RequestBody Map<String, String> body) {
        validateAccess();
        String ip = body.get("ip");
        if (ip == null || ip.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ip is required");
        }
        proxyManager.addBlacklistIp(name, ip.trim());
        return ResponseEntity.ok(Map.of("mapping", name, "action", "blacklist_add", "ip", ip.trim()));
    }

    @DeleteMapping("/mappings/{name}/security/blacklist/{ip}")
    public ResponseEntity<?> removeBlacklistIp(            @PathVariable String name,
            @PathVariable String ip) {
        validateAccess();
        proxyManager.removeBlacklistIp(name, ip.trim());
        return ResponseEntity.ok(Map.of("mapping", name, "action", "blacklist_remove", "ip", ip.trim()));
    }

    @PutMapping("/mappings/{name}/security/whitelist")
    public ResponseEntity<?> addWhitelistIp(            @PathVariable String name,
            @RequestBody Map<String, String> body) {
        validateAccess();
        String ip = body.get("ip");
        if (ip == null || ip.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ip is required");
        }
        proxyManager.addWhitelistIp(name, ip.trim());
        return ResponseEntity.ok(Map.of("mapping", name, "action", "whitelist_add", "ip", ip.trim()));
    }

    @DeleteMapping("/mappings/{name}/security/whitelist/{ip}")
    public ResponseEntity<?> removeWhitelistIp(            @PathVariable String name,
            @PathVariable String ip) {
        validateAccess();
        proxyManager.removeWhitelistIp(name, ip.trim());
        return ResponseEntity.ok(Map.of("mapping", name, "action", "whitelist_remove", "ip", ip.trim()));
    }

    // ── Reachability Test ─────────────────────────────────────────────

    @PostMapping("/reachability")
    public ResponseEntity<?> testReachability(            @RequestBody Map<String, Object> body) {
        validateAccess();
        String host = (String) body.get("host");
        Integer port = body.get("port") instanceof Number n ? n.intValue() : null;
        if (host == null || host.isBlank() || port == null || port < 1 || port > 65535) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "host and port (1-65535) are required");
        }
        // SSRF protection: block loopback/metadata/link-local
        String lower = host.toLowerCase().trim();
        if (lower.equals("localhost") || lower.equals("0.0.0.0")
                || lower.startsWith("127.") || lower.equals("::1")
                || lower.startsWith("169.254.") || lower.startsWith("metadata.")
                || lower.equals("[::1]")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Blocked: loopback and metadata addresses are not allowed for reachability tests");
        }

        long start = System.currentTimeMillis();
        boolean reachable = false;
        String error = null;
        try (var socket = new java.net.Socket()) {
            socket.connect(new java.net.InetSocketAddress(host, port), 5000);
            reachable = true;
        } catch (Exception e) {
            error = e.getMessage();
        }
        long latencyMs = System.currentTimeMillis() - start;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("host", host);
        result.put("port", port);
        result.put("reachable", reachable);
        result.put("latencyMs", latencyMs);
        if (error != null) result.put("error", error);
        return ResponseEntity.ok(result);
    }

    // ── QoS Bandwidth ──────────────────────────────────────────────────

    @GetMapping("/qos/stats")
    public ResponseEntity<?> qosStats() {
        validateAccess();
        var qos = proxyManager.getBandwidthQoS();
        if (qos == null) {
            return ResponseEntity.ok(Map.of("qosEnabled", false));
        }
        return ResponseEntity.ok(qos.getGlobalStats());
    }

    @GetMapping("/qos/stats/{mappingName}")
    public ResponseEntity<?> qosMappingStats(            @PathVariable String mappingName) {
        validateAccess();
        var qos = proxyManager.getBandwidthQoS();
        if (qos == null) {
            return ResponseEntity.ok(Map.of("qosEnabled", false));
        }
        var stats = qos.getStats(mappingName);
        if (stats == null) {
            return ResponseEntity.ok(Map.of("mapping", mappingName, "status", "not_tracked"));
        }
        return ResponseEntity.ok(Map.of(
            "mapping", mappingName,
            "currentBps", stats.currentBps(),
            "limitBps", stats.limitBps(),
            "utilizationPercent", stats.utilizationPercent(),
            "activeConnections", stats.activeConnections(),
            "totalBytes", stats.totalBytes()));
    }

    // ── Prometheus Scrape Endpoint ────────────────────────────────────

    @GetMapping(value = "/metrics", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> prometheusMetrics() {
        PrometheusMeterRegistry registry = proxyManager.getPrometheusRegistry();
        if (registry == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body("# Prometheus metrics not available (security layer disabled)\n");
        }
        return ResponseEntity.ok(registry.scrape());
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
        }

        // Feature inventory
        List<String> features = new ArrayList<>();
        features.add("protocol_detection");
        if (proxyManager.isSecurityEnabled()) {
            features.addAll(List.of("ai_verdict", "rate_limiting",
                "connection_tracking", "threat_event_reporting",
                "adaptive_rate_limits", "graceful_degradation"));
        }
        if (proxyManager.getTlsTerminator() != null) features.add("tls_termination");
        if (proxyManager.getZoneEnforcer() != null) features.add("zone_enforcement");
        if (proxyManager.getEgressFilter() != null) features.add("egress_filtering");
        if (proxyManager.getDeepPacketInspector() != null) features.add("deep_packet_inspection");
        if (proxyManager.getFtpCommandFilter() != null) features.add("ftp_command_filter");
        if (proxyManager.getHealthChecker() != null) features.add("backend_health_check");
        if (proxyManager.getAuditLogger() != null) features.add("audit_logging");
        if (proxyManager.getBandwidthQoS() != null) features.add("bandwidth_qos");
        features.add("proxy_protocol");
        features.add("connection_draining");
        health.put("features", features);

        // Backend health summary
        BackendHealthChecker hc = proxyManager.getHealthChecker();
        if (hc != null) {
            List<String> unhealthy = hc.getUnhealthyBackends();
            health.put("unhealthyBackends", unhealthy);
            if (!unhealthy.isEmpty()) {
                health.put("status", "DEGRADED");
            }
        }

        return health;
    }

    // ── Internal ───────────────────────────────────────────────────────

    /**
     * Validates management API access. Accepts two credential types:
     *
     * <ol>
     *   <li><b>SPIFFE JWT-SVID</b> (preferred) — {@code Authorization: Bearer <jwt>} header.
     *       Validated via SPIRE trust bundle. Zero static secrets. Short-lived (1h).
     *   <li><b>Platform JWT</b> — standard HS256 JWT issued by the auth service.
     *       Used by CLI operators and management tools that authenticate via platform login.
     * </ol>
     *
     * @throws ResponseStatusException 401 if neither credential is valid
     */
    private void validateAccess() {
        ServletRequestAttributes attrs =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "No request context");
        }
        HttpServletRequest request = attrs.getRequest();
        String auth = request.getHeader("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "Unauthorized: Bearer token required (SPIFFE JWT-SVID or Platform JWT)");
        }
        String token = auth.substring(7);

        // ── Path 1: SPIFFE JWT-SVID (preferred) ────────────────────────────
        if (spiffeProxyAuth != null && spiffeProxyAuth.isAvailable()) {
            if (spiffeProxyAuth.validate(token)) return;
        }

        // ── Path 2: Platform JWT (HS256 — for CLI operators) ───────────────
        if (isValidPlatformJwt(token)) return;

        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                "Unauthorized: invalid SPIFFE JWT-SVID or Platform JWT");
    }

    /**
     * Validates a Platform JWT (HS256) issued by the platform auth service.
     * Checks signature and expiry only — role/scope checks are left to the caller's context.
     */
    private boolean isValidPlatformJwt(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3) return false;

            // Verify HMAC-SHA256 signature
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(
                    platformJwtSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] expectedSig = mac.doFinal((parts[0] + "." + parts[1])
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            String padded = parts[2] + "=".repeat((4 - parts[2].length() % 4) % 4);
            byte[] actualSig = java.util.Base64.getUrlDecoder().decode(padded);
            if (!java.security.MessageDigest.isEqual(expectedSig, actualSig)) return false;

            // Verify expiry
            String payloadJson = new String(java.util.Base64.getUrlDecoder().decode(
                    parts[1].concat(switch (parts[1].length() % 4) {
                        case 2 -> "=="; case 3 -> "="; default -> "";
                    })), java.nio.charset.StandardCharsets.UTF_8);
            int expIdx = payloadJson.indexOf("\"exp\":");
            if (expIdx >= 0) {
                int colon = payloadJson.indexOf(':', expIdx);
                int end = payloadJson.indexOf(',', colon);
                if (end < 0) end = payloadJson.indexOf('}', colon);
                long exp = Long.parseLong(payloadJson.substring(colon + 1, end).trim());
                if (java.time.Instant.now().getEpochSecond() > exp) return false;
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

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

        String lower = host.toLowerCase().trim();
        if (lower.equals("localhost") || lower.equals("0.0.0.0")
                || lower.startsWith("127.") || lower.equals("::1")
                || lower.startsWith("169.254.")
                || lower.startsWith("metadata.")
                || lower.equals("[::1]")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "targetHost blocked: loopback and metadata addresses are not allowed");
        }
    }
}
