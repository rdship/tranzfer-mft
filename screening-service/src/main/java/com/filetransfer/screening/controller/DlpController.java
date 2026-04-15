package com.filetransfer.screening.controller;

import com.filetransfer.screening.service.DlpEngine;
import com.filetransfer.shared.entity.security.DlpPolicy;
import com.filetransfer.shared.repository.security.DlpPolicyRepository;
import com.filetransfer.shared.security.Roles;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.*;
import java.util.*;

/**
 * REST API for DLP policy management and manual scanning.
 */
@RestController
@RequestMapping("/api/v1/dlp")
@RequiredArgsConstructor
public class DlpController {

    private final DlpPolicyRepository policyRepository;
    private final DlpEngine dlpEngine;

    /** List all DLP policies */
    @GetMapping("/policies")
    @PreAuthorize(Roles.OPERATOR)
    public List<DlpPolicy> listPolicies() {
        return policyRepository.findAllByOrderByCreatedAtDesc();
    }

    /** Get a specific DLP policy */
    @GetMapping("/policies/{id}")
    @PreAuthorize(Roles.OPERATOR)
    public ResponseEntity<DlpPolicy> getPolicy(@PathVariable UUID id) {
        return policyRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /** Create a new DLP policy */
    @PostMapping("/policies")
    @PreAuthorize(Roles.ADMIN)
    public ResponseEntity<DlpPolicy> createPolicy(@Valid @RequestBody DlpPolicy policy) {
        if (policy.getName() == null || policy.getName().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        // Ensure no ID collision
        policy.setId(null);
        DlpPolicy saved = policyRepository.save(policy);
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    /** Update an existing DLP policy */
    @PutMapping("/policies/{id}")
    @PreAuthorize(Roles.ADMIN)
    public ResponseEntity<DlpPolicy> updatePolicy(@PathVariable UUID id,
                                                    @Valid @RequestBody DlpPolicy update) {
        DlpPolicy existing = policyRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("DLP policy not found: " + id));

        if (update.getName() != null) existing.setName(update.getName());
        if (update.getDescription() != null) existing.setDescription(update.getDescription());
        if (update.getPatterns() != null) existing.setPatterns(update.getPatterns());
        if (update.getAction() != null) existing.setAction(update.getAction());
        existing.setActive(update.isActive());

        return ResponseEntity.ok(policyRepository.save(existing));
    }

    /** Delete a DLP policy */
    @DeleteMapping("/policies/{id}")
    @PreAuthorize(Roles.ADMIN)
    public ResponseEntity<Void> deletePolicy(@PathVariable UUID id) {
        if (!policyRepository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        policyRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    /** Manual DLP scan of an uploaded file */
    @PostMapping("/scan")
    @PreAuthorize(Roles.OPERATOR)
    public ResponseEntity<DlpEngine.DlpScanResult> scanFile(
            @RequestPart("file") MultipartFile file,
            @RequestParam(required = false) String trackId) throws Exception {

        Path tempFile = Files.createTempFile("dlp_", "_" + file.getOriginalFilename());
        file.transferTo(tempFile.toFile());
        try {
            DlpEngine.DlpScanResult result = dlpEngine.scanFile(tempFile,
                    trackId != null ? trackId : "MANUAL");
            return ResponseEntity.ok(result);
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }
}
