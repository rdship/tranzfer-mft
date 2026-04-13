package com.filetransfer.shared.entity.core;

import com.filetransfer.shared.entity.Auditable;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.List;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "security_profiles")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class SecurityProfile extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(unique = true, nullable = false)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    /** SSH or TLS */
    @Column(nullable = false)
    @Builder.Default
    private String type = "SSH";

    // SSH-specific
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", name = "ssh_ciphers")
    private List<String> sshCiphers;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", name = "ssh_macs")
    private List<String> sshMacs;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", name = "kex_algorithms")
    private List<String> kexAlgorithms;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", name = "host_key_algorithms")
    private List<String> hostKeyAlgorithms;

    // TLS-specific
    private String tlsMinVersion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", name = "tls_ciphers")
    private List<String> tlsCiphers;

    @Builder.Default
    private boolean clientAuthRequired = false;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    // ── Algorithm whitelists ─────────────────────────────────────────────

    /** Allowed SSH ciphers — AEAD and CTR modes only, no CBC */
    public static final Set<String> ALLOWED_SSH_CIPHERS = Set.of(
            "aes256-gcm@openssh.com", "aes128-gcm@openssh.com",
            "chacha20-poly1305@openssh.com",
            "aes256-ctr", "aes192-ctr", "aes128-ctr"
    );

    /** Allowed SSH MAC algorithms — ETM (encrypt-then-MAC) preferred */
    public static final Set<String> ALLOWED_SSH_MACS = Set.of(
            "hmac-sha2-512-etm@openssh.com", "hmac-sha2-256-etm@openssh.com",
            "umac-128-etm@openssh.com",
            "hmac-sha2-512", "hmac-sha2-256"
    );

    /** Allowed SSH key exchange algorithms — ECDH and strong DH groups only */
    public static final Set<String> ALLOWED_SSH_KEX = Set.of(
            "curve25519-sha256", "curve25519-sha256@libssh.org",
            "ecdh-sha2-nistp521", "ecdh-sha2-nistp384", "ecdh-sha2-nistp256",
            "diffie-hellman-group18-sha512", "diffie-hellman-group16-sha512",
            "diffie-hellman-group-exchange-sha256",
            // Quantum-safe hybrid KEX (available with BouncyCastle PQC / OpenSSH 9.x+)
            "mlkem768x25519-sha256", "sntrup761x25519-sha512@openssh.com"
    );

    /** Allowed SSH host key algorithms */
    public static final Set<String> ALLOWED_HOST_KEY_ALGORITHMS = Set.of(
            "ssh-ed25519", "ecdsa-sha2-nistp256", "ecdsa-sha2-nistp384", "ecdsa-sha2-nistp521",
            "rsa-sha2-512", "rsa-sha2-256",
            // Quantum-safe signature algorithms (BouncyCastle PQC / OpenSSH 9.x+)
            "ssh-mldsaed25519", "ssh-mldsa65"
    );

    /** Allowed TLS cipher suites — TLS 1.3 + strong TLS 1.2 AEAD suites */
    public static final Set<String> ALLOWED_TLS_CIPHERS = Set.of(
            // TLS 1.3 (mandatory)
            "TLS_AES_256_GCM_SHA384", "TLS_AES_128_GCM_SHA256",
            "TLS_CHACHA20_POLY1305_SHA256",
            // TLS 1.2 ECDHE+AEAD only
            "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384",
            "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256",
            "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
            "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
            "TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256",
            "TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256"
    );

    /** Blocked cipher keywords — any suite containing these tokens is rejected */
    public static final Set<String> BLOCKED_CIPHER_KEYWORDS = Set.of(
            "NULL", "EXPORT", "DES", "3DES", "RC4", "RC2", "MD5",
            "anon", "EMPTY", "IDEA", "SEED", "CAMELLIA", "ARIA"
    );

    /** Allowed AS2 signing algorithms */
    public static final Set<String> ALLOWED_AS2_SIGNING = Set.of(
            "SHA256", "SHA384", "SHA512"
    );

    /** Allowed AS2 encryption algorithms */
    public static final Set<String> ALLOWED_AS2_ENCRYPTION = Set.of(
            "AES256", "AES192", "AES128"
    );

    /** Deprecated AS2 algorithms — warn but allow temporarily */
    public static final Set<String> DEPRECATED_AS2_ALGORITHMS = Set.of(
            "3DES", "SHA1"
    );

    /** Allowed TLS minimum versions */
    public static final Set<String> ALLOWED_TLS_VERSIONS = Set.of(
            "TLSv1.2", "TLSv1.3"
    );

    /** Returns true if the given cipher name contains a blocked keyword */
    public static boolean isBlockedCipher(String cipherName) {
        if (cipherName == null) return true;
        String upper = cipherName.toUpperCase();
        return BLOCKED_CIPHER_KEYWORDS.stream().anyMatch(upper::contains);
    }

}
