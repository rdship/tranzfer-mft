package com.filetransfer.storage.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

/**
 * Write-ahead intent log entry. Created before a file write begins,
 * marked DONE on completion, cleaned up on crash recovery.
 */
@Entity @Table(name = "write_intents")
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class WriteIntent {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String tempPath;         // temp file being written

    private String destPath;         // target CAS path (null if sha256 not yet known)

    @Column(nullable = false, length = 20) @Builder.Default
    private String status = "IN_PROGRESS";  // IN_PROGRESS | DONE | ABANDONED

    private int stripeCount;

    private long expectedSizeBytes;

    @Column(nullable = false, updatable = false) @Builder.Default
    private Instant createdAt = Instant.now();

    private Instant completedAt;
}
