package com.filetransfer.onboarding.dto.request;

import com.filetransfer.shared.enums.Protocol;
import lombok.Data;

import java.util.UUID;

@Data
public class UpdateServerInstanceRequest {
    private Protocol protocol;
    private String name;
    private String description;
    private String internalHost;
    private Integer internalPort;
    private String externalHost;
    private Integer externalPort;
    private Boolean useProxy;
    private String proxyHost;
    private Integer proxyPort;
    private Integer maxConnections;
    private Boolean active;
    private UUID folderTemplateId;
    private boolean clearFolderTemplate;
    private String defaultStorageMode;

    /** Proxy QoS policy update */
    private CreateServerInstanceRequest.ProxyQoSConfig proxyQos;

    // ── Advanced per-server configuration (V44) ──────────────────────────────
    /** Proxy group name (links to proxy_groups.name — V43). */
    private String  proxyGroupName;
    /** Security tier: NONE | RULES | AI | AI_LLM */
    private String  securityTier;
    /** SSH banner text shown to clients on connect. */
    private String  sshBannerMessage;
    /** Max failed auth attempts before disconnect. */
    private Integer maxAuthAttempts;
    /** Idle session timeout in seconds (0 = no timeout). */
    private Integer idleTimeoutSeconds;
    /** Absolute session duration limit in seconds (0 = no limit). */
    private Integer sessionMaxDurationSeconds;
    /** Comma-separated cipher allowlist (null = server defaults). */
    private String  allowedCiphers;
    private String  allowedMacs;
    private String  allowedKex;
    /** Toggle maintenance mode — new connections rejected gracefully. */
    private Boolean maintenanceMode;
    private String  maintenanceMessage;
}
