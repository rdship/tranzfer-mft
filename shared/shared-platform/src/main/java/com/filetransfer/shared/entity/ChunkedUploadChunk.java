package com.filetransfer.shared.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Tracks an individual chunk within a chunked upload session.
 */
@Entity
@Table(name = "chunked_upload_chunks", indexes = {
    @Index(name = "idx_chunk_upload_id", columnList = "uploadId"),
    @Index(name = "idx_chunk_number", columnList = "uploadId, chunkNumber", unique = true)
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ChunkedUploadChunk {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Reference to the parent ChunkedUpload */
    @Column(nullable = false)
    private UUID uploadId;

    /** Chunk sequence number (0-based) */
    private int chunkNumber;

    /** Size of this chunk in bytes */
    private long size;

    /** SHA-256 checksum of this chunk */
    @Column(length = 64)
    private String checksum;

    /** Physical storage path for this chunk */
    @Column(nullable = false)
    private String storagePath;

    @Column(nullable = false, updatable = false)
    @Builder.Default
    private Instant receivedAt = Instant.now();
}
