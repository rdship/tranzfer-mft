package com.filetransfer.onboarding.dto.response;

import com.filetransfer.shared.enums.Protocol;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Data
@Builder
public class AccountResponse {
    private UUID id;
    private Protocol protocol;
    private String username;
    private String homeDir;
    private Map<String, Boolean> permissions;
    private boolean active;
    private String serverInstance;
    private Instant createdAt;
    // Connection info
    private String connectionInstructions;
}
