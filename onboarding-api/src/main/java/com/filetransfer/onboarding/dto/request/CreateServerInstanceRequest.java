package com.filetransfer.onboarding.dto.request;

import com.filetransfer.shared.enums.Protocol;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.UUID;

@Data
public class CreateServerInstanceRequest {
    @NotBlank
    @Size(min = 1, max = 64)
    private String instanceId;

    @NotNull
    private Protocol protocol = Protocol.SFTP;

    @NotBlank
    private String name;

    private String description;

    @NotBlank
    private String internalHost;

    @NotNull
    private Integer internalPort = 2222;

    private String externalHost;
    private Integer externalPort;

    // Reverse proxy
    private boolean useProxy = false;
    private String proxyHost;
    private Integer proxyPort;

    private Integer maxConnections = 500;

    private UUID folderTemplateId;

    /**
     * VIRTUAL (default) routes uploads through the VFS / storage-manager /
     * content-addressed storage. PHYSICAL writes directly to the service's
     * local filesystem (legacy; requires the container to have the configured
     * path mounted).
     */
    private String defaultStorageMode = "VIRTUAL";

    /** Create the listener in a disabled-draft state (active=false). */
    private Boolean active;

    /** Proxy QoS policy — applied when useProxy=true */
    private ProxyQoSConfig proxyQos;

    // ── Advanced per-server configuration (V44) — R134f: all were settable
    // via PATCH only, so the admin UI create form silently dropped the values.
    // Aligns with the listener-UI gap audit's Phase 1.

    /** Proxy group name (links to proxy_groups.name). */
    private String  proxyGroupName;
    /** Security tier: NONE | RULES | AI | AI_LLM. */
    private String  securityTier;
    /** SSH banner text shown to clients on connect. */
    private String  sshBannerMessage;
    /** Max failed auth attempts before disconnect. */
    private Integer maxAuthAttempts;
    /** Idle session timeout in seconds (0 = no timeout). */
    private Integer idleTimeoutSeconds;
    /** Absolute session duration limit in seconds (0 = no limit). */
    private Integer sessionMaxDurationSeconds;
    /** Comma-separated SSH cipher allowlist (null = server defaults). */
    private String  allowedCiphers;
    private String  allowedMacs;
    private String  allowedKex;
    /** Maintenance-mode message (shown when maintenanceMode=true). */
    private String  maintenanceMessage;

    /** Compliance profile (HIPAA / PCI / GDPR etc.) — UUID of profile row. */
    private UUID    complianceProfileId;
    /** Security profile (TLS/auth policy bundle) — UUID of profile row. */
    private UUID    securityProfileId;

    // ── FTP per-listener advanced config (V87) ───────────────────────────────
    // All optional. Null = inherit service-wide ftp.* application property.

    /** PASV port range lower bound. Both bounds must be set together. */
    private Integer ftpPassivePortFrom;
    /** PASV port range upper bound. */
    private Integer ftpPassivePortTo;
    /** Keystore Manager alias for the per-listener TLS certificate. */
    private String  ftpTlsCertAlias;
    /** PROT level: NONE | C | P. Null = service-wide default. */
    private String  ftpProtRequired;
    /** Welcome banner on FTP connect. */
    private String  ftpBannerMessage;
    /** True = implicit FTPS (TLS on 990). Null = service-wide default. */
    private Boolean ftpImplicitTls;

    // ── FTP_WEB per-listener advanced config (V88) ───────────────────────────
    /** HTTP idle session timeout. */
    private Integer ftpWebSessionTimeoutSeconds;
    /** Per-request body size cap in bytes (0 = unlimited). */
    private Long ftpWebMaxUploadBytes;
    /** Keystore Manager alias for HTTPS certificate. */
    private String ftpWebTlsCertAlias;
    /** Branded title shown in partner portal header. */
    private String ftpWebPortalTitle;

    @Data
    public static class ProxyQoSConfig {
        private boolean enabled = true;
        private Long maxBytesPerSecond;
        private Long perConnectionMaxBytesPerSecond;
        private Integer priority;
        private Integer burstAllowancePercent;
    }
}
