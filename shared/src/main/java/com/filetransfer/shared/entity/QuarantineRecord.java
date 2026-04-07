package com.filetransfer.shared.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Tracks files quarantined by antivirus or DLP scanning.
 * Files remain quarantined until an admin reviews and either releases or deletes them.
 */
@Entity
@Table(name = "quarantine_records", indexes = {
    @Index(name = "idx_quarantine_status", columnList = "status"),
    @Index(name = "idx_quarantine_track_id", columnList = "trackId"),
    @Index(name = "idx_quarantine_detected_at", columnList = "quarantinedAt")
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class QuarantineRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Track ID of the original file transfer */
    @Column(length = 64)
    private String trackId;

    /** Original filename */
    @Column(nullable = false)
    private String filename;

    /** Account that uploaded the file */
    private String accountUsername;

    /** Original storage path before quarantine */
    @Column(nullable = false)
    private String originalPath;

    /** Path in quarantine storage */
    @Column(nullable = false)
    private String quarantinePath;

    /** Why the file was quarantined (e.g. "Malware detected: Eicar-Test-Signature") */
    @Column(nullable = false, length = 500)
    private String reason;

    /** Specific threat detected (virus name, DLP finding, etc.) */
    private String detectedThreat;

    /** Source of detection: AV, DLP, MANUAL */
    @Column(length = 20)
    @Builder.Default
    private String detectionSource = "AV";

    /** QUARANTINED, RELEASED, DELETED */
    @Column(nullable = false, length = 20)
    @Builder.Default
    private String status = "QUARANTINED";

    /** File size in bytes */
    private Long fileSizeBytes;

    /** SHA-256 checksum of the quarantined file */
    @Column(length = 64)
    private String sha256;

    @Column(nullable = false, updatable = false)
    @Builder.Default
    private Instant quarantinedAt = Instant.now();

    /** Admin who reviewed this quarantine record */
    private String reviewedBy;

    /** When the review happened */
    private Instant reviewedAt;

    /** Notes from the reviewer */
    @Column(length = 1000)
    private String reviewNotes;
}
