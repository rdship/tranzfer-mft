package com.filetransfer.onboarding.dto.request;

import com.filetransfer.shared.enums.Protocol;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.Map;

@Data
public class CreateAccountRequest {
    @NotNull
    private Protocol protocol;

    @NotBlank
    @Size(min = 3, max = 64)
    private String username;

    @NotBlank
    @Size(min = 8)
    private String password;

    // Optional SSH public key for SFTP key-based auth
    private String publicKey;

    // Optional: {"read": true, "write": true, "delete": false}
    private Map<String, Boolean> permissions;

    // Optional: assign to a specific SFTP server instance (e.g. "sftp-1", "sftp-2")
    // Null means the account can connect to any instance
    private String serverInstance;

    /** Per-user QoS overrides (null fields inherit from partner SLA tier). */
    private QoSConfig qos;

    @Data
    public static class QoSConfig {
        /** Upload speed limit in bytes/second (0 = unlimited). */
        private Long uploadBytesPerSecond;
        /** Download speed limit in bytes/second (0 = unlimited). */
        private Long downloadBytesPerSecond;
        /** Maximum concurrent sessions. */
        private Integer maxConcurrentSessions;
        /** Priority level: 1=highest, 10=lowest. */
        private Integer priority;
        /** Burst allowance percent above sustained rate. */
        private Integer burstAllowancePercent;
    }
}
