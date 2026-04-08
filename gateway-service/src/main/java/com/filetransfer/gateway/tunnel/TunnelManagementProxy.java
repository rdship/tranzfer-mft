package com.filetransfer.gateway.tunnel;

import com.filetransfer.tunnel.control.ControlMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * REST controller that mirrors the DMZ proxy's management API through the tunnel.
 * <p>
 * Internal services (onboarding-api, config-service) call these endpoints to manage
 * DMZ proxy mappings and security policies. Each request is translated into a CONTROL_REQ,
 * sent through the tunnel, and the CONTROL_RES is returned to the caller.
 * <p>
 * When the tunnel is down, all endpoints return 503 Service Unavailable.
 */
@Slf4j
@RestController
@RequestMapping("/api/proxy")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "tunnel.enabled", havingValue = "true")
public class TunnelManagementProxy {

    private static final long MANAGEMENT_TIMEOUT_MS = 5000;

    private final TunnelClient tunnelClient;

    // ── Mapping CRUD ──

    @PostMapping("/mappings")
    public CompletableFuture<ResponseEntity<byte[]>> createMapping(
            @RequestHeader Map<String, String> headers,
            @RequestBody byte[] body) {
        return forwardControl("POST", "/api/proxy/mappings", headers, body);
    }

    @PutMapping("/mappings/{name}")
    public CompletableFuture<ResponseEntity<byte[]>> updateMapping(
            @PathVariable String name,
            @RequestHeader Map<String, String> headers,
            @RequestBody byte[] body) {
        return forwardControl("PUT", "/api/proxy/mappings/" + name, headers, body);
    }

    @DeleteMapping("/mappings/{name}")
    public CompletableFuture<ResponseEntity<byte[]>> deleteMapping(
            @PathVariable String name,
            @RequestHeader Map<String, String> headers) {
        return forwardControl("DELETE", "/api/proxy/mappings/" + name, headers, null);
    }

    @GetMapping("/mappings")
    public CompletableFuture<ResponseEntity<byte[]>> listMappings(
            @RequestHeader Map<String, String> headers) {
        return forwardControl("GET", "/api/proxy/mappings", headers, null);
    }

    // ── Security policy ──

    @PutMapping("/mappings/{name}/security-policy")
    public CompletableFuture<ResponseEntity<byte[]>> updateSecurityPolicy(
            @PathVariable String name,
            @RequestHeader Map<String, String> headers,
            @RequestBody byte[] body) {
        return forwardControl("PUT", "/api/proxy/mappings/" + name + "/security-policy", headers, body);
    }

    // ── DMZ status ──

    @GetMapping("/status")
    public CompletableFuture<ResponseEntity<byte[]>> dmzStatus(
            @RequestHeader Map<String, String> headers) {
        return forwardControl("GET", "/api/proxy/status", headers, null);
    }

    // ── Catch-all: forward any other /api/proxy/** endpoint through the tunnel ──
    // Covers: security/*, backends/health, audit/*, zones/*, egress/*, qos/*, metrics, health

    @RequestMapping("/**")
    public CompletableFuture<ResponseEntity<byte[]>> catchAll(
            HttpServletRequest request,
            @RequestHeader Map<String, String> headers,
            @RequestBody(required = false) byte[] body) {
        String path = request.getRequestURI();
        String method = request.getMethod();
        return forwardControl(method, path, headers, body);
    }

    // ── Internal ──

    private CompletableFuture<ResponseEntity<byte[]>> forwardControl(
            String method, String path, Map<String, String> headers, byte[] body) {

        if (!tunnelClient.isActive()) {
            log.warn("Tunnel is down, cannot forward {} {}", method, path);
            return CompletableFuture.completedFuture(
                    ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                            .body("{\"error\":\"Tunnel to DMZ proxy is not active\"}".getBytes()));
        }

        ControlMessage request = ControlMessage.request(
                UUID.randomUUID().toString(), method, path, headers, body);

        log.debug("Forwarding management request: {} {} (correlationId={})",
                method, path, request.getCorrelationId());

        return tunnelClient.sendControlRequest(request, MANAGEMENT_TIMEOUT_MS)
                .thenApply(response -> {
                    log.debug("Management response: {} {} -> status={}",
                            method, path, response.getStatusCode());
                    return ResponseEntity
                            .status(response.getStatusCode())
                            .body(response.getBody());
                })
                .exceptionally(ex -> {
                    log.error("Management request failed: {} {}: {}", method, path, ex.getMessage());
                    return ResponseEntity
                            .status(HttpStatus.GATEWAY_TIMEOUT)
                            .body(("{\"error\":\"" + ex.getMessage().replace("\"", "'") + "\"}").getBytes());
                });
    }
}
