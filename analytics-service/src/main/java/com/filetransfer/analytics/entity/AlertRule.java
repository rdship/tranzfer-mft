package com.filetransfer.analytics.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "alert_rules")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AlertRule {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(nullable = false) private String name;
    private String serviceType;
    @Column(nullable = false) private String metric;
    @Column(nullable = false) private String operator;
    @Column(nullable = false) private Double threshold;
    @Builder.Default private int windowMinutes = 60;
    @Builder.Default private boolean enabled = true;
    private Instant lastTriggered;
    @Column(nullable = false, updatable = false) @Builder.Default private Instant createdAt = Instant.now();
}
