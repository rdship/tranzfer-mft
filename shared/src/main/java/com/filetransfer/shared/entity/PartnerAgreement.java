package com.filetransfer.shared.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Formal SLA agreement with a partner.
 * Defines expected delivery windows, volumes, and breach thresholds.
 */
@Entity @Table(name = "partner_agreements") @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PartnerAgreement extends Auditable {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(unique = true, nullable = false) private String name;
    private String description;

    /** Partner account this agreement applies to */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id")
    private TransferAccount account;

    /** Partner this agreement belongs to (optional) */
    @Column(name = "partner_id")
    private UUID partnerId;

    /** Expected delivery window: earliest hour (UTC) */
    private int expectedDeliveryStartHour;
    /** Expected delivery window: latest hour (UTC) — breach if not received by this */
    private int expectedDeliveryEndHour;

    /** Expected days of week (e.g. ["MONDAY","TUESDAY","WEDNESDAY","THURSDAY","FRIDAY"]) */
    @JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition = "jsonb")
    private List<String> expectedDays;

    /** Minimum expected files per delivery window */
    @Builder.Default private int minFilesPerWindow = 1;
    /** Maximum acceptable error rate (0.0-1.0) */
    @Builder.Default private double maxErrorRate = 0.05;
    /** Minutes of grace after delivery window before triggering breach */
    @Builder.Default private int gracePeriodMinutes = 30;

    /** Action on breach: ALERT, ALERT_AND_ESCALATE */
    @Builder.Default private String breachAction = "ALERT";

    @Builder.Default private boolean active = true;
    /** Total breaches recorded */
    @Builder.Default private int totalBreaches = 0;
    private Instant lastBreachAt;
}
