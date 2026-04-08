package com.filetransfer.shared.entity;

import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

/**
 * Unified partner entity for TranzFer MFT.
 * Represents an external or internal organization exchanging files through the platform.
 */
@Entity
@Table(name = "partners")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Partner extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String companyName;

    private String displayName;

    @Column(unique = true, nullable = false, length = 100)
    private String slug;

    @Column(length = 100)
    private String industry;

    @Column(length = 500)
    private String website;

    @Column(length = 1000)
    private String logoUrl;

    /** INTERNAL, EXTERNAL, VENDOR, CLIENT */
    @Column(nullable = false, length = 30)
    @Builder.Default
    private String partnerType = "EXTERNAL";

    /** PENDING, ACTIVE, SUSPENDED, OFFBOARDED */
    @Column(nullable = false, length = 30)
    @Builder.Default
    private String status = "PENDING";

    /** SETUP, CREDENTIALS, TESTING, LIVE */
    @Column(length = 30)
    @Builder.Default
    private String onboardingPhase = "SETUP";

    /** JSON array of enabled protocol strings, e.g. ["SFTP","AS2"] */
    @Column(columnDefinition = "jsonb")
    @Builder.Default
    private String protocolsEnabled = "[]";

    @Column(length = 30)
    @Builder.Default
    private String slaTier = "STANDARD";

    @Builder.Default
    private Long maxFileSizeBytes = 536870912L;

    @Builder.Default
    private Integer maxTransfersPerDay = 1000;

    @Builder.Default
    private Integer retentionDays = 90;

    @Column(columnDefinition = "TEXT")
    private String notes;
}
