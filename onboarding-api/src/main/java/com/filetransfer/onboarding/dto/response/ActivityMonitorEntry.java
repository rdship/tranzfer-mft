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

    // Fabric checkpoint enrichment (null when fabric disabled / no checkpoint yet)
    private Integer currentStep;
    private String currentStepType;
    private String processingInstance;
    private Long leaseRemainingMs;
    private Boolean isStuck;
    private String fabricStatus;

    // Phase 5: Health score (0-100) and error categorization
    private Integer healthScore;
    private String errorCategory; // NETWORK, AUTH, STORAGE, BUSINESS, SYSTEM, null

    // Phase 5: Duration in milliseconds (uploadedAt → completedAt)
    private Long durationMs;

    /** Compute health score: 0-100 based on transfer quality signals. */
    public static int computeHealthScore(String integrityStatus, String status,
                                          int retryCount, String encryptionOption,
                                          Instant uploadedAt, Instant completedAt) {
        int score = 0;
        if ("VERIFIED".equals(integrityStatus)) score += 30;
        else if ("PENDING".equals(integrityStatus)) score += 10;
        if ("MOVED_TO_SENT".equals(status) || "COMPLETED".equals(status)) score += 30;
        else if ("DOWNLOADED".equals(status)) score += 20;
        if (retryCount == 0) score += 20;
        else if (retryCount == 1) score += 10;
        if (encryptionOption != null && !"NONE".equals(encryptionOption)) score += 10;
        // SLA: completed within 60s of upload
        if (uploadedAt != null && completedAt != null
                && java.time.Duration.between(uploadedAt, completedAt).toSeconds() <= 60) {
            score += 10;
        }
        return Math.min(100, score);
    }

    /** Categorize error message into a standard bucket. */
    public static String categorizeError(String errorMessage) {
        if (errorMessage == null || errorMessage.isBlank()) return null;
        String lower = errorMessage.toLowerCase();
        if (lower.contains("connection refused") || lower.contains("timeout")
                || lower.contains("dns") || lower.contains("unreachable")
                || lower.contains("network")) return "NETWORK";
        if (lower.contains("credentials") || lower.contains("auth")
                || lower.contains("locked") || lower.contains("expired key")
                || lower.contains("permission denied") || lower.contains("403")) return "AUTH";
        if (lower.contains("disk full") || lower.contains("quota")
                || lower.contains("no space") || lower.contains("storage")) return "STORAGE";
        if (lower.contains("dlp") || lower.contains("compliance")
                || lower.contains("blocked") || lower.contains("sla")
                || lower.contains("quarantine")) return "BUSINESS";
        return "SYSTEM";
    }
}
