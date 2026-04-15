package com.filetransfer.shared.repository.transfer;

import com.filetransfer.shared.entity.transfer.FabricCheckpoint;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface FabricCheckpointRepository extends JpaRepository<FabricCheckpoint, UUID> {

    /**
     * Full timeline for a given trackId, ordered by step.
     * Used by /api/fabric/track/{trackId}/timeline endpoint.
     */
    List<FabricCheckpoint> findByTrackIdOrderByStepIndexAsc(String trackId);

    /**
     * Most recent checkpoint for a given (trackId, stepIndex) — handles retries.
     */
    Optional<FabricCheckpoint> findFirstByTrackIdAndStepIndexOrderByAttemptNumberDesc(
        String trackId, Integer stepIndex);

    /**
     * Find stuck work items (lease expired but still IN_PROGRESS).
     * Used by LeaseReaperJob.
     */
    @Query("SELECT c FROM FabricCheckpoint c " +
           "WHERE c.status = 'IN_PROGRESS' AND c.leaseExpiresAt < :now")
    List<FabricCheckpoint> findStuckCheckpoints(@Param("now") Instant now);

    /** Paginated variant for the observability /stuck endpoint. */
    @Query("SELECT c FROM FabricCheckpoint c " +
           "WHERE c.status = 'IN_PROGRESS' AND c.leaseExpiresAt < :now " +
           "ORDER BY c.leaseExpiresAt ASC")
    Page<FabricCheckpoint> findStuckCheckpoints(@Param("now") Instant now, Pageable pageable);

    /**
     * All in-flight work for a given instance (for dead-pod detection).
     */
    List<FabricCheckpoint> findByProcessingInstanceAndStatus(String instanceId, String status);

    /**
     * Count of active work per step type (for queue depth metrics).
     */
    @Query("SELECT c.stepType, COUNT(c) FROM FabricCheckpoint c " +
           "WHERE c.status = 'IN_PROGRESS' GROUP BY c.stepType")
    List<Object[]> countInProgressByStepType();

    /**
     * Recent completed checkpoints (for latency metrics).
     */
    @Query("SELECT c FROM FabricCheckpoint c " +
           "WHERE c.status = 'COMPLETED' AND c.completedAt > :since " +
           "ORDER BY c.completedAt DESC")
    List<FabricCheckpoint> findRecentCompleted(@Param("since") Instant since);

    /** Paginated variant for the observability /latency endpoint. */
    @Query("SELECT c FROM FabricCheckpoint c " +
           "WHERE c.status = 'COMPLETED' AND c.completedAt > :since " +
           "ORDER BY c.completedAt DESC")
    Page<FabricCheckpoint> findRecentCompleted(@Param("since") Instant since, Pageable pageable);

    /**
     * Batch-fetch all checkpoints for a set of trackIds.
     * Callers reduce to "latest per trackId" in memory. Used by
     * ActivityMonitorController for N-free fabric enrichment.
     */
    @Query("SELECT c FROM FabricCheckpoint c WHERE c.trackId IN :trackIds ORDER BY c.stepIndex DESC")
    List<FabricCheckpoint> findLatestByTrackIds(@Param("trackIds") Collection<String> trackIds);
}
