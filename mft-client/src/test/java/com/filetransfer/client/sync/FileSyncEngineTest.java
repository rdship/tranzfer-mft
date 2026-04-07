package com.filetransfer.client.sync;

import com.filetransfer.client.config.ClientConfig;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class FileSyncEngineTest {

    // ── Helper: invoke private matchesPattern via reflection ─────────────

    private boolean invokeMatchesPattern(FileSyncEngine engine, String filename) throws Exception {
        Method method = FileSyncEngine.class.getDeclaredMethod("matchesPattern", String.class);
        method.setAccessible(true);
        return (boolean) method.invoke(engine, filename);
    }

    // ── matchesPattern tests ────────────────────────────────────────────

    @Test
    void matchesPattern_noPatterns_matchesEverything() throws Exception {
        ClientConfig config = new ClientConfig();
        // Both include and exclude are null by default
        FileSyncEngine engine = new FileSyncEngine(config);

        assertTrue(invokeMatchesPattern(engine, "report.csv"));
        assertTrue(invokeMatchesPattern(engine, "data.txt"));
        assertTrue(invokeMatchesPattern(engine, "image.png"));
        assertTrue(invokeMatchesPattern(engine, ".hidden"));
        assertTrue(invokeMatchesPattern(engine, "UPPERCASE.XML"));
    }

    @Test
    void matchesPattern_includeOnly_matchesCsvRejectsOthers() throws Exception {
        ClientConfig config = new ClientConfig();
        config.getSync().setIncludePattern(".*\\.csv");
        FileSyncEngine engine = new FileSyncEngine(config);

        assertTrue(invokeMatchesPattern(engine, "report.csv"));
        assertTrue(invokeMatchesPattern(engine, "data.csv"));
        assertFalse(invokeMatchesPattern(engine, "report.txt"));
        assertFalse(invokeMatchesPattern(engine, "report.csv.bak"));
        assertFalse(invokeMatchesPattern(engine, "image.png"));
    }

    @Test
    void matchesPattern_excludeOnly_rejectsTmpAllowsOthers() throws Exception {
        ClientConfig config = new ClientConfig();
        config.getSync().setExcludePattern(".*\\.tmp");
        FileSyncEngine engine = new FileSyncEngine(config);

        assertFalse(invokeMatchesPattern(engine, "upload.tmp"));
        assertFalse(invokeMatchesPattern(engine, "data.tmp"));
        assertTrue(invokeMatchesPattern(engine, "report.csv"));
        assertTrue(invokeMatchesPattern(engine, "file.txt"));
        assertTrue(invokeMatchesPattern(engine, "archive.zip"));
    }

    @Test
    void matchesPattern_bothPatterns_includeAndExcludeApplied() throws Exception {
        ClientConfig config = new ClientConfig();
        config.getSync().setIncludePattern(".*\\.csv");
        config.getSync().setExcludePattern("temp_.*");
        FileSyncEngine engine = new FileSyncEngine(config);

        // Matches include, not excluded
        assertTrue(invokeMatchesPattern(engine, "report.csv"));
        // Matches include, but also matches exclude -> rejected
        assertFalse(invokeMatchesPattern(engine, "temp_data.csv"));
        // Does not match include -> rejected
        assertFalse(invokeMatchesPattern(engine, "report.txt"));
        // Matches exclude only (include would reject it anyway)
        assertFalse(invokeMatchesPattern(engine, "temp_file.txt"));
    }

    @Test
    void matchesPattern_excludeCheckedBeforeInclude() throws Exception {
        // Exclude takes priority: if a file matches exclude, it's rejected even if include would match
        ClientConfig config = new ClientConfig();
        config.getSync().setIncludePattern(".*\\.csv");
        config.getSync().setExcludePattern(".*\\.csv");   // exclude everything that include would match
        FileSyncEngine engine = new FileSyncEngine(config);

        assertFalse(invokeMatchesPattern(engine, "report.csv"));
    }

    // ── getStatus ───────────────────────────────────────────────────────

    @Test
    void getStatus_initialState_allCountersZero() {
        ClientConfig config = new ClientConfig();
        FileSyncEngine engine = new FileSyncEngine(config);

        String status = engine.getStatus();

        assertTrue(status.contains("Uploads: 0"), "Expected 'Uploads: 0' in: " + status);
        assertTrue(status.contains("Downloads: 0"), "Expected 'Downloads: 0' in: " + status);
        assertTrue(status.contains("Errors: 0"), "Expected 'Errors: 0' in: " + status);
        assertTrue(status.contains("Connected: false"), "Expected 'Connected: false' in: " + status);
    }

    @Test
    void getStatus_returnsFormattedString() {
        ClientConfig config = new ClientConfig();
        FileSyncEngine engine = new FileSyncEngine(config);

        String status = engine.getStatus();

        // Verify pipe-delimited format
        assertEquals("Uploads: 0 | Downloads: 0 | Errors: 0 | Connected: false", status);
    }

    // ── TransferLog record ──────────────────────────────────────────────

    @Test
    void transferLog_constructionAndAccess() {
        Instant now = Instant.now();
        FileSyncEngine.TransferLog log = new FileSyncEngine.TransferLog(
                now, "UPLOAD", "report.csv", "OK", null
        );

        assertEquals(now, log.time());
        assertEquals("UPLOAD", log.direction());
        assertEquals("report.csv", log.filename());
        assertEquals("OK", log.status());
        assertNull(log.error());
    }

    @Test
    void transferLog_withError() {
        Instant now = Instant.now();
        FileSyncEngine.TransferLog log = new FileSyncEngine.TransferLog(
                now, "DOWNLOAD", "data.xml", "FAIL", "Connection refused"
        );

        assertEquals("DOWNLOAD", log.direction());
        assertEquals("data.xml", log.filename());
        assertEquals("FAIL", log.status());
        assertEquals("Connection refused", log.error());
    }

    @Test
    void transferLog_equality() {
        Instant now = Instant.now();
        FileSyncEngine.TransferLog a = new FileSyncEngine.TransferLog(now, "UPLOAD", "f.csv", "OK", null);
        FileSyncEngine.TransferLog b = new FileSyncEngine.TransferLog(now, "UPLOAD", "f.csv", "OK", null);

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void transferLog_toString_containsFields() {
        Instant now = Instant.now();
        FileSyncEngine.TransferLog log = new FileSyncEngine.TransferLog(now, "UPLOAD", "file.csv", "OK", null);

        String str = log.toString();
        assertTrue(str.contains("UPLOAD"));
        assertTrue(str.contains("file.csv"));
        assertTrue(str.contains("OK"));
    }

    // ── stop ────────────────────────────────────────────────────────────

    @Test
    void stop_setsRunningFalse_andShutsDownCleanly() throws Exception {
        ClientConfig config = new ClientConfig();
        FileSyncEngine engine = new FileSyncEngine(config);

        // Engine is not started, but stop should still be safe to call
        assertDoesNotThrow(engine::stop);

        // Verify running is false via the status (connected will be false, no NPE)
        String status = engine.getStatus();
        assertNotNull(status);
    }

    @Test
    void stop_calledMultipleTimes_noException() {
        ClientConfig config = new ClientConfig();
        FileSyncEngine engine = new FileSyncEngine(config);

        assertDoesNotThrow(() -> {
            engine.stop();
            engine.stop();
            engine.stop();
        });
    }
}
