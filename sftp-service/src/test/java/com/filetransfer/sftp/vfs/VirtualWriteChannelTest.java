package com.filetransfer.sftp.vfs;

import com.filetransfer.shared.client.StorageServiceClient;
import com.filetransfer.shared.entity.vfs.VfsChunk;
import com.filetransfer.shared.entity.vfs.VirtualEntry;
import com.filetransfer.shared.repository.VfsChunkRepository;
import com.filetransfer.shared.repository.TransferAccountRepository;
import com.filetransfer.shared.repository.VfsIntentRepository;
import com.filetransfer.shared.repository.VirtualEntryRepository;
import com.filetransfer.shared.vfs.VirtualFileSystem;
import com.filetransfer.shared.vfs.VirtualSftpFileSystem;
import com.filetransfer.shared.vfs.VirtualSftpPath;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.lang.reflect.Field;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.spi.FileSystemProvider;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Bucket-aware write tests for SFTP VirtualWriteChannel.
 *
 * Tests the write path through VirtualSftpFileSystemProvider → VirtualWriteChannel
 * to verify INLINE, STANDARD, and CHUNKED bucket routing works end-to-end
 * for the SFTP service.
 */
@ExtendWith(MockitoExtension.class)
class VirtualWriteChannelTest {

    @Mock private VirtualEntryRepository entryRepository;
    @Mock private StorageServiceClient storageClient;
    @Mock private VfsIntentRepository intentRepository;
    @Mock private VfsChunkRepository chunkRepository;
    @Mock private EntityManager entityManager;
    @Mock private TransferAccountRepository accountRepository;

    private VirtualFileSystem vfs;
    private VirtualSftpFileSystem fileSystem;
    private FileSystemProvider provider;

