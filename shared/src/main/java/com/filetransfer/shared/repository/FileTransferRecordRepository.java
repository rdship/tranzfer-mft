package com.filetransfer.shared.repository;

import com.filetransfer.shared.entity.FileTransferRecord;
import com.filetransfer.shared.enums.FileTransferStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FileTransferRecordRepository extends JpaRepository<FileTransferRecord, UUID> {

    Optional<FileTransferRecord> findByTrackId(String trackId);

    List<FileTransferRecord> findByStatus(FileTransferStatus status);

    @Query("SELECT r FROM FileTransferRecord r " +
           "JOIN FETCH r.folderMapping fm " +
           "JOIN FETCH fm.destinationAccount da " +
           "WHERE da.id = :accountId AND r.destinationFilePath = :filePath AND r.status = :status")
    Optional<FileTransferRecord> findByDestinationAndStatus(UUID accountId, String filePath, FileTransferStatus status);

    List<FileTransferRecord> findByFolderMappingSourceAccountIdOrderByUploadedAtDesc(UUID accountId);
}
