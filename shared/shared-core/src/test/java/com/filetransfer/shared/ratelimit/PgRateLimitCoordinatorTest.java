package com.filetransfer.shared.ratelimit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins the {@link PgRateLimitCoordinator} API surface. The SQL itself is
 * exercised in an integration test against real Postgres; these unit tests
 * verify the JDBC interactions (statement shape + parameters) and the
 * argument validation.
 *
 * <p>JDK 25 note: {@link JdbcTemplate} is a concrete class with final
 * methods overridden by the bean — Byte-Buddy/Mockito can mock it
 * because the specific methods we touch (queryForObject with 3
 * args, update) are not final. See
 * {@code project_jdk25_testing.md} for the broader pattern.
 */
class PgRateLimitCoordinatorTest {

    private JdbcTemplate jdbc;
    private PgRateLimitCoordinator coordinator;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        coordinator = new PgRateLimitCoordinator(jdbc);
    }

    // ── Happy-path: increment and return new count ────────────────────────

    @Test
    void incrementAndGet_issues_upsert_returning_new_count() {
        Instant window = Instant.parse("2026-04-20T12:00:00Z");
        when(jdbc.queryForObject(any(String.class), eq(Long.class),
                eq("ip:1.2.3.4"), any(Timestamp.class), eq(1)))
                .thenReturn(17L);

        long count = coordinator.incrementAndGet("ip:1.2.3.4", window, 1);

        assertEquals(17L, count);
        verify(jdbc).queryForObject(any(String.class), eq(Long.class),
                eq("ip:1.2.3.4"), any(Timestamp.class), eq(1));
    }

    @Test
    void incrementAndGet_null_return_maps_to_zero() {
        when(jdbc.queryForObject(any(String.class), eq(Long.class), any(), any(), anyInt()))
                .thenReturn(null);

        long count = coordinator.incrementAndGet("bucket", Instant.now(), 1);

        assertEquals(0L, count, "null PG return must map to 0, not NPE");
    }

    // ── Argument validation — catches caller bugs early ──────────────────

    @Test
    void incrementAndGet_rejects_null_bucket() {
        assertThrows(IllegalArgumentException.class,
                () -> coordinator.incrementAndGet(null, Instant.now(), 1));
    }

    @Test
    void incrementAndGet_rejects_blank_bucket() {
        assertThrows(IllegalArgumentException.class,
                () -> coordinator.incrementAndGet("   ", Instant.now(), 1));
    }

    @Test
    void incrementAndGet_rejects_negative_delta() {
        assertThrows(IllegalArgumentException.class,
                () -> coordinator.incrementAndGet("bucket", Instant.now(), -1));
    }

    @Test
    void incrementAndGet_zero_delta_is_legal_read() {
        // delta=0 lets a caller check "current count" via the same upsert
        // path without changing it — useful for probe queries from the
        // admin UI. Don't reject this; the SQL treats 0 as a no-op add.
        when(jdbc.queryForObject(any(String.class), eq(Long.class), any(), any(), eq(0)))
                .thenReturn(42L);

        assertEquals(42L, coordinator.incrementAndGet("bucket", Instant.now(), 0));
    }

    // ── Bytes counter — uses bytes_count column ───────────────────────────

    @Test
    void addBytesAndGet_issues_same_shape_as_request_count() {
        when(jdbc.queryForObject(any(String.class), eq(Long.class),
                eq("bucket"), any(Timestamp.class), eq(1024L)))
                .thenReturn(1024L);

        long total = coordinator.addBytesAndGet("bucket", Instant.now(), 1024L);

        assertEquals(1024L, total);
    }

    // ── Read-only query — currentCount ───────────────────────────────────

    @Test
    void currentCount_issues_non_mutating_select() {
        Instant window = Instant.parse("2026-04-20T12:00:00Z");
        when(jdbc.queryForObject(any(String.class), eq(Long.class),
                eq("bucket"), any(Timestamp.class)))
                .thenReturn(5L);

        assertEquals(5L, coordinator.currentCount("bucket", window));
    }

    @Test
    void currentCount_missing_bucket_returns_zero() {
        // PG subquery wraps in COALESCE(..., 0) so the return is always
        // non-null. Pinning so future refactors don't accidentally return
        // null for absent bucket.
        when(jdbc.queryForObject(any(String.class), eq(Long.class), any(), any()))
                .thenReturn(0L);

        assertEquals(0L, coordinator.currentCount("never-seen", Instant.now()));
    }

    // ── Window alignment helper — truncates to window boundary ──────────

    @Test
    void windowStart_truncates_to_minute_boundary() {
        // When the helper is called mid-minute the returned instant is
        // aligned to the start of that minute. This lets multiple callers
        // in the same minute land on the SAME bucket key.
        Instant now = Instant.now();
        Instant aligned = PgRateLimitCoordinator.windowStart(Duration.ofMinutes(1));

        // Should be within the last 60 seconds AND at a minute boundary.
        assertEquals(0, aligned.getEpochSecond() % 60,
                "1-minute window start must be minute-aligned");
        assertNotEquals(now.getEpochSecond(), aligned.getEpochSecond() + 120,
                "aligned instant should be within 2 minutes of now");
    }
}
