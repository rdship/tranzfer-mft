package com.filetransfer.shared.entity.transfer;

import com.filetransfer.shared.entity.core.*;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.Immutable;

import java.time.Instant;
import java.util.UUID;

/**
 * Read-only JPA entity mapped to the {@code transfer_activity_view} materialized view.
 * Pre-joins all Activity Monitor data — zero joins at query time, sub-millisecond response.
 *
 * <p>Refresh: REFRESH MATERIALIZED VIEW CONCURRENTLY transfer_activity_view;
 * Triggered by scheduler every 30s or on-demand via API.
 */
@Data
@Entity
@Immutable
@Table(name = "transfer_activity_view")
public class TransferActivityView {

    @Id
    private UUID id;

    @Column(name = "track_id")
    private String trackId;

    @Column(name = "original_filename")
    private String originalFilename;

    @Enumerated(EnumType.STRING)
    private com.filetransfer.shared.enums.FileTransferStatus status;

    @Column(name = "file_size_bytes")
    private Long fileSizeBytes;

    @Column(name = "source_checksum")
    private String sourceChecksum;

    @Column(name = "destination_checksum")
    private String destinationChecksum;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "retry_count")
    private int retryCount;

    @Column(name = "uploaded_at")
    private Instant uploadedAt;

    @Column(name = "routed_at")
    private Instant routedAt;

    @Column(name = "downloaded_at")
    private Instant downloadedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "source_file_path")
    private String sourceFilePath;

    @Column(name = "destination_file_path")
    private String destinationFilePath;

    @Column(name = "source_account_id")
    private UUID sourceAccountId;

    @Column(name = "destination_account_id")
    private UUID destinationAccountId;

    @Column(name = "flow_id")
    private UUID flowId;

    // Pre-joined source
    @Column(name = "source_username")
    private String sourceUsername;

    @Column(name = "source_protocol")
    private String sourceProtocol;

    @Column(name = "source_partner_name")
    private String sourcePartnerName;

    // Pre-joined destination
    @Column(name = "dest_username")
    private String destUsername;

    @Column(name = "dest_protocol")
    private String destProtocol;

    @Column(name = "dest_partner_name")
    private String destPartnerName;

    // Pre-joined external destination
    @Column(name = "external_dest_name")
    private String externalDestName;

    // Pre-joined flow
    @Column(name = "flow_name")
    private String flowName;

    @Column(name = "flow_status")
    private String flowStatus;

    // Pre-joined encryption
    @Column(name = "encryption_option")
    private String encryptionOption;

    // Pre-computed
    @Column(name = "integrity_status")
    private String integrityStatus;

    @Column(name = "duration_ms")
    private Double durationMs;
}
