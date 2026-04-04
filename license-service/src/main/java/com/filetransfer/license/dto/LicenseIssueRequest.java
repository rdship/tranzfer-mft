package com.filetransfer.license.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.*;
import java.util.List;

@Data @NoArgsConstructor @AllArgsConstructor
public class LicenseIssueRequest {
    @NotBlank private String customerId;
    @NotBlank private String customerName;
    @NotNull private String edition;
    @Positive private int validDays = 365;
    private List<ServiceLicenseRequest> services;
    private String notes;

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class ServiceLicenseRequest {
        private String serviceType;
        private int maxInstances = 3;
        private int maxConcurrentConnections = 1000;
        private List<String> features;
    }
}
