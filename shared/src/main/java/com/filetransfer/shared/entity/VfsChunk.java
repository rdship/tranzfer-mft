package com.filetransfer.shared.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Chunk manifest entry for large-file (CHUNKED bucket) storage.
 *
 * <p>Files larger than {@code vfs.chunk-threshold-bytes} (default 64 MB)
 * are split into 4 MB chunks, each stored independently in CAS.
 * This table tracks the ordered chunk list so the file can be
 * reassembled on read and partially recovered after a pod crash.
 */
@Entity
@Table(name = "vfs_chunks")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class VfsChunk {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** The VirtualEntry this chunk belongs to. */
    @Column(nullable = false)
    private UUID entryId;

    /** Zero-based chunk index within the file. */
    @Column(nullable = false)
    private int chunkIndex;

    /** SHA-256 CAS key for this chunk's content. */
    @Column(nullable = false, length = 64)
    private String storageKey;

    @Column(nullable = false)
    private long sizeBytes;

    /** SHA-256 digest of this chunk's raw bytes. */
    @Column(nullable = false, length = 64)
    private String sha256;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    @Builder.Default
    private ChunkStatus status = ChunkStatus.PENDING;

    @Column(nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    public enum ChunkStatus { PENDING, STORED, VERIFIED }
}
