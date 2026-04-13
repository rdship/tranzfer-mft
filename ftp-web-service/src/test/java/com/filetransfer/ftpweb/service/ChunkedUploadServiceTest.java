package com.filetransfer.ftpweb.service;

import com.filetransfer.shared.entity.vfs.ChunkedUpload;
import com.filetransfer.shared.entity.vfs.ChunkedUploadChunk;
import com.filetransfer.shared.repository.ChunkedUploadChunkRepository;
import com.filetransfer.shared.repository.ChunkedUploadRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ChunkedUploadService.
 * Uses @TempDir for chunk temp storage.
 */
@ExtendWith(MockitoExtension.class)
class ChunkedUploadServiceTest {

    @Mock private ChunkedUploadRepository uploadRepository;
    @Mock private ChunkedUploadChunkRepository chunkRepository;

    private ChunkedUploadService service;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws Exception {
        service = new ChunkedUploadService(uploadRepository, chunkRepository);

        setField("chunkTempDir", tempDir.toString());
        setField("defaultChunkSize", 5242880L);
        setField("maxFileSize", 10737418240L);
        setField("expiryHours", 24);
    }

    private void setField(String name, Object value) throws Exception {
        Field field = ChunkedUploadService.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(service, value);
    }

    // ── initUpload ──────────────────────────────────────────────────────

