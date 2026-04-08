package com.filetransfer.onboarding.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ActivityMonitorEntry {

    private String trackId;
    private String filename;
    private String status;
    private Long fileSizeBytes;

    // Source
    private String sourceUsername;
    private String sourceProtocol;
    private String sourcePartnerName;
    private String sourcePath;

    // Destination
    private String destUsername;
    private String destProtocol;
    private String destPartnerName;
    private String destPath;

    // External destination
    private String externalDestName;

    // Checksums & integrity
    private String sourceChecksum;
    private String destinationChecksum;
    private String integrityStatus;

    // Encryption
    private String encryptionOption;

    // Flow execution
    private String flowName;
    private String flowStatus;

    // Timestamps
    private Instant uploadedAt;
    private Instant routedAt;
    private Instant downloadedAt;
    private Instant completedAt;

    // Error & retry
    private int retryCount;
    private String errorMessage;
}
