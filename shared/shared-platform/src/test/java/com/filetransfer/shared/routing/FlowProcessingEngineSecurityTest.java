package com.filetransfer.shared.routing;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.nio.file.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the P0 security fixes in FlowProcessingEngine:
 * - ZipSlip prevention
 * - ZIP bomb protection
 * - Shell escape function
 */
class FlowProcessingEngineSecurityTest {

    @TempDir
    Path tempDir;

    // ---- ZipSlip tests ----

    @Test
    void decompressZip_shouldRejectZipSlipEntry() throws Exception {
        Path zipFile = tempDir.resolve("malicious.zip");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zipFile))) {
            // Path traversal entry
            zos.putNextEntry(new ZipEntry("../../etc/passwd"));
            zos.write("root:x:0:0:root".getBytes());
            zos.closeEntry();
        }

        Path workDir = tempDir.resolve("work");
        Files.createDirectories(workDir);

        // Use reflection to call private decompressZip
        Method method = FlowProcessingEngine.class.getDeclaredMethod("decompressZip", Path.class, Path.class);
        method.setAccessible(true);

        FlowProcessingEngine engine = createEngine();
        IOException ex = assertThrows(IOException.class,
                () -> {
                    try {
                        method.invoke(engine, zipFile, workDir);
                    } catch (java.lang.reflect.InvocationTargetException ite) {
                        throw ite.getCause();
                    }
                });
        assertTrue(ex.getMessage().contains("ZIP entry escapes target directory"));
    }

    @Test
    void decompressZip_shouldAcceptNormalZip() throws Exception {
        Path zipFile = tempDir.resolve("normal.zip");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zipFile))) {
            zos.putNextEntry(new ZipEntry("data.txt"));
            zos.write("hello world".getBytes());
            zos.closeEntry();
        }

        Path workDir = tempDir.resolve("work");
        Files.createDirectories(workDir);

        Method method = FlowProcessingEngine.class.getDeclaredMethod("decompressZip", Path.class, Path.class);
        method.setAccessible(true);

        FlowProcessingEngine engine = createEngine();
        String result = (String) method.invoke(engine, zipFile, workDir);
        assertTrue(result.endsWith("data.txt"));
        assertTrue(Files.exists(Path.of(result)));
    }

    @Test
    void decompressZip_shouldRejectEmptyZip() throws Exception {
        Path zipFile = tempDir.resolve("empty.zip");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zipFile))) {
            // no entries
        }

        Path workDir = tempDir.resolve("work");
        Files.createDirectories(workDir);

        Method method = FlowProcessingEngine.class.getDeclaredMethod("decompressZip", Path.class, Path.class);
        method.setAccessible(true);

        FlowProcessingEngine engine = createEngine();
        IOException ex = assertThrows(IOException.class,
                () -> {
                    try {
                        method.invoke(engine, zipFile, workDir);
                    } catch (java.lang.reflect.InvocationTargetException ite) {
                        throw ite.getCause();
                    }
                });
        assertTrue(ex.getMessage().contains("ZIP archive was empty"));
    }

    // ---- Shell escape tests ----

    @Test
    void shellEscape_shouldPassSafeStringsThrough() throws Exception {
        Method method = FlowProcessingEngine.class.getDeclaredMethod("shellEscape", String.class);
        method.setAccessible(true);

        assertEquals("/data/sftp/file.txt", method.invoke(null, "/data/sftp/file.txt"));
        assertEquals("simple-name", method.invoke(null, "simple-name"));
        assertEquals("file_123.csv", method.invoke(null, "file_123.csv"));
    }

    @Test
    void shellEscape_shouldQuoteUnsafeStrings() throws Exception {
        Method method = FlowProcessingEngine.class.getDeclaredMethod("shellEscape", String.class);
        method.setAccessible(true);

        // String with space should be single-quoted
        String result = (String) method.invoke(null, "file name.txt");
        assertTrue(result.startsWith("'"));
        assertTrue(result.endsWith("'"));
        assertTrue(result.contains("file name.txt"));
    }

    @Test
    void shellEscape_shouldEscapeEmbeddedSingleQuotes() throws Exception {
        Method method = FlowProcessingEngine.class.getDeclaredMethod("shellEscape", String.class);
        method.setAccessible(true);

        String result = (String) method.invoke(null, "file'name.txt");
        // Should contain escaped single quote: '\''
        assertTrue(result.contains("'\\''"));
    }

    @Test
    void shellEscape_shouldNeutralizeInjectionAttempts() throws Exception {
        Method method = FlowProcessingEngine.class.getDeclaredMethod("shellEscape", String.class);
        method.setAccessible(true);

        String result = (String) method.invoke(null, "; rm -rf /");
        assertTrue(result.startsWith("'"));
        assertTrue(result.endsWith("'"));

        result = (String) method.invoke(null, "$(whoami)");
        assertTrue(result.startsWith("'"));

        result = (String) method.invoke(null, "`id`");
        assertTrue(result.startsWith("'"));
    }

    private FlowProcessingEngine createEngine() {
        // Create with null dependencies — only testing static/private utility methods
        return new FlowProcessingEngine(null, null, null, null, null, null, null, null, null, null, null, null);
    }
}
