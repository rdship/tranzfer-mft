package com.filetransfer.shared.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A named group of DMZ proxy instances sharing a network scope and security posture.
 *
 * <p>Proxy instances self-register in Redis at startup via {@code PROXY_GROUP_NAME} env var.
 * This entity stores the group <em>definition</em> (policies, metadata, routing priority).
 * Live membership (which instances are currently running) comes from Redis presence keys,
 * not from this table.
 *
 * <p>Default groups seeded by V43 migration:
 * <ul>
 *   <li>{@code internal} — corporate/private network, all protocols, TLS optional
 *   <li>{@code external} — internet-facing, SFTP/FTP/AS2/HTTPS only, TLS required
 * </ul>
 */
@Entity
@Table(name = "proxy_groups", indexes = {
        @Index(name = "idx_pg_type",   columnList = "type"),
        @Index(name = "idx_pg_active", columnList = "active")
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ProxyGroup {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true, length = 100)
    private String name;

    /**
     * Network scope: {@code INTERNAL}, {@code EXTERNAL}, {@code PARTNER},
     * {@code CLOUD}, or {@code CUSTOM}.
     */
    @Column(nullable = false, length = 30)
    @Builder.Default
    private String type = "INTERNAL";

    @Column(length = 500)
    private String description;

    /** Protocols permitted through this group's proxies (e.g. ["SFTP","FTP","AS2"]). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    @Builder.Default
    private List<String> allowedProtocols = List.of("SFTP", "FTP", "AS2", "HTTPS");

    /**
     * Whether TLS is mandatory for connections through this group.
     * Typically {@code true} for external-facing groups.
     */
    @Builder.Default
    private boolean tlsRequired = false;

    /**
     * Comma-separated CIDR ranges restricting source IPs (empty = any source allowed).
     * Example: {@code 10.0.0.0/8,192.168.0.0/16} for internal-only traffic.
     */
    @Column(length = 1000)
    private String trustedCidrs;

    @Builder.Default
    private int maxConnectionsPerInstance = 1000;

    /**
     * Routing priority — lower value = preferred when multiple groups can serve a request.
     * {@code internal} defaults to 10, {@code external} to 20.
     */
    @Builder.Default
    private int routingPriority = 100;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Builder.Default
    private boolean active = true;

    @Column(nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Builder.Default
    private Instant updatedAt = Instant.now();

    @PreUpdate
    void touch() { this.updatedAt = Instant.now(); }
}
