package com.filetransfer.shared.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * Phantom Folder virtual filesystem entry.
 *
 * <p>Every folder and file in the platform exists as a row in this table,
 * not as a physical directory on disk. Folders are zero-cost DB records.
 * Files point to content-addressed storage via {@code storageKey} (SHA-256).
 *
 * <p>This enables:
 * <ul>
 *   <li>Zero-cost account provisioning (no disk I/O for folder creation)</li>
 *   <li>Cross-account deduplication (same storageKey = one physical copy)</li>
 *   <li>Sub-millisecond directory listings (indexed DB queries vs readdir)</li>
 *   <li>Reference-counted CAS (never lose a file)</li>
 * </ul>
 */
@Entity
@Table(name = "virtual_entries", indexes = {
    @Index(name = "idx_ve_account_parent", columnList = "accountId, parentPath, deleted"),
    @Index(name = "idx_ve_account_path", columnList = "accountId, path, deleted"),
    @Index(name = "idx_ve_storage_key", columnList = "storageKey"),
    @Index(name = "idx_ve_track_id", columnList = "trackId")
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class VirtualEntry extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Account that owns this entry. */
    @Column(nullable = false)
    private UUID accountId;

    /** Full normalized path, e.g. "/inbox/invoice.edi" or "/inbox". */
    @Column(nullable = false, length = 1024)
    private String path;

    /** Parent directory path, e.g. "/inbox" for "/inbox/invoice.edi", "/" for "/inbox". */
    @Column(nullable = false, length = 1024)
    private String parentPath;

    /** Entry name (last path component), e.g. "invoice.edi" or "inbox". */
    @Column(nullable = false, length = 255)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private EntryType type;

    /** SHA-256 key referencing content-addressed storage. Null for DIR entries. */
    @Column(length = 64)
    private String storageKey;

    /** File size in bytes. 0 for DIR entries. */
    @Builder.Default
    private long sizeBytes = 0;

    /** MIME content type. Null for DIR entries. */
    private String contentType;

    /** Track ID from routing engine. Null for DIR entries. */
    @Column(length = 12)
    private String trackId;

    /** Number of times this file has been read. */
    @Builder.Default
    private int accessCount = 0;

    /** Last time this entry was accessed. */
    private Instant lastAccessedAt;

    /** Soft delete — entries are never hard-deleted. */
    @Builder.Default
    private boolean deleted = false;

    /** Optimistic lock version — prevents concurrent update conflicts. */
    @Version
    @Builder.Default
    private int version = 0;

    /** Inline content for INLINE bucket (files < 64 KB stored directly in DB). */
    @Column(columnDefinition = "bytea")
    private byte[] inlineContent;

    /** Storage routing bucket: INLINE, STANDARD, or CHUNKED. */
    @Column(length = 10)
    @Builder.Default
    private String storageBucket = "STANDARD";

    /** Whether inlineContent is gzip-compressed (for INLINE files > 4 KB). */
    @Builder.Default
    private boolean compressed = false;

    /** POSIX-style permissions string, e.g. "rwxr-xr-x". */
    @Column(length = 10)
    @Builder.Default
    private String permissions = "rwxr-xr-x";

    public enum EntryType { DIR, FILE }

    /** Convenience: is this a directory? */
    public boolean isDirectory() { return type == EntryType.DIR; }

    /** Convenience: is this a file? */
    public boolean isFile() { return type == EntryType.FILE; }
}
