package com.filetransfer.onboarding.dto.response;

import com.filetransfer.shared.enums.ClusterCommunicationMode;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Data
@Builder
public class ClusterInfoResponse {
    private UUID id;
    private String clusterId;
    private String displayName;
    private String description;
    private ClusterCommunicationMode communicationMode;
    private String region;
    private String environment;
    private String apiEndpoint;
    private boolean active;
    private Instant registeredAt;
    private Instant updatedAt;
    private long serviceCount;
    private List<ServiceRegistrationResponse> services;
}
