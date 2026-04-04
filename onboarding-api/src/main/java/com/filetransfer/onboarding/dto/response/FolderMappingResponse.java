package com.filetransfer.onboarding.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
public class FolderMappingResponse {
    private UUID id;
    private UUID sourceAccountId;
    private String sourceUsername;
    private String sourcePath;
    private UUID destinationAccountId;
    private String destinationUsername;
    private String destinationPath;
    private String filenamePattern;
    private boolean active;
    private Instant createdAt;
}
