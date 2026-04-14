package com.filetransfer.shared.entity.integration;

import com.filetransfer.shared.entity.core.*;
import com.filetransfer.shared.entity.transfer.*;

import com.filetransfer.shared.entity.core.Auditable;

import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import lombok.*;

import java.util.UUID;

/**
 * Template for notification messages.
 * Supports variable substitution in subject and body using ${variable} syntax.
 */
@Entity
@Table(name = "notification_templates", indexes = {
    @Index(name = "idx_notif_template_event_type", columnList = "eventType"),
    @Index(name = "idx_notif_template_channel", columnList = "channel")
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class NotificationTemplate extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Unique template name, e.g. "transfer-completed-email" */
    @NotBlank
    @Column(unique = true, nullable = false)
    private String name;

    /** Notification channel: EMAIL, WEBHOOK, SMS */
    @NotBlank
    @Size(max = 20)
    @Column(nullable = false, length = 20)
    private String channel;

    /** Subject line template (for email); may contain ${variable} placeholders */
    private String subjectTemplate;

    /** Body template; may contain ${variable} placeholders */
    @NotBlank
    @Column(columnDefinition = "TEXT", nullable = false)
    private String bodyTemplate;

    /** Event type this template is associated with, e.g. "transfer.completed" */
    @NotBlank
    @Column(nullable = false)
    private String eventType;

    /** Whether this template is active */
    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;
}
