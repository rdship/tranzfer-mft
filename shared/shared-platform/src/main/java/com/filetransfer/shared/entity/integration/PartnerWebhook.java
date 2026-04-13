package com.filetransfer.shared.entity.integration;

import com.filetransfer.shared.entity.core.*;
import com.filetransfer.shared.entity.transfer.*;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Partner-configurable webhook endpoint.
 *
 * <p>When a flow execution completes or fails, {@code PartnerWebhookDispatcher} fires an
 * HMAC-SHA256-signed HTTP POST to every active webhook whose {@code events} list includes
 * the event type ({@code FLOW_COMPLETED} or {@code FLOW_FAILED}).
 *
 * <p>The {@code secret} field is optional. If present, the dispatcher adds an
 * {@code X-Webhook-Signature: sha256=<hex>} header so receivers can verify authenticity.
 */
@Entity
@Table(name = "partner_webhooks", indexes = {
        @Index(name = "idx_pw_active",  columnList = "active"),
        @Index(name = "idx_pw_partner", columnList = "partnerName")
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PartnerWebhook {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String partnerName;

    @Column(nullable = false, length = 2048)
    private String url;

    /** Optional HMAC-SHA256 signing secret. Null = no signature header added. */
    private String secret;

    /** Event types that trigger this webhook (e.g. FLOW_COMPLETED, FLOW_FAILED). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    @Builder.Default
    private List<String> events = List.of("FLOW_COMPLETED", "FLOW_FAILED");

    @Builder.Default
    private boolean active = true;

    private String description;

    @Column(nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    private Instant lastTriggered;

    @Builder.Default
    private int totalCalls = 0;

    @Builder.Default
    private int failedCalls = 0;
}
