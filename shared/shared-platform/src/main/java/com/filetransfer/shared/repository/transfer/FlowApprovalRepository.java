package com.filetransfer.shared.repository.transfer;

import com.filetransfer.shared.entity.transfer.FlowApproval;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FlowApprovalRepository extends JpaRepository<FlowApproval, UUID> {

    Optional<FlowApproval> findByTrackIdAndStepIndex(String trackId, int stepIndex);

    List<FlowApproval> findByStatusOrderByRequestedAtDesc(FlowApproval.ApprovalStatus status);

    List<FlowApproval> findByTrackIdOrderByStepIndex(String trackId);
}
