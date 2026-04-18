package com.filetransfer.shared.repository.transfer;

import com.filetransfer.shared.entity.transfer.FlowExecution;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FlowExecutionRepository extends JpaRepository<FlowExecution, UUID> {
    Optional<FlowExecution> findByTrackId(String trackId);

    @Query("SELECT e FROM FlowExecution e LEFT JOIN FETCH e.flow WHERE e.trackId IN :trackIds")
    List<FlowExecution> findByTrackIdIn(@Param("trackIds") List<String> trackIds);

    /**
     * R125: fixed "could not determine data type of parameter $1" on Postgres —
     * when trackId and filename are null, the un-cast bind parameters reached
     * the driver as untyped NULLs and Postgres refused to plan the query. The
     * status parameter already had a CAST-to-text guard; we now apply the same
     * guard to the two String parameters. Also dropped the in-query ORDER BY
     * (Spring Data appends the Pageable's Sort, and the duplicate made the
     * generated SQL read {@code ORDER BY started_at DESC, started_at DESC}).
     */
    @Query("SELECT e FROM FlowExecution e WHERE " +
           "(CAST(:trackId AS string) IS NULL OR e.trackId = :trackId) AND " +
           "(CAST(:filename AS string) IS NULL OR e.originalFilename LIKE %:filename%) AND " +
           "(CAST(:status AS string) IS NULL OR e.status = :status)")
    Page<FlowExecution> search(
            @Param("trackId") String trackId,
            @Param("filename") String filename,
            @Param("status") FlowExecution.FlowStatus status,
            Pageable pageable);

    List<FlowExecution> findByStatusOrderByStartedAtDesc(FlowExecution.FlowStatus status);
    long countByStatus(FlowExecution.FlowStatus status);

    /**
     * Returns status → count for the 4 live-dashboard statuses in a single query.
     * Rows: [status (String), count (Long)]
     * Replaces 4 separate countByStatus() calls → 75% fewer DB round-trips on 5s polling.
     */
    @Query(value = "SELECT status, COUNT(*) FROM flow_executions " +
                   "WHERE status IN ('PROCESSING','PENDING','PAUSED','FAILED') GROUP BY status",
           nativeQuery = true)
    List<Object[]> countLiveStatuses();

    long countByStartedAtAfter(Instant since);

    /** For stuck execution recovery — finds PROCESSING executions older than threshold */
    List<FlowExecution> findByStatusAndStartedAtBefore(FlowExecution.FlowStatus status, Instant threshold);

    /** Observatory: recent executions with flow name eagerly loaded (avoids N+1). */
    @Query("SELECT e FROM FlowExecution e LEFT JOIN FETCH e.flow WHERE e.startedAt > :since")
    List<FlowExecution> findRecentWithFlow(@Param("since") Instant since);

    /** Scheduled retry: executions whose scheduled time has arrived and are still restartable. */
    @Query("SELECT e FROM FlowExecution e WHERE e.scheduledRetryAt IS NOT NULL AND e.scheduledRetryAt <= :now AND e.status IN :statuses")
    List<FlowExecution> findDueForRetry(@Param("now") Instant now, @Param("statuses") List<FlowExecution.FlowStatus> statuses);

    /** Scheduled retry: all executions with a pending scheduled retry, soonest first. */
    @Query("SELECT e FROM FlowExecution e WHERE e.scheduledRetryAt IS NOT NULL ORDER BY e.scheduledRetryAt ASC")
    List<FlowExecution> findAllScheduled();

    /**
     * Atomically clear scheduled retry fields — returns 1 if this instance "won the race",
     * 0 if another instance already cleared it. Prevents double-trigger on multi-instance deployments.
     */
    @Transactional
    @Modifying
    @Query("UPDATE FlowExecution e SET e.scheduledRetryAt = null, e.scheduledRetryBy = null WHERE e.id = :id AND e.scheduledRetryAt IS NOT NULL")
    int clearScheduledRetry(@Param("id") UUID id);
}
