package com.filetransfer.ftp.server;

import com.filetransfer.shared.client.StorageServiceClient;
import com.filetransfer.shared.entity.VirtualEntry;
import com.filetransfer.shared.vfs.VirtualFileSystem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.OutputStream;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Bucket-aware write tests for VirtualFtpFile.
 *
 * Verifies INLINE, STANDARD, and CHUNKED bucket routing in the FTP service's
 * VirtualOutputStream — the same WAIP-protected paths tested in shared but
 * exercised through the FTP FtpFile interface.
 */
@ExtendWith(MockitoExtension.class)
class VirtualFtpFileTest {

    @Mock private VirtualFileSystem vfs;
    @Mock private StorageServiceClient storageClient;

    private final UUID accountId = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @BeforeEach
    void setUp() {
        // stat() returns empty — file doesn't exist yet
        lenient().when(vfs.stat(any(), anyString())).thenReturn(Optional.empty());
    }

    // ── INLINE bucket ─────────────────────────────────────────────────

    @Test
    void write_inlineBucket_noCasCall() throws Exception {
        byte[] smallData = "tiny EDI content".getBytes();
        when(vfs.determineBucket(smallData.length, accountId)).thenReturn("INLINE");
        when(vfs.writeFile(eq(accountId), eq("/inbox/small.edi"), isNull(),
                eq((long) smallData.length), isNull(), isNull(), eq(smallData)))
                .thenReturn(VirtualEntry.builder().id(UUID.randomUUID()).build());

        VirtualFtpFile ftpFile = new VirtualFtpFile("/inbox/small.edi", accountId, vfs, storageClient);

        try (OutputStream out = ftpFile.createOutputStream(0)) {
            out.write(smallData);
        }

        // INLINE: content goes to VFS directly, zero CAS interaction
        verify(vfs).writeFile(eq(accountId), eq("/inbox/small.edi"), isNull(),
                eq((long) smallData.length), isNull(), isNull(), eq(smallData));
        verifyNoInteractions(storageClient);
    }

    // ── STANDARD bucket ───────────────────────────────────────────────

    @Test
    void write_standardBucket_onboardsToCas() throws Exception {
        byte[] mediumData = new byte[100_000]; // 100KB
        when(vfs.determineBucket(mediumData.length, accountId)).thenReturn("STANDARD");
        when(storageClient.store(any(Path.class), isNull(), anyString()))
                .thenReturn(Map.of("sha256", "abc123sha256", "trackId", "TRK001"));

        VirtualFtpFile ftpFile = new VirtualFtpFile("/inbox/report.csv", accountId, vfs, storageClient);

        try (OutputStream out = ftpFile.createOutputStream(0)) {
            out.write(mediumData);
        }

        // STANDARD: stream temp file to Storage Manager (zero heap copy), then register in VFS
        verify(storageClient).store(any(Path.class), isNull(), anyString());
        verify(vfs).writeFile(eq(accountId), eq("/inbox/report.csv"), eq("abc123sha256"),
                eq((long) mediumData.length), eq("TRK001"), isNull(), isNull());
    }

    // ── CHUNKED bucket ────────────────────────────────────────────────

    @Test
    void write_chunkedBucket_onboardsChunksAndRegisters() throws Exception {
        byte[] largeData = new byte[5 * 1024 * 1024]; // 5MB → 2 chunks at 4MB
        UUID entryId = UUID.randomUUID();
        VirtualEntry chunkedEntry = VirtualEntry.builder().id(entryId).build();

        when(vfs.determineBucket(largeData.length, accountId)).thenReturn("CHUNKED");
        when(vfs.writeFile(eq(accountId), eq("/inbox/archive.bin"), isNull(),
                eq((long) largeData.length), isNull(), isNull(), isNull()))
                .thenReturn(chunkedEntry);
        when(storageClient.store(anyString(), any(byte[].class), isNull(), anyString()))
                .thenReturn(Map.of("sha256", "chunksha256"));

        VirtualFtpFile ftpFile = new VirtualFtpFile("/inbox/archive.bin", accountId, vfs, storageClient);

        try (OutputStream out = ftpFile.createOutputStream(0)) {
            out.write(largeData);
        }

        // CHUNKED: manifest in VFS, chunks onboarded to Storage Manager
        verify(vfs).writeFile(eq(accountId), eq("/inbox/archive.bin"), isNull(),
                eq((long) largeData.length), isNull(), isNull(), isNull());
        verify(storageClient, times(2)).store(anyString(), any(byte[].class), isNull(), anyString());
        verify(vfs, times(2)).registerChunk(eq(entryId), anyInt(), eq("chunksha256"),
                anyLong(), eq("chunksha256"));
    }

    // ── Read routing ──────────────────────────────────────────────────

    @Test
    void read_delegatesToVfsReadFile() throws Exception {
        byte[] content = "file content".getBytes();
        VirtualEntry entry = VirtualEntry.builder()
                .accountId(accountId)
                .path("/inbox/data.txt")
                .type(VirtualEntry.EntryType.FILE)
                .sizeBytes(content.length)
                .build();
        when(vfs.stat(accountId, "/inbox/data.txt")).thenReturn(Optional.of(entry));
        when(vfs.readFile(accountId, "/inbox/data.txt")).thenReturn(content);

        VirtualFtpFile ftpFile = new VirtualFtpFile("/inbox/data.txt", accountId, vfs, storageClient);

        byte[] result = ftpFile.createInputStream(0).readAllBytes();

        assertArrayEquals(content, result);
        verify(vfs).readFile(accountId, "/inbox/data.txt");
    }

    // ── Empty write is no-op ──────────────────────────────────────────

    @Test
    void write_emptyData_noInteraction() throws Exception {
        VirtualFtpFile ftpFile = new VirtualFtpFile("/inbox/empty.txt", accountId, vfs, storageClient);

        try (OutputStream out = ftpFile.createOutputStream(0)) {
            // write nothing
        }

        verify(vfs, never()).writeFile(any(), anyString(), any(), anyLong(), any(), any(), any());
        verifyNoInteractions(storageClient);
    }

    // ── Directory operations ──────────────────────────────────────────

    @Test
    void mkdir_delegatesToVfs() {
        VirtualFtpFile ftpFile = new VirtualFtpFile("/inbox/newdir", accountId, vfs, storageClient);
        when(vfs.mkdir(accountId, "/inbox/newdir"))
                .thenReturn(VirtualEntry.builder().build());

        boolean result = ftpFile.mkdir();

        assertTrue(result);
        verify(vfs).mkdir(accountId, "/inbox/newdir");
    }

    @Test
    void delete_delegatesToVfs() {
        VirtualFtpFile ftpFile = new VirtualFtpFile("/inbox/old.txt", accountId, vfs, storageClient);
        when(vfs.delete(accountId, "/inbox/old.txt")).thenReturn(1);

        boolean result = ftpFile.delete();

        assertTrue(result);
        verify(vfs).delete(accountId, "/inbox/old.txt");
    }

    @Test
    void move_delegatesToVfs() {
        VirtualFtpFile src = new VirtualFtpFile("/inbox/a.txt", accountId, vfs, storageClient);
        VirtualFtpFile dst = new VirtualFtpFile("/outbox/a.txt", accountId, vfs, storageClient);

        boolean result = src.move(dst);

        assertTrue(result);
        verify(vfs).move(accountId, "/inbox/a.txt", "/outbox/a.txt");
    }
}
