package com.filetransfer.shared.entity;

import com.filetransfer.shared.entity.core.*;

import com.filetransfer.shared.enums.Protocol;
import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import lombok.*;

import java.util.UUID;

/**
 * Configuration for a legacy FTP/SFTP server that receives unknown users
 * forwarded from the gateway-service.
 */
@Entity
@Table(name = "legacy_server_configs")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class LegacyServerConfig extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @NotBlank
    @Column(nullable = false)
    private String name;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Protocol protocol;

    @NotBlank
    @Column(nullable = false)
    private String host;

    @Min(1)
    @Max(65535)
    @Column(nullable = false)
    private int port;

    /** Used by gateway to test connectivity; NOT used to authenticate on behalf of users */
    private String healthCheckUser;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

}
