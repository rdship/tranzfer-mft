package com.filetransfer.sentinel.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "sentinel_health_scores")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class HealthScore {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "overall_score", nullable = false)
    private Integer overallScore;

    @Column(name = "infrastructure_score", nullable = false)
    private Integer infrastructureScore;

    @Column(name = "data_score", nullable = false)
    private Integer dataScore;

    @Column(name = "security_score", nullable = false)
    private Integer securityScore;

    @Column(columnDefinition = "TEXT")
    private String details;

    @Column(name = "recorded_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant recordedAt = Instant.now();
}
