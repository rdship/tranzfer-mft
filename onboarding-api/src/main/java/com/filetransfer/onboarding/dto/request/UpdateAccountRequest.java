package com.filetransfer.onboarding.dto.request;

import lombok.Data;

import java.util.Map;

@Data
public class UpdateAccountRequest {
    private Boolean active;
    private String newPassword;
    private String publicKey;
    private Map<String, Boolean> permissions;
    private String serverInstance;

    /** Per-user QoS overrides (null fields = keep current value). */
    private QoSConfig qos;

    @Data
    public static class QoSConfig {
        private Long uploadBytesPerSecond;
        private Long downloadBytesPerSecond;
        private Integer maxConcurrentSessions;
        private Integer priority;
        private Integer burstAllowancePercent;
    }
}
