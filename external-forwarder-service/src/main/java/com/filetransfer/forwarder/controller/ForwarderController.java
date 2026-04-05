package com.filetransfer.forwarder.controller;

import com.filetransfer.forwarder.service.*;
import com.filetransfer.forwarder.transfer.TransferStallException;
import com.filetransfer.forwarder.transfer.TransferWatchdog;
import com.filetransfer.shared.entity.As2Partnership;
import com.filetransfer.shared.entity.DeliveryEndpoint;
import com.filetransfer.shared.entity.ExternalDestination;
import com.filetransfer.shared.enums.DeliveryProtocol;
import com.filetransfer.shared.enums.ExternalDestinationType;
import com.filetransfer.shared.repository.As2PartnershipRepository;
import com.filetransfer.shared.repository.DeliveryEndpointRepository;
import com.filetransfer.shared.repository.ExternalDestinationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * External Forwarder API with intelligent transfer management.
 *
 * <p>Key intelligence features:
 * <ul>
 *   <li><b>Activity-based sessions</b> — connections stay open as long as data flows,
 *       no fixed session timeout. Stalls are detected by the {@link TransferWatchdog}.</li>
 *   <li><b>Smart retry</b> — stall errors always get extra retry attempts with shorter
 *       backoff (the connection was partially working). Non-transient errors (auth, permission)
 *       are never retried.</li>
 *   <li><b>Progress-aware logging</b> — on stall, logs exactly how far the transfer got
 *       (bytes, percentage, idle time) for diagnostics.</li>
 * </ul>
 *
 * POST /api/forward/{destinationId}           — forward a multipart file
 * POST /api/forward/{destinationId}/base64    — forward a Base64-encoded file payload
 */
@Slf4j
@RestController
@RequestMapping("/api/forward")
@RequiredArgsConstructor
public class ForwarderController {

    private final ExternalDestinationRepository destinationRepository;
    private final DeliveryEndpointRepository deliveryEndpointRepository;
    private final As2PartnershipRepository as2PartnershipRepository;
    private final SftpForwarderService sftpForwarder;
    private final FtpForwarderService ftpForwarder;
    private final FtpsForwarderService ftpsForwarder;
    private final HttpForwarderService httpForwarder;
    private final KafkaForwarderService kafkaForwarder;
    private final As2ForwarderService as2Forwarder;
    private final As4ForwarderService as4Forwarder;
    private final TransferWatchdog transferWatchdog;

    @Value("${control-api.key:internal_control_secret}")
    private String controlApiKey;

    /** Extra retry attempts granted when a transfer stalls (on top of the configured max). */
    @Value("${forwarder.transfer.stall-extra-retries:2}")
    private int stallExtraRetries;

    /** Port counter for dynamic DMZ proxy mappings (range 40000-49999) */
    private final AtomicInteger dmzPortCounter = new AtomicInteger(40000);

    @PostMapping(value = "/{destinationId}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, String> forward(@PathVariable UUID destinationId,
                                        @RequestPart("file") MultipartFile file) throws Exception {
        ExternalDestination dest = findDest(destinationId);
        dispatch(dest, file.getOriginalFilename(), file.getBytes());
        return Map.of("status", "forwarded", "destination", dest.getName(), "file", file.getOriginalFilename());
    }

    @PostMapping("/{destinationId}/base64")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, String> forwardBase64(@PathVariable UUID destinationId,
                                              @RequestParam String filename,
                                              @RequestBody String base64Content) throws Exception {
        ExternalDestination dest = findDest(destinationId);
        byte[] bytes = Base64.getDecoder().decode(base64Content);
        dispatch(dest, filename, bytes);
        return Map.of("status", "forwarded", "destination", dest.getName(), "file", filename);
    }

    // --- Delivery Endpoint-based forwarding (used by FILE_DELIVERY flow step) ---

    @PostMapping(value = "/deliver/{endpointId}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, String> deliverFile(@PathVariable UUID endpointId,
                                            @RequestPart("file") MultipartFile file,
                                            @RequestParam(required = false) String trackId) throws Exception {
        DeliveryEndpoint ep = findEndpoint(endpointId);
        dispatchDelivery(ep, file.getOriginalFilename(), file.getBytes(), trackId);
        return Map.of("status", "delivered", "endpoint", ep.getName(),
                "protocol", ep.getProtocol().name(), "file", file.getOriginalFilename());
    }

