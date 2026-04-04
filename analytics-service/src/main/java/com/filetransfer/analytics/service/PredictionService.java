package com.filetransfer.analytics.service;

import com.filetransfer.analytics.dto.ScalingRecommendation;
import com.filetransfer.analytics.entity.MetricSnapshot;
import com.filetransfer.analytics.repository.MetricSnapshotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class PredictionService {

    private final MetricSnapshotRepository snapshotRepository;

    // Transfers per hour that one replica can handle, by service type
    private static final Map<String, Integer> CAPACITY_PER_REPLICA = Map.of(
            "SFTP", 500,
            "FTP", 1000,
            "FTP_WEB", 2000,
            "GATEWAY", 3000,
            "ENCRYPTION", 5000
    );

    public List<ScalingRecommendation> predictAll() {
        List<String> serviceTypes = List.of("SFTP", "FTP", "FTP_WEB", "GATEWAY", "ENCRYPTION");
        return serviceTypes.stream()
                .map(this::predictForService)
                .collect(Collectors.toList());
    }

    public ScalingRecommendation predictForService(String serviceType) {
        List<MetricSnapshot> snapshots = snapshotRepository
                .findTop48ByServiceTypeOrderBySnapshotTimeDesc(serviceType);

        if (snapshots.size() < 3) {
            return ScalingRecommendation.builder()
                    .serviceType(serviceType)
                    .currentLoad(0.0)
                    .predictedLoad24h(0.0)
                    .recommendedReplicas(1)
                    .currentReplicas(1)
                    .confidence(0.1)
                    .trend("STABLE")
                    .reason("Insufficient data for prediction (< 3 hourly snapshots)")
                    .build();
        }

        // Sort ascending by time
        snapshots.sort(Comparator.comparing(MetricSnapshot::getSnapshotTime));

        // Extract transfer rates (transfers per hour)
        double[] x = new double[snapshots.size()];
        double[] y = new double[snapshots.size()];
        for (int i = 0; i < snapshots.size(); i++) {
            x[i] = i;
            y[i] = snapshots.get(i).getTotalTransfers();
        }

        // Linear regression: y = m*x + b
        double[] regression = linearRegression(x, y);
        double slope = regression[0];
        double intercept = regression[1];
        double rSquared = calculateRSquared(x, y, slope, intercept);

        // Predict 24 hours ahead
        double predicted24h = Math.max(0, slope * (snapshots.size() + 24) + intercept);
        double currentLoad = snapshots.get(snapshots.size() - 1).getTotalTransfers();

        // Detect spikes: if any value > 3x average in last 48 snapshots
        double avg = Arrays.stream(y).average().orElse(0.0);
        boolean spikeDetected = Arrays.stream(y).anyMatch(v -> v > avg * 3 && avg > 0);

        // Trend detection
        String trend;
        if (Math.abs(slope) < avg * 0.05) {
            trend = "STABLE";
        } else if (spikeDetected) {
            trend = "SPIKE_DETECTED";
        } else if (slope > 0) {
            trend = "INCREASING";
        } else {
            trend = "DECREASING";
        }

        // Recommend replicas
        int capacityPerReplica = CAPACITY_PER_REPLICA.getOrDefault(serviceType, 500);
        int recommendedReplicas = (int) Math.min(20, Math.max(1, Math.ceil(predicted24h / capacityPerReplica)));

        return ScalingRecommendation.builder()
                .serviceType(serviceType)
                .currentLoad(currentLoad)
                .predictedLoad24h(predicted24h)
                .recommendedReplicas(recommendedReplicas)
                .currentReplicas(1)
                .confidence(Math.min(1.0, rSquared))
                .trend(trend)
                .reason(buildReason(trend, slope, avg, predicted24h, capacityPerReplica))
                .peakExpectedAt(slope > 0 ? Instant.now().plus(24, ChronoUnit.HOURS) : null)
                .build();
    }

    private double[] linearRegression(double[] x, double[] y) {
        int n = x.length;
        double sumX = 0, sumY = 0, sumXY = 0, sumX2 = 0;
        for (int i = 0; i < n; i++) {
            sumX += x[i]; sumY += y[i]; sumXY += x[i] * y[i]; sumX2 += x[i] * x[i];
        }
        double slope = (n * sumXY - sumX * sumY) / (n * sumX2 - sumX * sumX);
        double intercept = (sumY - slope * sumX) / n;
        return new double[]{slope, intercept};
    }

    private double calculateRSquared(double[] x, double[] y, double slope, double intercept) {
        double yMean = Arrays.stream(y).average().orElse(0.0);
        double ssTot = Arrays.stream(y).map(v -> (v - yMean) * (v - yMean)).sum();
        double ssRes = 0;
        for (int i = 0; i < x.length; i++) {
            double predicted = slope * x[i] + intercept;
            ssRes += (y[i] - predicted) * (y[i] - predicted);
        }
        return ssTot == 0 ? 1.0 : 1.0 - (ssRes / ssTot);
    }

    private String buildReason(String trend, double slope, double avg, double predicted, int capacity) {
        return switch (trend) {
            case "INCREASING" -> String.format("Traffic increasing at %.1f transfers/hour. Predicted %.0f/hr in 24h (capacity %d/replica).", slope, predicted, capacity);
            case "DECREASING" -> String.format("Traffic decreasing at %.1f transfers/hour. Scale-down may be possible.", Math.abs(slope));
            case "SPIKE_DETECTED" -> String.format("Traffic spike detected (peak > 3x avg of %.0f). Pre-scale recommended.", avg);
            default -> String.format("Traffic stable around %.0f transfers/hour.", avg);
        };
    }
}
