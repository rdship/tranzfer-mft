package com.filetransfer.shared.entity;

import com.filetransfer.shared.entity.core.*;

import com.filetransfer.shared.enums.ServiceType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.Map;
import java.util.UUID;

/**
 * Dynamic server instance configuration. Admins create/modify these at runtime
 * to spin up or reconfigure server instances without redeployment.
 */
@Entity
@Table(name = "server_configs")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ServerConfig extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ServiceType serviceType;

    @Column(nullable = false)
    private String host;

    @Column(nullable = false)
    private int port;

    /** Optional: NONE, HAPROXY, NGINX */
    @Builder.Default
    private String proxyType = "NONE";

    private String proxyHost;
    private Integer proxyPort;

    /** Extra key/value config (e.g., maxConnections, timeoutSeconds) */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, String> properties;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "folder_template_id")
    private FolderTemplate folderTemplate;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

}
