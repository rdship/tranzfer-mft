package com.filetransfer.storage.engine;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.security.MessageDigest;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the ParallelIOEngine write/read/tierCopy operations
 * using the real filesystem (no mocks needed for I/O engine).
 */
class ParallelIOEngineTest {

    @TempDir
    Path tempDir;

    private ParallelIOEngine engine;

    @BeforeEach
    void setUp() throws Exception {
        engine = new ParallelIOEngine();
        // Set fields via reflection since they're @Value-injected
        setField(engine, "stripeSizeKb", 64); // 64KB stripes for testing
        setField(engine, "ioThreads", 4);
        setField(engine, "writeBufferMb", 1);
        engine.init();
    }

    @Test
    void writeDirect_smallFile_shouldWriteCorrectly() throws Exception {
        byte[] data = "Hello, ParallelIOEngine!".getBytes();
        Path dest = tempDir.resolve("small.txt");

        ParallelIOEngine.WriteResult result = engine.write(
                new ByteArrayInputStream(data), dest, data.length);

        assertTrue(Files.exists(dest));
        assertEquals(data.length, result.getSizeBytes());
        assertFalse(result.isStriped());
        assertEquals(1, result.getStripeCount());
        assertArrayEquals(data, Files.readAllBytes(dest));
    }

    @Test
    void writeDirect_shouldComputeCorrectSha256() throws Exception {
        byte[] data = "checksum test data".getBytes();
        Path dest = tempDir.resolve("checksum.txt");

        String expectedHash = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(data));

        ParallelIOEngine.WriteResult result = engine.write(
                new ByteArrayInputStream(data), dest, data.length);

        assertEquals(expectedHash, result.getSha256());
    }

    @Test
    void writeStriped_largeFile_shouldWriteCorrectly() throws Exception {
        // Create data larger than stripe size (64KB) to force striped write
        byte[] data = new byte[256 * 1024]; // 256KB
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) (i % 251); // deterministic pattern
        }
        Path dest = tempDir.resolve("large.bin");

        ParallelIOEngine.WriteResult result = engine.write(
                new ByteArrayInputStream(data), dest, data.length);

        assertTrue(Files.exists(dest));
        assertEquals(data.length, result.getSizeBytes());
        assertTrue(result.isStriped());
        assertTrue(result.getStripeCount() > 1);

        // Verify integrity — the file content should be identical
        byte[] written = Files.readAllBytes(dest);
        assertArrayEquals(data, written, "Striped write should preserve data integrity");
    }

    @Test
    void writeStriped_shouldComputeCorrectSha256() throws Exception {
        byte[] data = new byte[128 * 1024]; // 128KB
        for (int i = 0; i < data.length; i++) data[i] = (byte) (i & 0xFF);
        Path dest = tempDir.resolve("hash-check.bin");

        String expectedHash = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(data));

        ParallelIOEngine.WriteResult result = engine.write(
                new ByteArrayInputStream(data), dest, data.length);

        assertEquals(expectedHash, result.getSha256());
    }

    @Test
    void read_shouldReturnFileContent() throws Exception {
        byte[] data = "read test content".getBytes();
        Path file = tempDir.resolve("read-test.txt");
        Files.write(file, data);

        ParallelIOEngine.ReadResult result = engine.read(file);

        assertArrayEquals(data, result.getData());
        assertEquals(data.length, result.getSizeBytes());
    }

    @Test
    void tierCopy_shouldCopyWithIntegrity() throws Exception {
        byte[] data = new byte[32 * 1024]; // 32KB
        for (int i = 0; i < data.length; i++) data[i] = (byte) (i % 127);
        Path src = tempDir.resolve("hot/file.bin");
        Path dst = tempDir.resolve("warm/file.bin");
        Files.createDirectories(src.getParent());

        Files.write(src, data);

        String sha256 = engine.tierCopy(src, dst);

        assertTrue(Files.exists(dst));
        assertArrayEquals(data, Files.readAllBytes(dst));

        String expectedHash = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(data));
        assertEquals(expectedHash, sha256);
    }

    @Test
    void write_shouldCreateParentDirectories() throws Exception {
        byte[] data = "nested write".getBytes();
        Path dest = tempDir.resolve("a/b/c/deep.txt");

        engine.write(new ByteArrayInputStream(data), dest, data.length);

        assertTrue(Files.exists(dest));
        assertArrayEquals(data, Files.readAllBytes(dest));
    }

    @Test
    void write_emptyFile_shouldHandleGracefully() throws Exception {
        byte[] data = new byte[0];
        Path dest = tempDir.resolve("empty.txt");

        ParallelIOEngine.WriteResult result = engine.write(
                new ByteArrayInputStream(data), dest, 0);

        assertTrue(Files.exists(dest));
        assertEquals(0, result.getSizeBytes());
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        var field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
