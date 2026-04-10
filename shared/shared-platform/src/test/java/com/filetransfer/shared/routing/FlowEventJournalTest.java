package com.filetransfer.shared.routing;

import com.filetransfer.shared.entity.FlowEvent;
import com.filetransfer.shared.repository.FlowEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for FlowEventJournal — the append-only event log
 * that records every state transition in a flow execution.
 */
@ExtendWith(MockitoExtension.class)
class FlowEventJournalTest {

    private static final String TRACK_ID = "TRK-002";
    private static final UUID EXEC_ID = UUID.randomUUID();

    @Mock
    private FlowEventRepository eventRepo;

    private FlowEventJournal journal;

    @BeforeEach
    void setUp() {
        journal = new FlowEventJournal(eventRepo);
    }

    @Test
    void recordExecutionStarted_shouldSaveEventWithCorrectType() {
        when(eventRepo.save(any(FlowEvent.class))).thenAnswer(inv -> inv.getArgument(0));
        journal.recordExecutionStarted(TRACK_ID, EXEC_ID, "sha256key", 5);

        ArgumentCaptor<FlowEvent> captor = ArgumentCaptor.forClass(FlowEvent.class);
        verify(eventRepo).save(captor.capture());

        FlowEvent saved = captor.getValue();
        assertEquals("EXECUTION_STARTED", saved.getEventType());
        assertEquals(TRACK_ID, saved.getTrackId());
        assertEquals(EXEC_ID, saved.getExecutionId());
        assertEquals("sha256key", saved.getStorageKey());
        assertTrue(saved.getMetadata().contains("\"stepCount\":5"));
    }

    @Test
    void recordStepCompleted_shouldSaveWithDuration() {
        when(eventRepo.save(any(FlowEvent.class))).thenAnswer(inv -> inv.getArgument(0));
        journal.recordStepCompleted(TRACK_ID, EXEC_ID, 2, "COMPRESS_GZIP", "outputKey", 1024L, 350L);

        ArgumentCaptor<FlowEvent> captor = ArgumentCaptor.forClass(FlowEvent.class);
        verify(eventRepo).save(captor.capture());

        FlowEvent saved = captor.getValue();
        assertEquals("STEP_COMPLETED", saved.getEventType());
        assertEquals(2, saved.getStepIndex());
        assertEquals("COMPRESS_GZIP", saved.getStepType());
        assertEquals(350L, saved.getDurationMs());
        assertEquals("outputKey", saved.getStorageKey());
        assertEquals(1024L, saved.getSizeBytes());
    }

    @Test
    void recordStepFailed_shouldTruncateLongErrors() {
        when(eventRepo.save(any(FlowEvent.class))).thenAnswer(inv -> inv.getArgument(0));
        String longError = "X".repeat(3000);

        journal.recordStepFailed(TRACK_ID, EXEC_ID, 0, "ENCRYPT_PGP", longError, 1);

        ArgumentCaptor<FlowEvent> captor = ArgumentCaptor.forClass(FlowEvent.class);
        verify(eventRepo).save(captor.capture());

        FlowEvent saved = captor.getValue();
        assertEquals("STEP_FAILED", saved.getEventType());
        assertNotNull(saved.getErrorMessage());
        assertEquals(2000, saved.getErrorMessage().length(), "Error should be truncated to 2000 chars");
    }

    @Test
    void recordExecutionCompleted_shouldSaveStepCount() {
        when(eventRepo.save(any(FlowEvent.class))).thenAnswer(inv -> inv.getArgument(0));
        journal.recordExecutionCompleted(TRACK_ID, EXEC_ID, 5000L, 4);

        ArgumentCaptor<FlowEvent> captor = ArgumentCaptor.forClass(FlowEvent.class);
        verify(eventRepo).save(captor.capture());

        FlowEvent saved = captor.getValue();
        assertEquals("EXECUTION_COMPLETED", saved.getEventType());
        assertEquals("COMPLETED", saved.getStatus());
        assertTrue(saved.getMetadata().contains("\"stepsExecuted\":4"));
        assertEquals(5000L, saved.getDurationMs());
    }

    @Test
    void getHistory_shouldDelegateToRepository() {
        List<FlowEvent> expected = List.of(
                FlowEvent.builder().trackId(TRACK_ID).eventType("EXECUTION_STARTED").build()
        );
        when(eventRepo.findByTrackIdOrderByCreatedAtAsc(TRACK_ID)).thenReturn(expected);

        List<FlowEvent> result = journal.getHistory(TRACK_ID);

        assertSame(expected, result);
        verify(eventRepo).findByTrackIdOrderByCreatedAtAsc(TRACK_ID);
    }

    @Test
    void save_whenRepositoryThrows_shouldNotPropagate() {
        // Reset the default stub and make save() throw
        reset(eventRepo);
        when(eventRepo.save(any(FlowEvent.class)))
                .thenThrow(new RuntimeException("DB connection lost"));

        // Should NOT throw — the journal swallows exceptions to avoid blocking the hot path
        assertDoesNotThrow(() ->
                journal.recordExecutionStarted(TRACK_ID, EXEC_ID, "key", 3));

        verify(eventRepo).save(any(FlowEvent.class));
    }
}
