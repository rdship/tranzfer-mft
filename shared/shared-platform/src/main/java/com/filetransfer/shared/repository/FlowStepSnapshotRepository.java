package com.filetransfer.shared.repository;

import com.filetransfer.shared.entity.FlowStepSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
