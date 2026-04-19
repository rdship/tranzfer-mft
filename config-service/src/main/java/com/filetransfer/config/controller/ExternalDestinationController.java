package com.filetransfer.config.controller;

import com.filetransfer.shared.entity.core.ExternalDestination;
import com.filetransfer.shared.entity.transfer.DeliveryEndpoint;
import com.filetransfer.shared.enums.AuthType;
import com.filetransfer.shared.enums.DeliveryProtocol;
import com.filetransfer.shared.enums.ExternalDestinationType;
import com.filetransfer.shared.repository.core.ExternalDestinationRepository;
import com.filetransfer.shared.repository.transfer.DeliveryEndpointRepository;
import com.filetransfer.shared.security.Roles;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

/**
 * External Destination API — configure SFTP/FTP/Kafka forwarding targets.
 *
 * GET    /api/external-destinations
 * GET    /api/external-destinations?type=KAFKA
 * GET    /api/external-destinations/{id}
 * POST   /api/external-destinations
 * PUT    /api/external-destinations/{id}
 * DELETE /api/external-destinations/{id}
 *
 * <p><b>R132 mirror-write:</b> for SFTP/FTP types, writes are mirrored to
 * {@code delivery_endpoints} so the flow engine (FILE_DELIVERY step) sees
 * the same endpoint the admin just created. Closes the "admin configures
 * a partner but flows can't deliver to it" confusion.
 */
@Slf4j
@RestController
@RequestMapping("/api/external-destinations")
@RequiredArgsConstructor
@PreAuthorize(Roles.OPERATOR)
public class ExternalDestinationController {

    private final ExternalDestinationRepository repository;
    private final DeliveryEndpointRepository deliveryEndpointRepository;

    @GetMapping
    public List<ExternalDestination> list(@RequestParam(required = false) ExternalDestinationType type) {
        return type != null ? repository.findByTypeAndActiveTrue(type) : repository.findByActiveTrue();
    }

    @GetMapping("/{id}")
    public ExternalDestination get(@PathVariable UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ExternalDestination create(@Valid @RequestBody ExternalDestination dest) {
        dest.setId(null);
        ExternalDestination saved = repository.save(dest);
        mirrorToDeliveryEndpoint(saved);
        return saved;
    }

    @PutMapping("/{id}")
    public ExternalDestination update(@PathVariable UUID id, @Valid @RequestBody ExternalDestination dest) {
        if (!repository.existsById(id)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        dest.setId(id);
        ExternalDestination saved = repository.save(dest);
        mirrorToDeliveryEndpoint(saved);
        return saved;
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        repository.findById(id).ifPresent(d -> {
            deliveryEndpointRepository.findByNameAndActiveTrue(d.getName())
                    .ifPresent(deliveryEndpointRepository::delete);
        });
        repository.deleteById(id);
    }

    /**
     * Upsert a matching {@link DeliveryEndpoint} by name so FILE_DELIVERY
     * flows can reach the destination the admin just created.
     * Kafka destinations are skipped (no DeliveryProtocol equivalent;
     * Kafka routing goes through a different forwarder path).
     */
    private void mirrorToDeliveryEndpoint(ExternalDestination src) {
        if (src.getType() == ExternalDestinationType.KAFKA) return;
        DeliveryProtocol protocol = src.getType() == ExternalDestinationType.SFTP
                ? DeliveryProtocol.SFTP : DeliveryProtocol.FTP;
        DeliveryEndpoint dest = deliveryEndpointRepository
                .findByNameAndActiveTrue(src.getName())
                .orElseGet(() -> DeliveryEndpoint.builder().name(src.getName()).build());
        dest.setProtocol(protocol);
        dest.setHost(src.getHost());
        dest.setPort(src.getPort());
        dest.setBasePath(src.getRemotePath());
        dest.setUsername(src.getUsername());
        dest.setEncryptedPassword(src.getEncryptedPassword());
        dest.setBearerToken(src.getBearerToken());
        dest.setAuthType(parseAuthType(src.getAuthType()));
        dest.setActive(src.isActive());
        try {
            deliveryEndpointRepository.save(dest);
        } catch (Exception ex) {
            log.warn("[ExternalDestinationController] mirror to delivery_endpoints failed for '{}': {}",
                    src.getName(), ex.getMessage());
        }
    }

    private AuthType parseAuthType(String value) {
        if (value == null || value.isBlank()) return AuthType.NONE;
        return switch (value.toUpperCase()) {
            case "BASIC", "PASSWORD" -> AuthType.BASIC;
            case "BEARER", "BEARER_TOKEN" -> AuthType.BEARER_TOKEN;
            case "API_KEY", "APIKEY" -> AuthType.API_KEY;
            case "SSH_KEY", "SSHKEY", "CLIENT_CERT" -> AuthType.SSH_KEY;
            case "OAUTH2", "OAUTH" -> AuthType.OAUTH2;
            default -> AuthType.NONE;
        };
    }
}
