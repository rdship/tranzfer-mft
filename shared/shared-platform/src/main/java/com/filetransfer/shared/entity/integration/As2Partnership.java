package com.filetransfer.shared.entity.integration;

import com.filetransfer.shared.entity.core.*;
import com.filetransfer.shared.entity.transfer.*;

import com.filetransfer.shared.entity.core.Auditable;

import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import lombok.*;

import java.util.UUID;

/**
 * AS2/AS4 Trading Partner configuration.
 * Defines the partnership parameters needed for B2B message exchange
 * using the AS2 (RFC 4130) or AS4 (OASIS ebMS3) protocols.
 */
@Entity
@Table(name = "as2_partnerships")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class As2Partnership extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @NotBlank
    @Column(nullable = false)
    private String partnerName;

    /** Remote partner's AS2-ID (used in AS2-To header) */
    @NotBlank
    @Column(name = "partner_as2_id", nullable = false, unique = true)
    private String partnerAs2Id;

    /** Our AS2-ID (used in AS2-From header) */
    @NotBlank
    @Column(name = "our_as2_id", nullable = false)
    private String ourAs2Id;

    /** Partner's receiving endpoint URL */
    @NotBlank
    @Size(max = 2000)
    @Column(nullable = false, length = 2000)
    private String endpointUrl;

    /** Partner's X.509 certificate in PEM format (for encryption and signature verification) */
    @Column(columnDefinition = "TEXT")
    private String partnerCertificate;

    /** Signing algorithm: SHA1, SHA256, SHA384, SHA512 */
    @Size(max = 50)
    @Column(length = 50)
    @Builder.Default
    private String signingAlgorithm = "SHA256";

    /** Encryption algorithm: 3DES, AES128, AES192, AES256 */
    @Size(max = 50)
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
    @Size(max = 2000)
    @Column(length = 2000)
    private String mdnUrl;

    /** Whether to compress the payload (ZLIB) before signing/encrypting */
    @Builder.Default
    private boolean compressionEnabled = false;

    /** Protocol: AS2 or AS4 */
    @NotBlank
    @Size(max = 10)
    @Column(nullable = false, length = 10)
    @Builder.Default
    private String protocol = "AS2";

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

}
