package com.filetransfer.storage.lifecycle;

import com.filetransfer.storage.entity.WriteIntent;
import com.filetransfer.storage.repository.WriteIntentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for WriteIntentService — the write-ahead intent log
 * that provides crash recovery for file writes.
 */
@ExtendWith(MockitoExtension.class)
class WriteIntentServiceTest {

    @Mock
    private WriteIntentRepository intentRepo;

    private WriteIntentService service;

    @BeforeEach
    void setUp() {
        service = new WriteIntentService(intentRepo);
    }

    @Test
    void create_shouldSaveIntentWithInProgressStatus() {
        when(intentRepo.save(any(WriteIntent.class))).thenAnswer(inv -> inv.getArgument(0));

        WriteIntent result = service.create("/tmp/storage-write-123.tmp", 4096L, 2);

        ArgumentCaptor<WriteIntent> captor = ArgumentCaptor.forClass(WriteIntent.class);
        verify(intentRepo).save(captor.capture());

        WriteIntent saved = captor.getValue();
        assertEquals("IN_PROGRESS", saved.getStatus());
        assertEquals("/tmp/storage-write-123.tmp", saved.getTempPath());
        assertEquals(4096L, saved.getExpectedSizeBytes());
        assertEquals(2, saved.getStripeCount());
    }

    @Test
    void complete_shouldUpdateStatusAndDestPath() {
        WriteIntent intent = WriteIntent.builder()
                .tempPath("/tmp/writing.tmp")
                .status("IN_PROGRESS")
                .build();
        when(intentRepo.save(any(WriteIntent.class))).thenAnswer(inv -> inv.getArgument(0));

        service.complete(intent, "/data/storage/hot/abc123");

        assertEquals("DONE", intent.getStatus());
        assertEquals("/data/storage/hot/abc123", intent.getDestPath());
        assertNotNull(intent.getCompletedAt(), "completedAt should be set");
        verify(intentRepo).save(intent);
    }

    @Test
    void abandon_shouldUpdateStatusToAbandoned() {
        WriteIntent intent = WriteIntent.builder()
                .tempPath("/tmp/dedup-hit.tmp")
                .status("IN_PROGRESS")
                .build();
        when(intentRepo.save(any(WriteIntent.class))).thenAnswer(inv -> inv.getArgument(0));

        service.abandon(intent);

        assertEquals("ABANDONED", intent.getStatus());
        assertNotNull(intent.getCompletedAt());
        verify(intentRepo).save(intent);
    }

    @Test
    void cleanOrphanedWrites_shouldDeleteOldTempFiles(@TempDir Path tempDir) throws Exception {
        // Create a temp file that simulates an orphaned write
        Path orphanFile = tempDir.resolve("orphan-write.tmp");
        Files.writeString(orphanFile, "orphaned data that never completed");

        // Build an old IN_PROGRESS intent pointing to the temp file
        WriteIntent orphanIntent = WriteIntent.builder()
                .tempPath(orphanFile.toString())
                .status("IN_PROGRESS")
                .createdAt(Instant.now().minus(60, ChronoUnit.MINUTES)) // older than 30-minute threshold
                .build();

        when(intentRepo.findByStatusAndCreatedAtBefore(eq("IN_PROGRESS"), any(Instant.class)))
                .thenReturn(List.of(orphanIntent));
        when(intentRepo.save(any(WriteIntent.class))).thenAnswer(inv -> inv.getArgument(0));

        // Invoke the private cleanOrphanedWrites method via the public onStartup()
        // which calls cleanOrphanedWrites() directly
        service.onStartup();

        // Verify the temp file was deleted
        assertFalse(Files.exists(orphanFile), "Orphaned temp file should be deleted");

        // Verify the intent was marked as ABANDONED
        assertEquals("ABANDONED", orphanIntent.getStatus());
        assertNotNull(orphanIntent.getCompletedAt());
        verify(intentRepo).save(orphanIntent);
    }

    // ── Helper ──────────────────────────────────────────────────────────────

    private static void setField(Object obj, String name, Object value) throws Exception {
        var field = obj.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(obj, value);
    }
}
