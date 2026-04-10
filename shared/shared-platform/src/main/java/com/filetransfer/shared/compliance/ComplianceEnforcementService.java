package com.filetransfer.shared.compliance;

import com.filetransfer.shared.entity.ComplianceProfile;
import com.filetransfer.shared.entity.ComplianceViolation;
import com.filetransfer.shared.repository.ComplianceProfileRepository;
import com.filetransfer.shared.repository.ComplianceViolationRepository;
import com.filetransfer.shared.routing.AiClassificationClient;
import jakarta.annotation.Nullable;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.*;

/**
 * Core compliance enforcement engine.
 * Evaluates file transfers against the compliance profile assigned to a server.
 * Called by RoutingEngine or any listener before allowing a file through.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ComplianceEnforcementService {

    private final ComplianceProfileRepository profileRepo;
    private final ComplianceViolationRepository violationRepo;

    @Autowired(required = false)
    @Nullable
    private AiClassificationClient aiClient;

    /**
     * Evaluate a file transfer against the compliance profile assigned to the server.
     * Returns a ComplianceResult with pass/fail and any violations.
     */
    public ComplianceResult evaluate(ComplianceContext ctx) {
        if (ctx.profileId() == null) {
            return ComplianceResult.pass(); // No compliance profile assigned
        }

        ComplianceProfile profile = profileRepo.findById(ctx.profileId()).orElse(null);
        if (profile == null || !profile.isActive()) {
            return ComplianceResult.pass();
        }

        List<ComplianceViolation> violations = new ArrayList<>();

        // 1. File extension check — blocked extensions
        if (profile.getBlockedFileExtensions() != null && !profile.getBlockedFileExtensions().isBlank()) {
            String ext = getExtension(ctx.filename());
            List<String> blocked = Arrays.asList(profile.getBlockedFileExtensions().split(","));
            if (blocked.stream().map(String::trim).anyMatch(b -> b.equalsIgnoreCase(ext))) {
                violations.add(buildViolation(ctx, profile, "BLOCKED_EXTENSION", "CRITICAL",
                    "File extension ." + ext + " is blocked by compliance profile '" + profile.getName() + "'"));
            }
        }

        // 1b. File extension check — allowed extensions whitelist
        if (profile.getAllowedFileExtensions() != null && !profile.getAllowedFileExtensions().isBlank()) {
            String ext = getExtension(ctx.filename());
            List<String> allowed = Arrays.asList(profile.getAllowedFileExtensions().split(","));
            if (allowed.stream().map(String::trim).noneMatch(a -> a.equalsIgnoreCase(ext))) {
                violations.add(buildViolation(ctx, profile, "BLOCKED_EXTENSION", "HIGH",
                    "File extension ." + ext + " is not in the allowed list for '" + profile.getName() + "'"));
            }
        }

        // 2. File size check
        if (profile.getMaxFileSizeBytes() != null && ctx.fileSizeBytes() > profile.getMaxFileSizeBytes()) {
            violations.add(buildViolation(ctx, profile, "FILE_TOO_LARGE", "MEDIUM",
                "File size " + ctx.fileSizeBytes() + " exceeds limit " + profile.getMaxFileSizeBytes()));
        }

        // 3. Encryption requirement
        if (profile.isRequireEncryption() && !ctx.isEncrypted()) {
            violations.add(buildViolation(ctx, profile, "ENCRYPTION_REQUIRED", "HIGH",
                "File must be encrypted (PGP or AES) per compliance profile '" + profile.getName() + "'"));
        }

        // 4. TLS requirement
        if (profile.isRequireTls() && !ctx.isTlsConnection()) {
            violations.add(buildViolation(ctx, profile, "TLS_REQUIRED", "CRITICAL",
                "Connection must use TLS/SFTP. Plain FTP is not allowed per '" + profile.getName() + "'"));
        }

        // 5. Checksum requirement
        if (profile.isRequireChecksum() && !ctx.hasChecksum()) {
            violations.add(buildViolation(ctx, profile, "CHECKSUM_REQUIRED", "HIGH",
                "File must have SHA-256 checksum verification per '" + profile.getName() + "'"));
        }

        // 6. AI classification check
        if (aiClient != null && ctx.filePath() != null) {
            try {
                var decision = aiClient.classify(ctx.filePath(), ctx.trackId(), ctx.isEncrypted());

                // Risk level check
                if (isRiskAboveThreshold(decision.riskLevel(), profile.getMaxAllowedRiskLevel())) {
                    violations.add(buildViolation(ctx, profile, "RISK_THRESHOLD_EXCEEDED", "CRITICAL",
                        "AI risk level " + decision.riskLevel() + " exceeds maximum " + profile.getMaxAllowedRiskLevel()
                        + ". Reason: " + decision.blockReason())
                        .toBuilder().aiRiskLevel(decision.riskLevel()).aiRiskScore(decision.riskScore())
                        .aiBlockReason(decision.blockReason()).build());
                }

                // Risk score check
                if (decision.riskScore() > profile.getMaxAllowedRiskScore()) {
                    violations.add(buildViolation(ctx, profile, "RISK_THRESHOLD_EXCEEDED", "HIGH",
                        "AI risk score " + decision.riskScore() + " exceeds threshold " + profile.getMaxAllowedRiskScore())
                        .toBuilder().aiRiskLevel(decision.riskLevel()).aiRiskScore(decision.riskScore())
                        .aiBlockReason(decision.blockReason()).build());
                }

                // Data type checks based on block reason
                if (decision.blockReason() != null) {
                    String reason = decision.blockReason().toUpperCase();
                    if (!profile.isAllowPciData() && reason.contains("PCI")) {
                        violations.add(buildViolation(ctx, profile, "PCI_DATA_DETECTED", "CRITICAL",
                            "PCI data detected but not allowed by profile '" + profile.getName() + "': " + decision.blockReason()));
                    }
                    if (!profile.isAllowPhiData() && (reason.contains("PHI") || reason.contains("HEALTH") || reason.contains("HIPAA"))) {
                        violations.add(buildViolation(ctx, profile, "PHI_DATA_DETECTED", "CRITICAL",
                            "Protected Health Information detected: " + decision.blockReason()));
                    }
                    if (!profile.isAllowPiiData() && reason.contains("PII")) {
                        violations.add(buildViolation(ctx, profile, "PII_DATA_DETECTED", "HIGH",
                            "Personally Identifiable Information detected: " + decision.blockReason()));
                    }
                }
            } catch (Exception e) {
                log.warn("[Compliance] AI classification failed for {} — skipping AI checks: {}",
                    ctx.filename(), e.getMessage());
            }
        }

        // Save violations and return result
        if (!violations.isEmpty()) {
            violationRepo.saveAll(violations);
            String action = profile.getViolationAction();
            boolean blocked = "BLOCK".equalsIgnoreCase(action);
            log.warn("[Compliance] {} violation(s) for {} on server {} — action: {}",
                violations.size(), ctx.filename(), ctx.serverName(), action);
            return new ComplianceResult(blocked, violations);
        }

        return ComplianceResult.pass();
    }

    // ── Helper methods ─────────────────────────────────────────────────────

    private ComplianceViolation buildViolation(ComplianceContext ctx, ComplianceProfile profile,
                                               String type, String severity, String details) {
        return ComplianceViolation.builder()
            .trackId(ctx.trackId())
            .profileId(profile.getId())
            .profileName(profile.getName())
            .serverInstanceId(ctx.serverInstanceId())
            .serverName(ctx.serverName())
            .username(ctx.username())
            .filename(ctx.filename())
            .fileSizeBytes(ctx.fileSizeBytes())
            .violationType(type)
            .severity(severity)
            .details(details)
            .action(profile.getViolationAction())
            .build();
    }

    private boolean isRiskAboveThreshold(String actual, String max) {
        Map<String, Integer> levels = Map.of(
            "NONE", 0, "LOW", 1, "MEDIUM", 2, "HIGH", 3, "CRITICAL", 4
        );
        return levels.getOrDefault(actual, 0) > levels.getOrDefault(max, 2);
    }

    private String getExtension(String filename) {
        if (filename == null) return "";
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(dot + 1).toLowerCase() : "";
    }

    // ── Context record ─────────────────────────────────────────────────────

    /**
     * Context for compliance evaluation — all information about the transfer being checked.
     */
    public record ComplianceContext(
        String trackId,
        UUID profileId,
        UUID serverInstanceId,
        String serverName,
        String username,
        String filename,
        long fileSizeBytes,
        boolean isEncrypted,
        boolean isTlsConnection,
        boolean hasChecksum,
        Path filePath
    ) {}

    // ── Result record ──────────────────────────────────────────────────────

    /**
     * Result of compliance evaluation — indicates if transfer is blocked and lists violations.
     */
    public record ComplianceResult(boolean blocked, List<ComplianceViolation> violations) {
        public static ComplianceResult pass() {
            return new ComplianceResult(false, List.of());
        }
        public boolean passed() {
            return !blocked && violations.isEmpty();
        }
    }
}
