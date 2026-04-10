package com.filetransfer.shared.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Compliance profile defining data classification, AI risk thresholds,
 * file rules, transfer rules, and enforcement actions.
 * Assigned to ServerInstance via complianceProfileId.
 */
@Entity
@Table(name = "compliance_profiles")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ComplianceProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(unique = true, nullable = false)
    private String name;                    // "PCI-DSS Strict", "HIPAA Healthcare", "Internal Only"

    private String description;

    @Column(nullable = false, length = 20)
    @Builder.Default
    private String severity = "HIGH";       // LOW, MEDIUM, HIGH, CRITICAL

    // ── Data Classification Rules ──────────────────────────────────────────

    @Builder.Default
    private boolean allowPciData = false;           // Credit card numbers, CVVs

    @Builder.Default
    private boolean allowPhiData = false;           // Protected health information

    @Builder.Default
    private boolean allowPiiData = true;            // Names, emails, phones (usually allowed)

    @Builder.Default
    private boolean allowClassifiedData = false;    // Government classified

    // ── AI Risk Threshold ──────────────────────────────────────────────────

    @Column(nullable = false, length = 20)
    @Builder.Default
    private String maxAllowedRiskLevel = "MEDIUM";  // NONE, LOW, MEDIUM, HIGH — files above this are BLOCKED

    @Builder.Default
    private int maxAllowedRiskScore = 70;           // 0-100, files scoring above this are BLOCKED

    // ── File Rules ─────────────────────────────────────────────────────────

    @Builder.Default
    private boolean requireEncryption = false;       // Files MUST be encrypted (PGP/AES)

    @Builder.Default
    private boolean requireScreening = true;         // Files MUST pass AV + sanctions screening

    @Builder.Default
    private boolean requireChecksum = false;          // Files MUST have SHA-256 verification

    @Column(columnDefinition = "TEXT")
    private String allowedFileExtensions;             // Comma-separated: "edi,xml,json,csv" — null = all allowed

    @Column(columnDefinition = "TEXT")
    private String blockedFileExtensions;             // Comma-separated: "exe,bat,cmd,ps1,sh" — null = none blocked

    private Long maxFileSizeBytes;                    // null = no limit

    // ── Transfer Rules ─────────────────────────────────────────────────────

    @Builder.Default
    private boolean requireTls = true;                // Connections MUST use TLS/SFTP (not plain FTP)

    @Builder.Default
    private boolean allowAnonymousAccess = false;     // Anonymous FTP connections blocked

    @Builder.Default
    private boolean requireMfa = false;               // Users MUST have 2FA enabled

    // ── Audit & Enforcement ────────────────────────────────────────────────

    @Builder.Default
    private boolean auditAllTransfers = true;         // Log every transfer in audit trail

    @Builder.Default
    private boolean notifyOnViolation = true;         // Send notification on violation

    @Column(nullable = false, length = 10)
    @Builder.Default
    private String violationAction = "BLOCK";         // BLOCK = reject file, WARN = allow but flag, LOG = silent log

    @Builder.Default
    private boolean active = true;

    @Column(nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    private Instant updatedAt;

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
