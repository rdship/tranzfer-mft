package com.filetransfer.shared.entity;

import com.filetransfer.shared.enums.AuthType;
import com.filetransfer.shared.enums.DeliveryProtocol;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * A delivery endpoint represents an external client communication configuration.
 * Supports SFTP, FTP, FTPS, HTTP, HTTPS, and API protocols.
 * Used by FILE_DELIVERY flow steps to deliver files to external systems.
 */
@Entity
@Table(name = "delivery_endpoints")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class DeliveryEndpoint {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(unique = true, nullable = false)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DeliveryProtocol protocol;

    // --- Connection ---

    @Column(length = 500)
    private String host;

    private Integer port;

    /** Remote directory for file protocols, base URL path for HTTP */
    @Column(length = 1000)
    private String basePath;

    // --- Authentication ---

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    @Builder.Default
    private AuthType authType = AuthType.NONE;

    private String username;

    /** AES-encrypted password */
    @Column(columnDefinition = "TEXT")
    private String encryptedPassword;

    /** PEM-format SSH private key (encrypted at rest) */
    @Column(columnDefinition = "TEXT")
    private String sshPrivateKey;

    /** Bearer token (encrypted at rest) */
    @Column(columnDefinition = "TEXT")
    private String bearerToken;

    /** Header name for API key auth, e.g. "X-API-Key" */
    private String apiKeyHeader;

    /** API key value (encrypted at rest) */
    @Column(columnDefinition = "TEXT")
    private String apiKeyValue;

    // --- HTTP-specific ---

    /** HTTP method: POST, PUT */
    @Column(length = 10)
    private String httpMethod;

    /** Custom HTTP headers as JSON map */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, String> httpHeaders;

    /** Content type for HTTP requests, e.g. "application/octet-stream" */
    @Column(length = 100)
    private String contentType;

    // --- TLS ---

    @Column(nullable = false)
    @Builder.Default
    private boolean tlsEnabled = false;

    /** Trust all certificates (for self-signed certs in dev/test) */
    @Column(nullable = false)
    @Builder.Default
    private boolean tlsTrustAll = false;

    // --- Proxy (optional — user decides per endpoint) ---

    /** Whether to route this delivery through a proxy. Default: false (direct connection) */
    @Column(nullable = false)
    @Builder.Default
    private boolean proxyEnabled = false;

    /** Proxy type: DMZ (platform DMZ proxy), HTTP, SOCKS5. Only used when proxyEnabled=true */
    @Column(length = 20)
    private String proxyType;

    /** Proxy hostname, e.g. "dmz-proxy" for the platform DMZ proxy */
    @Column(length = 500)
    private String proxyHost;

    /** Proxy port, e.g. 8088 for DMZ management API */
    private Integer proxyPort;

    // --- Resilience ---

    @Builder.Default
    private int connectionTimeoutMs = 30000;

    @Builder.Default
    private int readTimeoutMs = 60000;

    @Builder.Default
    private int retryCount = 3;

    @Builder.Default
    private int retryDelayMs = 5000;

    // --- AS2/AS4 partnership link ---

    /** Direct link to AS2 partnership config. Used when protocol is AS2 or AS4. */
    @Column(name = "as2_partnership_id")
    private UUID as2PartnershipId;

    // --- Metadata ---

    /** Comma-separated tags for categorization and filtering */
    @Column(length = 500)
    private String tags;

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
