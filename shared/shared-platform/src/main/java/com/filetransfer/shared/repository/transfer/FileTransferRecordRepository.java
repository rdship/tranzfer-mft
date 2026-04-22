package com.filetransfer.shared.repository.transfer;

import com.filetransfer.shared.entity.transfer.FileTransferRecord;
import com.filetransfer.shared.enums.FileTransferStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FileTransferRecordRepository
        extends JpaRepository<FileTransferRecord, UUID>,
                JpaSpecificationExecutor<FileTransferRecord> {

    List<FileTransferRecord> findByUploadedAtAfter(Instant since);

    /**
     * R134Y Sprint 8 — drives ActivityStreamConsumer's 1s SSE poll. Cursor
     * is an {@code Instant} starting at service boot; tick N returns every
     * record updated since tick N-1. Limit is implicit: the poll interval
     * × write rate bounds the row count returned per tick.
     */
    List<FileTransferRecord> findByUpdatedAtAfterOrderByUpdatedAtAsc(Instant since);

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

    // NOTE: the old searchForActivityMonitor JPQL with `:param IS NULL OR ...`
    // pattern was removed. Hibernate 6 binds null params as Types.NULL
    // (untyped) and PostgreSQL can't infer the column type for `$1 IS NULL`
    // when every filter is null on a default page load — producing
    // "could not determine data type of parameter $1". The controller
    // now builds a JpaSpecification dynamically via JpaSpecificationExecutor
    // so null filters contribute no predicates at all. See BUG-1 in
    // DEMO-RESULTS.md (2026-04-11).
}
