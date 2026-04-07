package com.filetransfer.notification.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.*;

import java.util.Map;

/**
 * Request body for sending a test notification.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TestNotificationRequest {
    @NotBlank
    private String channel;
    @NotBlank
    private String recipient;
    private String eventType;
    private String subject;
    private String body;
    private Map<String, Object> variables;
}
