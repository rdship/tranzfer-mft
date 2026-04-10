package com.filetransfer.ai.entity.edi;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * A trained conversion map — the output of the training engine.
 * Maps are versioned: each training run produces a new version.
 * The converter fetches the latest active version for a given map key.
 */
@Entity
@Table(name = "edi_conversion_maps", indexes = {
        @Index(name = "idx_ecm_map_key", columnList = "mapKey"),
        @Index(name = "idx_ecm_active", columnList = "mapKey,active"),
        @Index(name = "idx_ecm_partner", columnList = "partnerId")
})
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ConversionMap {

    @Id
    @Column(updatable = false, nullable = false)
    private UUID id;

    /** Composite key: sourceFormat:sourceType→targetFormat:targetType@partnerId */
    @Column(nullable = false, length = 200)
    private String mapKey;

    /** Human-readable name (e.g., "X12 850 → JSON for ACME Corp") */
    @Column(length = 300)
    private String name;

    @Column(nullable = false, length = 30)
    private String sourceFormat;

    @Column(length = 30)
    private String sourceType;

    @Column(nullable = false, length = 30)
    private String targetFormat;

    @Column(length = 30)
    private String targetType;

    @Column(length = 100)
    private String partnerId;

    /** ID of the standard map this was cloned from (null for trained maps) */
    @Column(length = 100)
    private String parentMapId;

    /** Map lifecycle status: DRAFT, ACTIVE, INACTIVE, DEPRECATED */
    @Column(length = 20)
    private String status;

    /** Map version — increments with each training run */
    private int version;

    /** Whether this is the active version (only one per mapKey) */
    private boolean active;

    /** Overall confidence score 0-100 */
    private int confidence;

    /** Number of training samples used to build this map */
    private int sampleCount;

    /** Number of field mappings in this map */
    private int fieldMappingCount;

    /**
     * The field mappings serialized as JSON.
     * Each entry: { sourceField, targetField, transform, transformParams, confidence, reasoning }
     */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String fieldMappingsJson;

    /** Generated executable mapping code (JSONata-like) */
    @Column(columnDefinition = "TEXT")
    private String generatedCode;

    /** Unmapped source fields (JSON array) */
    @Column(columnDefinition = "TEXT")
    private String unmappedSourceFieldsJson;

    /** Unmapped target fields (JSON array) */
    @Column(columnDefinition = "TEXT")
    private String unmappedTargetFieldsJson;

    /** Training session that produced this map */
    @Column(length = 36)
    private String trainingSessionId;

    /** Accuracy on held-out test samples (0-100), null if not tested */
    private Integer testAccuracy;

    /** Number of times this map was used for conversion */
    private long usageCount;

    /** Last time this map was used */
    private Instant lastUsedAt;

    /** Running average confidence across all conversions using this map */
    private Double avgConfidence;

    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID();
        createdAt = Instant.now();
    }

    public void incrementUsage() {
        this.usageCount++;
        this.lastUsedAt = Instant.now();
    }
}
