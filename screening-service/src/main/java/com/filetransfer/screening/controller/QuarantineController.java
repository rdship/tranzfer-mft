package com.filetransfer.screening.controller;

import com.filetransfer.screening.service.AntivirusEngine;
import com.filetransfer.shared.client.ClamAvClient;
import com.filetransfer.shared.entity.QuarantineRecord;
import com.filetransfer.shared.repository.QuarantineRecordRepository;
import com.filetransfer.shared.security.Roles;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/**
 * REST API for managing quarantined files.
 * All operations require ADMIN role for security.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/quarantine")
@RequiredArgsConstructor
public class QuarantineController {

    private final QuarantineRecordRepository quarantineRepository;
    private final AntivirusEngine antivirusEngine;

    /** List all quarantined files, optionally filtered by status */
    @GetMapping
    @PreAuthorize(Roles.OPERATOR)
    public List<QuarantineRecord> listQuarantined(
            @RequestParam(required = false) String status) {
        if (status != null) {
            return quarantineRepository.findByStatusOrderByQuarantinedAtDesc(status);
        }
        return quarantineRepository.findAllByOrderByQuarantinedAtDesc();
    }

    /** Get a specific quarantine record */
    @GetMapping("/{id}")
    @PreAuthorize(Roles.OPERATOR)
    public ResponseEntity<QuarantineRecord> getRecord(@PathVariable UUID id) {
        return quarantineRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Release a quarantined file — re-scans with ClamAV first.
     * Only ADMIN can release files. File must pass AV scan to be released.
     */
    @PostMapping("/{id}/release")
    @PreAuthorize(Roles.ADMIN)
    public ResponseEntity<Map<String, Object>> releaseFile(
            @PathVariable UUID id,
            @AuthenticationPrincipal String adminUser,
            @RequestBody(required = false) Map<String, String> body) {

        QuarantineRecord record = quarantineRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Quarantine record not found: " + id));

        if (!"QUARANTINED".equals(record.getStatus())) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "File is not in QUARANTINED status (current: " + record.getStatus() + ")"));
        }

        Path quarantinedFile = Paths.get(record.getQuarantinePath());
        if (!Files.exists(quarantinedFile)) {
            return ResponseEntity.status(HttpStatus.GONE).body(Map.of(
                    "error", "Quarantined file no longer exists on disk"));
        }

        // Re-scan before releasing
        ClamAvClient.ScanResult rescanResult = antivirusEngine.rescan(quarantinedFile);
        if (!rescanResult.isClean()) {
            String virus = rescanResult.getVirusName() != null
                    ? rescanResult.getVirusName() : "unknown threat";
            log.warn("Release denied for {}: re-scan detected {}", id, virus);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "error", "Re-scan detected threat: " + virus,
                    "status", "STILL_QUARANTINED"));
        }

        // Move file back to original path
        try {
            Path originalPath = Paths.get(record.getOriginalPath());
            Files.createDirectories(originalPath.getParent());
            Files.move(quarantinedFile, originalPath, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.error("Failed to restore file from quarantine: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "error", "Failed to restore file: " + e.getMessage()));
        }

        record.setStatus("RELEASED");
        record.setReviewedBy(adminUser != null ? adminUser : "ADMIN");
        record.setReviewedAt(Instant.now());
        if (body != null && body.containsKey("notes")) {
            record.setReviewNotes(body.get("notes"));
        }
        quarantineRepository.save(record);

        log.info("Quarantined file released: {} by {}", record.getFilename(), adminUser);

        return ResponseEntity.ok(Map.of(
                "status", "RELEASED",
                "filename", record.getFilename(),
                "restoredTo", record.getOriginalPath(),
                "reviewedBy", record.getReviewedBy()));
    }

    /**
     * Permanently delete a quarantined file.
     * Only ADMIN can delete. This is irreversible.
     */
    @DeleteMapping("/{id}")
    @PreAuthorize(Roles.ADMIN)
    public ResponseEntity<Map<String, Object>> deleteFile(
            @PathVariable UUID id,
            @AuthenticationPrincipal String adminUser) {

        QuarantineRecord record = quarantineRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Quarantine record not found: " + id));

        // Delete the physical file
        try {
            Path quarantinedFile = Paths.get(record.getQuarantinePath());
            Files.deleteIfExists(quarantinedFile);
        } catch (IOException e) {
            log.warn("Failed to delete quarantined file from disk: {}", e.getMessage());
        }

        record.setStatus("DELETED");
        record.setReviewedBy(adminUser != null ? adminUser : "ADMIN");
        record.setReviewedAt(Instant.now());
        quarantineRepository.save(record);

        log.info("Quarantined file permanently deleted: {} by {}", record.getFilename(), adminUser);

        return ResponseEntity.ok(Map.of(
                "status", "DELETED",
                "filename", record.getFilename(),
                "deletedBy", record.getReviewedBy()));
    }

    /** Quarantine statistics */
    @GetMapping("/stats")
    @PreAuthorize(Roles.OPERATOR)
    public Map<String, Object> stats() {
        return Map.of(
                "quarantined", quarantineRepository.countByStatus("QUARANTINED"),
                "released", quarantineRepository.countByStatus("RELEASED"),
                "deleted", quarantineRepository.countByStatus("DELETED"),
                "total", quarantineRepository.count()
        );
    }
}
