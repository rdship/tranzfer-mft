package com.filetransfer.onboarding.dto.response;

import com.filetransfer.shared.enums.Protocol;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
public class ServerInstanceResponse {
    private UUID     id;
    private String   instanceId;
    private Protocol protocol;
    private String   name;
    private String   description;
    private String   internalHost;
    private int      internalPort;
    private String   externalHost;
    private Integer  externalPort;
    private boolean  useProxy;
    private String   proxyHost;
    private Integer  proxyPort;
    private int      maxConnections;
    private UUID     folderTemplateId;
    private String   folderTemplateName;
    private String   defaultStorageMode;
    private boolean  active;
    private Instant  createdAt;
    private Instant  updatedAt;

    /** What clients should connect to (proxy/external/internal resolved). */
    private String clientHost;
    private int    clientPort;

    // Proxy QoS policy
    private boolean proxyQosEnabled;
    private Long    proxyQosMaxBytesPerSecond;
    private Long    proxyQosPerConnectionMaxBytesPerSecond;
    private Integer proxyQosPriority;
    private Integer proxyQosBurstAllowancePercent;

    // ── Advanced per-server configuration (V44) ──────────────────────────────
    private String  proxyGroupName;
    private String  securityTier;
    private String  sshBannerMessage;
    private int     maxAuthAttempts;
    private int     idleTimeoutSeconds;
    private int     sessionMaxDurationSeconds;
    private String  allowedCiphers;
    private String  allowedMacs;
    private String  allowedKex;
    private boolean maintenanceMode;
    private String  maintenanceMessage;

    /** Number of accounts currently assigned and enabled on this server. */
    private long assignedAccountCount;

    // ── Runtime bind state (V64) ─────────────────────────────────────────────
    /** BOUND | UNBOUND | BIND_FAILED | UNKNOWN — written by the protocol service. */
    private String  bindState;
    private String  bindError;
    private Instant lastBindAttemptAt;
    private String  boundNode;
}
