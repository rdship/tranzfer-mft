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
        profile.setId(null);
        return ResponseEntity.status(HttpStatus.CREATED).body(securityProfileRepository.save(profile));
    }

    @PutMapping("/{id}")
    public SecurityProfile update(@PathVariable UUID id, @RequestBody SecurityProfile profile) {
        if (!securityProfileRepository.existsById(id)) {
            throw new EntityNotFoundException("Security profile not found: " + id);
        }
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
}
