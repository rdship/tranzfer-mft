package com.filetransfer.storage.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

/**
 * Metadata for every file managed by the storage system.
 * Tracks: location, tier, size, checksums, access patterns, lifecycle state.
 */
@Entity @Table(name = "storage_objects", indexes = {
    @Index(name = "idx_so_track_id", columnList = "trackId"),
    @Index(name = "idx_so_tier", columnList = "tier"),
    @Index(name = "idx_so_account", columnList = "accountUsername"),
    @Index(name = "idx_so_sha256", columnList = "sha256", unique = true)
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class StorageObject {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    /** Track ID from file transfer */
    @Column(length = 12) private String trackId;
    /** Original filename */
    @Column(nullable = false) private String filename;
    /** Current physical path on disk */
    @Column(nullable = false) private String physicalPath;
    /** Logical path (what the user sees) */
    private String logicalPath;
    /** HOT, WARM, COLD, ARCHIVE, DELETED */
    @Column(nullable = false, length = 10) @Builder.Default private String tier = "HOT";
    /** File size in bytes */
    @Column(nullable = false) private long sizeBytes;
    /** SHA-256 checksum */
    @Column(length = 64) private String sha256;
    /** MIME type */
    private String contentType;
    /** Account that owns this file */
    private String accountUsername;
    /** Number of times accessed (reads) */
    @Builder.Default private int accessCount = 0;
    /** Last time the file was read */
    private Instant lastAccessedAt;
    /** When this file was stored */
    @Column(nullable = false, updatable = false) @Builder.Default private Instant createdAt = Instant.now();
    /** When tier was last changed */
    private Instant tierChangedAt;
    /** Backup status: NONE, PENDING, BACKED_UP */
    @Builder.Default private String backupStatus = "NONE";
    private Instant lastBackupAt;
    /** Is this a striped file (multi-chunk)? */
    @Builder.Default private boolean striped = false;
    private int stripeCount;
    /** Compression ratio if compressed (original/compressed) */
    private Double compressionRatio;
    /** Marked for deletion (soft delete — never hard delete) */
    @Builder.Default private boolean deleted = false;
}
