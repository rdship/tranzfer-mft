package com.filetransfer.config.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Simplified flow creation request — the "30-second flow" model.
 *
 * <p>An admin fills in 4 sections: WHEN, DO, DELIVER, ERROR.
 * The system auto-generates all underlying entities (FileFlow, steps,
 * folder mappings, delivery endpoints) behind the scenes.
 *
 * <p>Example:
 * <pre>
 * {
 *   "name": "ACME EDI Inbound",
 *   "source": "acme-corp",
 *   "filenamePattern": ".*\\.edi",
 *   "actions": ["SCREEN", "CONVERT_EDI", "COMPRESS_GZIP"],
 *   "deliverTo": "globalbank-sftp",
 *   "deliveryPath": "/inbound/converted",
 *   "onError": "RETRY",
 *   "retryCount": 3,
 *   "notifyOnFailure": true
 * }
 * </pre>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QuickFlowRequest {

    // ── WHEN ──
    /** Source partner slug or account username (optional — null = any source) */
    private String source;
    /** Filename regex pattern (e.g., ".*\\.csv", ".*\\.edi") */
    private String filenamePattern;
    /** Protocol filter: SFTP, FTP, AS2, or null (any) */
    private String protocol;
    /** Direction: INBOUND (default), OUTBOUND */
    @Builder.Default
    private String direction = "INBOUND";

    // ── DO ──
    /** Flow name (auto-generated if blank) */
    private String name;
    /** Ordered list of step types (e.g., ["SCREEN", "ENCRYPT_PGP", "COMPRESS_GZIP"]) */
    private List<String> actions;
    /** Optional: encryption key alias for ENCRYPT steps (from keystore-manager) */
    private String encryptionKeyAlias;
    /** Optional: EDI target format for CONVERT_EDI steps */
    @Builder.Default
    private String ediTargetFormat = "JSON";

    // ── DELIVER TO ──
    /** Destination: partner slug, account username, or external destination name */
    private String deliverTo;
    /** Destination path (default: /outbox) */
    @Builder.Default
    private String deliveryPath = "/outbox";

    // ── ERROR HANDLING ──
    /** On error: RETRY, QUARANTINE, NOTIFY, FAIL (default: RETRY) */
    @Builder.Default
    private String onError = "RETRY";
    /** Retry count (default: 3) */
    @Builder.Default
    private int retryCount = 3;
    /** Send notification on failure */
    @Builder.Default
    private boolean notifyOnFailure = true;
    /** Priority — lower = matched first (default: 50) */
    @Builder.Default
    private int priority = 50;
}
