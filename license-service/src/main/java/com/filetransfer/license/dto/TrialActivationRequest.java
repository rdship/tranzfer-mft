package com.filetransfer.license.dto;

import lombok.*;

@Data @NoArgsConstructor @AllArgsConstructor
public class TrialActivationRequest {
    private String fingerprint;
    private String customerId;
    private String customerName;
    private String serviceType;
    private String hostId;
}
