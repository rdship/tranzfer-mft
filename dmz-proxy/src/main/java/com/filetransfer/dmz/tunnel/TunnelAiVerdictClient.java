package com.filetransfer.dmz.tunnel;

import com.filetransfer.dmz.security.AiVerdictClient;
import com.filetransfer.dmz.security.SpiffeProxyAuth;
import com.filetransfer.tunnel.control.ControlMessage;
import com.filetransfer.tunnel.control.ControlMessageCodec;
import lombok.extern.slf4j.Slf4j;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Tunnel-aware AI verdict client that routes all HTTP calls through the DMZ tunnel
 * instead of making direct cross-zone HTTP requests to ai-engine:8091.
 * <p>
 * Cache-hit path is zero-overhead (reads parent's verdictCache directly via VarHandle).
 * Cache-miss path sends a CONTROL_REQ frame through the multiplexed tunnel, adding
 * only ~1-2ms of framing overhead on top of the original AI engine latency.
 * <p>
 * Fire-and-forget event reporting (reportEventAsync, reportEventsAsync) is sent as
 * one-way CONTROL_REQ frames — no response is awaited.
 */
@Slf4j
public class TunnelAiVerdictClient extends AiVerdictClient {

    private static final VarHandle VERDICT_CACHE_HANDLE;
    private static final VarHandle AI_ENGINE_AVAILABLE_HANDLE;

    static {
        try {
            var lookup = MethodHandles.privateLookupIn(AiVerdictClient.class, MethodHandles.lookup());
            VERDICT_CACHE_HANDLE = lookup.findVarHandle(AiVerdictClient.class, "verdictCache", ConcurrentHashMap.class);
            AI_ENGINE_AVAILABLE_HANDLE = lookup.findVarHandle(AiVerdictClient.class, "aiEngineAvailable", boolean.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError("Cannot access AiVerdictClient internals: " + e);
        }
    }

    private final TunnelAcceptor tunnelAcceptor;
    private final SpiffeProxyAuth spiffeAuth;
    private final long verdictTimeoutMs;

    /**
     * @param aiEngineUrl      base URL of the AI engine (used only by parent for cache-key compat)
     * @param verdictTimeoutMs timeout for verdict requests in milliseconds (preserved via orTimeout)
     * @param spiffeAuth       SPIFFE auth helper for outbound JWT-SVID headers
     * @param tunnelAcceptor   the tunnel acceptor (handler resolved lazily on client connect)
     */
    public TunnelAiVerdictClient(String aiEngineUrl, long verdictTimeoutMs,
                                  SpiffeProxyAuth spiffeAuth, TunnelAcceptor tunnelAcceptor) {
        super(aiEngineUrl, verdictTimeoutMs, spiffeAuth);
        this.tunnelAcceptor = tunnelAcceptor;
        this.spiffeAuth = spiffeAuth;
        this.verdictTimeoutMs = verdictTimeoutMs;
    }

    @Override
    @SuppressWarnings("unchecked")
    public CachedVerdict getVerdict(String sourceIp, int targetPort, String protocol, String securityTier) {
        // 1. Check parent's cache (sub-millisecond, zero tunnel overhead)
        ConcurrentHashMap<String, CachedVerdict> cache =
                (ConcurrentHashMap<String, CachedVerdict>) VERDICT_CACHE_HANDLE.get(this);
        String key = sourceIp + ":" + targetPort;
        CachedVerdict cached = cache.get(key);
        if (cached != null && !cached.isExpired()) {
            return cached;
        }

        // 2. If tunnel is not connected, fall back to parent's direct HTTP path
        //    This covers the startup window before gateway-service connects,
        //    and also tunnel disconnection scenarios — avoids hard-THROTTLE.
        DmzTunnelHandler th = tunnelAcceptor.getHandler();
        if (th == null || !th.isConnected()) {
            log.debug("Tunnel not connected — delegating to parent direct HTTP for verdict ({}:{})", sourceIp, targetPort);
            return super.getVerdict(sourceIp, targetPort, protocol, securityTier);
        }

        // 3. Send CONTROL_REQ through tunnel
        try {
            Map<String, Object> requestMap = new LinkedHashMap<>();
            requestMap.put("sourceIp", sourceIp);
            requestMap.put("targetPort", targetPort);
            requestMap.put("detectedProtocol", protocol != null ? protocol : "TCP");
            if (securityTier != null) {
                requestMap.put("securityTier", securityTier);
            }

            byte[] body = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(requestMap);

            ControlMessage request = ControlMessage.request(
                    UUID.randomUUID().toString(),
                    "POST",
                    "/api/v1/proxy/verdict",
                    aiHeaders(),
                    body
            );

            ControlMessage response = th
                    .sendControlRequest(request, verdictTimeoutMs)
                    .orTimeout(verdictTimeoutMs, TimeUnit.MILLISECONDS)
                    .join();

            if (response.getStatusCode() == 200 && response.getBody() != null) {
                String responseBody = new String(response.getBody(), java.nio.charset.StandardCharsets.UTF_8);
                // Use parent's getVerdict which will parse + cache, but that would do HTTP again.
                // Instead, parse and cache ourselves using the same logic as parent.
                CachedVerdict verdict = parseVerdictFromResponse(responseBody);
                cache.put(key, verdict);
                AI_ENGINE_AVAILABLE_HANDLE.set(this, true);
                return verdict;
            } else {
                log.warn("AI engine returned {} via tunnel", response.getStatusCode());
                return localFallbackThrottle(sourceIp, targetPort, protocol,
                        "http_" + response.getStatusCode());
            }
        } catch (Exception e) {
            log.warn("AI verdict via tunnel failed: {} — falling back to direct HTTP", e.getMessage());
            return super.getVerdict(sourceIp, targetPort, protocol, securityTier);
        }
    }

    @Override
    public void reportEventAsync(Map<String, Object> event) {
        try {
            byte[] body = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(event);
            ControlMessage request = ControlMessage.request(
                    UUID.randomUUID().toString(),
                    "POST",
                    "/api/v1/proxy/event",
                    aiHeaders(),
                    body
            );
            // Fire-and-forget — don't await response
            DmzTunnelHandler h = tunnelAcceptor.getHandler();
            if (h != null) h.sendControlRequest(request, 5000);
        } catch (Exception e) {
            log.trace("Event report via tunnel failed: {}", e.getMessage());
        }
    }

    @Override
    public void reportEventsAsync(List<Map<String, Object>> events) {
        if (events.isEmpty()) return;
        try {
            byte[] body = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(events);
            ControlMessage request = ControlMessage.request(
                    UUID.randomUUID().toString(),
                    "POST",
                    "/api/v1/proxy/events",
                    aiHeaders(),
                    body
            );
            // Fire-and-forget — don't await response
            DmzTunnelHandler h = tunnelAcceptor.getHandler();
            if (h != null) h.sendControlRequest(request, 10_000);
        } catch (Exception e) {
            log.trace("Batch event report via tunnel failed: {}", e.getMessage());
        }
    }

    // ── Private helpers ──────────────────────────────────────────────────

    private Map<String, String> aiHeaders() {
        LinkedHashMap<String, String> h = new LinkedHashMap<>();
        h.put("Content-Type", "application/json");
        if (spiffeAuth != null && spiffeAuth.isAvailable()) {
            String token = spiffeAuth.getJwtSvidFor("ai-engine");
            if (token != null) h.put("Authorization", "Bearer " + token);
        }
        return h;
    }

    /**
     * Parses a verdict JSON response body. Mirrors parent's private parseVerdict() logic
     * with the same TTL caps (ALLOW=15s, BLOCK=300s, default=60s).
     */
    private CachedVerdict parseVerdictFromResponse(String responseBody) throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var json = mapper.readTree(responseBody);

        Action action = Action.valueOf(json.path("action").asText("ALLOW"));
        int riskScore = json.path("riskScore").asInt(0);
        String reason = json.path("reason").asText("");
        int ttl = json.path("ttlSeconds").asInt(60);

        Integer maxConn = null, maxConcurrent = null;
        Long maxBytes = null;
        var rl = json.path("rateLimit");
        if (!rl.isMissingNode()) {
            maxConn = rl.path("maxConnectionsPerMinute").asInt(60);
            maxConcurrent = rl.path("maxConcurrentConnections").asInt(20);
            maxBytes = rl.path("maxBytesPerMinute").asLong(500_000_000L);
        }

        java.util.ArrayList<String> signals = new java.util.ArrayList<>();
        var sigNode = json.path("signals");
        if (sigNode.isArray()) {
            for (var s : sigNode) signals.add(s.asText());
        }

        // Security: cap local cache TTL — same caps as parent
        long cappedTtl = switch (action) {
            case BLOCK, BLACKHOLE -> Math.min(ttl, 300);
            case ALLOW -> Math.min(ttl, 15);
            default -> Math.min(ttl, 60);
        };

        Instant now = Instant.now();
        return new CachedVerdict(action, riskScore, reason,
                maxConn, maxConcurrent, maxBytes,
                signals, now, now.plusSeconds(cappedTtl));
    }

    /**
     * Local fallback when tunnel or AI engine is unavailable.
     * Fail-closed: THROTTLE with strict rate limits.
     */
    private CachedVerdict localFallbackThrottle(String sourceIp, int targetPort,
                                                 String protocol, String reason) {
        log.warn("AI engine unavailable via tunnel — THROTTLE mode (5/min per IP). Reason: {}", reason);
        Instant now = Instant.now();
        return new CachedVerdict(
                Action.THROTTLE,
                50,
                "Local fallback (THROTTLE): " + reason,
                5,
                2,
                10_000_000L,
                List.of("FALLBACK", "AI_UNAVAILABLE", "TUNNEL"),
                now,
                now.plusSeconds(30)
        );
    }
}
