package com.filetransfer.notification.dto;

import lombok.*;

import java.time.Instant;
import java.util.Map;

/**
 * Generic notification event received from RabbitMQ.
 * All platform events are normalized into this structure for processing.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationEvent {
    private String eventType;
    private String trackId;
    private String account;
    private String filename;
    private String protocol;
    private String service;
    private String severity;
    private Map<String, Object> payload;
    @Builder.Default
    private Instant timestamp = Instant.now();
}
