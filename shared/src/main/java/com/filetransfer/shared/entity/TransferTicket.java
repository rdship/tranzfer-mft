package com.filetransfer.shared.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * A transfer ticket authorizes a direct peer-to-peer file transfer
 * between two remote MFT clients. The server issues the ticket,
 * both clients validate against it, and the server records the outcome.
 */
@Entity
@Table(name = "transfer_tickets", indexes = {
    @Index(name = "idx_ticket_id", columnList = "ticketId", unique = true),
    @Index(name = "idx_ticket_track", columnList = "trackId")
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class TransferTicket {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Unique ticket ID shared between sender and receiver */
    @Column(unique = true, nullable = false, length = 36)
    private String ticketId;

    /** 12-char tracking ID for audit trail */
    @Column(nullable = false, length = 12)
    private String trackId;

    /** Sender account */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sender_account_id", nullable = false)
    private TransferAccount senderAccount;

    /** Receiver account */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "receiver_account_id", nullable = false)
    private TransferAccount receiverAccount;

    /** Filename being transferred */
    @Column(nullable = false)
    private String filename;

    /** File size in bytes */
    private Long fileSizeBytes;

    /** SHA-256 of the file (set by sender at ticket creation) */
    @Column(length = 64)
    private String sha256Checksum;

    /** Receiver's registered host:port for direct connection */
    private String receiverHost;
    private Integer receiverPort;

    /** Sender's registered host:port */
    private String senderHost;
    private Integer senderPort;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private TicketStatus status = TicketStatus.PENDING;

    /** One-time auth token for the sender to present to the receiver */
    @Column(nullable = false, length = 64)
    private String senderToken;

    /** Ticket expires after this time */
    @Column(nullable = false)
    private Instant expiresAt;

    @Column(nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    private Instant completedAt;

    private String errorMessage;

    public enum TicketStatus { PENDING, IN_PROGRESS, COMPLETED, FAILED, EXPIRED }
}
