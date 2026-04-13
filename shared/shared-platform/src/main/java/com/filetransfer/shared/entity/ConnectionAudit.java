package com.filetransfer.shared.entity;

import com.filetransfer.shared.entity.core.*;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

/**
 * Tracks every inbound connection attempt — which user connected,
 * from where, and whether they were routed to TranzFer or legacy.
 */
@Entity
@Table(name = "connection_audits", indexes = {
    @Index(name = "idx_ca_username", columnList = "username"),
    @Index(name = "idx_ca_partner", columnList = "partner_id"),
    @Index(name = "idx_ca_routed", columnList = "routed_to"),
    @Index(name = "idx_ca_ts", columnList = "connected_at")
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ConnectionAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "username", nullable = false, length = 100)
    private String username;

    @Column(name = "source_ip", length = 45)
    private String sourceIp;

    @Column(name = "protocol", length = 16)
    private String protocol;  // SFTP, FTP, FTP_WEB

    @Column(name = "routed_to", nullable = false, length = 16)
    private String routedTo;  // PLATFORM or LEGACY

    @Column(name = "legacy_host", length = 200)
    private String legacyHost;  // If routed to legacy, which host

    @Column(name = "partner_id")
    private UUID partnerId;

    @Column(name = "partner_name", length = 200)
    private String partnerName;

    @Column(name = "success")
    @Builder.Default
    private boolean success = true;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    @Column(name = "connected_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant connectedAt = Instant.now();
}