    private final UUID accountId = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @BeforeEach
    void setUp() throws Exception {
        vfs = new VirtualFileSystem(entryRepository, storageClient, intentRepository,
                chunkRepository, entityManager, accountRepository);

        // Set VFS config via reflection (same pattern as StorageBucketTest)
        Field podIdField = VirtualFileSystem.class.getDeclaredField("podId");
        podIdField.setAccessible(true);
        podIdField.set(vfs, "test-pod");

        Field inlineMax = VirtualFileSystem.class.getDeclaredField("inlineMaxBytes");
        inlineMax.setAccessible(true);
        inlineMax.set(vfs, 65536L);

        Field chunkThreshold = VirtualFileSystem.class.getDeclaredField("chunkThresholdBytes");
        chunkThreshold.setAccessible(true);
        chunkThreshold.set(vfs, 67108864L);

        // Mock advisory lock
        Query mockQuery = mock(Query.class);
        lenient().when(entityManager.createNativeQuery(anyString())).thenReturn(mockQuery);
        lenient().when(mockQuery.setParameter(anyString(), any())).thenReturn(mockQuery);
        lenient().when(mockQuery.getSingleResult()).thenReturn(BigInteger.ONE);

        // Intent save pass-through
        lenient().when(intentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(entryRepository.save(any())).thenAnswer(inv -> {
            VirtualEntry e = inv.getArgument(0);
            if (e.getId() == null) e.setId(UUID.randomUUID());
            return e;
        });

        // Build file system — provider is created internally (package-private constructor)
        fileSystem = new VirtualSftpFileSystem(accountId, vfs, storageClient);
        provider = fileSystem.provider();
    }

    private SeekableByteChannel openWriteChannel(String path) throws IOException {
        VirtualSftpPath vpath = new VirtualSftpPath(fileSystem, path);
        Set<OpenOption> opts = Set.of(StandardOpenOption.WRITE, StandardOpenOption.CREATE);
        return provider.newByteChannel(vpath, opts);
    }

    // ── INLINE bucket ─────────────────────────────────────────────────

    @Test
    void writeChannel_inlineBucket_storesInDb() throws Exception {
        byte[] smallData = "small EDI file content".getBytes();

        // No existing entry
        when(entryRepository.findByAccountIdAndPathAndDeletedFalse(any(), anyString()))
                .thenReturn(Optional.empty());
        when(entryRepository.existsByAccountIdAndPathAndDeletedFalse(any(), anyString()))
                .thenReturn(false);

        SeekableByteChannel channel = openWriteChannel("/inbox/small.edi");
        channel.write(ByteBuffer.wrap(smallData));
        channel.close();

        // INLINE: VFS writeFile called with inlineContent, no Storage Manager call
        verify(entryRepository).save(argThat(entry ->
                "INLINE".equals(entry.getStorageBucket()) &&
                entry.getInlineContent() != null &&
                entry.getInlineContent().length == smallData.length
        ));
        verifyNoInteractions(storageClient);
    }

    // ── STANDARD bucket ───────────────────────────────────────────────

    @Test
    void writeChannel_standardBucket_onboardsToCas() throws Exception {
        byte[] mediumData = new byte[100_000]; // 100KB

        when(entryRepository.findByAccountIdAndPathAndDeletedFalse(any(), anyString()))
                .thenReturn(Optional.empty());
        when(entryRepository.existsByAccountIdAndPathAndDeletedFalse(any(), anyString()))
                .thenReturn(false);
        // STANDARD now streams via store(Path, trackId, account) — zero heap copy
        when(storageClient.store(any(Path.class), isNull(), anyString()))
                .thenReturn(Map.of("sha256", "sha256hash", "trackId", "TRK001"));

        SeekableByteChannel channel = openWriteChannel("/inbox/report.csv");
        channel.write(ByteBuffer.wrap(mediumData));
        channel.close();

        // STANDARD: stream temp file to CAS, then register in VFS
        verify(storageClient).store(any(Path.class), isNull(), anyString());
        verify(entryRepository).save(argThat(entry ->
                "STANDARD".equals(entry.getStorageBucket()) &&
                "sha256hash".equals(entry.getStorageKey()) &&
                entry.getInlineContent() == null
        ));
    }

    // ── CHUNKED bucket ────────────────────────────────────────────────

    @Test
    void writeChannel_chunkedBucket_onboardsChunksAndRegisters() throws Exception {
        byte[] largeData = new byte[5 * 1024 * 1024]; // 5MB → 2 chunks at 4MB

        // Lower chunk threshold for this test so 5MB triggers CHUNKED
        Field chunkThreshold = VirtualFileSystem.class.getDeclaredField("chunkThresholdBytes");
        chunkThreshold.setAccessible(true);
        chunkThreshold.set(vfs, 4_000_000L); // 4MB threshold

        when(entryRepository.findByAccountIdAndPathAndDeletedFalse(any(), anyString()))
                .thenReturn(Optional.empty());
        when(entryRepository.existsByAccountIdAndPathAndDeletedFalse(any(), anyString()))
                .thenReturn(false);
        when(storageClient.store(anyString(), any(byte[].class), isNull(), anyString()))
                .thenReturn(Map.of("sha256", "chunksha256"));
        when(chunkRepository.save(any(VfsChunk.class))).thenAnswer(inv -> inv.getArgument(0));

        SeekableByteChannel channel = openWriteChannel("/inbox/archive.bin");
        channel.write(ByteBuffer.wrap(largeData));
        channel.close();

        // CHUNKED: manifest entry created, then chunks onboarded
        verify(entryRepository, atLeastOnce()).save(argThat(entry ->
                "CHUNKED".equals(entry.getStorageBucket())
        ));
        // 2 chunks: 4MB + 1MB
        verify(storageClient, times(2)).store(anyString(), any(byte[].class), isNull(), anyString());
        verify(chunkRepository, times(2)).save(any(VfsChunk.class));
    }

    // ── Empty write ───────────────────────────────────────────────────

    @Test
    void writeChannel_emptyWrite_noInteraction() throws Exception {
        SeekableByteChannel channel = openWriteChannel("/inbox/empty.txt");
        channel.close(); // close without writing

        verify(entryRepository, never()).save(any());
        verifyNoInteractions(storageClient);
    }

    // ── Read channel routes through VFS ───────────────────────────────

    @Test
    void readChannel_delegatesToVfsReadFile() throws Exception {
        byte[] content = "file data here".getBytes();
        VirtualEntry entry = VirtualEntry.builder()
                .id(UUID.randomUUID())
                .accountId(accountId)
                .path("/inbox/data.txt")
                .type(VirtualEntry.EntryType.FILE)
                .storageBucket("STANDARD")
                .trackId("TRK999")
                .sizeBytes(content.length)
                .build();

        // Override the lenient save mock for reads — need entry with ID
        when(entryRepository.findByAccountIdAndPathAndDeletedFalse(accountId, "/inbox/data.txt"))
                .thenReturn(Optional.of(entry));
        when(storageClient.retrieve("TRK999")).thenReturn(content);

        VirtualSftpPath vpath = new VirtualSftpPath(fileSystem, "/inbox/data.txt");
        Set<OpenOption> opts = Set.of(StandardOpenOption.READ);
        SeekableByteChannel channel = provider.newByteChannel(vpath, opts);

        ByteBuffer buf = ByteBuffer.allocate(100);
        int read = channel.read(buf);
        buf.flip();
        byte[] result = new byte[read];
        buf.get(result);

        assertArrayEquals(content, result);
        verify(storageClient).retrieve("TRK999");
    }
}
