package com.filetransfer.license.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "license_activations")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class LicenseActivation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "license_record_id", nullable = false)
    private LicenseRecord licenseRecord;

    @Column(nullable = false)
    private String serviceType;

    @Column(nullable = false)
    private String hostId;

    @Column(nullable = false)
    @Builder.Default
    private Instant activatedAt = Instant.now();

    private Instant lastCheckIn;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;
}
