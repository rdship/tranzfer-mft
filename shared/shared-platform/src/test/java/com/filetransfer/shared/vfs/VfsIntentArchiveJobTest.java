package com.filetransfer.shared.vfs;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class VfsIntentArchiveJobTest {

    private JdbcTemplate jdbc;
    private VfsIntentArchiveJob job;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        job = new VfsIntentArchiveJob(jdbc);
    }

    @Test
    void archivesBatchesUntilExhausted() {
        // First batch returns BATCH_SIZE (10,000) → loop continues
        // Second batch returns less → loop stops
        when(jdbc.update(anyString()))
                .thenReturn(10_000)  // first archiveBatch
                .thenReturn(3_000)   // second archiveBatch
                .thenReturn(0);      // purge old archive (1-year cleanup)

        job.archiveOldResolvedIntents();

        // 2 archive batches + 1 yearly purge = 3 update calls
        verify(jdbc, times(3)).update(anyString());
    }

    @Test
    void nothingToArchive_stillRunsYearlyPurge() {
        when(jdbc.update(anyString()))
                .thenReturn(0)  // archiveBatch returns 0 → nothing to archive
                .thenReturn(0); // yearly purge also returns 0

        job.archiveOldResolvedIntents();

        // 1 archive attempt (returns 0) + 1 yearly purge = 2 update calls
        verify(jdbc, times(2)).update(anyString());
    }

    @Test
    void archiveCteContainsResolvedStatusFilter() {
        when(jdbc.update(anyString())).thenReturn(0);

        job.archiveOldResolvedIntents();

        // Verify the CTE query targets only resolved statuses
        verify(jdbc).update(argThat((String sql) ->
                sql.contains("COMMITTED") && sql.contains("ABORTED")
                        && sql.contains("vfs_intents_archive")));
    }
}
