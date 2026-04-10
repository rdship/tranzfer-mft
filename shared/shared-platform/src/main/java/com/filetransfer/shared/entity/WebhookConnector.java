package com.filetransfer.shared.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.*;
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

    @NotBlank
    @Column(unique = true, nullable = false)
    private String name;

    /**
     * SERVICENOW, PAGERDUTY, SLACK, TEAMS, OPSGENIE, EMAIL, WEBHOOK
     */
    @NotBlank
    @Size(max = 20)
    @Column(nullable = false, length = 20)
    private String type;

    /** Target URL (webhook URL, ServiceNow instance, etc.) */
    @NotBlank
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

    @Size(max = 100)
    @Column(name = "channel", length = 100)
    private String channel;  // Slack channel

    @Size(max = 500)
    @Column(name = "api_key", length = 500)
    private String apiKey;  // OpsGenie API key

    @Size(max = 10)
    @Column(name = "region", length = 10)
    private String region;  // OpsGenie region (US, EU)

    @Size(max = 10)
    @Column(name = "priority", length = 10)
    private String priority;  // OpsGenie priority (P1-P5)

    @Size(max = 20)
    @Column(name = "auth_type", length = 20)
    private String authType;  // NONE, BEARER, BASIC (for webhook type)

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    private Instant lastTriggered;
    private int totalNotifications;

}
