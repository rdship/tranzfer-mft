package com.filetransfer.shared.entity;

import com.filetransfer.shared.enums.FileTransferStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "file_transfer_records")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FileTransferRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "folder_mapping_id", nullable = false)
    private FolderMapping folderMapping;

    @Column(nullable = false)
    private String originalFilename;

    // Absolute path on source service filesystem
    @Column(nullable = false)
    private String sourceFilePath;

    // Absolute path on destination service filesystem
    @Column(nullable = false)
    private String destinationFilePath;

    // Absolute path after archiving on source (moved from inbox → archive)
    private String archiveFilePath;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private FileTransferStatus status = FileTransferStatus.PENDING;

    private String errorMessage;

    @Column(nullable = false, updatable = false)
    @Builder.Default
    private Instant uploadedAt = Instant.now();

    private Instant routedAt;
    private Instant downloadedAt;
    private Instant completedAt;
}
