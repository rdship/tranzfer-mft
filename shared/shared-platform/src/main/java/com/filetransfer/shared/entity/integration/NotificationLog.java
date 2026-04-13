package com.filetransfer.shared.entity.integration;

import com.filetransfer.shared.entity.core.*;
import com.filetransfer.shared.entity.transfer.*;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable log of every notification dispatch attempt.
 * Tracks success/failure, retries, and error details for auditing.
 */
@Entity
@Table(name = "notification_logs", indexes = {
    @Index(name = "idx_notif_log_event_type", columnList = "eventType"),
    @Index(name = "idx_notif_log_status", columnList = "status"),
    @Index(name = "idx_notif_log_sent_at", columnList = "sentAt"),
    @Index(name = "idx_notif_log_channel", columnList = "channel")
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class NotificationLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** The event type that triggered this notification */
    @Column(nullable = false)
    private String eventType;

    /** Channel used: EMAIL, WEBHOOK, SMS */
    @Column(nullable = false, length = 20)
    private String channel;

    /** Recipient address (email, webhook URL, phone number) */
    @Column(nullable = false)
    private String recipient;

    /** Subject line (for email) */
    private String subject;

    /** Delivery status: SENT, FAILED, PENDING, RETRYING */
    @Column(nullable = false, length = 20)
    @Builder.Default
    private String status = "PENDING";

    /** Timestamp when the notification was sent (or last attempted) */
    @Builder.Default
    private Instant sentAt = Instant.now();

    /** Error message if delivery failed */
    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    /** Number of retry attempts */
    @Builder.Default
    private int retryCount = 0;

    /** Reference to the rule that triggered this notification */
    @Column(name = "rule_id")
    private UUID ruleId;

    /** Track ID linking to the originating transfer/event */
    @Column(length = 50)
    private String trackId;

    /** Timestamp when the log entry was created */
    @Column(nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
