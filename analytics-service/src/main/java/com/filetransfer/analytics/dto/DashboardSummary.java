package com.filetransfer.analytics.dto;

import lombok.*;
import java.util.List;
import java.util.Map;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class DashboardSummary {
    private long totalTransfersToday;
    private long totalTransfersLastHour;
    private double successRateToday;
    private double totalGbToday;
    private int activeConnections;
    private String topProtocol;
    private List<ActiveAlert> alerts;
    private List<ScalingRecommendation> scalingRecommendations;
    private List<TimeSeriesPoint> transfersPerHour;
    private Map<String, Long> transfersByProtocol;

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class ActiveAlert {
        private String ruleName;
        private String serviceType;
        private String metric;
        private double currentValue;
        private double threshold;
        private String severity; // WARN, CRITICAL
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class TimeSeriesPoint {
        private String hour;
        private long transfers;
        private double successRate;
        private long bytes;
    }
}
