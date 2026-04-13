package com.filetransfer.shared.dto;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

/**
 * DTO for LicenseActivation — resolves lazy licenseRecord reference.
 * Resolves lazy licenseRecord reference into flat fields.
 */
@Data
@Builder
public class LicenseActivationDto {
    private UUID id;
    private String serviceType;
    private String hostId;
    private Instant activatedAt;
    private Instant lastCheckIn;
    private boolean active;
    // Flat fields from lazy licenseRecord
    private String licenseId;
    private String customerName;
    private String edition;
    private Instant expiresAt;
}
