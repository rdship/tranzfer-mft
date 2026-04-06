package com.filetransfer.ai.entity.edi;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Tracks a natural language mapping correction conversation between a partner and the AI Engine.
 *
 * Lifecycle: ACTIVE → (partner corrects/tests iteratively) → APPROVED | REJECTED | EXPIRED
 *
 * On approval, the corrected field mappings are persisted as a new versioned ConversionMap
 * and the partner's FileFlow is updated with a CONVERT_EDI step.
 */
@Entity
@Table(name = "edi_mapping_correction_sessions", indexes = {
        @Index(name = "idx_mcs_partner", columnList = "partnerId"),
        @Index(name = "idx_mcs_status", columnList = "status"),
        @Index(name = "idx_mcs_map_key", columnList = "mapKey")
})
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class MappingCorrectionSession {

    @Id
    @Column(updatable = false, nullable = false)
    private UUID id;

    /** Partner making the corrections */
    @Column(nullable = false, length = 100)
    private String partnerId;

    /** Conversion path key (e.g., X12:850→JSON:*@partner-abc) */
    @Column(nullable = false, length = 200)
    private String mapKey;

    /** Existing ConversionMap being corrected (null if starting from scratch) */
    private UUID baseMapId;

    /** Version of the base map when this session started */
    @Builder.Default
    private int baseMapVersion = 0;

    /** Session lifecycle state */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private Status status = Status.ACTIVE;

    @Column(nullable = false, length = 30)
    private String sourceFormat;

    @Column(length = 30)
    private String sourceType;

    @Column(nullable = false, length = 30)
    private String targetFormat;

    @Column(length = 30)
    private String targetType;

    /** Working copy of field mappings — evolves with each correction */
    @Column(nullable = false, columnDefinition = "TEXT")
    @Builder.Default
    private String currentFieldMappingsJson = "[]";

    /** JSON array of CorrectionEntry objects tracking each correction round */
    @Column(nullable = false, columnDefinition = "TEXT")
    @Builder.Default
    private String correctionHistory = "[]";

    /** Sample EDI input used for testing */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String sampleInputContent;

    /** Optional expected output provided by partner */
    @Column(columnDefinition = "TEXT")
    private String sampleExpectedOutput;

    /** Most recent test conversion result */
    @Column(columnDefinition = "TEXT")
    private String latestTestOutput;

    /** Structured before/after diff of latest correction */
    @Column(columnDefinition = "TEXT")
    private String latestTestComparison;

    /** Number of corrections applied so far */
    @Builder.Default
    private int correctionCount = 0;

    /** Optional FileFlow to update on approval */
    private UUID flowId;

    private Instant createdAt;
    private Instant updatedAt;
    private Instant expiresAt;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID();
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
        if (expiresAt == null) expiresAt = now.plusSeconds(86400); // 24 hours
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }

    public enum Status {
        ACTIVE, TESTING, APPROVED, REJECTED, EXPIRED
    }
}
