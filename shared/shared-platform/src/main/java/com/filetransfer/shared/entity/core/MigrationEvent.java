package com.filetransfer.shared.entity.core;

import com.filetransfer.shared.entity.core.*;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

/**
 * Immutable audit trail for partner migration lifecycle events.
 */
@Entity
@Table(name = "migration_events", indexes = {
    @Index(name = "idx_me_partner", columnList = "partner_id"),
    @Index(name = "idx_me_created", columnList = "created_at")
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class MigrationEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "partner_id", nullable = false)
    private UUID partnerId;

    @Column(name = "partner_name")
    private String partnerName;

    @Column(name = "event_type", nullable = false, length = 30)
    private String eventType;  // DISCOVERED, ONBOARDED, SHADOW_ENABLED, SHADOW_DISABLED, VERIFICATION_STARTED, VERIFICATION_PASSED, VERIFICATION_FAILED, CUTOVER_COMPLETED, ROLLBACK

    @Column(name = "details", columnDefinition = "TEXT")
    private String details;

    @Column(name = "actor", length = 100)
    private String actor;  // admin email or "system"

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
