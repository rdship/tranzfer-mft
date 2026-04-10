package com.filetransfer.shared.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.*;
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

    @NotBlank
    @Column(unique = true, nullable = false)
    private String name;                    // "PCI-DSS Strict", "HIPAA Healthcare", "Internal Only"

    private String description;

    @NotBlank
    @Size(max = 20)
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

    @NotBlank
    @Size(max = 20)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private String maxAllowedRiskLevel = "MEDIUM";  // NONE, LOW, MEDIUM, HIGH — files above this are BLOCKED

    @Min(0)
    @Max(100)
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

    // ── Geo-Blocking & IP Restrictions ────────────────────────────────────

    /** ISO 3166-1 alpha-2 country codes blocked from login. Comma-separated: "RU,CN,IR,KP,CU" */
    @Column(columnDefinition = "TEXT")
    private String blockedCountries;

    /** ISO 3166-1 alpha-2 country codes ALLOWED. Null = all allowed. "US,CA,GB,DE" = whitelist mode. */
    @Column(columnDefinition = "TEXT")
    private String allowedCountries;

    /** CIDR ranges allowed to connect. Null = all IPs allowed. "10.0.0.0/8,192.168.0.0/16" */
    @Column(columnDefinition = "TEXT")
    private String allowedIpCidrs;

    /** CIDR ranges explicitly blocked. Checked before allowed list. */
    @Column(columnDefinition = "TEXT")
    private String blockedIpCidrs;

    // ── Business Hours Restrictions ───────────────────────────────────────

    /** If true, transfers only allowed during business hours. */
    @Builder.Default
    private boolean businessHoursOnly = false;

    /** Business hours start (0-23, e.g. 8 = 8:00 AM). Only enforced if businessHoursOnly=true. */
    @Builder.Default
    private int businessHoursStart = 8;

    /** Business hours end (0-23, e.g. 18 = 6:00 PM). */
    @Builder.Default
    private int businessHoursEnd = 18;

    /** Timezone for business hours. IANA format: "America/New_York", "Europe/London". */
    @Builder.Default
    private String businessHoursTimezone = "UTC";

    /** Days of week allowed (comma-separated): "MON,TUE,WED,THU,FRI". Null = all days. */
    @Column(columnDefinition = "TEXT")
    private String allowedDaysOfWeek;

    // ── Data Retention & Residency ────────────────────────────────────────

    /** Days to retain transferred files before auto-purge. 0 = no auto-purge. */
    @Min(0)
    @Builder.Default
    private int dataRetentionDays = 90;

    /** Days to retain audit logs. Regulatory minimum varies: PCI=1yr, HIPAA=6yr, SOX=7yr. */
    @Min(0)
    @Builder.Default
    private int auditRetentionDays = 365;

    /** Data residency requirement: "US", "EU", "UK", "ANY". Controls where files are stored. */
    @Size(max = 10)
    @Column(length = 10)
    @Builder.Default
    private String dataResidency = "ANY";

    // ── Encryption Standards ──────────────────────────────────────────────

    /** Minimum encryption key length in bits. E.g. 256 for AES-256, 2048 for RSA. */
    @Builder.Default
    private int minEncryptionKeyBits = 256;

    /** Allowed encryption algorithms. Comma-separated: "AES-256,PGP,RSA-2048". Null = any. */
    @Column(columnDefinition = "TEXT")
    private String allowedEncryptionAlgorithms;

    /** Minimum TLS version. "1.2" or "1.3". */
    @Size(max = 5)
    @Column(length = 5)
    @Builder.Default
    private String minTlsVersion = "1.2";

    // ── Password & Authentication Policy ──────────────────────────────────

    /** Minimum password length for accounts on this server. */
    @Min(1)
    @Builder.Default
    private int minPasswordLength = 12;

    /** Require uppercase + lowercase + digit + special character. */
    @Builder.Default
    private boolean requirePasswordComplexity = true;

    /** Days before password must be rotated. 0 = no rotation required. */
    @Builder.Default
    private int passwordRotationDays = 90;

    /** Max failed login attempts before account lockout. */
    @Min(1)
    @Builder.Default
    private int maxFailedLoginAttempts = 5;

    /** Lockout duration in minutes after max failed attempts. */
    @Min(0)
    @Builder.Default
    private int lockoutDurationMinutes = 30;

    // ── Session Management ────────────────────────────────────────────────

    /** Max idle time (minutes) before session is terminated. */
    @Builder.Default
    private int maxSessionIdleMinutes = 30;

    /** Max concurrent sessions per user. 0 = unlimited. */
    @Builder.Default
    private int maxConcurrentSessions = 3;

    // ── Rate Limiting & Quotas ────────────────────────────────────────────

    /** Max file transfers per user per hour. 0 = unlimited. */
    @Builder.Default
    private int maxTransfersPerHour = 0;

    /** Max file transfers per user per day. 0 = unlimited. */
    @Builder.Default
    private int maxTransfersPerDay = 0;

    /** Max total data transferred per user per day (bytes). 0 = unlimited. */
    @Builder.Default
    private long maxDataPerDayBytes = 0;

    // ── Dual Authorization ────────────────────────────────────────────────

    /** Require second approver for sensitive transfers. */
    @Builder.Default
    private boolean requireDualAuthorization = false;

    /** File size threshold (bytes) above which dual authorization is required. */
    @Builder.Default
    private long dualAuthThresholdBytes = 104857600;  // 100 MB

    // ── Audit & Enforcement ────────────────────────────────────────────────

    @Builder.Default
    private boolean auditAllTransfers = true;         // Log every transfer in audit trail

    @Builder.Default
    private boolean notifyOnViolation = true;         // Send notification on violation

    @NotBlank
    @Size(max = 10)
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
