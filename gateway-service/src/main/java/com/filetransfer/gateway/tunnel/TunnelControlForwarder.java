package com.filetransfer.gateway.tunnel;

import com.filetransfer.tunnel.control.ControlMessage;
import com.filetransfer.tunnel.control.ControlMessageCodec;
import com.filetransfer.tunnel.protocol.TunnelFrame;
import io.netty.channel.Channel;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Routes CONTROL_REQ frames from the DMZ proxy to internal HTTP services by path prefix.
 * <p>
 * Routing rules:
 * <ul>
 *   <li>/api/v1/proxy/   -> ai-engine</li>
 *   <li>/api/v1/screening/ -> screening-service</li>
 *   <li>/api/keystore/   -> keystore-manager</li>
 *   <li>/api/proxy/      -> self (gateway management API)</li>
 * </ul>
 * Uses java.net.http.HttpClient with sendAsync for non-blocking HTTP forwarding.
 * Preserves all headers from the original request.
 */
@Slf4j
public class TunnelControlForwarder {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    private final HttpClient httpClient;
    private final TunnelClientProperties properties;
    private final int gatewayPort;

    public TunnelControlForwarder(TunnelClientProperties properties, int gatewayPort) {
        this.properties = properties;
        this.gatewayPort = gatewayPort;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    /**
     * Forwards a CONTROL_REQ to the appropriate internal service and writes the
     * CONTROL_RES back on the tunnel channel.
     */
    public void forward(Channel tunnelChannel, ControlMessage request) {
        String baseUrl = resolveBaseUrl(request.getPath());
        if (baseUrl == null) {
            log.warn("No route for control request path: {}", request.getPath());
            sendErrorResponse(tunnelChannel, request.getCorrelationId(), 404, "No route for path");
            return;
        }

        URI uri = URI.create(baseUrl + request.getPath());
        HttpRequest.Builder httpBuilder = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(DEFAULT_TIMEOUT);

        // Preserve headers
        if (request.getHeaders() != null) {
            request.getHeaders().forEach((key, value) -> {
                // Skip restricted headers
                if (!isRestrictedHeader(key)) {
                    httpBuilder.header(key, value);
                }
            });
        }

        // Set method and body
        HttpRequest.BodyPublisher bodyPublisher = request.getBody() != null && request.getBody().length > 0
                ? HttpRequest.BodyPublishers.ofByteArray(request.getBody())
                : HttpRequest.BodyPublishers.noBody();

        switch (request.getMethod().toUpperCase()) {
            case "GET" -> httpBuilder.GET();
            case "POST" -> httpBuilder.POST(bodyPublisher);
            case "PUT" -> httpBuilder.PUT(bodyPublisher);
            case "DELETE" -> httpBuilder.DELETE();
            default -> httpBuilder.method(request.getMethod().toUpperCase(), bodyPublisher);
        }

        httpClient.sendAsync(httpBuilder.build(), HttpResponse.BodyHandlers.ofByteArray())
                .thenAccept(response -> {
                    ControlMessage controlResponse = ControlMessage.response(
                            request.getCorrelationId(),
                            response.statusCode(),
                            response.body()
                    );
                    byte[] encoded = ControlMessageCodec.encode(controlResponse);
                    tunnelChannel.writeAndFlush(TunnelFrame.controlResponse(encoded));
                    log.debug("Control request forwarded: {} {} -> {} (status={})",
                            request.getMethod(), request.getPath(), baseUrl, response.statusCode());
                })
                .exceptionally(ex -> {
                    log.error("Control request forwarding failed: {} {} -> {}: {}",
                            request.getMethod(), request.getPath(), baseUrl, ex.getMessage());
                    sendErrorResponse(tunnelChannel, request.getCorrelationId(), 502, ex.getMessage());
                    return null;
                });
    }

    private String resolveBaseUrl(String path) {
        if (path == null) return null;
        if (path.startsWith("/api/v1/proxy/")) return properties.getAiEngineUrl();
        if (path.startsWith("/api/v1/screening/")) return properties.getScreeningServiceUrl();
        if (path.startsWith("/api/keystore/")) return properties.getKeystoreManagerUrl();
        if (path.startsWith("/api/proxy/")) return "http://localhost:" + gatewayPort;
        return null;
    }

    private boolean isRestrictedHeader(String name) {
        // java.net.http.HttpClient restricts certain headers
        return name.equalsIgnoreCase("Host") || name.equalsIgnoreCase("Content-Length");
    }

    private void sendErrorResponse(Channel tunnelChannel, String correlationId, int status, String message) {
        try {
            byte[] body = ("{\"error\":\"" + (message != null ? message.replace("\"", "'") : "Unknown error") + "\"}").getBytes();
            ControlMessage errorResponse = ControlMessage.response(correlationId, status, body);
            byte[] encoded = ControlMessageCodec.encode(errorResponse);
            tunnelChannel.writeAndFlush(TunnelFrame.controlResponse(encoded));
        } catch (Exception e) {
            log.error("Failed to send error response for correlationId={}: {}", correlationId, e.getMessage());
        }
    }
}
