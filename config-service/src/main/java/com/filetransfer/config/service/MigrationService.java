package com.filetransfer.config.service;

import com.filetransfer.shared.entity.*;
import com.filetransfer.shared.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class MigrationService {

    private final PartnerRepository partnerRepo;
    private final MigrationEventRepository eventRepo;
    private final ConnectionAuditRepository auditRepo;
    private final LegacyServerConfigRepository legacyRepo;

    // ── Dashboard ────────────────────────────────────────────────────────────

    /** Aggregate migration stats for the dashboard */
    public Map<String, Object> getDashboard() {
        List<Partner> all = partnerRepo.findAll();
        Map<String, Long> byStatus = all.stream()
            .collect(Collectors.groupingBy(
                p -> p.getMigrationStatus() != null ? p.getMigrationStatus() : "NOT_STARTED",
                Collectors.counting()));

        Instant last24h = Instant.now().minus(24, ChronoUnit.HOURS);
        List<Object[]> routeCounts = auditRepo.countByRoutedToSince(last24h);
        Map<String, Long> connections24h = new HashMap<>();
        for (Object[] row : routeCounts) {
            connections24h.put((String) row[0], (Long) row[1]);
        }

        long shadowCount = all.stream().filter(Partner::isShadowModeEnabled).count();
        List<LegacyServerConfig> legacyServers = legacyRepo.findAll();

        Map<String, Object> dashboard = new LinkedHashMap<>();
        dashboard.put("totalPartners", all.size());
        dashboard.put("byStatus", byStatus);
        dashboard.put("shadowModeCount", shadowCount);
        dashboard.put("legacyServerCount", legacyServers.size());
        dashboard.put("connections24h", connections24h);
        dashboard.put("platformConnections24h", connections24h.getOrDefault("PLATFORM", 0L));
        dashboard.put("legacyConnections24h", connections24h.getOrDefault("LEGACY", 0L));
        dashboard.put("recentEvents", eventRepo.findByCreatedAtAfterOrderByCreatedAtDesc(last24h));
        return dashboard;
    }

    /** Per-partner connection breakdown (last 7 days) */
    public List<Map<String, Object>> getPartnerConnectionStats() {
        Instant last7d = Instant.now().minus(7, ChronoUnit.DAYS);
        List<Object[]> rows = auditRepo.countByPartnerAndRouteSince(last7d);
        Map<UUID, Map<String, Object>> byPartner = new LinkedHashMap<>();
        for (Object[] row : rows) {
            UUID pid = (UUID) row[0];
            String pname = (String) row[1];
            String route = (String) row[2];
            long count = (Long) row[3];
            byPartner.computeIfAbsent(pid, k -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("partnerId", pid);
                m.put("partnerName", pname);
                m.put("platformConnections", 0L);
                m.put("legacyConnections", 0L);
                return m;
            }).put("PLATFORM".equals(route) ? "platformConnections" : "legacyConnections", count);
        }
        return new ArrayList<>(byPartner.values());
    }

    // ── Lifecycle Actions ────────────────────────────────────────────────────

    @Transactional
    public Partner startMigration(UUID partnerId, String source, String notes) {
        Partner p = findPartner(partnerId);
        p.setMigrationStatus("IN_PROGRESS");
        p.setMigrationSource(source);
        p.setMigrationNotes(notes);
        p.setMigrationStartedAt(Instant.now());
        partnerRepo.save(p);
        recordEvent(p, "MIGRATION_STARTED", "Migration from " + source + " initiated");
        log.info("Migration started for partner {} from {}", p.getCompanyName(), source);
        return p;
    }

    @Transactional
    public Partner enableShadowMode(UUID partnerId, String legacyHost, Integer legacyPort, String legacyUsername) {
        Partner p = findPartner(partnerId);
        p.setMigrationStatus("SHADOW_MODE");
        p.setShadowModeEnabled(true);
        p.setLegacyHost(legacyHost);
        p.setLegacyPort(legacyPort);
        p.setLegacyUsername(legacyUsername);
        partnerRepo.save(p);
        recordEvent(p, "SHADOW_ENABLED", "Shadow mode: forwarding to " + legacyHost + ":" + legacyPort);
        log.info("Shadow mode enabled for partner {} → {}:{}", p.getCompanyName(), legacyHost, legacyPort);
        return p;
    }

    @Transactional
    public Partner disableShadowMode(UUID partnerId) {
        Partner p = findPartner(partnerId);
        p.setShadowModeEnabled(false);
        partnerRepo.save(p);
        recordEvent(p, "SHADOW_DISABLED", "Shadow mode disabled");
        return p;
    }

    @Transactional
    public Partner startVerification(UUID partnerId) {
        Partner p = findPartner(partnerId);
        p.setMigrationStatus("VERIFIED");
        p.setVerificationTransferCount(0);
        p.setVerificationLastAt(Instant.now());
        partnerRepo.save(p);
        recordEvent(p, "VERIFICATION_STARTED", "Verification phase started — monitoring transfers");
        return p;
    }

    @Transactional
    public Partner recordVerification(UUID partnerId, int transferCount, boolean passed, String details) {
        Partner p = findPartner(partnerId);
        p.setVerificationTransferCount(p.getVerificationTransferCount() + transferCount);
        p.setVerificationLastAt(Instant.now());
        partnerRepo.save(p);
        recordEvent(p, passed ? "VERIFICATION_PASSED" : "VERIFICATION_FAILED",
            transferCount + " transfers verified — " + details);
        return p;
    }

    @Transactional
    public Partner completeMigration(UUID partnerId) {
        Partner p = findPartner(partnerId);
        p.setMigrationStatus("COMPLETED");
        p.setMigrationCompletedAt(Instant.now());
        p.setShadowModeEnabled(false);
        partnerRepo.save(p);
        recordEvent(p, "CUTOVER_COMPLETED", "Migration completed — partner fully on TranzFer");
        log.info("Migration COMPLETED for partner {}", p.getCompanyName());
        return p;
    }

    @Transactional
    public Partner rollback(UUID partnerId, String reason) {
        Partner p = findPartner(partnerId);
        String prevStatus = p.getMigrationStatus();
        p.setMigrationStatus("IN_PROGRESS");
        p.setShadowModeEnabled(false);
        p.setMigrationCompletedAt(null);
        partnerRepo.save(p);
        recordEvent(p, "ROLLBACK", "Rolled back from " + prevStatus + ": " + reason);
        log.warn("Migration ROLLBACK for partner {}: {}", p.getCompanyName(), reason);
        return p;
    }

    // ── Connection Audit ─────────────────────────────────────────────────────

    /** Record an inbound connection (called by Gateway routing) */
    @Transactional
    public void recordConnection(String username, String sourceIp, String protocol,
                                  String routedTo, String legacyHost,
                                  UUID partnerId, String partnerName,
                                  boolean success, String failureReason) {
        ConnectionAudit audit = ConnectionAudit.builder()
            .username(username)
            .sourceIp(sourceIp)
            .protocol(protocol)
            .routedTo(routedTo)
            .legacyHost(legacyHost)
            .partnerId(partnerId)
            .partnerName(partnerName)
            .success(success)
            .failureReason(failureReason)
            .build();
        auditRepo.save(audit);

        // Update partner last connection timestamps
        if (partnerId != null) {
            partnerRepo.findById(partnerId).ifPresent(p -> {
                if ("PLATFORM".equals(routedTo)) {
                    p.setLastPlatformConnectionAt(Instant.now());
                } else {
                    p.setLastLegacyConnectionAt(Instant.now());
                }
                partnerRepo.save(p);
            });
        }
    }

    public List<ConnectionAudit> getConnectionHistory(UUID partnerId, int limit) {
        List<ConnectionAudit> all = auditRepo.findByPartnerIdOrderByConnectedAtDesc(partnerId);
        return all.size() > limit ? all.subList(0, limit) : all;
    }

    public List<MigrationEvent> getPartnerEvents(UUID partnerId) {
        return eventRepo.findByPartnerIdOrderByCreatedAtDesc(partnerId);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private Partner findPartner(UUID id) {
        return partnerRepo.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Partner not found: " + id));
    }

    private void recordEvent(Partner p, String type, String details) {
        eventRepo.save(MigrationEvent.builder()
            .partnerId(p.getId())
            .partnerName(p.getCompanyName())
            .eventType(type)
            .details(details)
            .actor(currentActor())
            .build());
    }

    private String currentActor() {
        try {
            var auth = org.springframework.security.core.context.SecurityContextHolder
                    .getContext().getAuthentication();
            return auth != null && auth.getName() != null ? auth.getName() : "system";
        } catch (Exception e) {
            return "system";
        }
    }
}
