package com.filetransfer.onboarding.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.UUID;

@Data
public class CreateFolderMappingRequest {

    @NotNull
    private UUID sourceAccountId;

    @NotBlank
    private String sourcePath;          // e.g. /inbox

    @NotNull
    private UUID destinationAccountId;

    @NotBlank
    private String destinationPath;     // e.g. /outbox

    /** Java regex for filename matching. Null / blank = match all files. */
    private String filenamePattern;
}
