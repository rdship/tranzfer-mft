package com.filetransfer.ai.service;

import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * AI Phase 2: Smart retry with failure classification.
 * Instead of blind retry, classifies the failure and decides the best action.
 */
@Service
@Slf4j
public class SmartRetryService {

    /**
     * Classify a failure and recommend action.
     */
    public RetryDecision classify(String errorMessage, String filename, int currentRetryCount) {
        String lowerError = errorMessage != null ? errorMessage.toLowerCase() : "";

        // Network / transient errors → retry quickly
        if (lowerError.contains("timeout") || lowerError.contains("connection reset") ||
                lowerError.contains("connection refused") || lowerError.contains("temporarily unavailable")) {
            return RetryDecision.builder()
                    .action("RETRY").delaySeconds(30 * (currentRetryCount + 1))
                    .reason("Transient network error — retry with backoff")
                    .failureCategory("NETWORK_TRANSIENT").build();
        }

        // Auth errors → don't retry (won't help)
        if (lowerError.contains("auth") || lowerError.contains("permission denied") ||
                lowerError.contains("access denied") || lowerError.contains("401") || lowerError.contains("403")) {
            return RetryDecision.builder()
                    .action("ALERT_NO_RETRY").delaySeconds(0)
                    .reason("Authentication/permission error — retrying won't help. Alert admin.")
                    .failureCategory("AUTH_FAILURE").build();
        }

        // Disk / storage errors → wait for cleanup
        if (lowerError.contains("disk full") || lowerError.contains("no space") ||
                lowerError.contains("quota exceeded")) {
            return RetryDecision.builder()
                    .action("RETRY_DELAYED").delaySeconds(600)
                    .reason("Storage full — wait 10 min for cleanup, then retry")
                    .failureCategory("STORAGE_FULL").build();
        }

        // Checksum mismatch → re-request from sender
        if (lowerError.contains("checksum") || lowerError.contains("integrity") ||
                lowerError.contains("corrupt")) {
            return RetryDecision.builder()
                    .action("RE_REQUEST").delaySeconds(0)
                    .reason("File integrity failure — request re-send from source")
                    .failureCategory("INTEGRITY_FAILURE").build();
        }

        // Encryption key errors → don't retry
        if (lowerError.contains("key expired") || lowerError.contains("key not found") ||
                lowerError.contains("decrypt failed") || lowerError.contains("pgp")) {
            return RetryDecision.builder()
                    .action("ALERT_NO_RETRY").delaySeconds(0)
                    .reason("Encryption key issue — admin must update keys before retry")
                    .failureCategory("ENCRYPTION_KEY").build();
        }

        // Schema / format errors → don't retry
        if (lowerError.contains("schema") || lowerError.contains("column") ||
                lowerError.contains("format") || lowerError.contains("parse")) {
            return RetryDecision.builder()
                    .action("QUARANTINE").delaySeconds(0)
                    .reason("File format/schema error — quarantine for manual review")
                    .failureCategory("FORMAT_ERROR").build();
        }

        // Default → retry with increasing backoff
        return RetryDecision.builder()
                .action("RETRY").delaySeconds(60 * (currentRetryCount + 1))
                .reason("Unknown error — retry with exponential backoff")
                .failureCategory("UNKNOWN").build();
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class RetryDecision {
        private String action;          // RETRY, RETRY_DELAYED, ALERT_NO_RETRY, RE_REQUEST, QUARANTINE
        private int delaySeconds;
        private String reason;
        private String failureCategory; // NETWORK_TRANSIENT, AUTH_FAILURE, STORAGE_FULL, etc.
    }
}
