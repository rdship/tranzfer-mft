package com.filetransfer.shared.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "sftp_server_instances")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SftpServerInstance {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "instance_id", unique = true, nullable = false, length = 64)
    private String instanceId;

    @Column(nullable = false)
    private String name;

    private String description;

    // Internal connection (Docker service name / direct host)
    @Column(name = "internal_host", nullable = false)
    private String internalHost;

    @Column(name = "internal_port", nullable = false)
    @Builder.Default
    private int internalPort = 2222;

    // External connection (what clients connect to)
    @Column(name = "external_host")
    private String externalHost;

    @Column(name = "external_port")
    private Integer externalPort;

    // Reverse proxy configuration
    @Column(name = "use_proxy", nullable = false)
    @Builder.Default
    private boolean useProxy = false;

    @Column(name = "proxy_host")
    private String proxyHost;

    @Column(name = "proxy_port")
    private Integer proxyPort;

    @Builder.Default
    private int maxConnections = 500;

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
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    /**
     * Returns the host:port that clients should use to connect.
     * If a proxy is configured, returns proxy address; otherwise external address.
     */
    public String getClientConnectionHost() {
        if (useProxy && proxyHost != null) return proxyHost;
        if (externalHost != null) return externalHost;
        return internalHost;
    }

    public int getClientConnectionPort() {
        if (useProxy && proxyPort != null) return proxyPort;
        if (externalPort != null) return externalPort;
        return internalPort;
    }
}
