package com.filetransfer.shared.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Tracks a chunked file upload session.
 * Supports resume capability — clients can query status to find missing chunks.
 */
@Entity
@Table(name = "chunked_uploads", indexes = {
    @Index(name = "idx_chunked_upload_status", columnList = "status"),
    @Index(name = "idx_chunked_upload_created", columnList = "createdAt")
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ChunkedUpload {

    @Id
    @Builder.Default
    private UUID id = UUID.randomUUID();

    /** Original filename */
    @Column(nullable = false)
    private String filename;

    /** Total file size in bytes */
    private long totalSize;

    /** Total number of expected chunks */
    private int totalChunks;

    /** Number of chunks received so far */
    @Builder.Default
    private int receivedChunks = 0;

    /** Size of each chunk in bytes (last chunk may be smaller) */
    private long chunkSize;

    /** INITIATED, IN_PROGRESS, ASSEMBLING, COMPLETED, FAILED, CANCELLED */
    @Column(nullable = false, length = 20)
    @Builder.Default
    private String status = "INITIATED";

    /** Expected SHA-256 checksum of the complete file (optional, for verification) */
    @Column(length = 64)
    private String checksum;

    /** Account that initiated the upload */
    private String accountUsername;

    /** Track ID assigned to this upload */
    @Column(length = 64)
    private String trackId;

    /** Content type of the file */
    private String contentType;

    /** Error message if status is FAILED */
    @Column(length = 500)
    private String errorMessage;

    @Column(nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    private Instant completedAt;

    /** Upload session expires if not completed within this time */
    @Builder.Default
    private Instant expiresAt = Instant.now().plusSeconds(86400); // 24h default
}
