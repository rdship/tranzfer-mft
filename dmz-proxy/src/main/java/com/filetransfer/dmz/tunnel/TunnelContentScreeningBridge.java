package com.filetransfer.dmz.tunnel;

import com.filetransfer.dmz.inspection.ContentScreeningBridge;
import com.filetransfer.dmz.security.SpiffeProxyAuth;
import com.filetransfer.tunnel.control.ControlMessage;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Tunnel-aware content screening bridge that routes screening HTTP calls through the
 * DMZ tunnel instead of making direct cross-zone requests to screening-service:8092.
 * <p>
 * Buffer management (feedData, transferComplete, transferCancelled) is unchanged —
 * only the HTTP call path in {@link #screenTransfer} is replaced with a CONTROL_REQ
 * frame sent through the multiplexed tunnel.
 * <p>
 * On tunnel failure, returns {@link Outcome#ERROR} so the caller can decide whether
 * to block or allow based on its {@link ContentScreeningConfig#blockOnTimeout()} setting.
 */
@Slf4j
public class TunnelContentScreeningBridge extends ContentScreeningBridge {

    private final TunnelAcceptor tunnelAcceptor;
    private final SpiffeProxyAuth spiffeAuth;
    private final int screeningTimeoutSeconds;

    /**
     * @param screeningServiceUrl base URL (used by parent for non-tunnel fallback paths)
     * @param spiffeAuth          SPIFFE auth helper for outbound JWT-SVID headers
     * @param config              screening configuration
     * @param tunnelAcceptor      the tunnel acceptor (handler resolved lazily)
     */
    public TunnelContentScreeningBridge(String screeningServiceUrl, SpiffeProxyAuth spiffeAuth,
                                         ContentScreeningConfig config,
                                         TunnelAcceptor tunnelAcceptor) {
        super(screeningServiceUrl, spiffeAuth, config);
        this.tunnelAcceptor = tunnelAcceptor;
        this.spiffeAuth = spiffeAuth;
        this.screeningTimeoutSeconds = config.screeningTimeoutSeconds();
    }

    /**
     * Overrides the parent's screenTransfer to route content scanning through the tunnel.
     * <p>
     * The parent's screenTransfer delegates to private doScreen() via supplyAsync.
     * We replace the entire future with a tunnel-based CONTROL_REQ that carries the
     * content as the POST body, matching the same HTTP contract as the screening-service.
     */
    @Override
    public CompletableFuture<ScreeningResult> screenTransfer(String sourceIp, int port,
                                                              String protocol, String filename,
                                                              byte[] content) {
        // Recheck the same preconditions the parent checks before doScreen
        if (content == null || content.length == 0) {
            return CompletableFuture.completedFuture(
                    new ScreeningResult(Outcome.SKIPPED, "empty_content", 0, 0));
        }

        DmzTunnelHandler th = tunnelAcceptor.getHandler();
        if (th == null || !th.isConnected()) {
            // Fall back to parent's direct HTTP path (covers startup window + tunnel outage)
            log.debug("Tunnel not connected — delegating screening to parent direct HTTP");
            return super.screenTransfer(sourceIp, port, protocol, filename, content);
        }

        long startTime = System.currentTimeMillis();

        ControlMessage request = ControlMessage.request(
                UUID.randomUUID().toString(),
                "POST",
                "/api/v1/screening/scan/text",
                buildScreeningHeaders(sourceIp, port, protocol, filename),
                content
        );

        long timeoutMs = screeningTimeoutSeconds * 1000L;

        return th.sendControlRequest(request, timeoutMs)
                .orTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .thenApply(response -> {
                    long durationMs = System.currentTimeMillis() - startTime;
                    if (response.getStatusCode() == 200 && response.getBody() != null) {
                        return parseResponse(
                                new String(response.getBody(), StandardCharsets.UTF_8), durationMs);
                    } else {
                        log.warn("Screening service returned {} via tunnel", response.getStatusCode());
                        return new ScreeningResult(Outcome.ERROR,
                                "http_" + response.getStatusCode(), 0, durationMs);
                    }
                })
                .exceptionally(ex -> {
                    long durationMs = System.currentTimeMillis() - startTime;
                    if (ex.getCause() instanceof java.util.concurrent.TimeoutException) {
                        log.warn("Screening via tunnel timed out after {}ms for {} from {}",
                                durationMs, protocol, sourceIp);
                        return new ScreeningResult(Outcome.TIMEOUT,
                                "tunnel_timeout_" + durationMs + "ms", 0, durationMs);
                    }
                    log.warn("Screening via tunnel failed for {} from {}: {}",
                            protocol, sourceIp, ex.getMessage());
                    return new ScreeningResult(Outcome.ERROR, ex.getMessage(), 0, durationMs);
                });
    }

    // ── Private helpers ──────────────────────────────────────────────────

    private Map<String, String> buildScreeningHeaders(String sourceIp, int port,
                                                       String protocol, String filename) {
        LinkedHashMap<String, String> h = new LinkedHashMap<>();
        h.put("Content-Type", "text/plain");
        if (spiffeAuth != null && spiffeAuth.isAvailable()) {
            String token = spiffeAuth.getJwtSvidFor("screening-service");
            if (token != null) h.put("Authorization", "Bearer " + token);
        }
        h.put("X-Source-Ip", sourceIp != null ? sourceIp : "unknown");
        h.put("X-Source-Port", String.valueOf(port));
        h.put("X-Protocol", protocol != null ? protocol : "UNKNOWN");
        h.put("X-Filename", filename != null ? filename : "unknown");
        return h;
    }

    /**
     * Parses the screening-service JSON response.
     * Mirrors parent's private parseScreeningResponse() — lightweight manual JSON extraction.
     */
    private ScreeningResult parseResponse(String responseBody, long durationMs) {
        try {
            String outcomeStr = extractJsonString(responseBody, "outcome");
            String detail = extractJsonString(responseBody, "detail");
            int hits = extractJsonInt(responseBody, "hits");
            if (hits == 0) {
                hits = extractJsonInt(responseBody, "hitsFound");
            }

            Outcome outcome;
            try {
                outcome = Outcome.valueOf(outcomeStr != null ? outcomeStr.toUpperCase() : "ERROR");
            } catch (IllegalArgumentException e) {
                outcome = Outcome.ERROR;
                detail = "unknown_outcome: " + outcomeStr;
            }
            return new ScreeningResult(outcome, detail != null ? detail : "", hits, durationMs);
        } catch (Exception e) {
            log.warn("Failed to parse screening response from tunnel: {}", e.getMessage());
            return new ScreeningResult(Outcome.ERROR, "parse_error: " + e.getMessage(), 0, durationMs);
        }
    }

    private static String extractJsonString(String json, String key) {
        String search = "\"" + key + "\"";
        int keyIndex = json.indexOf(search);
        if (keyIndex < 0) return null;

        int colonIndex = json.indexOf(':', keyIndex + search.length());
        if (colonIndex < 0) return null;

        int start = colonIndex + 1;
        while (start < json.length() && json.charAt(start) == ' ') start++;
        if (start >= json.length()) return null;

        if (json.charAt(start) == '"') {
            int end = json.indexOf('"', start + 1);
            if (end > start) return json.substring(start + 1, end);
        }

        int end = start;
        while (end < json.length() && json.charAt(end) != ',' && json.charAt(end) != '}') end++;
        return json.substring(start, end).trim();
    }

    private static int extractJsonInt(String json, String key) {
        String value = extractJsonString(json, key);
        if (value == null || value.isEmpty()) return 0;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
