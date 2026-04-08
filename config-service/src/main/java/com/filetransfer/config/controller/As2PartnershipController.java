package com.filetransfer.config.controller;

import com.filetransfer.shared.entity.As2Partnership;
import com.filetransfer.shared.entity.SecurityProfile;
import com.filetransfer.shared.repository.As2PartnershipRepository;
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
 * CRUD API for AS2/AS4 trading partner partnerships.
 *
 * Partnerships define the connection parameters for B2B message exchange:
 *   - Partner identification (AS2-ID)
 *   - Endpoint URL
 *   - Security settings (signing, encryption algorithms)
 *   - MDN (receipt) preferences
 */
@Slf4j
@RestController
@RequestMapping("/api/as2-partnerships")
@RequiredArgsConstructor
@PreAuthorize(Roles.OPERATOR)
public class As2PartnershipController {

    private final As2PartnershipRepository repository;

    @GetMapping
    public List<As2Partnership> list(
            @RequestParam(required = false) String protocol) {
        if (protocol != null) {
            return repository.findByProtocolAndActiveTrue(protocol.toUpperCase());
        }
        return repository.findByActiveTrue();
    }

    @GetMapping("/{id}")
    public As2Partnership get(@PathVariable UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Partnership not found: " + id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public As2Partnership create(@Valid @RequestBody As2Partnership partnership) {
        validatePartnership(partnership);
        log.info("Creating AS2 partnership: {} (partner={}, protocol={})",
                partnership.getPartnerName(), partnership.getPartnerAs2Id(), partnership.getProtocol());
        return repository.save(partnership);
    }

    @PutMapping("/{id}")
    public As2Partnership update(@PathVariable UUID id, @Valid @RequestBody As2Partnership updated) {
        As2Partnership existing = repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Partnership not found: " + id));

        validatePartnership(updated);
        existing.setPartnerName(updated.getPartnerName());
        existing.setPartnerAs2Id(updated.getPartnerAs2Id());
        existing.setOurAs2Id(updated.getOurAs2Id());
        existing.setEndpointUrl(updated.getEndpointUrl());
        existing.setPartnerCertificate(updated.getPartnerCertificate());
        existing.setSigningAlgorithm(updated.getSigningAlgorithm());
        existing.setEncryptionAlgorithm(updated.getEncryptionAlgorithm());
        existing.setMdnRequired(updated.isMdnRequired());
        existing.setMdnAsync(updated.isMdnAsync());
        existing.setMdnUrl(updated.getMdnUrl());
        existing.setCompressionEnabled(updated.isCompressionEnabled());
        existing.setProtocol(updated.getProtocol());
        existing.setActive(updated.isActive());

        log.info("Updated AS2 partnership: {} (id={})", existing.getPartnerName(), id);
        return repository.save(existing);
    }

    @PatchMapping("/{id}/toggle")
    public As2Partnership toggle(@PathVariable UUID id) {
        As2Partnership partnership = repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Partnership not found: " + id));
        partnership.setActive(!partnership.isActive());
        log.info("Toggled AS2 partnership: {} active={}", partnership.getPartnerName(), partnership.isActive());
        return repository.save(partnership);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        As2Partnership partnership = repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Partnership not found: " + id));
        partnership.setActive(false);
        repository.save(partnership);
        log.info("Deactivated AS2 partnership: {} (id={})", partnership.getPartnerName(), id);
    }

    private void validatePartnership(As2Partnership p) {
        if (p.getPartnerName() == null || p.getPartnerName().isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "partnerName is required");
        if (p.getPartnerAs2Id() == null || p.getPartnerAs2Id().isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "partnerAs2Id is required");
        if (p.getOurAs2Id() == null || p.getOurAs2Id().isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ourAs2Id is required");
        if (p.getEndpointUrl() == null || p.getEndpointUrl().isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "endpointUrl is required");

        String proto = p.getProtocol();
        if (proto == null || (!"AS2".equals(proto) && !"AS4".equals(proto)))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "protocol must be AS2 or AS4");

        // Reject deprecated/weak cryptographic algorithms
        String signing = p.getSigningAlgorithm();
        if (signing != null && SecurityProfile.DEPRECATED_AS2_ALGORITHMS.contains(signing.toUpperCase())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Signing algorithm '" + signing + "' is deprecated. Use: " + SecurityProfile.ALLOWED_AS2_SIGNING);
        }
        if (signing != null && !SecurityProfile.ALLOWED_AS2_SIGNING.contains(signing.toUpperCase())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Signing algorithm '" + signing + "' not allowed. Use: " + SecurityProfile.ALLOWED_AS2_SIGNING);
        }

        String encryption = p.getEncryptionAlgorithm();
        if (encryption != null && SecurityProfile.DEPRECATED_AS2_ALGORITHMS.contains(encryption.toUpperCase())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Encryption algorithm '" + encryption + "' is deprecated. Use: " + SecurityProfile.ALLOWED_AS2_ENCRYPTION);
        }
        if (encryption != null && !SecurityProfile.ALLOWED_AS2_ENCRYPTION.contains(encryption.toUpperCase())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Encryption algorithm '" + encryption + "' not allowed. Use: " + SecurityProfile.ALLOWED_AS2_ENCRYPTION);
        }
    }
}
