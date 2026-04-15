package com.filetransfer.config.controller;

import com.filetransfer.shared.entity.security.ComplianceProfile;
import com.filetransfer.shared.entity.security.ComplianceViolation;
import com.filetransfer.shared.entity.core.ServerInstance;
import com.filetransfer.shared.repository.security.ComplianceProfileRepository;
import com.filetransfer.shared.repository.security.ComplianceViolationRepository;
import com.filetransfer.shared.repository.core.ServerInstanceRepository;
import com.filetransfer.shared.security.Roles;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.*;

/**
 * Compliance Management API — profiles, violations, and server assignment.
 *
 * Profiles:
 *   GET    /api/compliance/profiles                    list all active profiles
 *   GET    /api/compliance/profiles/{id}               get one
 *   POST   /api/compliance/profiles                    create
 *   PUT    /api/compliance/profiles/{id}               update
 *   DELETE /api/compliance/profiles/{id}               deactivate (soft-delete)
 *
 * Violations:
 *   GET    /api/compliance/violations                  list violations (filterable)
 *   GET    /api/compliance/violations/count            unresolved count (badge)
 *   POST   /api/compliance/violations/{id}/resolve     resolve a violation
 *   GET    /api/compliance/violations/server/{serverId} violations per server
 *   GET    /api/compliance/violations/user/{username}   violations per user
 *
 * Server Assignment:
 *   PUT    /api/compliance/servers/{serverId}/profile   assign profile to server
 */
@Slf4j
@RestController
@RequestMapping("/api/compliance")
@RequiredArgsConstructor
public class ComplianceController {

    private final ComplianceProfileRepository profileRepo;
    private final ComplianceViolationRepository violationRepo;
    private final ServerInstanceRepository serverRepo;

    // ── Profiles ───────────────────────────────────────────────────────────

    @GetMapping("/profiles")
    @PreAuthorize(Roles.VIEWER)
    public List<ComplianceProfile> listProfiles() {
        return profileRepo.findByActiveTrue();
    }

    @GetMapping("/profiles/all")
    @PreAuthorize(Roles.VIEWER)
    public List<ComplianceProfile> listAllProfiles() {
        return profileRepo.findAll();
    }

    @GetMapping("/profiles/{id}")
    @PreAuthorize(Roles.VIEWER)
    public ComplianceProfile getProfile(@PathVariable UUID id) {
        return profileRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Profile not found"));
    }

    @PostMapping("/profiles")
    @PreAuthorize(Roles.OPERATOR)
    @ResponseStatus(HttpStatus.CREATED)
    public ComplianceProfile createProfile(@RequestBody ComplianceProfile profile) {
        if (profile.getName() == null || profile.getName().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Profile name is required");
        }
        if (profileRepo.findByName(profile.getName()).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Profile name already exists");
        }
        profile.setId(null);
        profile.setActive(true);
        ComplianceProfile saved = profileRepo.save(profile);
        log.info("[Compliance] Created profile '{}' severity={} action={}",
                saved.getName(), saved.getSeverity(), saved.getViolationAction());
        return saved;
    }

    @PutMapping("/profiles/{id}")
    @PreAuthorize(Roles.OPERATOR)
    public ComplianceProfile updateProfile(@PathVariable UUID id, @RequestBody ComplianceProfile updates) {
        ComplianceProfile existing = profileRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Profile not found"));

        if (updates.getName() != null) existing.setName(updates.getName());
        if (updates.getDescription() != null) existing.setDescription(updates.getDescription());
        if (updates.getSeverity() != null) existing.setSeverity(updates.getSeverity());

        // Data classification
        existing.setAllowPciData(updates.isAllowPciData());
        existing.setAllowPhiData(updates.isAllowPhiData());
        existing.setAllowPiiData(updates.isAllowPiiData());
        existing.setAllowClassifiedData(updates.isAllowClassifiedData());

        // AI risk thresholds
        if (updates.getMaxAllowedRiskLevel() != null) existing.setMaxAllowedRiskLevel(updates.getMaxAllowedRiskLevel());
        existing.setMaxAllowedRiskScore(updates.getMaxAllowedRiskScore());

        // File rules
        existing.setRequireEncryption(updates.isRequireEncryption());
        existing.setRequireScreening(updates.isRequireScreening());
        existing.setRequireChecksum(updates.isRequireChecksum());
        if (updates.getAllowedFileExtensions() != null) existing.setAllowedFileExtensions(updates.getAllowedFileExtensions());
        if (updates.getBlockedFileExtensions() != null) existing.setBlockedFileExtensions(updates.getBlockedFileExtensions());
        existing.setMaxFileSizeBytes(updates.getMaxFileSizeBytes());

        // Transfer rules
        existing.setRequireTls(updates.isRequireTls());
        existing.setAllowAnonymousAccess(updates.isAllowAnonymousAccess());
        existing.setRequireMfa(updates.isRequireMfa());

        // Enforcement
        existing.setAuditAllTransfers(updates.isAuditAllTransfers());
        existing.setNotifyOnViolation(updates.isNotifyOnViolation());
        if (updates.getViolationAction() != null) existing.setViolationAction(updates.getViolationAction());

        ComplianceProfile saved = profileRepo.save(existing);
        log.info("[Compliance] Updated profile '{}' severity={} action={}",
                saved.getName(), saved.getSeverity(), saved.getViolationAction());
        return saved;
    }

