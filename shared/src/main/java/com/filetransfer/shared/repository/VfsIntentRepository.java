package com.filetransfer.shared.repository;

import com.filetransfer.shared.entity.VfsIntent;
import com.filetransfer.shared.entity.VfsIntent.IntentStatus;
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

    /** Purge old resolved intents to prevent table bloat. */
    @Modifying
    @Query("DELETE FROM VfsIntent i WHERE i.status IN ('COMMITTED','ABORTED') AND i.resolvedAt < :before")
    int purgeResolved(Instant before);
}