    @PostMapping("/deliver/{endpointId}/base64")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, String> deliverBase64(@PathVariable UUID endpointId,
                                              @RequestParam String filename,
                                              @RequestParam(required = false) String trackId,
                                              @RequestBody String base64Content) throws Exception {
        DeliveryEndpoint ep = findEndpoint(endpointId);
        byte[] bytes = Base64.getDecoder().decode(base64Content);
        dispatchDelivery(ep, filename, bytes, trackId);
        return Map.of("status", "delivered", "endpoint", ep.getName(),
                "protocol", ep.getProtocol().name(), "file", filename);
    }

    private void dispatchDelivery(DeliveryEndpoint ep, String filename, byte[] bytes, String trackId) throws Exception {
        boolean useDmzProxy = ep.isProxyEnabled() && "DMZ".equalsIgnoreCase(ep.getProxyType());
        String dmzMappingName = null;

        log.info("Delivering {} ({} bytes) -> '{}' [{}://{}:{}] proxy={} trackId={}",
                filename, bytes.length, ep.getName(), ep.getProtocol(),
                ep.getHost(), ep.getPort(), ep.isProxyEnabled() ? ep.getProxyType() : "NONE", trackId);

        // If DMZ proxy is selected, hot-add a temporary port mapping so traffic
        // flows through the DMZ zone instead of going direct
        int dmzListenPort = 0;
        if (useDmzProxy) {
            dmzListenPort = dmzPortCounter.getAndUpdate(p -> p >= 49999 ? 40000 : p + 1);
            dmzMappingName = registerDmzMapping(ep, dmzListenPort);
        }

        int attempts = 0;
        int stallRetries = 0;
        int maxRetries = ep.getRetryCount() > 0 ? ep.getRetryCount() : 1;
        int effectiveMaxRetries = maxRetries; // increases on stalls
        Exception lastError = null;
        long baseDelay = ep.getRetryDelayMs() > 0 ? ep.getRetryDelayMs() : 5000;

        try {
            while (attempts < effectiveMaxRetries) {
                try {
                    // Build the effective endpoint — may be rewritten to point at DMZ proxy
                    DeliveryEndpoint effective = useDmzProxy
                            ? toDmzRoutedEndpoint(ep, dmzListenPort) : ep;

                    switch (effective.getProtocol()) {
                        case SFTP -> sftpForwarder.forward(toExternalDest(effective), filename, bytes);
                        case FTP -> ftpForwarder.forward(toExternalDest(effective), filename, bytes);
                        case FTPS -> ftpsForwarder.forward(effective, filename, bytes);
                        case HTTP, HTTPS, API -> httpForwarder.forward(effective, filename, bytes);
                        case AS2 -> dispatchAs2(effective, filename, bytes, trackId);
                        case AS4 -> dispatchAs4(effective, filename, bytes, trackId);
                    }
                    if (attempts > 0) {
                        log.info("Delivery succeeded on attempt {}/{} for '{}' (stall retries: {})",
                                attempts + 1, effectiveMaxRetries, ep.getName(), stallRetries);
                    }
                    return; // success
                } catch (Exception e) {
                    lastError = e;
                    attempts++;

                    boolean isStall = e instanceof TransferStallException;

                    // --- Smart retry classification ---
                    if (isStall) {
                        TransferStallException stall = (TransferStallException) e;
                        stallRetries++;

                        // Grant extra retry budget for stalls (connection was partially working)
                        if (stallRetries <= stallExtraRetries) {
                            effectiveMaxRetries = Math.max(effectiveMaxRetries, attempts + stallExtraRetries - stallRetries + 1);
                        }

                        log.warn("Transfer stalled for '{}': {}/{} bytes ({}%) transferred, "
                                        + "idle {}s — attempt {}/{} (stall retry {}/{})",
                                ep.getName(), stall.getBytesTransferred(), stall.getTotalBytes(),
                                stall.getProgressPercent(), stall.getIdleSeconds(),
                                attempts, effectiveMaxRetries, stallRetries, stallExtraRetries);
                    } else if (!isRetryableDeliveryError(e)) {
                        // Non-transient errors: auth, permission, not-found — stop immediately
                        log.error("Non-retryable delivery error for '{}': {}", ep.getName(), e.getMessage());
                        break;
                    }

                    if (attempts < effectiveMaxRetries) {
                        long delay;
                        if (isStall) {
                            // Shorter backoff for stalls — the connection was working, just had a hiccup
                            // Use linear backoff: baseDelay * stallRetry (not exponential)
                            delay = Math.min(baseDelay * stallRetries, 30_000);
                        } else {
                            // Exponential backoff with jitter for connection/protocol errors
                            long exponential = baseDelay * (1L << Math.min(attempts - 1, 6));
                            long capped = Math.min(exponential, 120_000);
                            double jitter = 0.75 + Math.random() * 0.5;
                            delay = (long) (capped * jitter);
                        }

                        log.warn("Delivery attempt {}/{} failed for '{}': {} — retrying in {}ms{}",
                                attempts, effectiveMaxRetries, ep.getName(), e.getMessage(), delay,
                                isStall ? " (stall recovery)" : " (exponential backoff)");
                        Thread.sleep(delay);
                    }
                }
            }
            throw new RuntimeException("Delivery failed after " + attempts + " attempt(s) to '"
                    + ep.getName() + "' (stall retries: " + stallRetries + "): "
                    + (lastError != null ? lastError.getMessage() : "unknown error"), lastError);
        } finally {
            // Always clean up the temporary DMZ mapping
            if (dmzMappingName != null) {
                unregisterDmzMapping(ep, dmzMappingName);
            }
        }
    }

