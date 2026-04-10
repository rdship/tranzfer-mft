package com.filetransfer.shared.repository;

import com.filetransfer.shared.entity.FileTransferRecord;
import com.filetransfer.shared.enums.FileTransferStatus;
import com.filetransfer.shared.enums.Protocol;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FileTransferRecordRepository extends JpaRepository<FileTransferRecord, UUID> {

    List<FileTransferRecord> findByUploadedAtAfter(Instant since);

    Optional<FileTransferRecord> findByTrackId(String trackId);

    List<FileTransferRecord> findByStatus(FileTransferStatus status);

    @Query("SELECT r FROM FileTransferRecord r " +
           "JOIN FETCH r.folderMapping fm " +
           "LEFT JOIN FETCH fm.sourceAccount " +
           "JOIN FETCH fm.destinationAccount da " +
           "WHERE da.id = :accountId AND r.destinationFilePath = :filePath AND r.status = :status")
    Optional<FileTransferRecord> findByDestinationAndStatus(@Param("accountId") UUID accountId,
                                                             @Param("filePath") String filePath,
                                                             @Param("status") FileTransferStatus status);

    @Query("SELECT r FROM FileTransferRecord r " +
           "JOIN FETCH r.folderMapping fm " +
           "JOIN FETCH fm.sourceAccount sa " +
           "LEFT JOIN FETCH fm.destinationAccount " +
           "WHERE sa.id = :accountId " +
           "ORDER BY r.uploadedAt DESC")
    List<FileTransferRecord> findByFolderMappingSourceAccountIdOrderByUploadedAtDesc(@Param("accountId") UUID accountId);

    @Query(value = "SELECT r FROM FileTransferRecord r " +
           "JOIN FETCH r.folderMapping fm " +
           "JOIN FETCH fm.sourceAccount sa " +
           "LEFT JOIN FETCH fm.destinationAccount " +
           "LEFT JOIN FETCH fm.externalDestination " +
           "WHERE (:trackId IS NULL OR r.trackId = :trackId) " +
           "AND (:filename IS NULL OR LOWER(r.originalFilename) LIKE LOWER(CONCAT('%', :filename, '%'))) " +
           "AND (:status IS NULL OR r.status = :status) " +
           "AND (:sourceUsername IS NULL OR sa.username = :sourceUsername) " +
           "AND (:protocol IS NULL OR sa.protocol = :protocol)",
           countQuery = "SELECT COUNT(r) FROM FileTransferRecord r " +
           "JOIN r.folderMapping fm " +
           "JOIN fm.sourceAccount sa " +
           "WHERE (:trackId IS NULL OR r.trackId = :trackId) " +
           "AND (:filename IS NULL OR LOWER(r.originalFilename) LIKE LOWER(CONCAT('%', :filename, '%'))) " +
           "AND (:status IS NULL OR r.status = :status) " +
           "AND (:sourceUsername IS NULL OR sa.username = :sourceUsername) " +
           "AND (:protocol IS NULL OR sa.protocol = :protocol)")
    Page<FileTransferRecord> searchForActivityMonitor(
            @Param("trackId") String trackId,
            @Param("filename") String filename,
            @Param("status") FileTransferStatus status,
            @Param("sourceUsername") String sourceUsername,
            @Param("protocol") Protocol protocol,
            Pageable pageable);
}