    @DeleteMapping("/profiles/{id}")
    @PreAuthorize(Roles.OPERATOR)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteProfile(@PathVariable UUID id) {
        ComplianceProfile existing = profileRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Profile not found"));
        existing.setActive(false);
        profileRepo.save(existing);
        log.info("[Compliance] Deactivated profile '{}'", existing.getName());
    }

    // ── Violations ─────────────────────────────────────────────────────────

    @GetMapping("/violations")
    @PreAuthorize(Roles.VIEWER)
    public List<ComplianceViolation> listViolations(
            @RequestParam(required = false) String severity,
            @RequestParam(required = false) Boolean resolved,
            @RequestParam(required = false) UUID serverId) {
        if (severity != null && resolved != null && !resolved) {
            return violationRepo.findBySeverityAndResolvedFalseOrderByCreatedAtDesc(severity);
        }
        if (serverId != null) {
            return violationRepo.findByServerInstanceIdOrderByCreatedAtDesc(serverId);
        }
        if (resolved != null && !resolved) {
            return violationRepo.findByResolvedFalseOrderByCreatedAtDesc();
        }
        // Default: all violations, most recent first
        return violationRepo.findAll(org.springframework.data.domain.Sort.by(
                org.springframework.data.domain.Sort.Direction.DESC, "createdAt"));
    }

    @GetMapping("/violations/count")
    @PreAuthorize(Roles.VIEWER)
    public Map<String, Long> violationCount() {
        return Map.of("unresolved", violationRepo.countByResolvedFalse());
    }

    @PostMapping("/violations/{id}/resolve")
    @PreAuthorize(Roles.OPERATOR)
    public ComplianceViolation resolveViolation(@PathVariable UUID id,
                                                 @RequestBody(required = false) Map<String, String> body) {
        ComplianceViolation violation = violationRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Violation not found"));
        violation.setResolved(true);
        violation.setResolvedAt(Instant.now());
        if (body != null) {
            violation.setResolvedBy(body.get("resolvedBy"));
            violation.setResolutionNote(body.get("note"));
        }
        ComplianceViolation saved = violationRepo.save(violation);
        log.info("[Compliance] Resolved violation {} type={} profile={}",
                saved.getId(), saved.getViolationType(), saved.getProfileName());
        return saved;
    }

    @GetMapping("/violations/server/{serverId}")
    @PreAuthorize(Roles.VIEWER)
    public List<ComplianceViolation> violationsByServer(@PathVariable UUID serverId) {
        return violationRepo.findByServerInstanceIdOrderByCreatedAtDesc(serverId);
    }

    @GetMapping("/violations/user/{username}")
    @PreAuthorize(Roles.VIEWER)
    public List<ComplianceViolation> violationsByUser(@PathVariable String username) {
        return violationRepo.findByUsernameOrderByCreatedAtDesc(username);
    }

    // ── Server Assignment ──────────────────────────────────────────────────

    @PutMapping("/servers/{serverId}/profile")
    @PreAuthorize(Roles.OPERATOR)
    public ResponseEntity<ServerInstance> assignProfile(@PathVariable UUID serverId,
                                                         @RequestBody Map<String, String> body) {
        ServerInstance server = serverRepo.findById(serverId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Server not found"));

        String profileIdStr = body.get("profileId");
        if (profileIdStr == null || profileIdStr.isBlank()) {
            // Remove assignment
            server.setComplianceProfileId(null);
        } else {
            UUID profileId = UUID.fromString(profileIdStr);
            profileRepo.findById(profileId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Profile not found"));
            server.setComplianceProfileId(profileId);
        }
        ServerInstance saved = serverRepo.save(server);
        log.info("[Compliance] Server '{}' assigned profile={}", saved.getName(), saved.getComplianceProfileId());
        return ResponseEntity.ok(saved);
    }
}
