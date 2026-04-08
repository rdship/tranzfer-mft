package com.filetransfer.config.controller;

import com.filetransfer.shared.entity.SecurityProfile;
import com.filetransfer.shared.repository.SecurityProfileRepository;
import com.filetransfer.shared.security.Roles;
import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/security-profiles")
@RequiredArgsConstructor
@PreAuthorize(Roles.OPERATOR)
public class SecurityProfileController {

    private final SecurityProfileRepository securityProfileRepository;

    @GetMapping
    public List<SecurityProfile> getAll() {
        return securityProfileRepository.findByActiveTrue();
    }

    @GetMapping("/{id}")
    public SecurityProfile getById(@PathVariable UUID id) {
        return securityProfileRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Security profile not found: " + id));
    }

    @PostMapping
    public ResponseEntity<SecurityProfile> create(@Valid @RequestBody SecurityProfile profile) {
        if (securityProfileRepository.existsByName(profile.getName())) {
            throw new IllegalArgumentException("Security profile name already exists: " + profile.getName());
        }
        validateAlgorithms(profile);
        profile.setId(null);
        return ResponseEntity.status(HttpStatus.CREATED).body(securityProfileRepository.save(profile));
    }

    @PutMapping("/{id}")
    public SecurityProfile update(@PathVariable UUID id, @Valid @RequestBody SecurityProfile profile) {
        if (!securityProfileRepository.existsById(id)) {
            throw new EntityNotFoundException("Security profile not found: " + id);
        }
        validateAlgorithms(profile);
        profile.setId(id);
        return securityProfileRepository.save(profile);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        SecurityProfile p = securityProfileRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Not found: " + id));
        p.setActive(false);
        securityProfileRepository.save(p);
        return ResponseEntity.noContent().build();
    }

    /**
     * Validates that all algorithm names in the profile are in the allowed whitelist.
     * Rejects any weak/deprecated algorithms to prevent misconfiguration.
     */
    private void validateAlgorithms(SecurityProfile profile) {
        List<String> violations = new ArrayList<>();

        if ("SSH".equalsIgnoreCase(profile.getType())) {
            validateList(profile.getSshCiphers(), SecurityProfile.ALLOWED_SSH_CIPHERS, "sshCiphers", violations);
            validateList(profile.getSshMacs(), SecurityProfile.ALLOWED_SSH_MACS, "sshMacs", violations);
            validateList(profile.getKexAlgorithms(), SecurityProfile.ALLOWED_SSH_KEX, "kexAlgorithms", violations);
            validateList(profile.getHostKeyAlgorithms(), SecurityProfile.ALLOWED_HOST_KEY_ALGORITHMS, "hostKeyAlgorithms", violations);
        } else if ("TLS".equalsIgnoreCase(profile.getType())) {
            if (profile.getTlsMinVersion() != null
                    && !SecurityProfile.ALLOWED_TLS_VERSIONS.contains(profile.getTlsMinVersion())) {
                violations.add("tlsMinVersion: '" + profile.getTlsMinVersion()
                        + "' not allowed. Use: " + SecurityProfile.ALLOWED_TLS_VERSIONS);
            }
            validateList(profile.getTlsCiphers(), SecurityProfile.ALLOWED_TLS_CIPHERS, "tlsCiphers", violations);
        }

        // Check for blocked cipher keywords in all cipher lists
        checkBlocked(profile.getSshCiphers(), "sshCiphers", violations);
        checkBlocked(profile.getTlsCiphers(), "tlsCiphers", violations);

        if (!violations.isEmpty()) {
            throw new IllegalArgumentException("Security profile contains disallowed algorithms: " + violations);
        }
    }

    private void validateList(List<String> values, java.util.Set<String> allowed, String fieldName, List<String> violations) {
        if (values == null || values.isEmpty()) return;
        for (String v : values) {
            if (!allowed.contains(v)) {
                violations.add(fieldName + ": '" + v + "' not in allowed list");
            }
        }
    }

    private void checkBlocked(List<String> values, String fieldName, List<String> violations) {
        if (values == null) return;
        for (String v : values) {
            if (SecurityProfile.isBlockedCipher(v)) {
                violations.add(fieldName + ": '" + v + "' contains blocked keyword");
            }
        }
    }
}
