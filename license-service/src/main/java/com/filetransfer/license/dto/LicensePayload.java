package com.filetransfer.license.dto;

import lombok.*;
import java.time.Instant;
import java.util.List;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class LicensePayload {
    private String licenseId;
    private String customerId;
    private String customerName;
    private String edition;
    private Instant issuedAt;
    private Instant expiresAt;
    private List<ServiceLicense> services;

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class ServiceLicense {
        private String serviceType;
        private int maxInstances;
        private int maxConcurrentConnections;
        private List<String> features;
    }
}
