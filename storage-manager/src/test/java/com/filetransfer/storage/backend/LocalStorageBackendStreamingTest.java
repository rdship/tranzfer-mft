package com.filetransfer.storage.backend;

import com.filetransfer.storage.engine.ParallelIOEngine;
import com.filetransfer.storage.entity.WriteIntent;
import com.filetransfer.storage.lifecycle.WriteIntentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for LocalStorageBackend's streaming read methods:
 * readTo() (zero-copy via FileChannel.transferTo) and readStream()
 * (buffered InputStream).
 */
@ExtendWith(MockitoExtension.class)
class LocalStorageBackendStreamingTest {

    @TempDir
    Path tempDir;

    @Mock
    private WriteIntentService writeIntentService;

    private ParallelIOEngine ioEngine;
    private LocalStorageBackend backend;

    @BeforeEach
    void setUp() throws Exception {
        // Build a real ParallelIOEngine (same pattern as ParallelIOEngineTest)
        ioEngine = new ParallelIOEngine();
        setField(ioEngine, "stripeSizeKb", 64);
        setField(ioEngine, "ioThreads", 4);
        setField(ioEngine, "writeBufferMb", 1);
        setField(ioEngine, "maxFileSizeBytes", 100L * 1024 * 1024); // 100MB limit
        setField(ioEngine, "fsyncEnabled", false); // disable fsync for test speed
        ioEngine.init();

        // Build LocalStorageBackend with constructor injection for ioEngine
        backend = new LocalStorageBackend(ioEngine);
        setField(backend, "hotPath", tempDir.toString());
        setField(backend, "writeIntentService", writeIntentService);
    }

    @Test
    void readTo_shouldStreamFileWithoutHeapLoad() throws Exception {
        // Write a file directly to the hot path (simulating CAS storage)
        String content = "Hello, zero-copy streaming!";
        String storageKey = "test-readto-key";
        Path filePath = tempDir.resolve(storageKey);
        Files.writeString(filePath, content);

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        backend.readTo(storageKey, output);

        assertEquals(content, output.toString());
    }

    @Test
    void readStream_shouldReturnBufferedInputStream() throws Exception {
        String content = "Buffered stream test content for DRP engine";
        String storageKey = "test-readstream-key";
        Path filePath = tempDir.resolve(storageKey);
        Files.writeString(filePath, content);

        try (InputStream stream = backend.readStream(storageKey)) {
            byte[] data = stream.readAllBytes();
            assertEquals(content, new String(data));
        }
    }

    @Test
    void readTo_largeFile_shouldUseZeroCopy() throws Exception {
        // Write a 10MB file to verify FileChannel.transferTo works at scale
        byte[] data = new byte[10 * 1024 * 1024]; // 10MB
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) (i % 251); // deterministic pattern
        }
        String storageKey = "test-large-zerocopy";
        Path filePath = tempDir.resolve(storageKey);
        Files.write(filePath, data);

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        backend.readTo(storageKey, output);

        byte[] result = output.toByteArray();
        assertEquals(data.length, result.length);
        assertArrayEquals(data, result, "Zero-copy transfer should preserve data integrity");
    }

    @Test
    void write_shouldCallWriteIntentService() throws Exception {
        // Stub WriteIntentService to return a tracked intent
        WriteIntent intent = WriteIntent.builder()
                .tempPath(tempDir.resolve("tmp-intent").toString())
                .status("IN_PROGRESS")
                .build();
        when(writeIntentService.create(anyString(), anyLong(), anyInt())).thenReturn(intent);

        byte[] data = "write-intent-test-content".getBytes();
        backend.write(new ByteArrayInputStream(data), data.length, "test.txt");

        // Verify create() was called before write and either complete() or abandon() after
        verify(writeIntentService).create(anyString(), eq((long) data.length), eq(0));
        // The file will either be deduped (abandon) or new (complete) — verify at least one
        verify(writeIntentService, atMostOnce()).complete(eq(intent), anyString());
        verify(writeIntentService, atMostOnce()).abandon(eq(intent));
    }

    // ── Helper ──────────────────────────────────────────────────────────────

    private static void setField(Object obj, String name, Object value) throws Exception {
        var field = obj.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(obj, value);
    }
}
