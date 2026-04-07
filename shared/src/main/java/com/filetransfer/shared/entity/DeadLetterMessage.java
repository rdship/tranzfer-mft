package com.filetransfer.shared.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Persisted dead-letter message.
 *
 * When a RabbitMQ consumer exhausts its retry budget the message lands
 * on the DLQ, gets persisted here, and can be inspected / retried / discarded
 * via the DLQ Management API.
 */
@Entity
@Table(name = "dead_letter_messages", indexes = {
    @Index(name = "idx_dlm_status", columnList = "status"),
    @Index(name = "idx_dlm_original_queue", columnList = "originalQueue"),
    @Index(name = "idx_dlm_created_at", columnList = "createdAt")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DeadLetterMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Queue the message was originally consumed from (e.g. sftp.account.events) */
    @Column(nullable = false)
    private String originalQueue;

    /** Exchange the message was published to */
    @Column(nullable = false)
    private String originalExchange;

    /** Routing key used when the message was published */
    @Column(nullable = false)
    private String routingKey;

    /** Serialised message payload (JSON) */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String payload;

    /** Error message from the last failed processing attempt */
    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    /** Number of times this message has been retried */
    @Column(nullable = false)
    @Builder.Default
    private int retryCount = 0;

    /** Maximum retries before the message was dead-lettered */
    @Column(nullable = false)
    @Builder.Default
    private int maxRetries = 3;

    /** PENDING = awaiting action, RETRIED = re-published, DISCARDED = manually removed */
    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private Status status = Status.PENDING;

    @Column(nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    private Instant retriedAt;

    public enum Status {
        PENDING, RETRIED, DISCARDED
    }
}
