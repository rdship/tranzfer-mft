package com.filetransfer.shared.entity;

import com.filetransfer.shared.enums.Protocol;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "server_instances")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ServerInstance {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "instance_id", unique = true, nullable = false, length = 64)
    private String instanceId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    @Builder.Default
    private Protocol protocol = Protocol.SFTP;

    @Column(nullable = false)
    private String name;

    private String description;

    // Internal connection (Docker service name / direct host)
    @Column(name = "internal_host", nullable = false)
    private String internalHost;

    @Column(name = "internal_port", nullable = false)
    @Builder.Default
    private int internalPort = 2222;

    // External connection (what clients connect to)
    @Column(name = "external_host")
    private String externalHost;

    @Column(name = "external_port")
    private Integer externalPort;

    // Reverse proxy configuration
    @Column(name = "use_proxy", nullable = false)
    @Builder.Default
    private boolean useProxy = false;

    @Column(name = "proxy_host")
    private String proxyHost;

    @Column(name = "proxy_port")
    private Integer proxyPort;

    @Builder.Default
    private int maxConnections = 500;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "folder_template_id")
    private FolderTemplate folderTemplate;

    /** PHYSICAL = legacy filesystem, VIRTUAL = phantom folder VFS. */
    @Column(name = "default_storage_mode", length = 10)
    @Builder.Default
    private String defaultStorageMode = "PHYSICAL";

    // Proxy QoS policy (persisted so proxy mappings can be recreated on restart)
    @Column(name = "proxy_qos_enabled")
    @Builder.Default
    private boolean proxyQosEnabled = false;

    @Column(name = "proxy_qos_max_bps")
    private Long proxyQosMaxBytesPerSecond;

    @Column(name = "proxy_qos_per_conn_max_bps")
    private Long proxyQosPerConnectionMaxBytesPerSecond;

    @Column(name = "proxy_qos_priority")
    @Builder.Default
    private Integer proxyQosPriority = 5;

    @Column(name = "proxy_qos_burst_pct")
    @Builder.Default
    private Integer proxyQosBurstAllowancePercent = 20;

    // ── Advanced per-server configuration (V44) ──────────────────────────────

    /** Proxy group name (links to proxy_groups.name). Null = default routing. */
    @Column(name = "proxy_group_name", length = 100)
    private String proxyGroupName;

    /** Security tier applied at this server: NONE, RULES, AI, AI_LLM. */
    @Column(name = "security_tier", length = 20)
    @Builder.Default
    private String securityTier = "RULES";

    /** Custom SSH banner shown to clients on connect (null = no banner). */
    @Column(name = "ssh_banner_message", columnDefinition = "TEXT")
    private String sshBannerMessage;

    /** Max failed authentication attempts before disconnecting. */
    @Column(name = "max_auth_attempts")
    @Builder.Default
    private int maxAuthAttempts = 3;

    /** Idle session timeout in seconds (0 = no timeout). */
    @Column(name = "idle_timeout_seconds")
    @Builder.Default
    private int idleTimeoutSeconds = 300;

    /** Absolute session length limit in seconds (0 = no limit). */
    @Column(name = "session_max_duration_sec")
    @Builder.Default
    private int sessionMaxDurationSeconds = 86400;

    /**
     * Comma-separated cipher allowlist (null = use server defaults).
     * Example: {@code aes256-gcm@openssh.com,aes128-gcm@openssh.com}
     */
    @Column(name = "allowed_ciphers", columnDefinition = "TEXT")
    private String allowedCiphers;

    /** Comma-separated MAC allowlist. */
    @Column(name = "allowed_macs", columnDefinition = "TEXT")
    private String allowedMacs;

    /** Comma-separated KEX allowlist. */
    @Column(name = "allowed_kex", columnDefinition = "TEXT")
    private String allowedKex;

    /** When true: new connections are rejected with a maintenance message. */
    /** Compliance profile assigned to this server. Null = no compliance enforcement. */
    @Column(name = "compliance_profile_id")
    private UUID complianceProfileId;

    @Column(name = "security_profile_id")
    private UUID securityProfileId;

    @Column(name = "maintenance_mode")
    @Builder.Default
    private boolean maintenanceMode = false;

    @Column(name = "maintenance_message", columnDefinition = "TEXT")
    private String maintenanceMessage;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    @Column(nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    /**
     * Returns the host:port that clients should use to connect.
     * If a proxy is configured, returns proxy address; otherwise external address.
     */
    public String getClientConnectionHost() {
        if (useProxy && proxyHost != null) return proxyHost;
        if (externalHost != null) return externalHost;
        return internalHost;
    }

    public int getClientConnectionPort() {
        if (useProxy && proxyPort != null) return proxyPort;
        if (externalPort != null) return externalPort;
        return internalPort;
    }
}
