package com.filetransfer.onboarding.dto.response;

import com.filetransfer.shared.enums.Protocol;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
public class ServerInstanceResponse {
    private UUID id;
    private String instanceId;
    private Protocol protocol;
    private String name;
    private String description;
    private String internalHost;
    private int internalPort;
    private String externalHost;
    private Integer externalPort;
    private boolean useProxy;
    private String proxyHost;
    private Integer proxyPort;
    private int maxConnections;
    private UUID folderTemplateId;
    private String folderTemplateName;
    private String defaultStorageMode;
    private boolean active;
    private Instant createdAt;
    private Instant updatedAt;
    // Computed: what clients should use to connect
    private String clientHost;
    private int clientPort;
}
