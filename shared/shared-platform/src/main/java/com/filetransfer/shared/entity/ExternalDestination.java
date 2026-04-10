package com.filetransfer.shared.entity;

import com.filetransfer.shared.enums.ExternalDestinationType;
import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

/**
 * An external system that files can be forwarded to: SFTP server, FTP server, or Kafka topic.
 */
@Entity
@Table(name = "external_destinations")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ExternalDestination extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ExternalDestinationType type;

    // SFTP / FTP fields
    private String host;
    private Integer port;
    private String username;
    /** AES-encrypted password stored at rest */
    private String encryptedPassword;
    private String remotePath;

    // Kafka fields
    private String kafkaTopic;
    private String kafkaBootstrapServers;
    /** JSON Kafka producer properties override */
    @Column(columnDefinition = "TEXT")
    private String kafkaProducerConfig;

    @Column(name = "auth_type", length = 20)
    private String authType;  // NONE, BASIC, BEARER, CLIENT_CERT

    @Column(name = "ssh_key_alias", length = 100)
    private String sshKeyAlias;

    @Column(name = "cert_alias", length = 100)
    private String certAlias;

    @Column(name = "passive_mode")
    private boolean passiveMode;

    @Column(name = "bearer_token")
    private String bearerToken;

    @Column(name = "protocol_config", columnDefinition = "TEXT")
    private String protocolConfig;  // JSON for additional protocol-specific fields

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

}
