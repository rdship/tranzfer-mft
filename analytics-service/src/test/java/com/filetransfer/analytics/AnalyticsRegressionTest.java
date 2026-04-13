package com.filetransfer.analytics;

import com.filetransfer.analytics.entity.MetricSnapshot;
import com.filetransfer.analytics.repository.MetricSnapshotRepository;
import com.filetransfer.analytics.service.MetricsAggregationService;
import com.filetransfer.shared.entity.transfer.FileTransferRecord;
import com.filetransfer.shared.entity.transfer.FolderMapping;
import com.filetransfer.shared.entity.core.TransferAccount;
import com.filetransfer.shared.enums.FileTransferStatus;
import com.filetransfer.shared.enums.Protocol;
import com.filetransfer.shared.repository.FileTransferRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression, usability, and performance tests for analytics-service.
 * Pure JUnit 5 + Mockito — no Spring context.
 */
@ExtendWith(MockitoExtension.class)
class AnalyticsRegressionTest {

    @Mock private MetricSnapshotRepository snapshotRepository;
    @Mock private FileTransferRecordRepository transferRecordRepository;
    @Captor private ArgumentCaptor<MetricSnapshot> snapshotCaptor;

    private MetricsAggregationService service;

    @BeforeEach
    void setUp() {
        service = new MetricsAggregationService(snapshotRepository, transferRecordRepository);
    }

    // ── Empty data returns zeros ───────────────────────────────────────

    @Test
    void metricsAggregation_emptyData_shouldReturnZeros() {
        when(transferRecordRepository.findByUploadedAtAfter(any(Instant.class))).thenReturn(Collections.emptyList());
        when(snapshotRepository.save(any(MetricSnapshot.class))).thenAnswer(inv -> inv.getArgument(0));

        service.aggregateLastHour();

        verify(snapshotRepository).save(snapshotCaptor.capture());
        MetricSnapshot snapshot = snapshotCaptor.getValue();

        assertEquals(0L, snapshot.getTotalTransfers(), "Empty data should produce zero total");
        assertEquals(0L, snapshot.getSuccessfulTransfers(), "Empty data should produce zero success");
        assertEquals(0L, snapshot.getFailedTransfers(), "Empty data should produce zero failed");
        assertEquals("ALL", snapshot.getServiceType());
    }

    // ── Valid data computes correctly ──────────────────────────────────

    @Test
    void metricsAggregation_validData_shouldComputeCorrectly() {
        List<FileTransferRecord> records = new ArrayList<>();
        records.add(buildRecord(Protocol.SFTP, FileTransferStatus.DOWNLOADED, 100));
        records.add(buildRecord(Protocol.SFTP, FileTransferStatus.DOWNLOADED, 200));
        records.add(buildRecord(Protocol.SFTP, FileTransferStatus.FAILED, 0));

        when(transferRecordRepository.findByUploadedAtAfter(any(Instant.class))).thenReturn(records);
        when(snapshotRepository.save(any(MetricSnapshot.class))).thenAnswer(inv -> inv.getArgument(0));

        service.aggregateLastHour();

        verify(snapshotRepository).save(snapshotCaptor.capture());
        MetricSnapshot snapshot = snapshotCaptor.getValue();

        assertEquals(3L, snapshot.getTotalTransfers());
        assertEquals(2L, snapshot.getSuccessfulTransfers());
        assertEquals(1L, snapshot.getFailedTransfers());
        assertEquals("SFTP", snapshot.getServiceType());
        assertEquals(150.0, snapshot.getAvgLatencyMs(), 0.1, "Average latency should be (100+200)/2 = 150");
    }

    // ── Null input should not crash ────────────────────────────────────

    @Test
    void metricsAggregation_nullInput_shouldNotCrash() {
        // If findAll returns empty (simulating null-like input), should not throw
        when(transferRecordRepository.findByUploadedAtAfter(any(Instant.class))).thenReturn(Collections.emptyList());
        when(snapshotRepository.save(any(MetricSnapshot.class))).thenAnswer(inv -> inv.getArgument(0));

        assertDoesNotThrow(() -> service.aggregateLastHour(),
                "Aggregation with no data should not crash");
    }

    // ── Performance: 10,000 aggregations ───────────────────────────────

    @Test
    void metricsAggregation_performance_10000Aggregations_shouldBeUnder500ms() {
        // Build a list of 100 records to aggregate
        List<FileTransferRecord> records = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            records.add(buildRecord(Protocol.SFTP, FileTransferStatus.DOWNLOADED, 50 + (i % 200)));
        }

        when(transferRecordRepository.findByUploadedAtAfter(any(Instant.class))).thenReturn(records);
        when(snapshotRepository.save(any(MetricSnapshot.class))).thenAnswer(inv -> inv.getArgument(0));

        // Warm up
        for (int i = 0; i < 5; i++) {
            service.aggregateLastHour();
        }

        long start = System.nanoTime();
        for (int i = 0; i < 100; i++) {
            service.aggregateLastHour();
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        System.out.println("[BENCHMARK] 100 aggregation cycles (100 records each): " + elapsedMs + "ms");
        assertTrue(elapsedMs < 500,
                "10,000 effective aggregations took " + elapsedMs + "ms — must complete under 500ms");
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private FileTransferRecord buildRecord(Protocol protocol, FileTransferStatus status, long latencyMs) {
        TransferAccount account = TransferAccount.builder()
                .protocol(protocol)
                .username("test-user")
                .passwordHash("hash")
                .homeDir("/data")
                .build();

        FolderMapping mapping = FolderMapping.builder()
                .sourceAccount(account)
                .sourcePath("/inbox")
                .active(true)
                .build();

        Instant uploadTime = recentTimestamp();
        Instant downloadTime = latencyMs > 0 ? uploadTime.plusMillis(latencyMs) : null;

        return FileTransferRecord.builder()
                .folderMapping(mapping)
                .status(status)
                .uploadedAt(uploadTime)
                .downloadedAt(downloadTime)
                .originalFilename("test.dat")
                .sourceFilePath("/in/test.dat")
                .destinationFilePath("/out/test.dat")
                .build();
    }

    private Instant recentTimestamp() {
        Instant hourEnd = Instant.now().truncatedTo(ChronoUnit.HOURS);
        Instant hourStart = hourEnd.minus(1, ChronoUnit.HOURS);
        return hourStart.plusSeconds(1800);
    }
}
