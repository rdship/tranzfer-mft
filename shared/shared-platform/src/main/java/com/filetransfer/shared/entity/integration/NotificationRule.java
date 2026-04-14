package com.filetransfer.shared.entity.integration;

import com.filetransfer.shared.entity.core.*;
import com.filetransfer.shared.entity.transfer.*;

import com.filetransfer.shared.entity.core.Auditable;

import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Rule that matches events to notification channels and recipients.
 * When an event matches the eventTypePattern, a notification is dispatched
 * to the specified channel/recipients using the linked template.
 */
@Entity
@Table(name = "notification_rules", indexes = {
    @Index(name = "idx_notif_rule_event_pattern", columnList = "eventTypePattern"),
    @Index(name = "idx_notif_rule_channel", columnList = "channel")
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class NotificationRule extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Unique rule name for identification */
    @NotBlank
    @Column(unique = true, nullable = false)
    private String name;

    /** Event type pattern to match, supports wildcards: "transfer.*", "security.threat.*" */
    @NotBlank
    @Column(nullable = false)
    private String eventTypePattern;

    /** Notification channel: EMAIL, WEBHOOK, SMS */
    @NotBlank
    @Size(max = 20)
    @Column(nullable = false, length = 20)
    private String channel;

    /** List of recipient addresses (emails, webhook URLs, phone numbers) */
    @NotNull
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private List<String> recipients;

    /** Optional reference to a notification template */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "template_id")
    private NotificationTemplate template;

    /** Whether this rule is enabled */
    @Column(nullable = false)
    @Builder.Default
    private boolean enabled = true;

    /** Optional JSON conditions for advanced filtering, e.g. {"severity": "CRITICAL"} */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> conditions;
}
