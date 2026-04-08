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

    private String defaultStorageMode = "PHYSICAL";
}
