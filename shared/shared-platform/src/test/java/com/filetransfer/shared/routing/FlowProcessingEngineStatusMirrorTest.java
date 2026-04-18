package com.filetransfer.shared.routing;

import com.filetransfer.shared.entity.transfer.FileTransferRecord;
import com.filetransfer.shared.entity.transfer.FlowExecution;
import com.filetransfer.shared.enums.FileTransferStatus;
import com.filetransfer.shared.repository.transfer.FileTransferRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * R105a: pins the FileTransferRecord status-mirror behaviour that closes the
 * R100 mailbox-flow bug (status stayed PENDING forever even after flowStatus
 * reached COMPLETED).
 *
 * <p>These tests exercise the private {@code mirrorTerminalStatusToTransferRecord}
 * helper via reflection, because the full executeFlow paths require the whole
 * shared-platform graph (SEDA, storage-manager client, fabric bridge). The
 * helper is the single point through which all 12 terminal paths flow, so
 * pinning its contract covers the entire fix.
 */
class FlowProcessingEngineStatusMirrorTest {

    private FileTransferRecordRepository recordRepo;
    private FlowProcessingEngine engine;
    private Method mirrorMethod;

    @BeforeEach
    void setUp() throws Exception {
        recordRepo = mock(FileTransferRecordRepository.class);
        engine = new FlowProcessingEngine(
                null,          // flowRepository
                null,          // executionRepository
                null,          // approvalRepository
                recordRepo,    // fileTransferRecordRepository <-- under test
                null,          // trackIdGenerator
                null,          // accountRepository
                null,          // deliveryEndpointRepository
                null,          // clusterService
                null,          // restTemplate
                null,          // platformConfig
                null,          // storageClient
                null,          // eventPublisher
                null           // serviceProps
        );
        mirrorMethod = FlowProcessingEngine.class.getDeclaredMethod(
                "mirrorTerminalStatusToTransferRecord",
                FlowExecution.class, FileTransferStatus.class, String.class);
        mirrorMethod.setAccessible(true);
    }

    @Test
    void transitionsPendingRecordToCompletedOnFlowSuccess() throws Exception {
        FileTransferRecord record = FileTransferRecord.builder()
                .id(UUID.randomUUID())
                .trackId("TRZ-TEST1")
                .originalFilename("r104-e2e.dat")
                .sourceFilePath("/in/r104-e2e.dat")
                .destinationFilePath("/out/r104-e2e.dat")
                .status(FileTransferStatus.PENDING)
                .build();
        FlowExecution exec = FlowExecution.builder()
                .id(UUID.randomUUID()).trackId("TRZ-TEST1")
                .transferRecord(record).build();
        when(recordRepo.findByTrackId(record.getTrackId())).thenReturn(Optional.of(record));

        mirrorMethod.invoke(engine, exec, FileTransferStatus.COMPLETED, null);

        assertThat(record.getStatus()).isEqualTo(FileTransferStatus.COMPLETED);
        assertThat(record.getCompletedAt()).isNotNull();
        verify(recordRepo).save(record);
    }

    @Test
    void setsErrorMessageOnFailedTransition() throws Exception {
        FileTransferRecord record = FileTransferRecord.builder()
                .id(UUID.randomUUID()).trackId("TRZ-FAIL").originalFilename("f.dat")
                .sourceFilePath("/a").destinationFilePath("/b")
                .status(FileTransferStatus.PENDING).build();
        FlowExecution exec = FlowExecution.builder()
                .id(UUID.randomUUID()).trackId("TRZ-FAIL").transferRecord(record).build();
        when(recordRepo.findByTrackId(record.getTrackId())).thenReturn(Optional.of(record));

        mirrorMethod.invoke(engine, exec, FileTransferStatus.FAILED, "Step ENCRYPT failed: key missing");

        assertThat(record.getStatus()).isEqualTo(FileTransferStatus.FAILED);
        assertThat(record.getErrorMessage()).isEqualTo("Step ENCRYPT failed: key missing");
        assertThat(record.getCompletedAt()).isNotNull();
    }

    @Test
    void doesNotRegressDownloadedRecord() throws Exception {
        FileTransferRecord record = FileTransferRecord.builder()
                .id(UUID.randomUUID()).trackId("TRZ-DL").originalFilename("f.dat")
                .sourceFilePath("/a").destinationFilePath("/b")
                .status(FileTransferStatus.DOWNLOADED)
                .downloadedAt(Instant.now().minusSeconds(60)).build();
        FlowExecution exec = FlowExecution.builder()
                .id(UUID.randomUUID()).trackId("TRZ-DL").transferRecord(record).build();
        when(recordRepo.findByTrackId(record.getTrackId())).thenReturn(Optional.of(record));

        mirrorMethod.invoke(engine, exec, FileTransferStatus.COMPLETED, null);

        assertThat(record.getStatus()).isEqualTo(FileTransferStatus.DOWNLOADED);
        verify(recordRepo, never()).save(any());
    }

