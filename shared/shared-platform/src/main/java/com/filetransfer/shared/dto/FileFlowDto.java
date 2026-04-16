package com.filetransfer.shared.dto;

import com.filetransfer.shared.entity.transfer.FileFlow;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * DTO for FileFlow — safe for Jackson serialization with open-in-view=false.
 * Resolves lazy associations (sourceAccount, destinationAccount, externalDestination) into flat IDs/names.
 */
@Data
@Builder
public class FileFlowDto implements java.io.Serializable {
    private UUID id;
    private String name;
    private String description;
    private String filenamePattern;
    private String sourcePath;
    private String destinationPath;
    private String direction;
    private int priority;
    private boolean active;
    private List<FileFlow.FlowStep> steps;
    private UUID partnerId;
    private Instant createdAt;
    private Instant updatedAt;
    // Flat fields from lazy associations
    private UUID sourceAccountId;
    private String sourceAccountUsername;
    private UUID destinationAccountId;
    private String destinationAccountUsername;
    private UUID externalDestinationId;
    private String externalDestinationName;

    public static FileFlowDto from(FileFlow flow) {
        FileFlowDtoBuilder b = FileFlowDto.builder()
                .id(flow.getId())
                .name(flow.getName())
                .description(flow.getDescription())
                .filenamePattern(flow.getFilenamePattern())
                .sourcePath(flow.getSourcePath())
                .destinationPath(flow.getDestinationPath())
                .direction(flow.getDirection())
                .priority(flow.getPriority())
                .active(flow.isActive())
                .steps(flow.getSteps())
                .partnerId(flow.getPartnerId())
                .createdAt(flow.getCreatedAt())
                .updatedAt(flow.getUpdatedAt());

        try {
            if (flow.getSourceAccount() != null) {
                b.sourceAccountId(flow.getSourceAccount().getId());
                b.sourceAccountUsername(flow.getSourceAccount().getUsername());
            }
        } catch (Exception ignored) { }

        try {
            if (flow.getDestinationAccount() != null) {
                b.destinationAccountId(flow.getDestinationAccount().getId());
                b.destinationAccountUsername(flow.getDestinationAccount().getUsername());
            }
        } catch (Exception ignored) { }

        try {
            if (flow.getExternalDestination() != null) {
                b.externalDestinationId(flow.getExternalDestination().getId());
                b.externalDestinationName(flow.getExternalDestination().getName());
            }
        } catch (Exception ignored) { }

        return b.build();
    }
}
