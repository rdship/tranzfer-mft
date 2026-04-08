package com.filetransfer.sentinel.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "sentinel_correlation_groups")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class CorrelationGroup {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(length = 500)
    private String title;

    @Column(name = "root_cause", length = 500)
    private String rootCause;

    @Column(name = "finding_count")
    @Builder.Default
    private Integer findingCount = 0;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
