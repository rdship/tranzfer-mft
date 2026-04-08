package com.filetransfer.shared.vfs;

import com.filetransfer.shared.client.StorageServiceClient;
import com.filetransfer.shared.entity.VfsIntent;
import com.filetransfer.shared.entity.VirtualEntry;
import com.filetransfer.shared.repository.VfsChunkRepository;
import com.filetransfer.shared.repository.VfsIntentRepository;
import com.filetransfer.shared.repository.TransferAccountRepository;
import com.filetransfer.shared.repository.VirtualEntryRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.math.BigInteger;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class VirtualFileSystemWaipTest {

    private VirtualEntryRepository entryRepository;
    private StorageServiceClient storageClient;
    private VfsIntentRepository intentRepository;
    private VfsChunkRepository chunkRepository;
    private EntityManager entityManager;
    private TransferAccountRepository accountRepository;
    private VirtualFileSystem vfs;

    private final UUID accountId = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @BeforeEach
    void setUp() throws Exception {
        entryRepository = mock(VirtualEntryRepository.class);
        storageClient = mock(StorageServiceClient.class);
        intentRepository = mock(VfsIntentRepository.class);
        chunkRepository = mock(VfsChunkRepository.class);
        entityManager = mock(EntityManager.class);
        accountRepository = mock(TransferAccountRepository.class);

        vfs = new VirtualFileSystem(entryRepository, storageClient, intentRepository,
                chunkRepository, entityManager, accountRepository);

        // Set podId via reflection (normally @PostConstruct)
        Field podIdField = VirtualFileSystem.class.getDeclaredField("podId");
        podIdField.setAccessible(true);
        podIdField.set(vfs, "test-pod-1");

        // Set bucket thresholds via reflection
        Field inlineMax = VirtualFileSystem.class.getDeclaredField("inlineMaxBytes");
        inlineMax.setAccessible(true);
        inlineMax.set(vfs, 65536L);

        Field chunkThreshold = VirtualFileSystem.class.getDeclaredField("chunkThresholdBytes");
        chunkThreshold.setAccessible(true);
        chunkThreshold.set(vfs, 67108864L);

        // Stub advisory lock (native query)
        Query mockQuery = mock(Query.class);
        when(entityManager.createNativeQuery(anyString())).thenReturn(mockQuery);
        when(mockQuery.setParameter(anyString(), any())).thenReturn(mockQuery);
        when(mockQuery.getSingleResult()).thenReturn(BigInteger.ONE);

        // Stub intent save to return the input
        when(intentRepository.save(any(VfsIntent.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    // ── Bucket Routing ─────────────────────────────────────────────────

    @Test
    void determineBucket_smallFile_returnsInline() {
        assertEquals("INLINE", vfs.determineBucket(100));
        assertEquals("INLINE", vfs.determineBucket(65536));
    }

    @Test
    void determineBucket_mediumFile_returnsStandard() {
        assertEquals("STANDARD", vfs.determineBucket(65537));
        assertEquals("STANDARD", vfs.determineBucket(67108864));
    }

    @Test
    void determineBucket_largeFile_returnsChunked() {
        assertEquals("CHUNKED", vfs.determineBucket(67108865));
        assertEquals("CHUNKED", vfs.determineBucket(10_000_000_000L));
    }

    // ── INLINE Write ───────────────────────────────────────────────────

    @Test
    void writeFile_inline_storesContentInDbRow() {
        byte[] content = "small EDI file content".getBytes();
        when(entryRepository.existsByAccountIdAndPathAndDeletedFalse(any(), eq("/"))).thenReturn(true);
        when(entryRepository.findByAccountIdAndPathAndDeletedFalse(any(), eq("/inbox/test.edi"))).thenReturn(Optional.empty());
        when(entryRepository.existsByAccountIdAndPathAndDeletedFalse(any(), eq("/inbox"))).thenReturn(true);
        when(entryRepository.save(any(VirtualEntry.class))).thenAnswer(inv -> inv.getArgument(0));

        VirtualEntry result = vfs.writeFile(accountId, "/inbox/test.edi", null,
                content.length, null, null, content);

        assertEquals("INLINE", result.getStorageBucket());
        assertArrayEquals(content, result.getInlineContent());
        assertNull(result.getStorageKey());

        // Verify intent was created and committed
        ArgumentCaptor<VfsIntent> intentCaptor = ArgumentCaptor.forClass(VfsIntent.class);
        verify(intentRepository, times(2)).save(intentCaptor.capture());
        VfsIntent committed = intentCaptor.getAllValues().get(1);
        assertEquals(VfsIntent.IntentStatus.COMMITTED, committed.getStatus());
    }

    // ── STANDARD Write ─────────────────────────────────────────────────

    @Test
    void writeFile_standard_createsIntentAndCommits() {
        when(entryRepository.existsByAccountIdAndPathAndDeletedFalse(any(), eq("/"))).thenReturn(true);
        when(entryRepository.findByAccountIdAndPathAndDeletedFalse(any(), eq("/inbox/report.csv"))).thenReturn(Optional.empty());
        when(entryRepository.existsByAccountIdAndPathAndDeletedFalse(any(), eq("/inbox"))).thenReturn(true);
        when(entryRepository.save(any(VirtualEntry.class))).thenAnswer(inv -> inv.getArgument(0));

        VirtualEntry result = vfs.writeFile(accountId, "/inbox/report.csv",
                "abc123sha256", 100_000, "TRK001", "text/csv", null);

        assertEquals("STANDARD", result.getStorageBucket());
        assertEquals("abc123sha256", result.getStorageKey());
        assertNull(result.getInlineContent());

        // Intent was saved twice (create + commit) and final state is COMMITTED
        ArgumentCaptor<VfsIntent> captor = ArgumentCaptor.forClass(VfsIntent.class);
        verify(intentRepository, times(2)).save(captor.capture());
        assertEquals(VfsIntent.OpType.WRITE, captor.getAllValues().get(0).getOp());
        // Same object ref mutated — final state is COMMITTED
        assertEquals(VfsIntent.IntentStatus.COMMITTED, captor.getAllValues().get(1).getStatus());
        assertNotNull(captor.getAllValues().get(1).getResolvedAt());
    }

    // ── Overwrite ──────────────────────────────────────────────────────

    @Test
    void writeFile_existingFile_updatesEntry() {
        VirtualEntry existing = VirtualEntry.builder()
                .id(UUID.randomUUID())
                .accountId(accountId)
                .path("/inbox/test.edi")
                .parentPath("/inbox")
                .name("test.edi")
                .type(VirtualEntry.EntryType.FILE)
                .storageKey("old-sha256")
                .storageBucket("STANDARD")
                .build();

        when(entryRepository.findByAccountIdAndPathAndDeletedFalse(any(), eq("/inbox/test.edi")))
                .thenReturn(Optional.of(existing));
        when(entryRepository.save(any(VirtualEntry.class))).thenAnswer(inv -> inv.getArgument(0));

        byte[] newContent = "updated content".getBytes();
        VirtualEntry result = vfs.writeFile(accountId, "/inbox/test.edi", null,
                newContent.length, null, null, newContent);

        assertEquals("INLINE", result.getStorageBucket());
        assertArrayEquals(newContent, result.getInlineContent());
    }

    // ── Directory Conflict ─────────────────────────────────────────────

    @Test
    void writeFile_directoryConflict_throws() {
        VirtualEntry dir = VirtualEntry.builder()
                .accountId(accountId)
                .path("/inbox")
                .type(VirtualEntry.EntryType.DIR)
                .build();

        when(entryRepository.findByAccountIdAndPathAndDeletedFalse(any(), eq("/inbox")))
                .thenReturn(Optional.of(dir));

        assertThrows(IllegalStateException.class, () ->
                vfs.writeFile(accountId, "/inbox", null, 100, null, null, new byte[100]));
    }

    // ── Delete ─────────────────────────────────────────────────────────

    @Test
    void delete_createsIntentAndCommits() {
        VirtualEntry file = VirtualEntry.builder()
                .accountId(accountId)
                .path("/inbox/test.edi")
                .type(VirtualEntry.EntryType.FILE)
                .deleted(false)
                .build();

        when(entryRepository.findByAccountIdAndPathAndDeletedFalse(any(), eq("/inbox/test.edi")))
                .thenReturn(Optional.of(file));
        when(entryRepository.save(any(VirtualEntry.class))).thenAnswer(inv -> inv.getArgument(0));

        int deleted = vfs.delete(accountId, "/inbox/test.edi");

        assertEquals(1, deleted);
        assertTrue(file.isDeleted());

        ArgumentCaptor<VfsIntent> captor = ArgumentCaptor.forClass(VfsIntent.class);
        verify(intentRepository, times(2)).save(captor.capture());
        assertEquals(VfsIntent.OpType.DELETE, captor.getAllValues().get(0).getOp());
        assertEquals(VfsIntent.IntentStatus.COMMITTED, captor.getAllValues().get(1).getStatus());
    }

    @Test
    void delete_nonExistent_abortsIntent() {
        when(entryRepository.findByAccountIdAndPathAndDeletedFalse(any(), eq("/inbox/missing.edi")))
                .thenReturn(Optional.empty());

        int deleted = vfs.delete(accountId, "/inbox/missing.edi");

        assertEquals(0, deleted);

        ArgumentCaptor<VfsIntent> captor = ArgumentCaptor.forClass(VfsIntent.class);
        verify(intentRepository, times(2)).save(captor.capture());
        assertEquals(VfsIntent.IntentStatus.ABORTED, captor.getAllValues().get(1).getStatus());
    }

    // ── Move ───────────────────────────────────────────────────────────

    @Test
    void move_createsIntentAndCommits() {
        VirtualEntry file = VirtualEntry.builder()
                .accountId(accountId)
                .path("/inbox/test.edi")
                .parentPath("/inbox")
                .name("test.edi")
                .type(VirtualEntry.EntryType.FILE)
                .build();

        when(entryRepository.existsByAccountIdAndPathAndDeletedFalse(any(), eq("/inbox/test.edi"))).thenReturn(true);
        when(entryRepository.existsByAccountIdAndPathAndDeletedFalse(any(), eq("/outbox/test.edi"))).thenReturn(false);
        when(entryRepository.existsByAccountIdAndPathAndDeletedFalse(any(), eq("/outbox"))).thenReturn(true);
        when(entryRepository.findByAccountIdAndPathAndDeletedFalse(any(), eq("/inbox/test.edi")))
                .thenReturn(Optional.of(file));
        when(entryRepository.save(any(VirtualEntry.class))).thenAnswer(inv -> inv.getArgument(0));

        vfs.move(accountId, "/inbox/test.edi", "/outbox/test.edi");

        assertEquals("/outbox/test.edi", file.getPath());

        ArgumentCaptor<VfsIntent> captor = ArgumentCaptor.forClass(VfsIntent.class);
        verify(intentRepository, times(2)).save(captor.capture());
        VfsIntent moveIntent = captor.getAllValues().get(0);
        assertEquals(VfsIntent.OpType.MOVE, moveIntent.getOp());
        assertEquals("/inbox/test.edi", moveIntent.getPath());
        assertEquals("/outbox/test.edi", moveIntent.getDestPath());
        assertEquals(VfsIntent.IntentStatus.COMMITTED, captor.getAllValues().get(1).getStatus());
    }

    // ── Read (bucket-aware) ────────────────────────────────────────────

    @Test
    void readFile_inlineBucket_returnsFromDbDirectly() {
        byte[] content = "inline content".getBytes();
        VirtualEntry file = VirtualEntry.builder()
                .accountId(accountId)
                .path("/inbox/test.edi")
                .type(VirtualEntry.EntryType.FILE)
                .storageBucket("INLINE")
                .inlineContent(content)
                .build();

        when(entryRepository.findByAccountIdAndPathAndDeletedFalse(any(), eq("/inbox/test.edi")))
                .thenReturn(Optional.of(file));
        when(entryRepository.save(any(VirtualEntry.class))).thenAnswer(inv -> inv.getArgument(0));

        byte[] result = vfs.readFile(accountId, "/inbox/test.edi");

        assertArrayEquals(content, result);
        // Verify NO call to Storage Manager
        verifyNoInteractions(storageClient);
    }

    @Test
    void readFile_standardBucket_retrievesFromCas() {
        VirtualEntry file = VirtualEntry.builder()
                .accountId(accountId)
                .path("/inbox/report.csv")
                .type(VirtualEntry.EntryType.FILE)
                .storageBucket("STANDARD")
                .trackId("TRK001")
                .build();

        when(entryRepository.findByAccountIdAndPathAndDeletedFalse(any(), eq("/inbox/report.csv")))
                .thenReturn(Optional.of(file));
        when(entryRepository.save(any(VirtualEntry.class))).thenAnswer(inv -> inv.getArgument(0));
        when(storageClient.retrieve("TRK001")).thenReturn("csv data".getBytes());

        byte[] result = vfs.readFile(accountId, "/inbox/report.csv");

        assertArrayEquals("csv data".getBytes(), result);
        verify(storageClient).retrieve("TRK001");
    }

    // ── Advisory Lock ──────────────────────────────────────────────────

    @Test
    void writeFile_acquiresAdvisoryLock() {
        when(entryRepository.existsByAccountIdAndPathAndDeletedFalse(any(), eq("/"))).thenReturn(true);
        when(entryRepository.findByAccountIdAndPathAndDeletedFalse(any(), eq("/test.edi"))).thenReturn(Optional.empty());
        when(entryRepository.save(any(VirtualEntry.class))).thenAnswer(inv -> inv.getArgument(0));

        vfs.writeFile(accountId, "/test.edi", null, 10, null, null, new byte[10]);

        // Verify advisory lock was acquired
        verify(entityManager).createNativeQuery(contains("pg_advisory_xact_lock"));
    }
}
