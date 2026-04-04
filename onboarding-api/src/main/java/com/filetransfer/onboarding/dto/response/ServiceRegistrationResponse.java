package com.filetransfer.onboarding.dto.response;

import com.filetransfer.shared.enums.ServiceType;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
public class ServiceRegistrationResponse {
    private UUID id;
    private String serviceInstanceId;
    private String clusterId;
    private ServiceType serviceType;
    private String host;
    private int controlPort;
    private boolean active;
    private Instant lastHeartbeat;
    private Instant registeredAt;
}
