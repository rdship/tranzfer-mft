package com.filetransfer.storage.coordination;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Pins the behavioural contract of {@link StorageCoordinationService} —
 * argument validation + JDBC call shape. The atomic-upsert SQL itself is
 * exercised end-to-end in an integration test against real Postgres;
 * these unit tests verify the service layer's input/output semantics.
 */
class StorageCoordinationServiceTest {

    private JdbcTemplate jdbc;
    private StorageCoordinationService service;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        service = new StorageCoordinationService(jdbc);
    }

    // ── tryAcquire — argument validation ─────────────────────────────────

    @Test
    void tryAcquire_rejects_blank_lockKey() {
        assertThrows(IllegalArgumentException.class,
                () -> service.tryAcquire("", "holder-1", Duration.ofSeconds(30)));
    }

    @Test
    void tryAcquire_rejects_null_holder() {
        assertThrows(IllegalArgumentException.class,
                () -> service.tryAcquire("vfs:write:x", null, Duration.ofSeconds(30)));
    }

    @Test
    void tryAcquire_rejects_zero_ttl() {
        assertThrows(IllegalArgumentException.class,
                () -> service.tryAcquire("vfs:write:x", "holder-1", Duration.ZERO));
    }

    @Test
    void tryAcquire_rejects_negative_ttl() {
        assertThrows(IllegalArgumentException.class,
                () -> service.tryAcquire("vfs:write:x", "holder-1", Duration.ofSeconds(-5)));
    }

    @Test
    void tryAcquire_rejects_ttl_greater_than_30_minutes() {
        // Sanity cap — catches caller bugs like passing milliseconds where
        // seconds were expected. 30 min is way longer than any realistic
        // VFS write or flow step.
        assertThrows(IllegalArgumentException.class,
                () -> service.tryAcquire("vfs:write:x", "holder-1", Duration.ofMinutes(31)));
    }

    // ── tryAcquire — happy path ──────────────────────────────────────────

    @Test
    void tryAcquire_returns_true_when_upsert_affects_one_row() {
        when(jdbc.update(any(String.class), any(), any(), any(Timestamp.class), any(Timestamp.class)))
                .thenReturn(1);

        boolean held = service.tryAcquire("vfs:write:foo",
                "holder-1", Duration.ofSeconds(30));

        assertTrue(held);
    }

    @Test
    void tryAcquire_returns_false_when_different_holder_has_live_lease() {
        // SQL returns 0 rows affected when the ON CONFLICT WHERE filter
        // rejects the UPDATE (different holder, unexpired lease).
        when(jdbc.update(any(String.class), any(), any(), any(Timestamp.class), any(Timestamp.class)))
                .thenReturn(0);

        boolean held = service.tryAcquire("vfs:write:foo",
                "holder-2", Duration.ofSeconds(30));

        assertFalse(held);
    }

    // ── extend — only the holder can extend ──────────────────────────────

    @Test
    void extend_returns_true_when_row_updated() {
        when(jdbc.update(any(String.class), any(Timestamp.class), any(), any()))
                .thenReturn(1);

        assertTrue(service.extend("vfs:write:foo", "holder-1", Duration.ofSeconds(60)));
    }

    @Test
    void extend_returns_false_when_not_held() {
        when(jdbc.update(any(String.class), any(Timestamp.class), any(), any()))
                .thenReturn(0);

        assertFalse(service.extend("vfs:write:foo", "holder-1", Duration.ofSeconds(60)));
    }

    // ── release — holder-only ────────────────────────────────────────────

    @Test
    void release_returns_true_when_row_deleted() {
        when(jdbc.update(any(String.class), any(), any()))
                .thenReturn(1);

        assertTrue(service.release("vfs:write:foo", "holder-1"));
    }

    @Test
    void release_returns_false_when_different_holder() {
        // DELETE WHERE lock_key=? AND holder_id=? — zero rows affected
        // when we're not the holder (never acquired OR already released
        // OR reaper purged it).
        when(jdbc.update(any(String.class), any(), any()))
                .thenReturn(0);

        assertFalse(service.release("vfs:write:foo", "not-the-holder"));
    }

    // ── reaper — purges expired rows ─────────────────────────────────────

    @Test
    void reaper_runs_delete_and_returns_row_count() {
        when(jdbc.update(any(String.class))).thenReturn(3);

        int purged = service.reapExpiredLeases();

        assertEquals(3, purged);
    }

    @Test
    void reaper_zero_purged_does_not_throw() {
        when(jdbc.update(any(String.class))).thenReturn(0);

        assertDoesNotThrow(() -> service.reapExpiredLeases());
    }
}
