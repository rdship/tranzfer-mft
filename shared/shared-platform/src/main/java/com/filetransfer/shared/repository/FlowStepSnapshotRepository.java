package com.filetransfer.shared.repository;

import com.filetransfer.shared.entity.FlowStepSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FlowStepSnapshotRepository extends JpaRepository<FlowStepSnapshot, UUID> {

    /** All steps for a transfer, ordered by step index. */
    List<FlowStepSnapshot> findByTrackIdOrderByStepIndex(String trackId);

    /** All steps for an execution record. */
    List<FlowStepSnapshot> findByFlowExecutionIdOrderByStepIndex(UUID flowExecutionId);

    /** Single step lookup for file preview. */
    Optional<FlowStepSnapshot> findByTrackIdAndStepIndex(String trackId, int stepIndex);

    /**
     * Aggregate per-step-type latency summary for the given time window.
     * Returns rows: [stepType, avgMs, p95Ms, minMs, maxMs, totalCount, failedCount]
     */
    @Query(value = """
        SELECT
            step_type,
            AVG(duration_ms)::FLOAT                                                  AS avg_ms,
            PERCENTILE_CONT(0.95) WITHIN GROUP (ORDER BY duration_ms)::FLOAT         AS p95_ms,
            MIN(duration_ms)                                                          AS min_ms,
            MAX(duration_ms)                                                          AS max_ms,
            COUNT(*)                                                                  AS total_count,
            SUM(CASE WHEN step_status ILIKE 'FAILED%' THEN 1 ELSE 0 END)             AS failed_count
        FROM flow_step_snapshots
        WHERE created_at > :since AND duration_ms IS NOT NULL
        GROUP BY step_type
        ORDER BY avg_ms DESC
        """, nativeQuery = true)
    List<Object[]> summarizeByStepType(@Param("since") Instant since);

    /**
     * Per-step-type × hour-of-day (UTC) grid for the latency heatmap.
     * Returns rows: [stepType, hourOfDay, avgMs, callCount]
     */
    @Query(value = """
        SELECT
            step_type,
            EXTRACT(HOUR FROM created_at AT TIME ZONE 'UTC')::INTEGER  AS hour_of_day,
            AVG(duration_ms)::FLOAT                                    AS avg_ms,
            COUNT(*)                                                    AS call_count
        FROM flow_step_snapshots
        WHERE created_at > :since AND duration_ms IS NOT NULL
        GROUP BY step_type, EXTRACT(HOUR FROM created_at AT TIME ZONE 'UTC')
        ORDER BY step_type, hour_of_day
        """, nativeQuery = true)
    List<Object[]> heatmapByStepAndHour(@Param("since") Instant since);
}
