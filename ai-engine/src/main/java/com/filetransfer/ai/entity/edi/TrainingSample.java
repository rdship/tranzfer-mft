package com.filetransfer.ai.entity.edi;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * A single training sample: an EDI input paired with its expected output.
 * Multiple samples for the same source→target conversion improve map accuracy.
 */
@Entity
@Table(name = "edi_training_samples", indexes = {
        @Index(name = "idx_ets_source_format", columnList = "sourceFormat,sourceType"),
        @Index(name = "idx_ets_target_format", columnList = "targetFormat"),
        @Index(name = "idx_ets_partner", columnList = "partnerId"),
        @Index(name = "idx_ets_map_key", columnList = "sourceFormat,sourceType,targetFormat,partnerId")
})
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class TrainingSample {

    @Id
    @Column(updatable = false, nullable = false)
    private UUID id;

    /** EDI format of the input (X12, EDIFACT, HL7, SWIFT_MT, etc.) */
    @Column(nullable = false, length = 30)
    private String sourceFormat;

    /** Transaction type within the format (850, 810, ORDERS, INVOIC, etc.) */
    @Column(length = 30)
    private String sourceType;

    /** Format version (005010, D01B, etc.) */
    @Column(length = 20)
    private String sourceVersion;

    /** Target output format (JSON, XML, CSV, X12, EDIFACT, CUSTOM, etc.) */
    @Column(nullable = false, length = 30)
    private String targetFormat;

    /** Target transaction type if converting between EDI standards */
    @Column(length = 30)
    private String targetType;

    /** Partner ID — allows building partner-specific maps */
    @Column(length = 100)
    private String partnerId;

    /** The raw EDI input content */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String inputContent;

    /** The expected output content */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String outputContent;

    /** Human-provided notes about this sample */
    @Column(columnDefinition = "TEXT")
    private String notes;

    /** Quality score assigned during validation (0-100) */
    private int qualityScore;

    /** Whether this sample has been validated by a human */
    private boolean validated;

    /** Number of times this sample contributed to training */
    private int usageCount;

    private Instant createdAt;
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID();
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }

    /** Composite key for grouping samples into the same map */
    public String getMapKey() {
        String partner = (partnerId != null && !partnerId.isBlank()) ? partnerId : "_default";
        return sourceFormat + ":" + (sourceType != null ? sourceType : "*")
                + "→" + targetFormat + ":" + (targetType != null ? targetType : "*")
                + "@" + partner;
    }
}
