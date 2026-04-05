package com.filetransfer.onboarding.dto.request;

import lombok.Data;

@Data
public class UpdateServerInstanceRequest {
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
}
