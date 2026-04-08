package com.filetransfer.shared.vfs;

import com.filetransfer.shared.client.StorageServiceClient;
import com.filetransfer.shared.entity.VfsIntent;
import com.filetransfer.shared.entity.VfsIntent.IntentStatus;
import com.filetransfer.shared.repository.VfsIntentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.*;

class VfsIntentRecoveryJobTest {

    private VfsIntentRepository intentRepository;
    private VirtualFileSystem vfs;
    private StorageServiceClient storageClient;
    private VfsIntentRecoveryJob job;

    private final UUID accountId = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @BeforeEach
    void setUp() {
        intentRepository = mock(VfsIntentRepository.class);
        vfs = mock(VirtualFileSystem.class);
        storageClient = mock(StorageServiceClient.class);
        job = new VfsIntentRecoveryJob(intentRepository, vfs, storageClient);
    }

    private VfsIntent makeIntent(VfsIntent.OpType op, String path, String storageKey) {
        return VfsIntent.builder()
                .id(UUID.randomUUID())
                .accountId(accountId)
                .op(op)
                .path(path)
                .storageKey(storageKey)
                .sizeBytes(1024)
                .trackId("TRK001")
                .status(IntentStatus.PENDING)
                .podId("dead-pod")
                .createdAt(Instant.now().minusSeconds(600))
                .build();
    }

    // ── WRITE Recovery ─────────────────────────────────────────────────

    @Test
    void writeWithCasPresent_replaysDbEntry() {
        VfsIntent intent = makeIntent(VfsIntent.OpType.WRITE, "/inbox/test.edi", "sha256abc");
        when(intentRepository.findByStatusAndCreatedAtBefore(eq(IntentStatus.PENDING), any()))
                .thenReturn(List.of(intent));
        when(intentRepository.resolve(intent.getId(), IntentStatus.PENDING, IntentStatus.RECOVERING))
                .thenReturn(1);
        when(storageClient.existsBySha256("sha256abc")).thenReturn(true);
        when(vfs.exists(accountId, "/inbox/test.edi")).thenReturn(false);

        job.recoverStaleIntents();

        // Verify DB entry was replayed
        verify(vfs).writeFile(accountId, "/inbox/test.edi", "sha256abc", 1024, "TRK001", null);
        verify(intentRepository).resolve(intent.getId(), IntentStatus.RECOVERING, IntentStatus.COMMITTED);
    }

    @Test
    void writeWithNoCas_abortsIntent() {
        VfsIntent intent = makeIntent(VfsIntent.OpType.WRITE, "/inbox/test.edi", "sha256abc");
        when(intentRepository.findByStatusAndCreatedAtBefore(eq(IntentStatus.PENDING), any()))
                .thenReturn(List.of(intent));
        when(intentRepository.resolve(intent.getId(), IntentStatus.PENDING, IntentStatus.RECOVERING))
                .thenReturn(1);
        when(storageClient.existsBySha256("sha256abc")).thenReturn(false);
        when(vfs.exists(accountId, "/inbox/test.edi")).thenReturn(false);

        job.recoverStaleIntents();

        verify(vfs, never()).writeFile(any(), any(), any(), anyLong(), any(), any());
        verify(intentRepository).resolve(intent.getId(), IntentStatus.RECOVERING, IntentStatus.ABORTED);
    }

    @Test
    void writeBothExist_marksCommitted() {
        VfsIntent intent = makeIntent(VfsIntent.OpType.WRITE, "/inbox/test.edi", "sha256abc");
        when(intentRepository.findByStatusAndCreatedAtBefore(eq(IntentStatus.PENDING), any()))
                .thenReturn(List.of(intent));
        when(intentRepository.resolve(intent.getId(), IntentStatus.PENDING, IntentStatus.RECOVERING))
                .thenReturn(1);
        when(storageClient.existsBySha256("sha256abc")).thenReturn(true);
        when(vfs.exists(accountId, "/inbox/test.edi")).thenReturn(true);

        job.recoverStaleIntents();

        // Already completed — just mark it
        verify(vfs, never()).writeFile(any(), any(), any(), anyLong(), any(), any());
        verify(intentRepository).resolve(intent.getId(), IntentStatus.RECOVERING, IntentStatus.COMMITTED);
    }

