package com.filetransfer.analytics.dto;

import lombok.*;
import java.util.List;
import java.util.Map;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class TransferMetricsSummary {
    private String period;
    private String serviceType;
    private long totalTransfers;
    private double successRate;
    private double totalGigabytes;
    private double avgLatencyMs;
    private double p95LatencyMs;
    private Map<String, Long> breakdownByProtocol;
    private List<DashboardSummary.TimeSeriesPoint> breakdownByHour;
}
