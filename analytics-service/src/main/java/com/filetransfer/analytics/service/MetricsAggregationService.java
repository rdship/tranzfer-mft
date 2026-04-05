package com.filetransfer.analytics.service;

import com.filetransfer.analytics.entity.MetricSnapshot;
import com.filetransfer.analytics.repository.MetricSnapshotRepository;
import com.filetransfer.shared.entity.FileTransferRecord;
import com.filetransfer.shared.enums.FileTransferStatus;
import com.filetransfer.shared.repository.FileTransferRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class MetricsAggregationService {

    private final MetricSnapshotRepository snapshotRepository;
    private final FileTransferRecordRepository transferRecordRepository;

    @Scheduled(fixedDelay = 3600000) // every hour
    @SchedulerLock(name = "analytics_aggregateLastHour", lockAtLeastFor = "PT50M", lockAtMostFor = "PT2H")
    @Transactional
    public void aggregateLastHour() {
        Instant hourEnd = Instant.now().truncatedTo(ChronoUnit.HOURS);
        Instant hourStart = hourEnd.minus(1, ChronoUnit.HOURS);

        log.info("Aggregating metrics for window: {} - {}", hourStart, hourEnd);

        // Group transfers by protocol/service type
        List<FileTransferRecord> records = transferRecordRepository.findAll().stream()
                .filter(r -> r.getUploadedAt() != null
                        && r.getUploadedAt().isAfter(hourStart)
                        && r.getUploadedAt().isBefore(hourEnd))
                .collect(Collectors.toList());

        // Group by protocol (maps to service type)
        Map<String, List<FileTransferRecord>> byProtocol = records.stream()
                .collect(Collectors.groupingBy(r ->
                        r.getFolderMapping() != null
                                ? r.getFolderMapping().getSourceAccount().getProtocol().name()
                                : "UNKNOWN"));

        for (Map.Entry<String, List<FileTransferRecord>> entry : byProtocol.entrySet()) {
            String serviceType = entry.getKey();
            List<FileTransferRecord> svcRecords = entry.getValue();

            long total = svcRecords.size();
            long success = svcRecords.stream()
                    .filter(r -> r.getStatus() == FileTransferStatus.DOWNLOADED || r.getStatus() == FileTransferStatus.MOVED_TO_SENT).count();
            long failed = svcRecords.stream()
                    .filter(r -> r.getStatus() == FileTransferStatus.FAILED).count();

            // Latency calculation
            List<Long> latencies = svcRecords.stream()
                    .filter(r -> r.getDownloadedAt() != null && r.getUploadedAt() != null)
                    .map(r -> ChronoUnit.MILLIS.between(r.getUploadedAt(), r.getDownloadedAt()))
                    .sorted()
                    .collect(Collectors.toList());

            double avgLatency = latencies.isEmpty() ? 0.0 :
                    latencies.stream().mapToLong(Long::longValue).average().orElse(0.0);
            double p95 = percentile(latencies, 95);
            double p99 = percentile(latencies, 99);

            MetricSnapshot snapshot = MetricSnapshot.builder()
                    .snapshotTime(hourStart)
                    .serviceType(serviceType)
                    .totalTransfers(total)
                    .successfulTransfers(success)
                    .failedTransfers(failed)
                    .avgLatencyMs(avgLatency)
                    .p95LatencyMs(p95)
                    .p99LatencyMs(p99)
                    .build();

            snapshotRepository.save(snapshot);
        }

        if (records.isEmpty()) {
            // Save zero-records to maintain time series continuity
            MetricSnapshot empty = MetricSnapshot.builder()
                    .snapshotTime(hourStart)
                    .serviceType("ALL")
                    .totalTransfers(0L)
                    .successfulTransfers(0L)
                    .failedTransfers(0L)
                    .build();
            try { snapshotRepository.save(empty); } catch (Exception ignored) {}
        }

        log.info("Aggregation complete: {} records processed", records.size());
    }

    private double percentile(List<Long> sorted, int percentile) {
        if (sorted.isEmpty()) return 0.0;
        int idx = (int) Math.ceil(percentile / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(idx, sorted.size() - 1)));
    }

    public List<MetricSnapshot> getSnapshots(String serviceType, int hours) {
        Instant from = Instant.now().minus(hours, ChronoUnit.HOURS);
        Instant to = Instant.now();
        if (serviceType == null || serviceType.equalsIgnoreCase("ALL")) {
            return snapshotRepository.findBySnapshotTimeBetweenOrderBySnapshotTimeAsc(from, to);
        }
        return snapshotRepository.findBySnapshotTimeBetweenAndServiceTypeOrderBySnapshotTimeAsc(from, to, serviceType);
    }
}
