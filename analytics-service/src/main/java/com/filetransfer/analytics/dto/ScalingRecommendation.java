package com.filetransfer.analytics.dto;

import lombok.*;
import java.time.Instant;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ScalingRecommendation {
    private String serviceType;
    private double currentLoad;
    private double predictedLoad24h;
    private int recommendedReplicas;
    private int currentReplicas;
    private double confidence;
    private String reason;
    private String trend; // INCREASING, DECREASING, STABLE, SPIKE_DETECTED
    private Instant peakExpectedAt;
}
