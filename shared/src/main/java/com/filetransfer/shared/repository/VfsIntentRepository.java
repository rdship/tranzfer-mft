package com.filetransfer.shared.repository;

import com.filetransfer.shared.entity.VfsIntent;
import com.filetransfer.shared.entity.VfsIntent.IntentStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface VfsIntentRepository extends JpaRepository<VfsIntent, UUID> {

    /** Find stale PENDING intents older than threshold (for recovery). */
    List<VfsIntent> findByStatusAndCreatedAtBefore(IntentStatus status, Instant threshold);

    /** Atomic CAS transition — returns 1 if updated, 0 if another pod beat us. */
    @Modifying
    @Query("UPDATE VfsIntent i SET i.status = :newStatus, i.resolvedAt = CURRENT_TIMESTAMP " +
           "WHERE i.id = :id AND i.status = :expectedStatus")
    int resolve(UUID id, IntentStatus expectedStatus, IntentStatus newStatus);

    /** Abort all PENDING intents for a pod (graceful shutdown). */
    @Modifying
    @Query("UPDATE VfsIntent i SET i.status = 'ABORTED', i.resolvedAt = CURRENT_TIMESTAMP " +
           "WHERE i.podId = :podId AND i.status = 'PENDING'")
    int abortByPod(String podId);

    /** Count intents by status (dashboard metrics). */
    long countByStatus(IntentStatus status);

    /**
     * Aggregate COUNT per status in a single query (dashboard).
     * Returns rows of [status (IntentStatus), count (Long)].
     */
    @Query("SELECT i.status, COUNT(i) FROM VfsIntent i GROUP BY i.status")
    List<Object[]> countGroupByStatus();

    /** Recent intents for audit trail (dashboard). */
    @Query("SELECT i FROM VfsIntent i ORDER BY i.createdAt DESC")
    List<VfsIntent> findTopNOrderByCreatedAtDesc(Pageable pageable);

    /** Count PENDING intents referencing a given CAS storage key (orphan reaper guard). */
    long countByStatusAndStorageKey(IntentStatus status, String storageKey);

    /** Purge old resolved intents to prevent table bloat. */
    @Modifying
    @Query("DELETE FROM VfsIntent i WHERE i.status IN ('COMMITTED','ABORTED') AND i.resolvedAt < :before")
    int purgeResolved(Instant before);
}