    // --- DMZ Proxy integration ---

    /**
     * Hot-add a temporary port mapping on the DMZ proxy so that traffic for this
     * delivery is routed through the DMZ zone.
     * Returns the mapping name for later cleanup.
     */
    private String registerDmzMapping(DeliveryEndpoint ep, int listenPort) {
        String dmzApiUrl = "http://" + ep.getProxyHost() + ":" + ep.getProxyPort();
        String mappingName = "delivery-" + ep.getId().toString().substring(0, 8)
                + "-" + System.currentTimeMillis();

        Map<String, Object> mapping = new LinkedHashMap<>();
        mapping.put("name", mappingName);
        mapping.put("listenPort", listenPort);
        mapping.put("targetHost", ep.getHost());
        mapping.put("targetPort", ep.getPort());

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Internal-Key", controlApiKey);

        RestTemplate rest = new RestTemplate();
        rest.postForEntity(dmzApiUrl + "/api/proxy/mappings",
                new HttpEntity<>(mapping, headers), Map.class);

        log.info("DMZ proxy mapping created: {} -> {}:{} via :{}",
                mappingName, ep.getHost(), ep.getPort(), listenPort);
        return mappingName;
    }

    /** Remove the temporary DMZ proxy mapping after delivery completes */
    private void unregisterDmzMapping(DeliveryEndpoint ep, String mappingName) {
        try {
            String dmzApiUrl = "http://" + ep.getProxyHost() + ":" + ep.getProxyPort();
            HttpHeaders headers = new HttpHeaders();
            headers.set("X-Internal-Key", controlApiKey);

            RestTemplate rest = new RestTemplate();
            rest.exchange(dmzApiUrl + "/api/proxy/mappings/" + mappingName,
                    HttpMethod.DELETE, new HttpEntity<>(headers), Void.class);

            log.info("DMZ proxy mapping removed: {}", mappingName);
        } catch (Exception e) {
            log.warn("Failed to cleanup DMZ proxy mapping '{}': {}", mappingName, e.getMessage());
        }
    }

    /**
     * Rewrite the endpoint to route through the DMZ proxy.
     * Host/port point to the DMZ proxy's listen port; all other config (auth, TLS, path) stays the same.
     */
    private DeliveryEndpoint toDmzRoutedEndpoint(DeliveryEndpoint original, int listenPort) {
        return DeliveryEndpoint.builder()
                .id(original.getId())
                .name(original.getName())
                .description(original.getDescription())
                .protocol(original.getProtocol())
                .host(original.getProxyHost())  // route through DMZ proxy host
                .port(listenPort)               // route through DMZ proxy listen port
                .basePath(original.getBasePath())
                .authType(original.getAuthType())
                .username(original.getUsername())
                .encryptedPassword(original.getEncryptedPassword())
                .sshPrivateKey(original.getSshPrivateKey())
                .bearerToken(original.getBearerToken())
                .apiKeyHeader(original.getApiKeyHeader())
                .apiKeyValue(original.getApiKeyValue())
                .httpMethod(original.getHttpMethod())
                .httpHeaders(original.getHttpHeaders())
                .contentType(original.getContentType())
                .tlsEnabled(original.isTlsEnabled())
                .tlsTrustAll(original.isTlsTrustAll())
                .connectionTimeoutMs(original.getConnectionTimeoutMs())
                .readTimeoutMs(original.getReadTimeoutMs())
                .proxyEnabled(false) // no double-proxying
                .active(true)
                .build();
    }

