package com.filetransfer.shared.entity.core;

import com.filetransfer.shared.entity.core.Auditable;

import com.filetransfer.shared.enums.EncryptionAlgorithm;
import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

/**
 * Stores encryption key material for a transfer account.
 * Private keys and symmetric keys are stored encrypted at rest (AES-wrapped).
 */
@Entity
@Table(name = "encryption_keys")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class EncryptionKey extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id")
    private TransferAccount account;

    @Column(nullable = false)
    private String keyName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EncryptionAlgorithm algorithm;

    /** PGP public key (ASCII-armored) */
    @Column(columnDefinition = "TEXT")
    private String publicKey;

    /** PGP private key (ASCII-armored, AES-wrapped at rest) */
    @Column(columnDefinition = "TEXT")
    private String encryptedPrivateKey;

    /** AES-256 symmetric key (Base64, AES-wrapped at rest using master key) */
    @Column(columnDefinition = "TEXT")
    private String encryptedSymmetricKey;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

}