    // ── DELETE Recovery ─────────────────────────────────────────────────

    @Test
    void delete_alwaysAborted() {
        VfsIntent intent = makeIntent(VfsIntent.OpType.DELETE, "/inbox/test.edi", null);
        when(intentRepository.findByStatusAndCreatedAtBefore(eq(IntentStatus.PENDING), any()))
                .thenReturn(List.of(intent));
        when(intentRepository.resolve(intent.getId(), IntentStatus.PENDING, IntentStatus.RECOVERING))
                .thenReturn(1);

        job.recoverStaleIntents();

        verify(intentRepository).resolve(intent.getId(), IntentStatus.RECOVERING, IntentStatus.ABORTED);
    }

    // ── MOVE Recovery ──────────────────────────────────────────────────

    @Test
    void moveSourceExists_abortsIntent() {
        VfsIntent intent = makeIntent(VfsIntent.OpType.MOVE, "/inbox/test.edi", null);
        intent.setDestPath("/outbox/test.edi");
        when(intentRepository.findByStatusAndCreatedAtBefore(eq(IntentStatus.PENDING), any()))
                .thenReturn(List.of(intent));
        when(intentRepository.resolve(intent.getId(), IntentStatus.PENDING, IntentStatus.RECOVERING))
                .thenReturn(1);
        when(vfs.exists(accountId, "/inbox/test.edi")).thenReturn(true);
        when(vfs.exists(accountId, "/outbox/test.edi")).thenReturn(false);

        job.recoverStaleIntents();

        verify(intentRepository).resolve(intent.getId(), IntentStatus.RECOVERING, IntentStatus.ABORTED);
    }

    @Test
    void moveDestExists_commitsIntent() {
        VfsIntent intent = makeIntent(VfsIntent.OpType.MOVE, "/inbox/test.edi", null);
        intent.setDestPath("/outbox/test.edi");
        when(intentRepository.findByStatusAndCreatedAtBefore(eq(IntentStatus.PENDING), any()))
                .thenReturn(List.of(intent));
        when(intentRepository.resolve(intent.getId(), IntentStatus.PENDING, IntentStatus.RECOVERING))
                .thenReturn(1);
        when(vfs.exists(accountId, "/inbox/test.edi")).thenReturn(false);
        when(vfs.exists(accountId, "/outbox/test.edi")).thenReturn(true);

        job.recoverStaleIntents();

        verify(intentRepository).resolve(intent.getId(), IntentStatus.RECOVERING, IntentStatus.COMMITTED);
    }

    // ── Concurrency ────────────────────────────────────────────────────

    @Test
    void concurrentRecovery_onlyOneWins() {
        VfsIntent intent = makeIntent(VfsIntent.OpType.WRITE, "/inbox/test.edi", "sha256abc");
        when(intentRepository.findByStatusAndCreatedAtBefore(eq(IntentStatus.PENDING), any()))
                .thenReturn(List.of(intent));
        // Another pod already claimed it
        when(intentRepository.resolve(intent.getId(), IntentStatus.PENDING, IntentStatus.RECOVERING))
                .thenReturn(0);

        job.recoverStaleIntents();

        // No recovery action taken
        verify(storageClient, never()).existsBySha256(any());
        verify(vfs, never()).writeFile(any(), any(), any(), anyLong(), any(), any());
    }

    // ── Archival delegation ──────────────────────────────────────────────

    @Test
    void doesNotPurgeDirectly_archivalHandledBySeparateJob() {
        when(intentRepository.findByStatusAndCreatedAtBefore(eq(IntentStatus.PENDING), any()))
                .thenReturn(List.of());

        job.recoverStaleIntents();

        // purgeResolved is no longer called — archival is handled by VfsIntentArchiveJob
        verify(intentRepository, never()).purgeResolved(any());
    }
}
