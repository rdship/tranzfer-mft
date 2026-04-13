package com.filetransfer.shared.entity.security;

import com.filetransfer.shared.entity.core.*;

import com.filetransfer.shared.entity.Auditable;

import com.filetransfer.shared.enums.SecurityTier;
import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "listener_security_policies")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ListenerSecurityPolicy extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @NotBlank
    @Column(nullable = false)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "security_tier", nullable = false, length = 10)
    @Builder.Default
    private SecurityTier securityTier = SecurityTier.AI;

    @ManyToOne
    @JoinColumn(name = "server_instance_id")
    private ServerInstance serverInstance;

    @ManyToOne
    @JoinColumn(name = "external_destination_id")
    private ExternalDestination externalDestination;

    // ── Network rules ──────────────────────────────────────────────────

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", name = "ip_whitelist")
    private List<String> ipWhitelist;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", name = "ip_blacklist")
    private List<String> ipBlacklist;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", name = "geo_allowed_countries")
    private List<String> geoAllowedCountries;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", name = "geo_blocked_countries")
    private List<String> geoBlockedCountries;

    // ── Rate limiting & connections ────────────────────────────────────

    @Min(1)
    @Column(name = "rate_limit_per_minute", nullable = false)
    @Builder.Default
    private int rateLimitPerMinute = 60;

    @Min(1)
    @Column(name = "max_concurrent", nullable = false)
    @Builder.Default
    private int maxConcurrent = 20;

    @Column(name = "max_bytes_per_minute", nullable = false)
    @Builder.Default
    private long maxBytesPerMinute = 500_000_000L;

    @Min(1)
    @Column(name = "max_auth_attempts", nullable = false)
    @Builder.Default
    private int maxAuthAttempts = 5;

    @Min(0)
    @Column(name = "idle_timeout_seconds", nullable = false)
    @Builder.Default
    private int idleTimeoutSeconds = 300;

    @Column(name = "require_encryption", nullable = false)
    @Builder.Default
    private boolean requireEncryption = false;

    @Column(name = "connection_logging", nullable = false)
    @Builder.Default
    private boolean connectionLogging = true;

    // ── File rules ─────────────────────────────────────────────────────

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", name = "allowed_file_extensions")
    private List<String> allowedFileExtensions;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", name = "blocked_file_extensions")
    private List<String> blockedFileExtensions;

    @Column(name = "max_file_size_bytes")
    @Builder.Default
    private long maxFileSizeBytes = 0;

    // ── Transfer windows ───────────────────────────────────────────────

    @Column(columnDefinition = "jsonb", name = "transfer_windows")
    private String transferWindows;

    // ── Status ─────────────────────────────────────────────────────────

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;
}
