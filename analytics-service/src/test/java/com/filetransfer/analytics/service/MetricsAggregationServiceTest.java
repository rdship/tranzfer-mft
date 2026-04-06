package com.filetransfer.analytics.service;

import com.filetransfer.analytics.entity.MetricSnapshot;
import com.filetransfer.analytics.repository.MetricSnapshotRepository;
import com.filetransfer.shared.entity.FileTransferRecord;
import com.filetransfer.shared.entity.FolderMapping;
import com.filetransfer.shared.entity.TransferAccount;
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

import java.lang.reflect.Method;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for MetricsAggregationService.
 * Validates aggregation of transfer records into metric snapshots,
 * including success/failure counting, percentile latency calculations,
 * throughput, protocol grouping, and empty-data edge cases.
 * No Spring context required.
 */
@ExtendWith(MockitoExtension.class)
class MetricsAggregationServiceTest {

    @Mock private MetricSnapshotRepository snapshotRepository;
    @Mock private FileTransferRecordRepository transferRecordRepository;

    @Captor private ArgumentCaptor<MetricSnapshot> snapshotCaptor;

    private MetricsAggregationService service;

    /** The aggregation window used by our test data */
    private Instant windowStart;
    private Instant windowEnd;

    @BeforeEach
    void setUp() {
        service = new MetricsAggregationService(snapshotRepository, transferRecordRepository);
        // The service truncates to current hour, so we set up a window
        // that our test data will fall into
        windowEnd = Instant.now().truncatedTo(ChronoUnit.HOURS);
        windowStart = windowEnd.minus(1, ChronoUnit.HOURS);
    }

    // ── Mixed success/failure aggregation ───────────────────────────────

    @Test
    void aggregateLastHour_mixedSuccessAndFailure_correctCounts() {
        List<FileTransferRecord> records = new ArrayList<>();
        // 3 success (DOWNLOADED), 1 success (MOVED_TO_SENT), 2 failed
        records.add(buildRecord(Protocol.SFTP, FileTransferStatus.DOWNLOADED, 100));
        records.add(buildRecord(Protocol.SFTP, FileTransferStatus.DOWNLOADED, 200));
        records.add(buildRecord(Protocol.SFTP, FileTransferStatus.DOWNLOADED, 150));
        records.add(buildRecord(Protocol.SFTP, FileTransferStatus.MOVED_TO_SENT, 300));
        records.add(buildRecord(Protocol.SFTP, FileTransferStatus.FAILED, 0));
        records.add(buildRecord(Protocol.SFTP, FileTransferStatus.FAILED, 0));

        when(transferRecordRepository.findAll()).thenReturn(records);
        when(snapshotRepository.save(any(MetricSnapshot.class))).thenAnswer(inv -> inv.getArgument(0));

        service.aggregateLastHour();

        verify(snapshotRepository).save(snapshotCaptor.capture());
        MetricSnapshot snapshot = snapshotCaptor.getValue();

        assertEquals(6L, snapshot.getTotalTransfers());
        assertEquals(4L, snapshot.getSuccessfulTransfers()); // 3 DOWNLOADED + 1 MOVED_TO_SENT
        assertEquals(2L, snapshot.getFailedTransfers());
        assertEquals("SFTP", snapshot.getServiceType());
    }

    // ── P95/P99 latency calculation ─────────────────────────────────────

    @Test
    void aggregateLastHour_p95p99Latency_verifiedWithKnownData() {
        // Create 20 records with known latencies: 10, 20, 30, ..., 200 ms
        List<FileTransferRecord> records = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            records.add(buildRecord(Protocol.SFTP, FileTransferStatus.DOWNLOADED, i * 10));
        }

        when(transferRecordRepository.findAll()).thenReturn(records);
        when(snapshotRepository.save(any(MetricSnapshot.class))).thenAnswer(inv -> inv.getArgument(0));

        service.aggregateLastHour();

        verify(snapshotRepository).save(snapshotCaptor.capture());
        MetricSnapshot snapshot = snapshotCaptor.getValue();

        // P95: ceil(0.95 * 20) - 1 = ceil(19) - 1 = 18 -> sorted[18] = 190
        assertEquals(190.0, snapshot.getP95LatencyMs());
        // P99: ceil(0.99 * 20) - 1 = ceil(19.8) - 1 = 19 -> sorted[19] = 200
        assertEquals(200.0, snapshot.getP99LatencyMs());

