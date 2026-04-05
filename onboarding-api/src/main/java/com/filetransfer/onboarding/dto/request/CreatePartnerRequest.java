package com.filetransfer.onboarding.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class CreatePartnerRequest {

    @NotBlank
    @Size(max = 255)
    private String companyName;

    @Size(max = 255)
    private String displayName;

    @Size(max = 100)
    private String industry;

    @Size(max = 500)
    private String website;

    @Size(max = 1000)
    private String logoUrl;

    /** INTERNAL, EXTERNAL, VENDOR, CLIENT */
    private String partnerType;

    private List<String> protocolsEnabled;

    /** STANDARD, PREMIUM, ENTERPRISE */
    private String slaTier;

    private Long maxFileSizeBytes;

    private Integer maxTransfersPerDay;

    private Integer retentionDays;

    private String notes;

    private List<ContactRequest> contacts;

    @Data
    public static class ContactRequest {
        @NotBlank
        private String name;
        private String email;
        private String phone;
        private String role;
        private boolean isPrimary;
    }
}
