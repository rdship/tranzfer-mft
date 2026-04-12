package com.filetransfer.onboarding.controller;

import com.filetransfer.shared.config.PlatformConfig;
import com.filetransfer.shared.security.Roles;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.util.*;

/**
 * Aggregated view of all platform service listeners.
 * Queries /api/internal/listeners on every registered service
 * and returns a unified dashboard of HTTP + HTTPS endpoints.
 *
 * <p>The UI's Server Settings page shows this as a grid:
 * each service with its ports, protocols, TLS status, and start/stop controls.
 */
@Slf4j
@RestController
@RequestMapping("/api/platform/listeners")
@RequiredArgsConstructor
@PreAuthorize(Roles.ADMIN)
public class PlatformListenerController {

    private final PlatformConfig platformConfig;
    private final RestTemplate restTemplate;

    /**
     * Returns all listeners across all services.
     */
    @GetMapping
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getAllListeners() {
        List<Map<String, Object>> all = new ArrayList<>();

        Map<String, String> services = Map.ofEntries(
                Map.entry("onboarding-api", "http://onboarding-api:8080"),
                Map.entry("config-service", "http://config-service:8084"),
                Map.entry("sftp-service", "http://sftp-service:8081"),
                Map.entry("ftp-service", "http://ftp-service:8082"),
                Map.entry("ftp-web-service", "http://ftp-web-service:8083"),
                Map.entry("gateway-service", "http://gateway-service:8085"),
                Map.entry("encryption-service", "http://encryption-service:8086"),
                Map.entry("forwarder-service", "http://external-forwarder-service:8087"),
                Map.entry("dmz-proxy", "http://dmz-proxy-internal:8088"),
                Map.entry("license-service", "http://license-service:8089"),
                Map.entry("analytics-service", "http://analytics-service:8090"),
                Map.entry("ai-engine", "http://ai-engine:8091"),
                Map.entry("screening-service", "http://screening-service:8092"),
                Map.entry("keystore-manager", "http://keystore-manager:8093"),
                Map.entry("as2-service", "http://as2-service:8094"),
                Map.entry("edi-converter", "http://edi-converter:8095"),
                Map.entry("storage-manager", "http://storage-manager:8096"),
                Map.entry("notification-service", "http://notification-service:8097"),
                Map.entry("platform-sentinel", "http://platform-sentinel:8098")
        );

        for (var entry : services.entrySet()) {
            try {
                List<Map<String, Object>> listeners = restTemplate.getForObject(
                        entry.getValue() + "/api/internal/listeners", List.class);
                if (listeners != null) {
                    all.addAll(listeners);
                }
            } catch (Exception e) {
                // Service down — add an OFFLINE placeholder
                all.add(Map.of(
                        "service", entry.getKey(),
                        "state", "OFFLINE",
                        "error", e.getMessage() != null ? e.getMessage() : "unreachable"
                ));
            }
        }
        return all;
    }

    /**
     * Pause a specific service's listener (stop accepting connections on that port).
     */
    @PostMapping("/{service}/{port}/pause")
    public Map<String, Object> pauseListener(@PathVariable String service, @PathVariable int port) {
        return controlListener(service, port, "pause");
    }

    /**
     * Resume a specific service's listener.
     */
    @PostMapping("/{service}/{port}/resume")
    public Map<String, Object> resumeListener(@PathVariable String service, @PathVariable int port) {
        return controlListener(service, port, "resume");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> controlListener(String service, int port, String action) {
        // Resolve service URL from the same map
        String baseUrl = resolveServiceUrl(service);
        if (baseUrl == null) {
            return Map.of("error", "Unknown service: " + service);
        }
        try {
            return restTemplate.postForObject(
                    baseUrl + "/api/internal/listeners/" + port + "/" + action,
                    null, Map.class);
        } catch (Exception e) {
            return Map.of("error", e.getMessage());
        }
    }

    private String resolveServiceUrl(String service) {
        return switch (service) {
            case "onboarding-api" -> "http://onboarding-api:8080";
            case "config-service" -> "http://config-service:8084";
            case "sftp-service" -> "http://sftp-service:8081";
            case "ftp-service" -> "http://ftp-service:8082";
            case "gateway-service" -> "http://gateway-service:8085";
            case "encryption-service" -> "http://encryption-service:8086";
            case "screening-service" -> "http://screening-service:8092";
            case "keystore-manager" -> "http://keystore-manager:8093";
            case "storage-manager" -> "http://storage-manager:8096";
            case "ai-engine" -> "http://ai-engine:8091";
            case "platform-sentinel" -> "http://platform-sentinel:8098";
            default -> null;
        };
    }
}
