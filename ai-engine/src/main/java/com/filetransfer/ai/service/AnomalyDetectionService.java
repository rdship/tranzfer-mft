package com.filetransfer.ai.service;

import com.filetransfer.shared.entity.FileTransferRecord;
import com.filetransfer.shared.repository.FileTransferRecordRepository;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Detects anomalies in file transfer patterns.
 * Uses statistical analysis (z-score) on historical data — no LLM needed.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AnomalyDetectionService {

    private final FileTransferRecordRepository transferRecordRepository;

    @Value("${ai.anomaly.threshold-sigma:3.0}")
    private double thresholdSigma;

    @Value("${ai.anomaly.lookback-days:30}")
    private int lookbackDays;

    private final List<Anomaly> activeAnomalies = Collections.synchronizedList(new ArrayList<>());

    @Scheduled(fixedDelay = 300000) // every 5 min
    @SchedulerLock(name = "ai_anomalyDetection_detectAnomalies", lockAtLeastFor = "PT4M", lockAtMostFor = "PT14M")
    public void detectAnomalies() {
        List<FileTransferRecord> records = transferRecordRepository.findAll();
        Instant cutoff = Instant.now().minus(lookbackDays, ChronoUnit.DAYS);
        Instant recentCutoff = Instant.now().minus(1, ChronoUnit.HOURS);

        List<FileTransferRecord> historical = records.stream()
                .filter(r -> r.getUploadedAt() != null && r.getUploadedAt().isAfter(cutoff))
                .collect(Collectors.toList());

        List<FileTransferRecord> recent = records.stream()
                .filter(r -> r.getUploadedAt() != null && r.getUploadedAt().isAfter(recentCutoff))
                .collect(Collectors.toList());

        activeAnomalies.clear();

        // 1. Volume anomaly: is recent hour's volume unusual?
        Map<String, List<FileTransferRecord>> byDay = historical.stream()
                .collect(Collectors.groupingBy(r -> r.getUploadedAt().truncatedTo(ChronoUnit.HOURS).toString()));
        double[] hourlyCounts = byDay.values().stream().mapToDouble(List::size).toArray();

        if (hourlyCounts.length > 5) {
            double mean = Arrays.stream(hourlyCounts).average().orElse(0);
            double stdDev = Math.sqrt(Arrays.stream(hourlyCounts).map(v -> (v - mean) * (v - mean)).average().orElse(0));
            double zScore = stdDev > 0 ? (recent.size() - mean) / stdDev : 0;

            if (Math.abs(zScore) > thresholdSigma) {
                activeAnomalies.add(Anomaly.builder()
                        .type(zScore > 0 ? "VOLUME_SPIKE" : "VOLUME_DROP")
                        .severity(Math.abs(zScore) > 5 ? "CRITICAL" : "HIGH")
                        .description(String.format("Transfer volume is %.1fσ from normal (current: %d, avg: %.0f/hr)",
                                zScore, recent.size(), mean))
                        .currentValue(recent.size())
                        .expectedValue(mean)
                        .zScore(zScore)
                        .detectedAt(Instant.now())
                        .build());
            }
        }

        // 2. File size anomaly per account
        Map<String, List<FileTransferRecord>> byAccount = historical.stream()
                .filter(r -> r.getFileSizeBytes() != null && r.getFolderMapping() != null)
                .collect(Collectors.groupingBy(r -> r.getFolderMapping().getSourceAccount().getUsername()));

        for (FileTransferRecord r : recent) {
            if (r.getFileSizeBytes() == null || r.getFolderMapping() == null) continue;
            String username = r.getFolderMapping().getSourceAccount().getUsername();
            List<FileTransferRecord> acctHistory = byAccount.getOrDefault(username, List.of());
            if (acctHistory.size() < 5) continue;

            double[] sizes = acctHistory.stream().mapToDouble(h -> h.getFileSizeBytes() != null ? h.getFileSizeBytes() : 0).toArray();
            double avgSize = Arrays.stream(sizes).average().orElse(0);
            double sizeStdDev = Math.sqrt(Arrays.stream(sizes).map(v -> (v - avgSize) * (v - avgSize)).average().orElse(0));

            if (sizeStdDev > 0) {
                double sizeZ = (r.getFileSizeBytes() - avgSize) / sizeStdDev;
                if (Math.abs(sizeZ) > thresholdSigma) {
                    activeAnomalies.add(Anomaly.builder()
                            .type("FILE_SIZE_ANOMALY")
                            .severity(sizeZ > 5 ? "CRITICAL" : "HIGH")
                            .description(String.format("File from %s is %.1fσ from normal size (%.0f KB vs avg %.0f KB)",
                                    username, sizeZ, r.getFileSizeBytes() / 1024.0, avgSize / 1024.0))
                            .account(username)
                            .trackId(r.getTrackId())
                            .currentValue(r.getFileSizeBytes())
                            .expectedValue(avgSize)
                            .zScore(sizeZ)
                            .detectedAt(Instant.now())
                            .build());
                }
            }
        }

        // 3. Unusual hour detection
        Map<Integer, Long> hourCounts = historical.stream()
                .collect(Collectors.groupingBy(r -> {
                    java.time.ZonedDateTime zdt = r.getUploadedAt().atZone(java.time.ZoneOffset.UTC);
                    return zdt.getHour();
                }, Collectors.counting()));
        int currentHour = java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC).getHour();
        long currentHourHistorical = hourCounts.getOrDefault(currentHour, 0L);
        long totalTransfers = hourCounts.values().stream().mapToLong(Long::longValue).sum();

        if (totalTransfers > 100 && recent.size() > 0 && currentHourHistorical == 0) {
            activeAnomalies.add(Anomaly.builder()
                    .type("UNUSUAL_TIME")
                    .severity("MEDIUM")
                    .description(String.format("Transfers occurring at hour %02d:00 UTC — no historical activity at this time", currentHour))
                    .detectedAt(Instant.now())
                    .build());
        }

        // 4. Missing expected transfer
        // (Check if accounts that normally send files haven't sent today)
        Instant todayStart = Instant.now().truncatedTo(ChronoUnit.DAYS);
        Map<String, Long> dailyByAccount = historical.stream()
                .filter(r -> r.getFolderMapping() != null)
                .collect(Collectors.groupingBy(
                        r -> r.getFolderMapping().getSourceAccount().getUsername(),
                        Collectors.counting()));
        Set<String> todayAccounts = recent.stream()
                .filter(r -> r.getFolderMapping() != null)
                .map(r -> r.getFolderMapping().getSourceAccount().getUsername())
                .collect(Collectors.toSet());

        for (Map.Entry<String, Long> e : dailyByAccount.entrySet()) {
            double avgDailyRate = e.getValue() / (double) lookbackDays;
            if (avgDailyRate > 0.8 && !todayAccounts.contains(e.getKey())) {
                // Account normally sends daily but hasn't sent today
                activeAnomalies.add(Anomaly.builder()
                        .type("MISSING_TRANSFER")
                        .severity("MEDIUM")
                        .description(String.format("Account '%s' normally sends ~%.0f files/day but has sent 0 in the last hour",
                                e.getKey(), avgDailyRate))
                        .account(e.getKey())
                        .detectedAt(Instant.now())
                        .build());
            }
        }

        if (!activeAnomalies.isEmpty()) {
            log.warn("Anomaly detection: {} anomalies found", activeAnomalies.size());
        }
    }

    public List<Anomaly> getActiveAnomalies() {
        return Collections.unmodifiableList(activeAnomalies);
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class Anomaly {
        private String type;
        private String severity;
        private String description;
        private String account;
        private String trackId;
        private double currentValue;
        private double expectedValue;
        private double zScore;
        private Instant detectedAt;
    }
}
