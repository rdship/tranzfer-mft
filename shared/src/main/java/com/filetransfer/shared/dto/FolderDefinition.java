package com.filetransfer.shared.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FolderDefinition {
    private String path;
    private String description;
    private Map<String, String> metadata;
}
