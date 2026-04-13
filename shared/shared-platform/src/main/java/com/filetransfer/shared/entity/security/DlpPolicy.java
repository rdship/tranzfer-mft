package com.filetransfer.shared.entity.security;

import com.filetransfer.shared.entity.core.*;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Configurable DLP (Data Loss Prevention) policy.
 * Each policy contains a set of patterns that match sensitive data types
 * and an action to take when matches are found.
 */
@Entity
@Table(name = "dlp_policies", indexes = {
    @Index(name = "idx_dlp_policy_active", columnList = "active"),
    @Index(name = "idx_dlp_policy_name", columnList = "name", unique = true)
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class DlpPolicy {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Human-readable policy name */
    @Column(nullable = false, unique = true, length = 100)
    private String name;

    /** Description of what this policy detects */
    @Column(length = 500)
    private String description;

    /**
     * List of pattern definitions stored as JSONB.
     * Each pattern: { "type": "PCI_CREDIT_CARD", "regex": "\\d{4}...", "label": "Visa" }
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<PatternDefinition> patterns;

    /** Action to take: BLOCK, FLAG, LOG */
    @Column(nullable = false, length = 10)
    @Builder.Default
    private String action = "BLOCK";

    /** Whether this policy is active */
    @Builder.Default
    private boolean active = true;

    @Column(nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    private Instant updatedAt;

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class PatternDefinition {
        /** Sensitivity type: PCI_CREDIT_CARD, PII_SSN, PII_EMAIL, PII_PHONE, PCI_IBAN, CUSTOM */
        private String type;
        /** Regular expression pattern */
        private String regex;
        /** Human-readable label */
        private String label;
    }
}
