package com.filetransfer.storage.repository;

import com.filetransfer.storage.entity.StorageObject;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StorageObjectRepository extends JpaRepository<StorageObject, UUID> {
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
