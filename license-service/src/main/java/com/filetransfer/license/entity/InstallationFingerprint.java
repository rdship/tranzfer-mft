package com.filetransfer.license.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "installation_fingerprints")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class InstallationFingerprint {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(unique = true, nullable = false)
    private String fingerprint;

    @Column(nullable = false)
    private Instant trialStarted;

    @Column(nullable = false)
    private Instant trialExpires;

    private String customerId;
    private String customerName;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    @Column(nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
