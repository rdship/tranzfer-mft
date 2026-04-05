package com.filetransfer.shared.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * AS2/AS4 Trading Partner configuration.
 * Defines the partnership parameters needed for B2B message exchange
 * using the AS2 (RFC 4130) or AS4 (OASIS ebMS3) protocols.
 */
@Entity
@Table(name = "as2_partnerships")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class As2Partnership {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String partnerName;

    /** Remote partner's AS2-ID (used in AS2-To header) */
    @Column(nullable = false, unique = true)
    private String partnerAs2Id;

    /** Our AS2-ID (used in AS2-From header) */
    @Column(nullable = false)
    private String ourAs2Id;

    /** Partner's receiving endpoint URL */
    @Column(nullable = false, length = 2000)
    private String endpointUrl;

    /** Partner's X.509 certificate in PEM format (for encryption and signature verification) */
    @Column(columnDefinition = "TEXT")
    private String partnerCertificate;

    /** Signing algorithm: SHA1, SHA256, SHA384, SHA512 */
    @Column(length = 50)
    @Builder.Default
    private String signingAlgorithm = "SHA256";

    /** Encryption algorithm: 3DES, AES128, AES192, AES256 */
    @Column(length = 50)
    @Builder.Default
    private String encryptionAlgorithm = "AES256";

    /** Whether to request a Message Disposition Notification (receipt) */
    @Builder.Default
    private boolean mdnRequired = true;

    /** Whether MDN should be returned asynchronously */
    @Builder.Default
    private boolean mdnAsync = false;

    /** URL for async MDN delivery (only used when mdnAsync=true) */
    @Column(length = 2000)
    private String mdnUrl;

    /** Whether to compress the payload (ZLIB) before signing/encrypting */
    @Builder.Default
    private boolean compressionEnabled = false;

    /** Protocol: AS2 or AS4 */
    @Column(nullable = false, length = 10)
    @Builder.Default
    private String protocol = "AS2";

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
}
