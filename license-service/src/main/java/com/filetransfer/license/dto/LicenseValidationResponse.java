package com.filetransfer.license.dto;

import lombok.*;
import java.time.Instant;
import java.util.List;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class LicenseValidationResponse {
    private boolean valid;
    private String edition;
    private String mode;
    private Integer trialDaysRemaining;
    private Instant expiresAt;
    private List<String> features;
    private int maxInstances;
    private int maxConcurrentConnections;
    private String message;
}
