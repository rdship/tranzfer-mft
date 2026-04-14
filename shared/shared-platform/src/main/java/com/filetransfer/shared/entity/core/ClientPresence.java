package com.filetransfer.shared.entity.core;

import com.filetransfer.shared.entity.core.*;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Tracks which MFT clients are currently online and reachable.
 * Clients heartbeat every 30 seconds.
 */
@Entity
@Table(name = "client_presence", indexes = {
    @Index(name = "idx_presence_username", columnList = "username", unique = true)
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ClientPresence {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Transfer account username */
    @Column(unique = true, nullable = false)
    private String username;

    /** Client's reachable host/IP for direct P2P connections */
    @Column(nullable = false)
    private String host;

    /** Client's P2P receiver port */
    @Column(nullable = false)
    private int port;

    /** Protocol the client's receiver speaks (HTTP) */
    @Builder.Default
    private String protocol = "HTTP";

    /** Client version */
    private String clientVersion;

    /** Last heartbeat time — if > 2 min old, consider offline */
    @Column(nullable = false)
    @Builder.Default
    private Instant lastSeen = Instant.now();

    @Column(nullable = false)
    @Builder.Default
    private boolean online = true;
}
