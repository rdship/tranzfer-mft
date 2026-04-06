package com.filetransfer.dmz.audit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class AuditLoggerTest {

    @TempDir
    Path tempDir;

    AuditLogger logger;

    @BeforeEach
    void setUp() {
        logger = new AuditLogger(tempDir.toString(), 90, 100, true);
    }

    @AfterEach
    void tearDown() {
        if (logger != null) logger.shutdown();
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private List<String> readLogLines() throws Exception {
        logger.flush();
        Thread.sleep(100);
        try (Stream<Path> files = Files.list(tempDir)) {
            List<Path> jsonlFiles = files
                    .filter(p -> p.toString().endsWith(".jsonl"))
                    .toList();
            assertFalse(jsonlFiles.isEmpty(), "Expected at least one .jsonl file");
            return Files.readAllLines(jsonlFiles.get(0));
        }
    }

    // ── Tests ────────────────────────────────────────────────────────────

    @Test
    void disabled_noFilesCreated() throws Exception {
        // Shut down the enabled logger from setUp
        logger.shutdown();

        // Use a fresh subdirectory so the enabled logger's file does not interfere
        Path disabledDir = tempDir.resolve("disabled");
        Files.createDirectories(disabledDir);

        logger = new AuditLogger(disabledDir.toString(), 90, 100, false);

        logger.logConnection("OPEN", "10.0.0.1", 8088, "sftp-gw", "RULES", "ALLOW", 0, "SSH", "test");
        logger.logTls("10.0.0.1", 8088, "sftp-gw", "TLSv1.3", "AES256", true, "CN=test");
        logger.logVerdict("10.0.0.1", 8088, "sftp-gw", "ALLOW", 0, "clean", false, null);

        Thread.sleep(100);

        try (Stream<Path> files = Files.list(disabledDir)) {
            List<Path> jsonlFiles = files
                    .filter(p -> p.toString().endsWith(".jsonl"))
                    .toList();
            assertTrue(jsonlFiles.isEmpty(), "No .jsonl files should exist when disabled");
        }
    }

    @Test
    void logConnection_writesJsonLine() throws Exception {
        logger.logConnection("OPEN", "192.168.1.100", 8088, "sftp-gw",
                "RULES", "ALLOW", 10, "SSH", "new connection");

        List<String> lines = readLogLines();
        assertFalse(lines.isEmpty(), "Expected at least one log line");

        String line = lines.get(0);
        assertTrue(line.contains("\"type\":\"CONNECTION\""), "Line should contain CONNECTION type");
        assertTrue(line.contains("192.168.1.100"), "Line should contain source IP");
    }

    @Test
    void logTls_writesJsonLine() throws Exception {
        logger.logTls("10.0.0.5", 443, "https-gw", "TLSv1.3",
                "TLS_AES_256_GCM_SHA384", true, "CN=client.example.com");

        List<String> lines = readLogLines();
        assertFalse(lines.isEmpty());

        String line = lines.get(0);
        assertTrue(line.contains("\"type\":\"TLS\""), "Line should contain TLS type");
    }

    @Test
    void logVerdict_writesJsonLine() throws Exception {
        logger.logVerdict("10.0.0.5", 8088, "sftp-gw", "BLOCK", 85,
                "suspicious pattern", true, 150L);

        List<String> lines = readLogLines();
        assertFalse(lines.isEmpty());

        String line = lines.get(0);
        assertTrue(line.contains("\"type\":\"VERDICT\""), "Line should contain VERDICT type");
    }

    @Test
    void logZone_writesJsonLine() throws Exception {
        logger.logZone("10.0.0.5", 8088, "sftp-gw", "DMZ", "INTERNAL",
                true, "allowed by policy");

        List<String> lines = readLogLines();
        assertFalse(lines.isEmpty());

        String line = lines.get(0);
        assertTrue(line.contains("\"type\":\"ZONE\""), "Line should contain ZONE type");
    }

    @Test
    void logEgress_writesJsonLine() throws Exception {
        logger.logEgress("backend.internal", 8080, "sftp-gw",
                true, "whitelisted host");

        List<String> lines = readLogLines();
        assertFalse(lines.isEmpty());

        String line = lines.get(0);
        assertTrue(line.contains("\"type\":\"EGRESS\""), "Line should contain EGRESS type");
    }

    @Test
    void logInspection_writesJsonLine() throws Exception {
        logger.logInspection("10.0.0.5", 8088, "sftp-gw", "SSH",
                "malware_signature", "CRITICAL", "Detected known malware pattern");

        List<String> lines = readLogLines();
        assertFalse(lines.isEmpty());

        String line = lines.get(0);
        assertTrue(line.contains("\"type\":\"INSPECTION\""), "Line should contain INSPECTION type");
    }

    @Test
    void multipleEvents_allWritten() throws Exception {
        for (int i = 0; i < 10; i++) {
            logger.logConnection("OPEN", "10.0.0." + i, 8088, "sftp-gw",
                    "RULES", "ALLOW", i, "SSH", "connection " + i);
        }

        List<String> lines = readLogLines();
        assertEquals(10, lines.size(), "Expected exactly 10 log lines");
    }

    @Test
    void getStats_tracksEventCount() {
        for (int i = 0; i < 5; i++) {
            logger.logConnection("OPEN", "10.0.0.1", 8088, "sftp-gw",
                    "RULES", "ALLOW", 0, "SSH", "event " + i);
        }

        Map<String, Object> stats = logger.getStats();
        assertEquals(5L, stats.get("eventsWritten"), "eventsWritten should be 5");
        assertTrue((boolean) stats.get("enabled"), "enabled should be true");
    }

    @Test
    void shutdown_flushesAndCloses() throws Exception {
        logger.logConnection("OPEN", "10.0.0.1", 8088, "sftp-gw",
                "RULES", "ALLOW", 0, "SSH", "pre-shutdown event");
        logger.logVerdict("10.0.0.1", 8088, "sftp-gw", "ALLOW", 0,
                "clean", false, null);

        logger.shutdown();

        // After shutdown, file should have content
        try (Stream<Path> files = Files.list(tempDir)) {
            List<Path> jsonlFiles = files
                    .filter(p -> p.toString().endsWith(".jsonl"))
                    .toList();
            assertFalse(jsonlFiles.isEmpty(), "Expected at least one .jsonl file after shutdown");

            List<String> lines = Files.readAllLines(jsonlFiles.get(0));
            assertFalse(lines.isEmpty(), "File should have content after shutdown flush");
        }

        // Prevent tearDown from calling shutdown again on an already-shut-down logger
        logger = null;
    }

    @Test
    void jsonEscaping_handlesSpecialChars() throws Exception {
        String trickyDetail = "line1\nline2\ttab \"quoted\" back\\slash";

        logger.logConnection("OPEN", "10.0.0.1", 8088, "sftp-gw",
                "RULES", "ALLOW", 0, "SSH", trickyDetail);

        List<String> lines = readLogLines();
        assertFalse(lines.isEmpty());

        String line = lines.get(0);
        // The raw line should be valid single-line JSON (no unescaped newlines)
        assertFalse(line.contains("\n"), "JSON line should not contain literal newline");
        // Escaped forms should be present
        assertTrue(line.contains("\\n"), "Should contain escaped newline");
        assertTrue(line.contains("\\t"), "Should contain escaped tab");
        assertTrue(line.contains("\\\"quoted\\\""), "Should contain escaped quotes");
        assertTrue(line.contains("\\\\slash"), "Should contain escaped backslash");
    }
}
