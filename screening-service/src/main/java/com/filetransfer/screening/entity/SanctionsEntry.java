package com.filetransfer.screening.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "sanctions_entries", indexes = {
    @Index(name = "idx_sanc_name_lower", columnList = "nameLower"),
    @Index(name = "idx_sanc_source", columnList = "source")
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class SanctionsEntry {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    /** Original name from sanctions list */
    @Column(nullable = false) private String name;
    /** Lowercased + normalized for fuzzy matching */
    @Column(nullable = false) private String nameLower;
    /** OFAC_SDN, EU_SANCTIONS, UN_SANCTIONS, UK_SANCTIONS, CUSTOM */
    @Column(nullable = false, length = 20) private String source;
    /** Entity type: individual, organization, vessel, aircraft */
    private String entityType;
    /** Country/program */
    private String program;
    /** Alternate names (semicolon-separated) */
    @Column(columnDefinition = "TEXT") private String aliases;
    /** Additional identifiers (passport, tax ID, etc.) */
    @Column(columnDefinition = "TEXT") private String identifiers;
    @Column(nullable = false, updatable = false) @Builder.Default private Instant loadedAt = Instant.now();
}
