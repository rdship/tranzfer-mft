package com.filetransfer.forwarder.controller;

import com.filetransfer.forwarder.service.*;
import com.filetransfer.shared.entity.DeliveryEndpoint;
import com.filetransfer.shared.entity.ExternalDestination;
import com.filetransfer.shared.enums.DeliveryProtocol;
import com.filetransfer.shared.enums.ExternalDestinationType;
import com.filetransfer.shared.repository.DeliveryEndpointRepository;
import com.filetransfer.shared.repository.ExternalDestinationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.Base64;
import java.util.Map;
import java.util.UUID;

/**
 * External Forwarder API
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
    private final SftpForwarderService sftpForwarder;
    private final FtpForwarderService ftpForwarder;
    private final FtpsForwarderService ftpsForwarder;
    private final HttpForwarderService httpForwarder;
    private final KafkaForwarderService kafkaForwarder;

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
                                            @RequestPart("file") MultipartFile file) throws Exception {
        DeliveryEndpoint ep = findEndpoint(endpointId);
        dispatchDelivery(ep, file.getOriginalFilename(), file.getBytes());
        return Map.of("status", "delivered", "endpoint", ep.getName(),
                "protocol", ep.getProtocol().name(), "file", file.getOriginalFilename());
    }

    @PostMapping("/deliver/{endpointId}/base64")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, String> deliverBase64(@PathVariable UUID endpointId,
                                              @RequestParam String filename,
                                              @RequestBody String base64Content) throws Exception {
        DeliveryEndpoint ep = findEndpoint(endpointId);
        byte[] bytes = Base64.getDecoder().decode(base64Content);
        dispatchDelivery(ep, filename, bytes);
        return Map.of("status", "delivered", "endpoint", ep.getName(),
                "protocol", ep.getProtocol().name(), "file", filename);
    }

    private void dispatchDelivery(DeliveryEndpoint ep, String filename, byte[] bytes) throws Exception {
        log.info("Delivering {} ({} bytes) → '{}' [{}://{}:{}]",
                filename, bytes.length, ep.getName(), ep.getProtocol(), ep.getHost(), ep.getPort());

        int attempts = 0;
        int maxRetries = ep.getRetryCount() > 0 ? ep.getRetryCount() : 1;
        Exception lastError = null;

        while (attempts < maxRetries) {
            try {
                switch (ep.getProtocol()) {
                    case SFTP -> sftpForwarder.forward(toExternalDest(ep), filename, bytes);
                    case FTP -> ftpForwarder.forward(toExternalDest(ep), filename, bytes);
                    case FTPS -> ftpsForwarder.forward(ep, filename, bytes);
                    case HTTP, HTTPS, API -> httpForwarder.forward(ep, filename, bytes);
                }
                return; // success
            } catch (Exception e) {
                lastError = e;
                attempts++;
                if (attempts < maxRetries) {
                    log.warn("Delivery attempt {}/{} failed for '{}': {} — retrying in {}ms",
                            attempts, maxRetries, ep.getName(), e.getMessage(), ep.getRetryDelayMs());
                    Thread.sleep(ep.getRetryDelayMs());
                }
            }
        }
        throw new RuntimeException("Delivery failed after " + maxRetries + " attempts to '"
                + ep.getName() + "': " + (lastError != null ? lastError.getMessage() : "unknown error"), lastError);
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
        log.info("Forwarding {} ({} bytes) → {} [{}]", filename, bytes.length, dest.getName(), dest.getType());
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

    private ExternalDestination findDest(UUID id) {
        return destinationRepository.findById(id)
                .filter(ExternalDestination::isActive)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "External destination not found or inactive: " + id));
    }
}
