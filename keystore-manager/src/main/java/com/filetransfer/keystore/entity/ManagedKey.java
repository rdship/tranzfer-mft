package com.filetransfer.keystore.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

/**
 * A centrally managed key or certificate.
 * All services request keys from here instead of managing their own.
 *
 * Types: SSH_HOST_KEY, SSH_USER_KEY, PGP_PUBLIC, PGP_PRIVATE, PGP_KEYPAIR,
 *        AES_SYMMETRIC, TLS_CERTIFICATE, TLS_PRIVATE_KEY, TLS_KEYSTORE,
 *        HMAC_SECRET, API_KEY
 */
@Entity @Table(name = "managed_keys", indexes = {
    @Index(name = "idx_mk_alias", columnList = "alias", unique = true),
    @Index(name = "idx_mk_type", columnList = "keyType"),
    @Index(name = "idx_mk_owner", columnList = "ownerService")
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ManagedKey {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;

    /** Unique human-readable alias (e.g. "sftp-host-key", "partner-acme-pgp-pub") */
    @Column(unique = true, nullable = false) private String alias;

    /** SSH_HOST_KEY, SSH_USER_KEY, PGP_PUBLIC, PGP_PRIVATE, PGP_KEYPAIR,
     *  AES_SYMMETRIC, TLS_CERTIFICATE, TLS_PRIVATE_KEY, TLS_KEYSTORE,
     *  HMAC_SECRET, API_KEY */
    @Column(nullable = false, length = 30) private String keyType;

    /** Algorithm: RSA-2048, RSA-4096, EC-P256, AES-256, ED25519, PGP, X509 */
    private String algorithm;

    /** The key/cert material (PEM-encoded for keys/certs, Base64 for binary, hex for symmetric) */
    @Column(columnDefinition = "TEXT", nullable = false) private String keyMaterial;

    /** Public part only (for keypairs — so other services can get pubkey without the private) */
    @Column(columnDefinition = "TEXT") private String publicKeyMaterial;

    /** Fingerprint (SHA-256 of the key) */
    @Column(length = 64) private String fingerprint;

    /** Which service owns/created this key */
    private String ownerService;

    /** Which partner/account this key is for (optional) */
    private String partnerAccount;

    /** Purpose description */
    private String description;

    /** Key size in bits */
    private Integer keySizeBits;

    /** For certificates: subject DN */
    private String subjectDn;
    /** For certificates: issuer DN */
    private String issuerDn;
    /** When the key/cert becomes valid */
    private Instant validFrom;
    /** When the key/cert expires */
    private Instant expiresAt;

    /** Is this key currently active? */
    @Builder.Default private boolean active = true;

    /** Has this key been rotated? Points to the new key alias */
    private String rotatedToAlias;

    @Column(nullable = false, updatable = false) @Builder.Default private Instant createdAt = Instant.now();
    @Builder.Default private Instant updatedAt = Instant.now();

    @PreUpdate void onUpdate() { this.updatedAt = Instant.now(); }
}
