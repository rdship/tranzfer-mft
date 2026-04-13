package com.filetransfer.shared.dto;

import com.filetransfer.shared.entity.FlowExecution;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * DTO for FlowExecution — safe for Jackson serialization with open-in-view=false.
 * Resolves lazy associations (flow, transferRecord) into flat fields.
 */
@Data
@Builder
public class FlowExecutionDto {
    private UUID id;
    private String trackId;
    private String status;
    private int currentStep;
    private String originalFilename;
    private String currentFilePath;
    private int attemptNumber;
    private String errorMessage;
    private Instant startedAt;
    private Instant completedAt;
    private Instant scheduledRetryAt;
    private String scheduledRetryBy;
    private String restartedBy;
    private Instant restartedAt;
    private boolean terminationRequested;
    private List<FlowExecution.StepResult> stepResults;
    // Flat fields from lazy associations
    private UUID flowId;
    private String flowName;
    private UUID transferRecordId;
    private String transferRecordFilename;

    public static FlowExecutionDto from(FlowExecution exec) {
        FlowExecutionDtoBuilder b = FlowExecutionDto.builder()
                .id(exec.getId())
                .trackId(exec.getTrackId())
                .status(exec.getStatus() != null ? exec.getStatus().name() : null)
                .currentStep(exec.getCurrentStep())
                .originalFilename(exec.getOriginalFilename())
                .currentFilePath(exec.getCurrentFilePath())
                .attemptNumber(exec.getAttemptNumber())
                .errorMessage(exec.getErrorMessage())
                .startedAt(exec.getStartedAt())
                .completedAt(exec.getCompletedAt())
                .scheduledRetryAt(exec.getScheduledRetryAt())
                .scheduledRetryBy(exec.getScheduledRetryBy())
                .restartedBy(exec.getRestartedBy())
                .restartedAt(exec.getRestartedAt())
                .terminationRequested(exec.isTerminationRequested())
                .stepResults(exec.getStepResults());

        // Safely resolve lazy associations inside the transaction
        try {
            if (exec.getFlow() != null) {
                b.flowId(exec.getFlow().getId());
                b.flowName(exec.getFlow().getName());
            }
        } catch (Exception ignored) { /* LazyInit outside tx — skip */ }

        try {
            if (exec.getTransferRecord() != null) {
                b.transferRecordId(exec.getTransferRecord().getId());
                b.transferRecordFilename(exec.getTransferRecord().getOriginalFilename());
            }
        } catch (Exception ignored) { }

        return b.build();
    }
}
