package com.filetransfer.onboarding.dto.request;

import lombok.Data;

import java.util.List;

@Data
public class UpdatePartnerRequest {

    private String companyName;
    private String displayName;
    private String industry;
    private String website;
    private String logoUrl;
    private String partnerType;
    private List<String> protocolsEnabled;
    private String slaTier;
    private Long maxFileSizeBytes;
    private Integer maxTransfersPerDay;
    private Integer retentionDays;
    private String notes;
    private List<CreatePartnerRequest.ContactRequest> contacts;
}
