package com.filetransfer.shared.repository;

import com.filetransfer.shared.entity.FlowExecution;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FlowExecutionRepository extends JpaRepository<FlowExecution, UUID> {
    Optional<FlowExecution> findByTrackId(String trackId);

    @Query("SELECT e FROM FlowExecution e LEFT JOIN FETCH e.flow WHERE e.trackId IN :trackIds")
    List<FlowExecution> findByTrackIdIn(@Param("trackIds") List<String> trackIds);

    @Query("SELECT e FROM FlowExecution e WHERE " +
           "(:trackId IS NULL OR e.trackId = :trackId) AND " +
           "(:filename IS NULL OR e.originalFilename LIKE %:filename%) AND " +
           "(:status IS NULL OR e.status = :status) " +
           "ORDER BY e.startedAt DESC")
    Page<FlowExecution> search(
            @Param("trackId") String trackId,
            @Param("filename") String filename,
            @Param("status") FlowExecution.FlowStatus status,
            Pageable pageable);

    List<FlowExecution> findByStatusOrderByStartedAtDesc(FlowExecution.FlowStatus status);
    long countByStatus(FlowExecution.FlowStatus status);
    long countByStartedAtAfter(Instant since);

    /** For stuck execution recovery — finds PROCESSING executions older than threshold */
    List<FlowExecution> findByStatusAndStartedAtBefore(FlowExecution.FlowStatus status, Instant threshold);

    /** Observatory: recent executions with flow name eagerly loaded (avoids N+1). */
    @Query("SELECT e FROM FlowExecution e LEFT JOIN FETCH e.flow WHERE e.startedAt > :since")
    List<FlowExecution> findRecentWithFlow(@Param("since") Instant since);
}
