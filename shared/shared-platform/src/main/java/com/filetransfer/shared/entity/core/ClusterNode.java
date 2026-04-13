package com.filetransfer.shared.entity.core;

import com.filetransfer.shared.enums.ClusterCommunicationMode;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "cluster_nodes")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ClusterNode {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(unique = true, nullable = false)
    private String clusterId;

    private String displayName;

    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private ClusterCommunicationMode communicationMode = ClusterCommunicationMode.WITHIN_CLUSTER;

    private String region;

    private String environment;

    private String apiEndpoint;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    @Column(nullable = false, updatable = false)
    @Builder.Default
    private Instant registeredAt = Instant.now();

    @Column(nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();

    @PreUpdate
    private void preUpdate() {
        this.updatedAt = Instant.now();
    }
}
