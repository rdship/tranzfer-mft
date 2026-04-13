package com.filetransfer.shared.entity.core;

import com.filetransfer.shared.enums.ServiceType;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "service_registrations")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ServiceRegistration {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(unique = true, nullable = false)
    private String serviceInstanceId;

    @Column(nullable = false)
    private String clusterId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ServiceType serviceType;

    @Column(nullable = false)
    private String host;

    @Column(nullable = false)
    private int controlPort;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    @Column(nullable = false)
    @Builder.Default
    private Instant lastHeartbeat = Instant.now();

    @Column(nullable = false, updatable = false)
    @Builder.Default
    private Instant registeredAt = Instant.now();
}
