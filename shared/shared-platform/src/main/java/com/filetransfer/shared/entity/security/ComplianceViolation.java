package com.filetransfer.shared.entity.security;

import com.filetransfer.shared.entity.core.*;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Records every compliance violation detected during file transfer.
 * Linked to the ComplianceProfile that was violated and the server where it occurred.
 */
@Entity
@Table(name = "compliance_violations", indexes = {
    @Index(name = "idx_cv_track_id", columnList = "trackId"),
    @Index(name = "idx_cv_profile_id", columnList = "profileId"),
    @Index(name = "idx_cv_severity", columnList = "severity"),
    @Index(name = "idx_cv_created_at", columnList = "createdAt"),
    @Index(name = "idx_cv_resolved", columnList = "resolved")
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder(toBuilder = true)
public class ComplianceViolation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 12)
    private String trackId;

    @Column(nullable = false)
    private UUID profileId;                // ComplianceProfile that was violated

    @Column(nullable = false)
    private String profileName;            // Denormalized for display

    private UUID serverInstanceId;         // Server where violation occurred
    private String serverName;             // Denormalized

    private String username;               // User who triggered violation
    private String filename;
    private Long fileSizeBytes;

    @Column(nullable = false, length = 30)
    private String violationType;          // PCI_DATA_DETECTED, PHI_DATA_DETECTED, PII_DATA_DETECTED,
                                           // RISK_THRESHOLD_EXCEEDED, ENCRYPTION_REQUIRED,
                                           // SCREENING_REQUIRED, BLOCKED_EXTENSION, FILE_TOO_LARGE,
                                           // TLS_REQUIRED, MFA_REQUIRED, CHECKSUM_REQUIRED

    @Column(nullable = false, length = 20)
    @Builder.Default
    private String severity = "HIGH";      // LOW, MEDIUM, HIGH, CRITICAL

    @Column(columnDefinition = "TEXT")
    private String details;                // Human-readable explanation

    @Column(nullable = false, length = 10)
    @Builder.Default
    private String action = "BLOCKED";     // BLOCKED, WARNED, LOGGED

    private String aiRiskLevel;
    private Integer aiRiskScore;

    @Column(columnDefinition = "TEXT")
    private String aiBlockReason;

    @Builder.Default
    private boolean resolved = false;
    private String resolvedBy;
    private Instant resolvedAt;

    @Column(columnDefinition = "TEXT")
    private String resolutionNote;

    @Column(nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
