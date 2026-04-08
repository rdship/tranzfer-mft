package com.filetransfer.sentinel.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "sentinel_rules")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class SentinelRule {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 50)
    private String analyzer;

    @Column(nullable = false, unique = true, length = 100)
    private String name;

    @Column(length = 500)
    private String description;

    @Column(nullable = false)
    @Builder.Default
    private Boolean enabled = true;

    @Column(nullable = false, length = 20)
    @Builder.Default
    private String severity = "MEDIUM";

    @Column(name = "threshold_value")
    private Double thresholdValue;

    @Column(name = "window_minutes")
    @Builder.Default
    private Integer windowMinutes = 60;

    @Column(name = "cooldown_minutes")
    @Builder.Default
    private Integer cooldownMinutes = 30;

    @Column(columnDefinition = "TEXT")
    private String config;

    @Column(name = "last_triggered")
    private Instant lastTriggered;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    public boolean isInCooldown() {
        if (lastTriggered == null || cooldownMinutes == null) return false;
        return Instant.now().isBefore(lastTriggered.plusSeconds(cooldownMinutes * 60L));
    }
}
