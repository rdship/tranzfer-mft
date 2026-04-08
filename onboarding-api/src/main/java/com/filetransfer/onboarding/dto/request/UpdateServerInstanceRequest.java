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
}
