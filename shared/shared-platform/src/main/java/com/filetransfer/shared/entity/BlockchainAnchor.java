package com.filetransfer.shared.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name = "blockchain_anchors", indexes = {
    @Index(name = "idx_bc_track", columnList = "trackId")
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class BlockchainAnchor {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(nullable = false, length = 12) private String trackId;
    @Column(nullable = false) private String filename;
    @Column(nullable = false, length = 64) private String sha256;
    /** Merkle root of the batch this anchor belongs to */
    @Column(length = 64) private String merkleRoot;
    /** Chain: INTERNAL (append-only DB), ETHEREUM, POLYGON */
    @Builder.Default private String chain = "INTERNAL";
    /** Transaction hash on external chain (if applicable) */
    private String txHash;
    /** Block number */
    private Long blockNumber;
    /** Cryptographic proof for independent verification */
    @Column(columnDefinition = "TEXT") private String proof;
    @Column(nullable = false, updatable = false) @Builder.Default private Instant anchoredAt = Instant.now();
}
