package com.filetransfer.shared.entity;

import com.filetransfer.shared.enums.ExternalDestinationType;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * An external system that files can be forwarded to: SFTP server, FTP server, or Kafka topic.
 */
@Entity
@Table(name = "external_destinations")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ExternalDestination {

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

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    @Column(nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
