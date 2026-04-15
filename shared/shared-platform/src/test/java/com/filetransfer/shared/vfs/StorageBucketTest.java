package com.filetransfer.shared.vfs;

import com.filetransfer.shared.client.StorageServiceClient;
import com.filetransfer.shared.entity.vfs.VfsChunk;
import com.filetransfer.shared.entity.vfs.VirtualEntry;
import com.filetransfer.shared.repository.vfs.VfsChunkRepository;
import com.filetransfer.shared.repository.vfs.VfsIntentRepository;
import com.filetransfer.shared.repository.core.TransferAccountRepository;
import com.filetransfer.shared.repository.vfs.VirtualEntryRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.math.BigInteger;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StorageBucketTest {

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

        Field podIdField = VirtualFileSystem.class.getDeclaredField("podId");
        podIdField.setAccessible(true);
        podIdField.set(vfs, "test-pod");

        Field inlineMax = VirtualFileSystem.class.getDeclaredField("inlineMaxBytes");
        inlineMax.setAccessible(true);
        inlineMax.set(vfs, 65536L);

        Field chunkThreshold = VirtualFileSystem.class.getDeclaredField("chunkThresholdBytes");
        chunkThreshold.setAccessible(true);
        chunkThreshold.set(vfs, 67108864L);

        Query mockQuery = mock(Query.class);
        when(entityManager.createNativeQuery(anyString())).thenReturn(mockQuery);
        when(mockQuery.setParameter(anyString(), any())).thenReturn(mockQuery);
        when(mockQuery.getSingleResult()).thenReturn(BigInteger.ONE);

        when(intentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    // ── Bucket Thresholds ──────────────────────────────────────────────

    @Test
    void thresholds_inlineBoundary() {
        assertEquals("INLINE", vfs.determineBucket(0));
        assertEquals("INLINE", vfs.determineBucket(1));
        assertEquals("INLINE", vfs.determineBucket(65536));
        assertEquals("STANDARD", vfs.determineBucket(65537));
    }

    @Test
    void thresholds_chunkedBoundary() {
        assertEquals("STANDARD", vfs.determineBucket(67108864));
        assertEquals("CHUNKED", vfs.determineBucket(67108865));
        assertEquals("CHUNKED", vfs.determineBucket(10_737_418_240L)); // 10GB
    }

    // ── Read Routing ───────────────────────────────────────────────────

    @Test
    void readFile_inlineBucket_noStorageClientCall() {
        byte[] content = "inline data".getBytes();
        VirtualEntry entry = VirtualEntry.builder()
                .accountId(accountId)
                .path("/inbox/small.edi")
                .type(VirtualEntry.EntryType.FILE)
                .storageBucket("INLINE")
                .inlineContent(content)
                .build();

        when(entryRepository.findByAccountIdAndPathAndDeletedFalse(any(), eq("/inbox/small.edi")))
                .thenReturn(Optional.of(entry));
        when(entryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        byte[] result = vfs.readFile(accountId, "/inbox/small.edi");

        assertArrayEquals(content, result);
        verifyNoInteractions(storageClient);
    }

    @Test
    void readFile_standardBucket_callsStorageClient() {
        VirtualEntry entry = VirtualEntry.builder()
                .accountId(accountId)
                .path("/inbox/medium.csv")
                .type(VirtualEntry.EntryType.FILE)
                .storageBucket("STANDARD")
                .trackId("TRK123")
                .build();

        when(entryRepository.findByAccountIdAndPathAndDeletedFalse(any(), eq("/inbox/medium.csv")))
                .thenReturn(Optional.of(entry));
        when(entryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(storageClient.retrieve("TRK123")).thenReturn("csv content".getBytes());

        byte[] result = vfs.readFile(accountId, "/inbox/medium.csv");

        assertArrayEquals("csv content".getBytes(), result);
        verify(storageClient).retrieve("TRK123");
    }

    @Test
    void readFile_chunkedBucket_reassemblesChunks() {
        UUID entryId = UUID.randomUUID();
        VirtualEntry entry = VirtualEntry.builder()
                .id(entryId)
                .accountId(accountId)
                .path("/inbox/large.bin")
                .type(VirtualEntry.EntryType.FILE)
                .storageBucket("CHUNKED")
                .sizeBytes(8)
                .build();

        VfsChunk chunk0 = VfsChunk.builder()
                .entryId(entryId).chunkIndex(0).storageKey("key0").sizeBytes(4).sha256("h0").build();
        VfsChunk chunk1 = VfsChunk.builder()
                .entryId(entryId).chunkIndex(1).storageKey("key1").sizeBytes(4).sha256("h1").build();

        when(entryRepository.findByAccountIdAndPathAndDeletedFalse(any(), eq("/inbox/large.bin")))
                .thenReturn(Optional.of(entry));
        when(entryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(chunkRepository.findByEntryIdOrderByChunkIndex(entryId))
                .thenReturn(List.of(chunk0, chunk1));
        when(storageClient.retrieve("key0")).thenReturn("AAAA".getBytes());
        when(storageClient.retrieve("key1")).thenReturn("BBBB".getBytes());

        byte[] result = vfs.readFile(accountId, "/inbox/large.bin");

        assertArrayEquals("AAAABBBB".getBytes(), result);
        verify(storageClient).retrieve("key0");
        verify(storageClient).retrieve("key1");
    }

    // ── Chunk Registration ──────────────────────────────────────────────

    @Test
    void registerChunk_createsChunkRecord() {
        UUID entryId = UUID.randomUUID();

        when(chunkRepository.save(any(VfsChunk.class))).thenAnswer(inv -> inv.getArgument(0));

        VfsChunk chunk = vfs.registerChunk(entryId, 0, "sha256key", 4_000_000, "sha256key");

        assertNotNull(chunk);
        assertEquals(entryId, chunk.getEntryId());
        assertEquals(0, chunk.getChunkIndex());
        assertEquals("sha256key", chunk.getStorageKey());
        assertEquals(4_000_000, chunk.getSizeBytes());
        assertEquals(VfsChunk.ChunkStatus.STORED, chunk.getStatus());
        verify(chunkRepository).save(any(VfsChunk.class));
    }

    // ── Legacy (null bucket defaults to STANDARD) ──────────────────────

    @Test
    void readFile_nullBucket_treatedAsStandard() {
        VirtualEntry entry = VirtualEntry.builder()
                .accountId(accountId)
                .path("/inbox/legacy.txt")
                .type(VirtualEntry.EntryType.FILE)
                .storageBucket(null)
                .trackId("TRK_LEGACY")
                .build();

        when(entryRepository.findByAccountIdAndPathAndDeletedFalse(any(), eq("/inbox/legacy.txt")))
                .thenReturn(Optional.of(entry));
        when(entryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(storageClient.retrieve("TRK_LEGACY")).thenReturn("legacy data".getBytes());

        byte[] result = vfs.readFile(accountId, "/inbox/legacy.txt");

        assertArrayEquals("legacy data".getBytes(), result);
    }
}
