package com.filetransfer.config.controller;

import com.filetransfer.config.service.MigrationService;
import com.filetransfer.shared.entity.security.ConnectionAudit;
import com.filetransfer.shared.entity.core.MigrationEvent;
import com.filetransfer.shared.entity.core.Partner;
import com.filetransfer.shared.repository.PartnerRepository;
import com.filetransfer.shared.security.Roles;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Migration API — manage partner migration from legacy MFT products.
 *
 * GET    /api/v1/migration/dashboard          — aggregate stats
 * GET    /api/v1/migration/partners           — all partners with migration status
 * GET    /api/v1/migration/partners/{id}      — partner migration detail
 * POST   /api/v1/migration/partners/{id}/start          — start migration
 * POST   /api/v1/migration/partners/{id}/shadow         — enable shadow mode
 * DELETE /api/v1/migration/partners/{id}/shadow         — disable shadow mode
 * POST   /api/v1/migration/partners/{id}/verify         — start verification
 * POST   /api/v1/migration/partners/{id}/verify/record  — record verification result
 * POST   /api/v1/migration/partners/{id}/complete       — complete migration
 * POST   /api/v1/migration/partners/{id}/rollback       — rollback
 * GET    /api/v1/migration/partners/{id}/events         — migration event history
 * GET    /api/v1/migration/partners/{id}/connections    — connection audit
 * GET    /api/v1/migration/connection-stats             — per-partner connection breakdown
 */
@RestController
@RequestMapping("/api/v1/migration")
@RequiredArgsConstructor
@PreAuthorize(Roles.ADMIN)
public class MigrationController {

    private final MigrationService migrationService;
    private final PartnerRepository partnerRepo;

    @GetMapping("/dashboard")
    public Map<String, Object> dashboard() {
        return migrationService.getDashboard();
    }

    @GetMapping("/partners")
    public List<Partner> partners(@RequestParam(required = false) String status) {
        if (status != null) return partnerRepo.findByStatus(status);
        return partnerRepo.findAll();
    }

    @GetMapping("/partners/{id}")
    public Map<String, Object> partnerDetail(@PathVariable UUID id) {
        Partner p = partnerRepo.findById(id).orElseThrow();
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("partner", p);
        detail.put("events", migrationService.getPartnerEvents(id));
        detail.put("recentConnections", migrationService.getConnectionHistory(id, 50));
        return detail;
    }

    @PostMapping("/partners/{id}/start")
    public Partner start(@PathVariable UUID id, @RequestBody Map<String, String> body) {
        return migrationService.startMigration(id,
            body.getOrDefault("source", "unknown"),
            body.get("notes"));
    }

    @PostMapping("/partners/{id}/shadow")
    public Partner enableShadow(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
        return migrationService.enableShadowMode(id,
            (String) body.get("legacyHost"),
            body.get("legacyPort") != null ? ((Number) body.get("legacyPort")).intValue() : null,
            (String) body.get("legacyUsername"));
    }

    @DeleteMapping("/partners/{id}/shadow")
    public Partner disableShadow(@PathVariable UUID id) {
        return migrationService.disableShadowMode(id);
    }

    @PostMapping("/partners/{id}/verify")
    public Partner startVerify(@PathVariable UUID id) {
        return migrationService.startVerification(id);
    }

    @PostMapping("/partners/{id}/verify/record")
    public Partner recordVerify(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
        return migrationService.recordVerification(id,
            ((Number) body.getOrDefault("transferCount", 0)).intValue(),
            Boolean.TRUE.equals(body.get("passed")),
            (String) body.getOrDefault("details", ""));
    }

    @PostMapping("/partners/{id}/complete")
    public Partner complete(@PathVariable UUID id) {
        return migrationService.completeMigration(id);
    }

    @PostMapping("/partners/{id}/rollback")
    public Partner rollback(@PathVariable UUID id, @RequestBody Map<String, String> body) {
        return migrationService.rollback(id, body.getOrDefault("reason", "Manual rollback"));
    }

    @GetMapping("/partners/{id}/events")
    public List<MigrationEvent> events(@PathVariable UUID id) {
        return migrationService.getPartnerEvents(id);
    }

    @GetMapping("/partners/{id}/connections")
    public List<ConnectionAudit> connections(@PathVariable UUID id,
                                              @RequestParam(defaultValue = "100") int limit) {
        return migrationService.getConnectionHistory(id, limit);
    }

    @GetMapping("/connection-stats")
    public List<Map<String, Object>> connectionStats() {
        return migrationService.getPartnerConnectionStats();
    }

    /**
     * Internal endpoint called by gateway-service to record inbound connections
     * for migration tracking. Accessible via SPIFFE JWT-SVID (ROLE_INTERNAL).
     */
    @PostMapping("/audit-connection")
    @PreAuthorize(Roles.INTERNAL)
    public void auditConnection(@RequestBody Map<String, String> body) {
        migrationService.recordConnection(
            body.getOrDefault("username", ""),
            body.getOrDefault("sourceIp", ""),
            body.getOrDefault("protocol", ""),
            body.getOrDefault("routedTo", "PLATFORM"),
            body.get("legacyHost"),
            body.get("partnerId") != null && !body.get("partnerId").isEmpty()
                ? UUID.fromString(body.get("partnerId")) : null,
            body.get("partnerName"),
            !"false".equals(body.get("success")),
            body.get("failureReason")
        );
    }
}
