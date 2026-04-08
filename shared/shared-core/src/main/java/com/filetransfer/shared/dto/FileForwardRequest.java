package com.filetransfer.shared.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FileForwardRequest {
    private UUID recordId;
    private String destinationUsername;
    private String destinationAbsolutePath;
    /** Base64-encoded file bytes */
    private String fileContentBase64;
    private String originalFilename;
}
