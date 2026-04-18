package com.filetransfer.storage.repository;

import com.filetransfer.storage.entity.StorageObject;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StorageObjectRepository extends JpaRepository<StorageObject, UUID> {
    /**
     * R125: flow engines write multiple storage objects per trackId (input
     * snapshot + per-step outputs). The original {@code Optional<T>} finder
     * threw "Query did not return a unique result" when two or more rows
     * shared a trackId, turning Activity Monitor downloads into a 500. We
     * now resolve "the file for this trackId" as the newest non-deleted row.
     */
    Optional<StorageObject> findFirstByTrackIdAndDeletedFalseOrderByCreatedAtDesc(String trackId);

    /** @deprecated ambiguous for multi-step flows — use the ordered finder. */
    @Deprecated
    Optional<StorageObject> findByTrackIdAndDeletedFalse(String trackId);
    Optional<StorageObject> findBySha256AndDeletedFalse(String sha256);
    List<StorageObject> findByTierAndDeletedFalse(String tier);
    List<StorageObject> findByAccountUsernameAndDeletedFalseOrderByCreatedAtDesc(String account);
    List<StorageObject> findByTierAndCreatedAtBeforeAndDeletedFalse(String tier, Instant before);
    List<StorageObject> findByBackupStatusAndDeletedFalse(String status);

    @Query("SELECT SUM(s.sizeBytes) FROM StorageObject s WHERE s.tier = :tier AND s.deleted = false")
    Long sumSizeByTier(String tier);

    @Query("SELECT COUNT(s) FROM StorageObject s WHERE s.tier = :tier AND s.deleted = false")
    long countByTier(String tier);
}
