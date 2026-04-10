package com.filetransfer.shared.entity;

import com.filetransfer.shared.enums.Protocol;
import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "transfer_accounts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TransferAccount extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Protocol protocol;

    @NotBlank
    @Column(unique = true, nullable = false)
    private String username;

    @NotBlank
    @Column(nullable = false)
    private String passwordHash;

    // OpenSSH authorized_keys format (SFTP only)
    @Column(columnDefinition = "TEXT")
    private String publicKey;

    @NotBlank
    @Column(nullable = false)
    private String homeDir;

    // e.g. {"read": true, "write": true, "delete": false}
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    @Builder.Default
    private Map<String, Boolean> permissions = Map.of("read", true, "write", true, "delete", false);

    // Server instance this account is assigned to (null = any instance, works for all protocols)
    @Size(max = 64)
    @Column(length = 64)
    private String serverInstance;

    // Partner this account belongs to (optional)
    @Column(name = "partner_id")
    private UUID partnerId;

    /** PHYSICAL = legacy filesystem, VIRTUAL = phantom folder VFS. */
    @Size(max = 10)
    @Column(name = "storage_mode", length = 10)
    @Builder.Default
    private String storageMode = "PHYSICAL";

    /** Per-account inline threshold override (null = use system default vfs.inline-max-bytes). */
    @Column(name = "inline_max_bytes")
    private Long inlineMaxBytes;

    /** Per-account chunk threshold override (null = use system default vfs.chunk-threshold-bytes). */
    @Column(name = "chunk_threshold_bytes")
    private Long chunkThresholdBytes;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    // ── QoS (Quality of Service) — per-user bandwidth and priority ──

    /** Upload speed limit in bytes/second (null = use SLA tier default, 0 = unlimited). */
    @Column(name = "qos_upload_bytes_per_second")
    private Long qosUploadBytesPerSecond;

    /** Download speed limit in bytes/second (null = use SLA tier default, 0 = unlimited). */
    @Column(name = "qos_download_bytes_per_second")
    private Long qosDownloadBytesPerSecond;

    /** Maximum concurrent sessions for this account (null = use SLA tier default). */
    @Column(name = "qos_max_concurrent_sessions")
    private Integer qosMaxConcurrentSessions;

    /** QoS priority 1=highest, 10=lowest (null = use SLA tier default). */
    @Column(name = "qos_priority")
    private Integer qosPriority;

    /** Burst allowance percent above sustained rate (null = use SLA tier default). */
    @Column(name = "qos_burst_allowance_percent")
    private Integer qosBurstAllowancePercent;

    @OneToMany(mappedBy = "account", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<AuditLog> auditLogs;
}
