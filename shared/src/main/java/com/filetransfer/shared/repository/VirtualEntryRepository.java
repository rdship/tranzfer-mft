package com.filetransfer.shared.repository;

import com.filetransfer.shared.entity.VirtualEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface VirtualEntryRepository extends JpaRepository<VirtualEntry, UUID> {

    /** List immediate children of a directory (for ls / readdir). */
    List<VirtualEntry> findByAccountIdAndParentPathAndDeletedFalse(UUID accountId, String parentPath);

    /** Lookup a single entry by full path. */
    Optional<VirtualEntry> findByAccountIdAndPathAndDeletedFalse(UUID accountId, String path);

    /** Check if an entry exists at a given path. */
    boolean existsByAccountIdAndPathAndDeletedFalse(UUID accountId, String path);

    /** Find all entries under a prefix (for recursive delete/move). */
    List<VirtualEntry> findByAccountIdAndPathStartingWithAndDeletedFalse(UUID accountId, String pathPrefix);

    /** Count references to a storage key (for safe CAS cleanup). */
    @Query("SELECT COUNT(v) FROM VirtualEntry v WHERE v.storageKey = :storageKey AND v.deleted = false")
    long countByStorageKey(String storageKey);

    /** Count files for an account (monitoring). */
    @Query("SELECT COUNT(v) FROM VirtualEntry v WHERE v.accountId = :accountId AND v.type = 'FILE' AND v.deleted = false")
    long countFilesByAccount(UUID accountId);

    /** Count directories for an account (monitoring). */
    @Query("SELECT COUNT(v) FROM VirtualEntry v WHERE v.accountId = :accountId AND v.type = 'DIR' AND v.deleted = false")
    long countDirsByAccount(UUID accountId);

    /** Sum file sizes for an account (quota enforcement). */
    @Query("SELECT COALESCE(SUM(v.sizeBytes), 0) FROM VirtualEntry v WHERE v.accountId = :accountId AND v.type = 'FILE' AND v.deleted = false")
    long sumSizeByAccount(UUID accountId);

    /** Count files by storage bucket (dashboard metrics). */
    @Query("SELECT COUNT(v) FROM VirtualEntry v WHERE v.storageBucket = :bucket AND v.type = 'FILE' AND v.deleted = false")
    long countByStorageBucketAndDeletedFalse(String bucket);

    /** Count files with null bucket — legacy entries (dashboard metrics). */
    @Query("SELECT COUNT(v) FROM VirtualEntry v WHERE v.storageBucket IS NULL AND v.type = 'FILE' AND v.deleted = false")
    long countByStorageBucketIsNullAndDeletedFalse();

    /** Sum file sizes by bucket (dashboard metrics). */
    @Query("SELECT SUM(v.sizeBytes) FROM VirtualEntry v WHERE v.storageBucket = :bucket AND v.type = 'FILE' AND v.deleted = false")
    Long sumSizeByBucketAndDeletedFalse(String bucket);

    /** Count files by account and bucket (per-account dashboard). */
    @Query("SELECT COUNT(v) FROM VirtualEntry v WHERE v.accountId = :accountId AND v.storageBucket = :bucket AND v.type = 'FILE' AND v.deleted = false")
    long countByAccountIdAndStorageBucketAndDeletedFalse(UUID accountId, String bucket);

    /** Soft-delete all entries under a path prefix (recursive delete). */
    @Modifying
    @Query("UPDATE VirtualEntry v SET v.deleted = true WHERE v.accountId = :accountId AND v.path LIKE :pathPrefix AND v.deleted = false")
    int softDeleteByPrefix(UUID accountId, String pathPrefix);

    /** Bulk rename path prefix (for move/rename operations). */
    @Modifying
    @Query("UPDATE VirtualEntry v SET v.path = CONCAT(:newPrefix, SUBSTRING(v.path, LENGTH(:oldPrefix) + 1)), " +
           "v.parentPath = CASE WHEN v.path = :oldPrefix THEN :newParent ELSE CONCAT(:newPrefix, SUBSTRING(v.parentPath, LENGTH(:oldPrefix) + 1)) END " +
           "WHERE v.accountId = :accountId AND v.path LIKE CONCAT(:oldPrefix, '%') AND v.deleted = false")
    int renamePrefixBulk(UUID accountId, String oldPrefix, String newPrefix, String newParent);
}