    @Test
    void initUpload_fileTooLarge_throws() {
        long tooLarge = 10737418240L + 1;

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.initUpload("big.zip", tooLarge, 10, null, "user1", "application/zip"));
        assertTrue(ex.getMessage().contains("exceeds maximum"));
    }

    @Test
    void initUpload_totalChunksZero_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> service.initUpload("file.zip", 1000, 0, null, "user1", "application/zip"));
    }

    @Test
    void initUpload_totalChunksNegative_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> service.initUpload("file.zip", 1000, -1, null, "user1", "application/zip"));
    }

    @Test
    void initUpload_success() {
        when(uploadRepository.save(any(ChunkedUpload.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        ChunkedUpload result = service.initUpload(
                "data.csv", 10_000_000, 2, "abc123", "user1", "text/csv");

        assertNotNull(result);
        assertEquals("data.csv", result.getFilename());
        assertEquals(10_000_000, result.getTotalSize());
        assertEquals(2, result.getTotalChunks());
        assertEquals("abc123", result.getChecksum());
        assertEquals("user1", result.getAccountUsername());
        assertEquals("INITIATED", result.getStatus());

        verify(uploadRepository).save(any(ChunkedUpload.class));
    }

    @Test
    void initUpload_createsDirectory() {
        when(uploadRepository.save(any(ChunkedUpload.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        ChunkedUpload result = service.initUpload(
                "data.csv", 10_000_000, 2, null, "user1", "text/csv");

        // Verify the upload directory was created
        Path uploadDir = tempDir.resolve(result.getId().toString());
        assertTrue(Files.isDirectory(uploadDir));
    }

    // ── receiveChunk ────────────────────────────────────────────────────

    @Test
    void receiveChunk_uploadNotFound_throws() {
        UUID uploadId = UUID.randomUUID();
        when(uploadRepository.findById(uploadId)).thenReturn(Optional.empty());

        assertThrows(NoSuchElementException.class,
                () -> service.receiveChunk(uploadId, 0, InputStream.nullInputStream(), 0));
    }

    @Test
    void receiveChunk_uploadCompleted_throws() {
        UUID uploadId = UUID.randomUUID();
        ChunkedUpload upload = ChunkedUpload.builder()
                .id(uploadId).status("COMPLETED").totalChunks(3).build();
        when(uploadRepository.findById(uploadId)).thenReturn(Optional.of(upload));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.receiveChunk(uploadId, 0, InputStream.nullInputStream(), 0));
        assertTrue(ex.getMessage().contains("COMPLETED"));
    }

    @Test
    void receiveChunk_uploadCancelled_throws() {
        UUID uploadId = UUID.randomUUID();
        ChunkedUpload upload = ChunkedUpload.builder()
                .id(uploadId).status("CANCELLED").totalChunks(3).build();
        when(uploadRepository.findById(uploadId)).thenReturn(Optional.of(upload));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.receiveChunk(uploadId, 0, InputStream.nullInputStream(), 0));
        assertTrue(ex.getMessage().contains("CANCELLED"));
    }

    @Test
    void receiveChunk_invalidChunkNumber_negative_throws() {
        UUID uploadId = UUID.randomUUID();
        ChunkedUpload upload = ChunkedUpload.builder()
                .id(uploadId).status("INITIATED").totalChunks(3).build();
        when(uploadRepository.findById(uploadId)).thenReturn(Optional.of(upload));

        assertThrows(IllegalArgumentException.class,
                () -> service.receiveChunk(uploadId, -1, InputStream.nullInputStream(), 0));
    }

    @Test
    void receiveChunk_invalidChunkNumber_tooHigh_throws() {
        UUID uploadId = UUID.randomUUID();
        ChunkedUpload upload = ChunkedUpload.builder()
                .id(uploadId).status("INITIATED").totalChunks(3).build();
        when(uploadRepository.findById(uploadId)).thenReturn(Optional.of(upload));

        assertThrows(IllegalArgumentException.class,
                () -> service.receiveChunk(uploadId, 3, InputStream.nullInputStream(), 0));
    }

    @Test
    void receiveChunk_idempotent_returnsExisting() throws IOException {
        UUID uploadId = UUID.randomUUID();
        ChunkedUpload upload = ChunkedUpload.builder()
                .id(uploadId).status("IN_PROGRESS").totalChunks(3).build();
        when(uploadRepository.findById(uploadId)).thenReturn(Optional.of(upload));

        ChunkedUploadChunk existingChunk = ChunkedUploadChunk.builder()
                .id(UUID.randomUUID()).uploadId(uploadId).chunkNumber(0).size(100).build();
        when(chunkRepository.findByUploadIdAndChunkNumber(uploadId, 0))
                .thenReturn(Optional.of(existingChunk));

        ChunkedUploadChunk result = service.receiveChunk(
                uploadId, 0, InputStream.nullInputStream(), 100);

        assertSame(existingChunk, result);
        verify(chunkRepository, never()).save(any());
    }

    @Test
    void receiveChunk_success() throws IOException {
        UUID uploadId = UUID.randomUUID();
        ChunkedUpload upload = ChunkedUpload.builder()
                .id(uploadId).status("INITIATED").totalChunks(3).receivedChunks(0).build();
        when(uploadRepository.findById(uploadId)).thenReturn(Optional.of(upload));
        when(chunkRepository.findByUploadIdAndChunkNumber(uploadId, 0))
                .thenReturn(Optional.empty());
        when(chunkRepository.save(any(ChunkedUploadChunk.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(uploadRepository.save(any(ChunkedUpload.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        // Create the upload directory (normally done by initUpload)
        Files.createDirectories(tempDir.resolve(uploadId.toString()));

        byte[] data = "chunk-data-content".getBytes();
        ChunkedUploadChunk result = service.receiveChunk(
                uploadId, 0, new ByteArrayInputStream(data), data.length);

        assertNotNull(result);
        assertEquals(uploadId, result.getUploadId());
        assertEquals(0, result.getChunkNumber());
        assertNotNull(result.getChecksum());

        // Status should transition from INITIATED to IN_PROGRESS
        assertEquals("IN_PROGRESS", upload.getStatus());
        assertEquals(1, upload.getReceivedChunks());

        verify(chunkRepository).save(any(ChunkedUploadChunk.class));
        verify(uploadRepository).save(upload);
    }

    // ── completeUpload ──────────────────────────────────────────────────

    @Test
    void completeUpload_missingChunks_throws() {
        UUID uploadId = UUID.randomUUID();
        ChunkedUpload upload = ChunkedUpload.builder()
                .id(uploadId).status("IN_PROGRESS").totalChunks(3).build();
        when(uploadRepository.findById(uploadId)).thenReturn(Optional.of(upload));
        when(chunkRepository.countByUploadId(uploadId)).thenReturn(2L);

        assertThrows(IllegalStateException.class,
                () -> service.completeUpload(uploadId));
    }

    @Test
    void completeUpload_alreadyCompleted_throws() {
        UUID uploadId = UUID.randomUUID();
        ChunkedUpload upload = ChunkedUpload.builder()
                .id(uploadId).status("COMPLETED").totalChunks(2).build();
        when(uploadRepository.findById(uploadId)).thenReturn(Optional.of(upload));

        assertThrows(IllegalStateException.class,
                () -> service.completeUpload(uploadId));
    }

    @Test
    void completeUpload_success_noChecksum() throws Exception {
        UUID uploadId = UUID.randomUUID();
        ChunkedUpload upload = ChunkedUpload.builder()
                .id(uploadId).filename("assembled.bin")
                .status("IN_PROGRESS").totalChunks(2).checksum(null).build();
        when(uploadRepository.findById(uploadId)).thenReturn(Optional.of(upload));
        when(chunkRepository.countByUploadId(uploadId)).thenReturn(2L);
        when(uploadRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // Create chunk files on disk
        Path uploadDir = Files.createDirectories(tempDir.resolve(uploadId.toString()));
        Path chunk0 = uploadDir.resolve("chunk_000000");
        Path chunk1 = uploadDir.resolve("chunk_000001");
        Files.write(chunk0, "AAAA".getBytes());
        Files.write(chunk1, "BBBB".getBytes());

        List<ChunkedUploadChunk> chunks = List.of(
                ChunkedUploadChunk.builder()
                        .uploadId(uploadId).chunkNumber(0).storagePath(chunk0.toString()).build(),
                ChunkedUploadChunk.builder()
                        .uploadId(uploadId).chunkNumber(1).storagePath(chunk1.toString()).build()
        );
        when(chunkRepository.findByUploadIdOrderByChunkNumberAsc(uploadId)).thenReturn(chunks);

        Path result = service.completeUpload(uploadId);

        assertNotNull(result);
        assertTrue(Files.exists(result));
        assertEquals("AAAABBBB", Files.readString(result));
        assertEquals("COMPLETED", upload.getStatus());
        assertNotNull(upload.getCompletedAt());
    }

    @Test
    void completeUpload_checksumMismatch_fails() throws Exception {
        UUID uploadId = UUID.randomUUID();
        ChunkedUpload upload = ChunkedUpload.builder()
                .id(uploadId).filename("assembled.bin")
                .status("IN_PROGRESS").totalChunks(1).checksum("wrong-checksum").build();
        when(uploadRepository.findById(uploadId)).thenReturn(Optional.of(upload));
        when(chunkRepository.countByUploadId(uploadId)).thenReturn(1L);
        when(uploadRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // Create chunk file on disk
        Path uploadDir = Files.createDirectories(tempDir.resolve(uploadId.toString()));
        Path chunk0 = uploadDir.resolve("chunk_000000");
        Files.write(chunk0, "some data".getBytes());

        List<ChunkedUploadChunk> chunks = List.of(
                ChunkedUploadChunk.builder()
                        .uploadId(uploadId).chunkNumber(0).storagePath(chunk0.toString()).build()
        );
        when(chunkRepository.findByUploadIdOrderByChunkNumberAsc(uploadId)).thenReturn(chunks);

        IOException ex = assertThrows(IOException.class,
                () -> service.completeUpload(uploadId));
        assertTrue(ex.getMessage().contains("Checksum verification failed")
                || ex.getMessage().contains("Failed to assemble"));
        assertEquals("FAILED", upload.getStatus());
        assertNotNull(upload.getErrorMessage());
    }

    @Test
    void completeUpload_checksumMatch_succeeds() throws Exception {
        UUID uploadId = UUID.randomUUID();
        byte[] fileData = "complete file content".getBytes();

        // Compute expected checksum
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(fileData);
        String expectedChecksum = HexFormat.of().formatHex(digest.digest());

        ChunkedUpload upload = ChunkedUpload.builder()
                .id(uploadId).filename("verified.bin")
                .status("IN_PROGRESS").totalChunks(1).checksum(expectedChecksum).build();
        when(uploadRepository.findById(uploadId)).thenReturn(Optional.of(upload));
        when(chunkRepository.countByUploadId(uploadId)).thenReturn(1L);
        when(uploadRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Path uploadDir = Files.createDirectories(tempDir.resolve(uploadId.toString()));
        Path chunk0 = uploadDir.resolve("chunk_000000");
        Files.write(chunk0, fileData);

        List<ChunkedUploadChunk> chunks = List.of(
                ChunkedUploadChunk.builder()
                        .uploadId(uploadId).chunkNumber(0).storagePath(chunk0.toString()).build()
        );
        when(chunkRepository.findByUploadIdOrderByChunkNumberAsc(uploadId)).thenReturn(chunks);

        Path result = service.completeUpload(uploadId);

        assertEquals("COMPLETED", upload.getStatus());
        assertTrue(Files.exists(result));
    }

    // ── getStatus ───────────────────────────────────────────────────────

    @Test
    void getStatus_uploadNotFound_throws() {
        UUID uploadId = UUID.randomUUID();
        when(uploadRepository.findById(uploadId)).thenReturn(Optional.empty());

        assertThrows(NoSuchElementException.class,
                () -> service.getStatus(uploadId));
    }

    @Test
    void getStatus_allChunksReceived() {
        UUID uploadId = UUID.randomUUID();
        ChunkedUpload upload = ChunkedUpload.builder()
                .id(uploadId).filename("file.dat")
                .status("IN_PROGRESS").totalChunks(3).totalSize(15000).chunkSize(5000)
                .createdAt(Instant.now()).expiresAt(Instant.now().plusSeconds(86400)).build();
        when(uploadRepository.findById(uploadId)).thenReturn(Optional.of(upload));

        List<ChunkedUploadChunk> receivedChunks = List.of(
                ChunkedUploadChunk.builder().uploadId(uploadId).chunkNumber(0).build(),
                ChunkedUploadChunk.builder().uploadId(uploadId).chunkNumber(1).build(),
                ChunkedUploadChunk.builder().uploadId(uploadId).chunkNumber(2).build()
        );
        when(chunkRepository.findByUploadIdOrderByChunkNumberAsc(uploadId)).thenReturn(receivedChunks);

        ChunkedUploadService.UploadStatus status = service.getStatus(uploadId);

        assertEquals(uploadId, status.getUploadId());
        assertEquals("file.dat", status.getFilename());
        assertEquals(3, status.getTotalChunks());
        assertEquals(3, status.getReceivedChunks());
        assertTrue(status.getMissingChunks().isEmpty());
        assertEquals(100.0, status.getProgressPercent());
    }

    @Test
    void getStatus_withMissingChunks() {
        UUID uploadId = UUID.randomUUID();
        ChunkedUpload upload = ChunkedUpload.builder()
                .id(uploadId).filename("file.dat")
                .status("IN_PROGRESS").totalChunks(5).totalSize(25000).chunkSize(5000)
                .createdAt(Instant.now()).expiresAt(Instant.now().plusSeconds(86400)).build();
        when(uploadRepository.findById(uploadId)).thenReturn(Optional.of(upload));

        // Only chunks 0 and 3 received — 1, 2, 4 are missing
        List<ChunkedUploadChunk> receivedChunks = List.of(
                ChunkedUploadChunk.builder().uploadId(uploadId).chunkNumber(0).build(),
                ChunkedUploadChunk.builder().uploadId(uploadId).chunkNumber(3).build()
        );
        when(chunkRepository.findByUploadIdOrderByChunkNumberAsc(uploadId)).thenReturn(receivedChunks);

        ChunkedUploadService.UploadStatus status = service.getStatus(uploadId);

        assertEquals(2, status.getReceivedChunks());
        assertEquals(List.of(1, 2, 4), status.getMissingChunks());
        assertEquals(40.0, status.getProgressPercent());
    }

    // ── cancelUpload ────────────────────────────────────────────────────

    @Test
    void cancelUpload_uploadNotFound_throws() {
        UUID uploadId = UUID.randomUUID();
        when(uploadRepository.findById(uploadId)).thenReturn(Optional.empty());

        assertThrows(NoSuchElementException.class,
                () -> service.cancelUpload(uploadId));
    }

    @Test
    void cancelUpload_completedUpload_throws() {
        UUID uploadId = UUID.randomUUID();
        ChunkedUpload upload = ChunkedUpload.builder()
                .id(uploadId).status("COMPLETED").build();
        when(uploadRepository.findById(uploadId)).thenReturn(Optional.of(upload));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.cancelUpload(uploadId));
        assertTrue(ex.getMessage().contains("Cannot cancel a completed upload"));
    }

    @Test
    void cancelUpload_success() throws IOException {
        UUID uploadId = UUID.randomUUID();
        ChunkedUpload upload = ChunkedUpload.builder()
                .id(uploadId).filename("partial.dat").status("IN_PROGRESS").build();
        when(uploadRepository.findById(uploadId)).thenReturn(Optional.of(upload));

        // Create chunk files on disk
        Path uploadDir = Files.createDirectories(tempDir.resolve(uploadId.toString()));
        Path chunkFile = Files.createFile(uploadDir.resolve("chunk_000000"));

        List<ChunkedUploadChunk> chunks = List.of(
                ChunkedUploadChunk.builder()
                        .uploadId(uploadId).chunkNumber(0).storagePath(chunkFile.toString()).build()
        );
        when(chunkRepository.findByUploadIdOrderByChunkNumberAsc(uploadId)).thenReturn(chunks);
        when(uploadRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.cancelUpload(uploadId);

        assertEquals("CANCELLED", upload.getStatus());
        verify(chunkRepository).deleteByUploadId(uploadId);
        // Chunk files and directory should be cleaned up
        assertFalse(Files.exists(chunkFile));
        assertFalse(Files.exists(uploadDir));
    }

    @Test
    void cancelUpload_initiatedStatus_success() {
        UUID uploadId = UUID.randomUUID();
        ChunkedUpload upload = ChunkedUpload.builder()
                .id(uploadId).filename("new.dat").status("INITIATED").build();
        when(uploadRepository.findById(uploadId)).thenReturn(Optional.of(upload));
        when(chunkRepository.findByUploadIdOrderByChunkNumberAsc(uploadId))
                .thenReturn(Collections.emptyList());
        when(uploadRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.cancelUpload(uploadId);

        assertEquals("CANCELLED", upload.getStatus());
        verify(chunkRepository).deleteByUploadId(uploadId);
    }
}
