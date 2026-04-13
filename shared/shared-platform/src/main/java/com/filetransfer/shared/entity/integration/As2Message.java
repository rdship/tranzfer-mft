package com.filetransfer.shared.entity.integration;

import com.filetransfer.shared.entity.core.*;
import com.filetransfer.shared.entity.transfer.*;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Tracks AS2/AS4 message exchanges for audit and retry purposes.
 * Each outbound or inbound AS2 message gets a record here.
 */
@Entity
@Table(name = "as2_messages")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class As2Message {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** AS2 Message-ID header value (globally unique) */
    @Column(nullable = false, unique = true, length = 500)
    private String messageId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "partnership_id", nullable = false)
    private As2Partnership partnership;

    /** OUTBOUND or INBOUND */
    @Column(nullable = false, length = 10)
    @Builder.Default
    private String direction = "OUTBOUND";

    private String filename;

    private Long fileSize;

    /** PENDING, SENT, ACKNOWLEDGED, FAILED, RETRYING */
    @Column(nullable = false, length = 50)
    @Builder.Default
    private String status = "PENDING";

    /** Whether MDN (receipt) has been received */
    @Builder.Default
    private boolean mdnReceived = false;

    /** MDN disposition: processed, failed/failure, etc. */
    @Column(length = 100)
    private String mdnStatus;

    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    /** Link to platform track ID for correlation */
    @Column(length = 50)
    private String trackId;

    @Column(nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();

    @PreUpdate
    void onUpdate() { this.updatedAt = Instant.now(); }
}
