package com.filetransfer.shared.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * PCI DSS 10.x compliant audit log.
 * IMMUTABLE — no @PreUpdate, no setters on critical fields.
 * Every file operation, auth event, and config change is recorded here.
 */
@Entity
@Table(name = "audit_logs", indexes = {
    @Index(name = "idx_audit_timestamp", columnList = "timestamp"),
    @Index(name = "idx_audit_track_id", columnList = "trackId"),
    @Index(name = "idx_audit_action", columnList = "action"),
    @Index(name = "idx_audit_account_id", columnList = "account_id")
})
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Link to transfer account (null for system events) */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id")
    private TransferAccount account;

    /** Track ID linking this event to a specific file transfer */
    @Column(length = 12)
    private String trackId;

    /**
     * Action type: FILE_UPLOAD, FILE_DOWNLOAD, FILE_ROUTE, FILE_DELETE,
     * FILE_ENCRYPT, FILE_DECRYPT, FILE_COMPRESS, FILE_DECOMPRESS,
     * LOGIN, LOGIN_FAIL, LOGOUT, ACCOUNT_CREATE, ACCOUNT_DISABLE,
     * CONFIG_CHANGE, FLOW_EXECUTE, FLOW_FAIL, SYSTEM_ERROR
     */
    @Column(nullable = false, length = 30)
    private String action;

    /** True = operation succeeded, False = failed */
    @Column(nullable = false)
    @Builder.Default
    private boolean success = true;

    /** File path (relative to home dir for file ops) */
    private String path;

    /** Original filename */
    private String filename;

    /** File size in bytes at time of operation */
    private Long fileSizeBytes;

    /** SHA-256 checksum of the file at time of operation */
    @Column(length = 64)
    private String sha256Checksum;

    /** Source IP address of the client */
    @Column(length = 45)
    private String ipAddress;

    /** Session or connection identifier */
    private String sessionId;

    /** The user or system principal who performed the action */
    @Column(nullable = false)
    @Builder.Default
    private String principal = "system";

    /** Error message if success=false */
    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    /** Additional structured metadata */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> metadata;

    /** Immutable timestamp — set once at creation, never changed */
    @Column(nullable = false, updatable = false)
    @Builder.Default
    private Instant timestamp = Instant.now();

    /**
     * HMAC signature of (id + action + trackId + path + sha256 + timestamp)
     * for tamper detection. If this doesn't match, the record was altered.
     */
    @Column(length = 64)
    private String integrityHash;
}