        // Average: (10+20+...+200)/20 = 2100/20 = 105.0
        assertEquals(105.0, snapshot.getAvgLatencyMs());
    }

    @Test
    void percentile_singleElement_returnsThatElement() throws Exception {
        // Test the private percentile method directly
        Method percentileMethod = MetricsAggregationService.class.getDeclaredMethod(
                "percentile", List.class, int.class);
        percentileMethod.setAccessible(true);

        List<Long> single = List.of(42L);

        double p95 = (double) percentileMethod.invoke(service, single, 95);
        double p99 = (double) percentileMethod.invoke(service, single, 99);

        assertEquals(42.0, p95);
        assertEquals(42.0, p99);
    }

    @Test
    void percentile_emptyList_returnsZero() throws Exception {
        Method percentileMethod = MetricsAggregationService.class.getDeclaredMethod(
                "percentile", List.class, int.class);
        percentileMethod.setAccessible(true);

        double result = (double) percentileMethod.invoke(service, Collections.emptyList(), 95);

        assertEquals(0.0, result);
    }

    // ── Empty records handling ───────────────────────────────────────────

    @Test
    void aggregateLastHour_emptyRecords_savesZeroSnapshot() {
        when(transferRecordRepository.findAll()).thenReturn(Collections.emptyList());
        when(snapshotRepository.save(any(MetricSnapshot.class))).thenAnswer(inv -> inv.getArgument(0));

        service.aggregateLastHour();

        verify(snapshotRepository).save(snapshotCaptor.capture());
        MetricSnapshot snapshot = snapshotCaptor.getValue();

        assertEquals("ALL", snapshot.getServiceType());
        assertEquals(0L, snapshot.getTotalTransfers());
        assertEquals(0L, snapshot.getSuccessfulTransfers());
        assertEquals(0L, snapshot.getFailedTransfers());
    }

    @Test
    void aggregateLastHour_emptyRecords_noDivisionByZero() {
        when(transferRecordRepository.findAll()).thenReturn(Collections.emptyList());
        when(snapshotRepository.save(any(MetricSnapshot.class))).thenAnswer(inv -> inv.getArgument(0));

        // Should not throw ArithmeticException or any other exception
        assertDoesNotThrow(() -> service.aggregateLastHour());
    }

    @Test
    void aggregateLastHour_emptyRecordsSaveFailure_doesNotPropagate() {
        when(transferRecordRepository.findAll()).thenReturn(Collections.emptyList());
        when(snapshotRepository.save(any(MetricSnapshot.class)))
                .thenThrow(new RuntimeException("DB constraint violation"));

        // The method catches exceptions when saving zero-records
        assertDoesNotThrow(() -> service.aggregateLastHour());
    }

    // ── Protocol grouping ───────────────────────────────────────────────

    @Test
    void aggregateLastHour_multipleProtocols_groupedCorrectly() {
        List<FileTransferRecord> records = new ArrayList<>();
        // 2 SFTP records
        records.add(buildRecord(Protocol.SFTP, FileTransferStatus.DOWNLOADED, 100));
        records.add(buildRecord(Protocol.SFTP, FileTransferStatus.DOWNLOADED, 200));
        // 3 FTP records
        records.add(buildRecord(Protocol.FTP, FileTransferStatus.DOWNLOADED, 50));
        records.add(buildRecord(Protocol.FTP, FileTransferStatus.FAILED, 0));
        records.add(buildRecord(Protocol.FTP, FileTransferStatus.DOWNLOADED, 75));

        when(transferRecordRepository.findAll()).thenReturn(records);
        when(snapshotRepository.save(any(MetricSnapshot.class))).thenAnswer(inv -> inv.getArgument(0));

        service.aggregateLastHour();

        // Should save 2 snapshots: one for SFTP, one for FTP
        verify(snapshotRepository, times(2)).save(snapshotCaptor.capture());
        List<MetricSnapshot> snapshots = snapshotCaptor.getAllValues();

        MetricSnapshot sftpSnapshot = snapshots.stream()
                .filter(s -> "SFTP".equals(s.getServiceType()))
                .findFirst().orElseThrow();
        MetricSnapshot ftpSnapshot = snapshots.stream()
                .filter(s -> "FTP".equals(s.getServiceType()))
                .findFirst().orElseThrow();

        assertEquals(2L, sftpSnapshot.getTotalTransfers());
        assertEquals(2L, sftpSnapshot.getSuccessfulTransfers());
        assertEquals(0L, sftpSnapshot.getFailedTransfers());

        assertEquals(3L, ftpSnapshot.getTotalTransfers());
        assertEquals(2L, ftpSnapshot.getSuccessfulTransfers());
        assertEquals(1L, ftpSnapshot.getFailedTransfers());
    }

    @Test
    void aggregateLastHour_unknownProtocol_groupedAsUnknown() {
        // Record without folderMapping maps to "UNKNOWN"
        FileTransferRecord record = FileTransferRecord.builder()
                .status(FileTransferStatus.DOWNLOADED)
                .uploadedAt(recentTimestamp())
                .downloadedAt(recentTimestamp().plusMillis(100))
                .originalFilename("test.dat")
                .sourceFilePath("/in/test.dat")
                .destinationFilePath("/out/test.dat")
                .build();

        when(transferRecordRepository.findAll()).thenReturn(List.of(record));
        when(snapshotRepository.save(any(MetricSnapshot.class))).thenAnswer(inv -> inv.getArgument(0));

        service.aggregateLastHour();

        verify(snapshotRepository).save(snapshotCaptor.capture());
        assertEquals("UNKNOWN", snapshotCaptor.getValue().getServiceType());
    }

    // ── Latency with missing download times ─────────────────────────────

    @Test
    void aggregateLastHour_recordsWithoutDownloadTime_excludedFromLatency() {
        List<FileTransferRecord> records = new ArrayList<>();
        // Record WITH download time -> latency calculable
        records.add(buildRecord(Protocol.SFTP, FileTransferStatus.DOWNLOADED, 150));
        // Record WITHOUT download time (FAILED before download)
        FileTransferRecord failedRecord = buildRecord(Protocol.SFTP, FileTransferStatus.FAILED, 0);
        failedRecord.setDownloadedAt(null); // explicitly null
        records.add(failedRecord);

        when(transferRecordRepository.findAll()).thenReturn(records);
        when(snapshotRepository.save(any(MetricSnapshot.class))).thenAnswer(inv -> inv.getArgument(0));

        service.aggregateLastHour();

        verify(snapshotRepository).save(snapshotCaptor.capture());
        MetricSnapshot snapshot = snapshotCaptor.getValue();

        // Only 1 record has latency data -> average = 150
        assertEquals(150.0, snapshot.getAvgLatencyMs());
    }

    // ── getSnapshots delegation ─────────────────────────────────────────

    @Test
    void getSnapshots_allServiceType_delegatesToUnfilteredQuery() {
        Instant now = Instant.now();
        when(snapshotRepository.findBySnapshotTimeBetweenOrderBySnapshotTimeAsc(any(), any()))
                .thenReturn(List.of());

        service.getSnapshots("ALL", 24);

        verify(snapshotRepository).findBySnapshotTimeBetweenOrderBySnapshotTimeAsc(any(), any());
        verify(snapshotRepository, never())
                .findBySnapshotTimeBetweenAndServiceTypeOrderBySnapshotTimeAsc(any(), any(), anyString());
    }

    @Test
    void getSnapshots_nullServiceType_delegatesToUnfilteredQuery() {
        when(snapshotRepository.findBySnapshotTimeBetweenOrderBySnapshotTimeAsc(any(), any()))
                .thenReturn(List.of());

        service.getSnapshots(null, 12);

        verify(snapshotRepository).findBySnapshotTimeBetweenOrderBySnapshotTimeAsc(any(), any());
    }

    @Test
    void getSnapshots_specificServiceType_delegatesToFilteredQuery() {
        when(snapshotRepository.findBySnapshotTimeBetweenAndServiceTypeOrderBySnapshotTimeAsc(
                any(), any(), eq("SFTP"))).thenReturn(List.of());

        service.getSnapshots("SFTP", 6);

        verify(snapshotRepository).findBySnapshotTimeBetweenAndServiceTypeOrderBySnapshotTimeAsc(
                any(), any(), eq("SFTP"));
    }

    @Test
    void getSnapshots_caseInsensitiveAll_delegatesToUnfiltered() {
        when(snapshotRepository.findBySnapshotTimeBetweenOrderBySnapshotTimeAsc(any(), any()))
                .thenReturn(List.of());

        service.getSnapshots("all", 24);

        verify(snapshotRepository).findBySnapshotTimeBetweenOrderBySnapshotTimeAsc(any(), any());
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    /**
     * Build a FileTransferRecord that falls within the current aggregation window.
     * @param protocol the protocol to assign via FolderMapping->sourceAccount
     * @param status the transfer status
     * @param latencyMs latency in ms between upload and download (0 means no download)
     */
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

    /**
     * Returns a timestamp that falls within the current aggregation window
     * (between hourStart and hourEnd as defined by aggregateLastHour()).
     */
    private Instant recentTimestamp() {
        Instant hourEnd = Instant.now().truncatedTo(ChronoUnit.HOURS);
        Instant hourStart = hourEnd.minus(1, ChronoUnit.HOURS);
        return hourStart.plusSeconds(1800); // middle of the window
    }
}
