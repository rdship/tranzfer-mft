package com.filetransfer.shared.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Defines a file processing pipeline (flow).
 * A flow is a named sequence of steps applied to files matching certain criteria.
 * Example: "Partner-Inbound" → decompress → decrypt(PGP) → route-to-ftp
 */
@Entity
@Table(name = "file_flows")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class FileFlow {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(unique = true, nullable = false)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    /** Regex pattern for matching filenames (null = match all) */
    private String filenamePattern;

    /** Source account (optional — null means any account can trigger) */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_account_id")
    private TransferAccount sourceAccount;

    /** Source folder path (relative to user home, e.g. "/inbox") */
    private String sourcePath;

    /**
     * Ordered list of processing steps as JSON.
     * Each step: {"type":"ENCRYPT|DECRYPT|COMPRESS|DECOMPRESS|RENAME|ROUTE","config":{...}}
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private List<FlowStep> steps;

    /** Destination account (final delivery target) */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "destination_account_id")
    private TransferAccount destinationAccount;

    /** Destination path (relative to dest account home) */
    private String destinationPath;

    /** External destination (instead of internal account) */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "external_destination_id")
    private ExternalDestination externalDestination;

    /** Priority — lower number = evaluated first */
    @Builder.Default
    private int priority = 100;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    @Column(nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();

    @PreUpdate
    void onUpdate() { this.updatedAt = Instant.now(); }

    /**
     * A single step in the processing pipeline.
     */
    @Data @NoArgsConstructor @AllArgsConstructor @Builder
    public static class FlowStep {
        /** Step type: ENCRYPT_PGP, DECRYPT_PGP, ENCRYPT_AES, DECRYPT_AES,
         *  COMPRESS_GZIP, DECOMPRESS_GZIP, COMPRESS_ZIP, DECOMPRESS_ZIP,
         *  RENAME, SCREEN, EXECUTE_SCRIPT, MAILBOX, FILE_DELIVERY, ROUTE */
        private String type;

        /** Step-specific config (e.g. {"keyId":"uuid"} for encryption,
         *  {"pattern":"${filename}.gz"} for rename) */
        private java.util.Map<String, String> config;

        /** Order within the flow (0-based) */
        private int order;
    }
}
