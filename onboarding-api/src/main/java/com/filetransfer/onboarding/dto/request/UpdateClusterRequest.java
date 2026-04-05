package com.filetransfer.onboarding.dto.request;

import lombok.Data;

@Data
public class UpdateClusterRequest {
    private String displayName;
    private String description;
    private String communicationMode;
    private String region;
    private String apiEndpoint;
}
