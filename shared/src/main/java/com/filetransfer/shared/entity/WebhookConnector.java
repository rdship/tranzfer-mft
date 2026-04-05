package com.filetransfer.shared.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Configurable external connector for event notifications.
 * Supports: ServiceNow, PagerDuty, Slack, Microsoft Teams, OpsGenie, Email, Generic Webhook.
 */
@Entity
@Table(name = "webhook_connectors")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class WebhookConnector extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(unique = true, nullable = false)
    private String name;

    /**
     * SERVICENOW, PAGERDUTY, SLACK, TEAMS, OPSGENIE, EMAIL, WEBHOOK
     */
    @Column(nullable = false, length = 20)
    private String type;

    /** Target URL (webhook URL, ServiceNow instance, etc.) */
    @Column(nullable = false)
    private String url;

    /** Auth token / API key / Bearer token */
    private String authToken;

    /** ServiceNow: username for basic auth */
    private String username;

    /** ServiceNow: password for basic auth */
    private String password;

    /** Which event types trigger this connector */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    @Builder.Default
    private List<String> triggerEvents = List.of("TRANSFER_FAILED", "AI_BLOCKED", "INTEGRITY_FAIL",
            "FLOW_FAIL", "QUARANTINE", "ANOMALY_DETECTED", "LICENSE_EXPIRED");

    /** Minimum severity to trigger: LOW, MEDIUM, HIGH, CRITICAL */
    @Builder.Default
    private String minSeverity = "HIGH";

    /** Additional headers as JSON */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, String> customHeaders;

    /** ServiceNow-specific fields */
    private String snowInstanceId;
    private String snowAssignmentGroup;
    private String snowCategory;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    private Instant lastTriggered;
    private int totalNotifications;

}