    @Test
    void doesNotRegressMovedToSentRecord() throws Exception {
        FileTransferRecord record = FileTransferRecord.builder()
                .id(UUID.randomUUID()).trackId("TRZ-MTS").originalFilename("f.dat")
                .sourceFilePath("/a").destinationFilePath("/b")
                .status(FileTransferStatus.MOVED_TO_SENT).build();
        FlowExecution exec = FlowExecution.builder()
                .id(UUID.randomUUID()).trackId("TRZ-MTS").transferRecord(record).build();
        when(recordRepo.findByTrackId(record.getTrackId())).thenReturn(Optional.of(record));

        mirrorMethod.invoke(engine, exec, FileTransferStatus.FAILED, "late failure");

        assertThat(record.getStatus()).isEqualTo(FileTransferStatus.MOVED_TO_SENT);
        verify(recordRepo, never()).save(any());
    }

    @Test
    void isNoOpWhenNoTransferRecordForTrackId() throws Exception {
        // R114: helper looks up by trackId; ad-hoc flows with no matching record are no-ops.
        FlowExecution exec = FlowExecution.builder()
                .id(UUID.randomUUID()).trackId("TRZ-ADHOC").build();
        when(recordRepo.findByTrackId("TRZ-ADHOC")).thenReturn(Optional.empty());

        mirrorMethod.invoke(engine, exec, FileTransferStatus.COMPLETED, null);

        verify(recordRepo, never()).save(any());
    }

    @Test
    void looksUpByTrackIdEvenWhenTransferRecordFieldIsUnpopulated() throws Exception {
        // R114 regression pin: production FlowExecution.builder() never sets
        // .transferRecord(), so R105a's original exec.getTransferRecord() check
        // made the mirror a no-op in real traffic. After R114, the helper looks
        // up by exec.getTrackId() — this test proves that path works even when
        // the legacy association is null.
        FileTransferRecord record = FileTransferRecord.builder()
                .id(UUID.randomUUID()).trackId("TRZ-LOOKUP").originalFilename("r113-e2e.dat")
                .sourceFilePath("/in").destinationFilePath("/out")
                .status(FileTransferStatus.PENDING).build();
        FlowExecution exec = FlowExecution.builder()
                .id(UUID.randomUUID()).trackId("TRZ-LOOKUP").build();  // NO .transferRecord()
        when(recordRepo.findByTrackId("TRZ-LOOKUP")).thenReturn(Optional.of(record));

        mirrorMethod.invoke(engine, exec, FileTransferStatus.FAILED, "Step FAILED");

        assertThat(record.getStatus()).isEqualTo(FileTransferStatus.FAILED);
        assertThat(record.getErrorMessage()).isEqualTo("Step FAILED");
        assertThat(record.getCompletedAt()).isNotNull();
        verify(recordRepo).save(record);
    }

    @Test
    void preservesExistingCompletedAt() throws Exception {
        Instant existing = Instant.now().minusSeconds(300);
        FileTransferRecord record = FileTransferRecord.builder()
                .id(UUID.randomUUID()).trackId("TRZ-CAT").originalFilename("f.dat")
                .sourceFilePath("/a").destinationFilePath("/b")
                .status(FileTransferStatus.PENDING)
                .completedAt(existing).build();
        FlowExecution exec = FlowExecution.builder()
                .id(UUID.randomUUID()).trackId("TRZ-CAT").transferRecord(record).build();
        when(recordRepo.findByTrackId(record.getTrackId())).thenReturn(Optional.of(record));

        mirrorMethod.invoke(engine, exec, FileTransferStatus.COMPLETED, null);

        assertThat(record.getCompletedAt()).isEqualTo(existing);
    }

    @Test
    void swallowsRepositoryExceptionWithoutThrowing() throws Exception {
        FileTransferRecord record = FileTransferRecord.builder()
                .id(UUID.randomUUID()).trackId("TRZ-EX").originalFilename("f.dat")
                .sourceFilePath("/a").destinationFilePath("/b")
                .status(FileTransferStatus.PENDING).build();
        FlowExecution exec = FlowExecution.builder()
                .id(UUID.randomUUID()).trackId("TRZ-EX").transferRecord(record).build();
        when(recordRepo.findByTrackId(record.getTrackId())).thenThrow(new RuntimeException("DB down"));

        // Must not propagate — mirror is best-effort; flow completion already committed
        mirrorMethod.invoke(engine, exec, FileTransferStatus.COMPLETED, null);

        assertThat(record.getStatus()).isEqualTo(FileTransferStatus.PENDING);
    }
}
