package com.filetransfer.shared.entity;

import com.filetransfer.shared.enums.FileTransferStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "file_transfer_records", indexes = {
    @Index(name = "idx_ftr_track_id", columnList = "trackId", unique = true)
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class FileTransferRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** 12-character tracking ID (e.g. "TRZA1B2C3D4E") */
    @Column(unique = true, length = 12)
    private String trackId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "folder_mapping_id", nullable = false)
    private FolderMapping folderMapping;

    @Column(nullable = false)
    private String originalFilename;

    @Column(nullable = false)
    private String sourceFilePath;

    @Column(nullable = false)
    private String destinationFilePath;

    private String archiveFilePath;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private FileTransferStatus status = FileTransferStatus.PENDING;

    private String errorMessage;

    /** Size in bytes */
    private Long fileSizeBytes;

    @Column(nullable = false, updatable = false)
    @Builder.Default
    private Instant uploadedAt = Instant.now();

    private Instant routedAt;
    private Instant downloadedAt;
    private Instant completedAt;
}