    /** Adapt DeliveryEndpoint to ExternalDestination for legacy SFTP/FTP forwarders */
    private ExternalDestination toExternalDest(DeliveryEndpoint ep) {
        return ExternalDestination.builder()
                .name(ep.getName())
                .type(ep.getProtocol() == DeliveryProtocol.SFTP
                        ? ExternalDestinationType.SFTP : ExternalDestinationType.FTP)
                .host(ep.getHost())
                .port(ep.getPort())
                .username(ep.getUsername())
                .encryptedPassword(ep.getEncryptedPassword())
                .remotePath(ep.getBasePath())
                .active(true)
                .build();
    }

    private DeliveryEndpoint findEndpoint(UUID id) {
        return deliveryEndpointRepository.findById(id)
                .filter(DeliveryEndpoint::isActive)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Delivery endpoint not found or inactive: " + id));
    }

    // --- Legacy ExternalDestination-based forwarding ---

    private void dispatch(ExternalDestination dest, String filename, byte[] bytes) throws Exception {
        log.info("Forwarding {} ({} bytes) -> {} [{}]", filename, bytes.length, dest.getName(), dest.getType());
        if (dest.getType() == ExternalDestinationType.SFTP) {
            sftpForwarder.forward(dest, filename, bytes);
        } else if (dest.getType() == ExternalDestinationType.FTP) {
            ftpForwarder.forward(dest, filename, bytes);
        } else if (dest.getType() == ExternalDestinationType.KAFKA) {
            kafkaForwarder.forward(dest, filename, bytes);
        } else {
            throw new IllegalArgumentException("Unknown destination type: " + dest.getType());
        }
    }

    private void dispatchAs2(DeliveryEndpoint ep, String filename, byte[] bytes, String trackId) throws Exception {
        As2Partnership partnership = resolvePartnership(ep, "AS2");
        as2Forwarder.forward(partnership, filename, bytes, trackId);
    }

    private void dispatchAs4(DeliveryEndpoint ep, String filename, byte[] bytes, String trackId) throws Exception {
        As2Partnership partnership = resolvePartnership(ep, "AS4");
        as4Forwarder.forward(partnership, filename, bytes, trackId);
    }

    /**
     * Resolve AS2/AS4 partnership from endpoint config.
     * Prefers direct as2PartnershipId link, falls back to name match.
     */
    private As2Partnership resolvePartnership(DeliveryEndpoint ep, String protocol) {
        // Direct link via as2PartnershipId (preferred)
        if (ep.getAs2PartnershipId() != null) {
            return as2PartnershipRepository.findById(ep.getAs2PartnershipId())
                    .filter(p -> p.isActive() && protocol.equals(p.getProtocol()))
                    .orElseThrow(() -> new IllegalArgumentException(
                            protocol + " partnership not found or inactive: " + ep.getAs2PartnershipId()));
        }
        // Fallback: match by endpoint name = partnership name
        return as2PartnershipRepository.findByPartnerNameAndActiveTrue(ep.getName())
                .filter(p -> protocol.equals(p.getProtocol()))
                .orElseThrow(() -> new IllegalArgumentException(
                        "No " + protocol + " partnership found for endpoint: " + ep.getName()));
    }

    /**
     * Determine if a delivery error is transient (worth retrying).
     * Auth failures, permission errors, and format errors should not be retried.
     * Stall errors are always retried (handled separately in the retry loop).
     */
    boolean isRetryableDeliveryError(Exception e) {
        // Stalls are always retryable — handled with special logic above
        if (e instanceof TransferStallException) return true;

        String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
        if (msg.contains("permission denied") || msg.contains("auth") || msg.contains("401") || msg.contains("403")) return false;
        if (msg.contains("no such file") || msg.contains("not found") || msg.contains("404")) return false;
        if (msg.contains("key expired") || msg.contains("certificate")) return false;
        return true;
    }

    private ExternalDestination findDest(UUID id) {
        return destinationRepository.findById(id)
                .filter(ExternalDestination::isActive)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "External destination not found or inactive: " + id));
    }
}
