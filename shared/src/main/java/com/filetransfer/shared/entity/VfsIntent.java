package com.filetransfer.shared.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Write-Ahead Intent log entry.
 *
 * <p>Records the intent of a mutable VFS operation BEFORE execution.
 * On success the intent is marked COMMITTED within the same transaction.
 * On pod crash the intent remains PENDING and is recovered or aborted
 * by {@code VfsIntentRecoveryJob}.
 */
@Entity
@Table(name = "vfs_intents")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class VfsIntent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID accountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private OpType op;

    @Column(nullable = false, length = 1024)
    private String path;

    /** Destination path — only for MOVE operations. */
    @Column(length = 1024)
    private String destPath;

    /** SHA-256 CAS key — only for WRITE operations. */
    @Column(length = 64)
    private String storageKey;

    @Column(length = 12)
    private String trackId;

    @Builder.Default
    private long sizeBytes = 0;

    @Column(length = 128)
    private String contentType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    @Builder.Default
    private IntentStatus status = IntentStatus.PENDING;

    /** Kubernetes pod hostname that created this intent. */
    @Column(length = 255)
    private String podId;

    @Column(nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    /** When the intent was resolved (COMMITTED / ABORTED). */
    private Instant resolvedAt;

    public enum OpType { WRITE, DELETE, MOVE }

    public enum IntentStatus { PENDING, COMMITTED, ABORTED, RECOVERING }
}
