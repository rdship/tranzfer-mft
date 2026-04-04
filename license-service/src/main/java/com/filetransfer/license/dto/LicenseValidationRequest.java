package com.filetransfer.license.dto;

import lombok.*;

@Data @NoArgsConstructor @AllArgsConstructor
public class LicenseValidationRequest {
    private String licenseKey;
    private String serviceType;
    private String hostId;
    private String installationFingerprint;
    private String customerId;
    private String customerName;
}
