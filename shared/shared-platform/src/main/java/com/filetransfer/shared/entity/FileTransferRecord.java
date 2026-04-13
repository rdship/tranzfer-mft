package com.filetransfer.shared.entity;

import com.filetransfer.shared.enums.FileTransferStatus;
import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "file_transfer_records", indexes = {
    @Index(name = "idx_ftr_track_id", columnList = "trackId", unique = true),
    @Index(name = "idx_ftr_status", columnList = "status"),
    @Index(name = "idx_ftr_uploaded", columnList = "uploadedAt")
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class FileTransferRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @NotBlank
    @Size(max = 12)
    @Column(unique = true, length = 12)
    private String trackId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "folder_mapping_id")
    private FolderMapping folderMapping;

    /** Source account for VIRTUAL-mode transfers (no FolderMapping) */
    @Column(name = "source_account_id")
    private UUID sourceAccountId;

    /** Flow that processed this transfer (VIRTUAL mode) */
    @Column(name = "flow_id")
    private UUID flowId;

    /** Destination account (set by MAILBOX step or flow delivery) */
    @Column(name = "destination_account_id")
    private UUID destinationAccountId;

    @NotBlank
    @Column(nullable = false)
    private String originalFilename;

    @Column(nullable = false)
    private String sourceFilePath;

    @Column(nullable = false)
    private String destinationFilePath;

    private String archiveFilePath;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private FileTransferStatus status = FileTransferStatus.PENDING;

    private String errorMessage;

    /** File size in bytes at upload */
    private Long fileSizeBytes;

    /** SHA-256 checksum at upload (source integrity) */
    @Size(max = 64)
    @Column(length = 64)
    private String sourceChecksum;

    /** SHA-256 checksum at destination (delivery integrity) */
    @Size(max = 64)
    @Column(length = 64)
    private String destinationChecksum;

    /** Number of delivery attempts */
    @Min(0)
    @Builder.Default
    private int retryCount = 0;

    @Column(nullable = false, updatable = false)
    @Builder.Default
    private Instant uploadedAt = Instant.now();

    private Instant routedAt;
    private Instant downloadedAt;
    private Instant completedAt;

    /** Updated on every save — used for retry backoff timing */
    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    void onPrePersist() {
        if (updatedAt == null) updatedAt = Instant.now();
    }

    @PreUpdate
    void onPreUpdate() {
        updatedAt = Instant.now();
    }
}
