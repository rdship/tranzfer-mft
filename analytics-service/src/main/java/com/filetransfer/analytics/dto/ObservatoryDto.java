package com.filetransfer.analytics.dto;

import lombok.*;
import java.time.Instant;
import java.util.List;

public class ObservatoryDto {

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class HeatmapCell {
        /** 0 = today, N = N days ago */
        private int dayOffset;
        /** 0-23 UTC hour */
        private int hour;
        private long count;
        private long failedCount;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class ServiceNode {
        /** Lowercase-hyphenated service ID, e.g. "ftp-web", "ai-engine" */
        private String id;
        private String label;
        /** INGRESS | PROCESSING | DELIVERY | PLATFORM */
        private String tier;
        /** UP | DEGRADED | DOWN | UNKNOWN */
        private String health;
        private long transfersLastHour;
        private double errorRate;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class DomainGroup {
        private String domainName;
        private long totalCount;
        private long completedCount;
        private long failedCount;
        private long processingCount;
        /** 0.0 – 1.0 */
        private double successRate;
        private Instant lastActivityAt;
        private String topError;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class ObservatoryData {
        private List<HeatmapCell> heatmap;
        private List<ServiceNode> serviceGraph;
        private List<DomainGroup> domainGroups;
        private Instant generatedAt;
    }

    /** Per-step-type latency summary over a time window. */
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class StepSummary {
        private String stepType;
        private double avgMs;
        private double p95Ms;
        private long minMs;
        private long maxMs;
        private long totalCalls;
        private long failedCalls;
        /** failedCalls / totalCalls, 0.0–1.0 */
        private double failureRate;
    }

    /** One cell in the step-type × hour-of-day latency heatmap. */
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class StepHeatmapCell {
        private String stepType;
        /** 0-23 UTC hour of day */
        private int hourOfDay;
        private double avgMs;
        private long callCount;
    }

    /** Full step-latency data response. */
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class StepLatencyData {
        private List<StepSummary> summary;
        private List<StepHeatmapCell> heatmap;
        /** Window size that was queried */
        private int hours;
        private Instant generatedAt;
    }
}
