package com.filetransfer.shared.routing;

import com.filetransfer.shared.entity.FlowEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for FlowActor — the stateful actor that replays events
 * from the journal to reconstruct execution state.
 */
class FlowActorTest {

    private static final String TRACK_ID = "TRK-001";
    private static final UUID EXEC_ID = UUID.randomUUID();

    private FlowActor actor;

    @BeforeEach
    void setUp() {
        actor = new FlowActor(TRACK_ID);
    }

    @Test
    void replayFromJournal_executionStarted_shouldSetProcessingStatus() {
        actor.replayFromJournal(List.of(
                FlowEvent.builder()
                        .trackId(TRACK_ID).executionId(EXEC_ID)
                        .eventType("EXECUTION_STARTED")
                        .storageKey("abc123def456")
                        .build()
        ));

        assertEquals("PROCESSING", actor.getStatus());
        assertEquals("abc123def456", actor.getCurrentStorageKey());
        assertEquals(0, actor.getCurrentStep());
    }

    @Test
    void replayFromJournal_stepCompleted_shouldAdvanceCurrentStep() {
        actor.replayFromJournal(List.of(
                FlowEvent.builder()
                        .trackId(TRACK_ID).executionId(EXEC_ID)
                        .eventType("EXECUTION_STARTED")
                        .storageKey("key0")
                        .build(),
                FlowEvent.builder()
                        .trackId(TRACK_ID).executionId(EXEC_ID)
                        .eventType("STEP_COMPLETED")
                        .stepIndex(0).stepType("COMPRESS_GZIP")
                        .storageKey("key1")
                        .build()
        ));

        assertEquals(1, actor.getCurrentStep());
        assertEquals("key1", actor.getCurrentStorageKey());
    }

    @Test
    void replayFromJournal_stepFailed_shouldSetFailedStatus() {
        actor.replayFromJournal(List.of(
                FlowEvent.builder()
                        .trackId(TRACK_ID).executionId(EXEC_ID)
                        .eventType("EXECUTION_STARTED")
                        .storageKey("key0")
                        .build(),
                FlowEvent.builder()
                        .trackId(TRACK_ID).executionId(EXEC_ID)
                        .eventType("STEP_FAILED")
                        .stepIndex(0).stepType("ENCRYPT_PGP")
                        .errorMessage("PGP key expired")
                        .build()
        ));

        assertEquals("FAILED", actor.getStatus());
    }

    @Test
    void replayFromJournal_executionPaused_shouldSetPausedStatus() {
        actor.replayFromJournal(List.of(
                FlowEvent.builder()
                        .trackId(TRACK_ID).executionId(EXEC_ID)
                        .eventType("EXECUTION_STARTED")
                        .storageKey("key0")
                        .build(),
                FlowEvent.builder()
                        .trackId(TRACK_ID).executionId(EXEC_ID)
                        .eventType("EXECUTION_PAUSED")
                        .stepIndex(1).status("PAUSED")
                        .build()
        ));

        assertEquals("PAUSED", actor.getStatus());
    }

    @Test
    void replayFromJournal_approvalReceived_shouldResumeProcessing() {
        actor.replayFromJournal(List.of(
                FlowEvent.builder()
                        .trackId(TRACK_ID).executionId(EXEC_ID)
                        .eventType("EXECUTION_STARTED")
                        .storageKey("key0")
                        .build(),
                FlowEvent.builder()
                        .trackId(TRACK_ID).executionId(EXEC_ID)
                        .eventType("EXECUTION_PAUSED")
                        .stepIndex(1).status("PAUSED")
                        .build(),
                FlowEvent.builder()
                        .trackId(TRACK_ID).executionId(EXEC_ID)
                        .eventType("APPROVAL_RECEIVED")
                        .stepIndex(1).status("APPROVED")
                        .actor("admin@company.com")
                        .build()
        ));

        assertEquals("PROCESSING", actor.getStatus());
    }

    @Test
    void replayFromJournal_fullLifecycle_shouldReconstructCorrectState() {
        actor.replayFromJournal(List.of(
                // 1. EXECUTION_STARTED
                FlowEvent.builder()
                        .trackId(TRACK_ID).executionId(EXEC_ID)
                        .eventType("EXECUTION_STARTED")
                        .storageKey("key0")
                        .build(),
                // 2. STEP_STARTED (step 0)
                FlowEvent.builder()
                        .trackId(TRACK_ID).executionId(EXEC_ID)
                        .eventType("STEP_STARTED")
                        .stepIndex(0).stepType("COMPRESS_GZIP")
                        .attemptNumber(1)
                        .build(),
                // 3. STEP_COMPLETED (step 0)
                FlowEvent.builder()
                        .trackId(TRACK_ID).executionId(EXEC_ID)
                        .eventType("STEP_COMPLETED")
                        .stepIndex(0).stepType("COMPRESS_GZIP")
                        .storageKey("key1")
                        .build(),
                // 4. STEP_STARTED (step 1)
                FlowEvent.builder()
                        .trackId(TRACK_ID).executionId(EXEC_ID)
                        .eventType("STEP_STARTED")
                        .stepIndex(1).stepType("ENCRYPT_PGP")
                        .attemptNumber(1)
                        .build(),
                // 5. STEP_COMPLETED (step 1)
                FlowEvent.builder()
                        .trackId(TRACK_ID).executionId(EXEC_ID)
                        .eventType("STEP_COMPLETED")
                        .stepIndex(1).stepType("ENCRYPT_PGP")
                        .storageKey("key2")
                        .build(),
                // 6. STEP_STARTED (step 2)
                FlowEvent.builder()
                        .trackId(TRACK_ID).executionId(EXEC_ID)
                        .eventType("STEP_STARTED")
                        .stepIndex(2).stepType("RENAME_FILE")
                        .attemptNumber(1)
                        .build(),
                // 7. STEP_COMPLETED (step 2)
                FlowEvent.builder()
                        .trackId(TRACK_ID).executionId(EXEC_ID)
                        .eventType("STEP_COMPLETED")
                        .stepIndex(2).stepType("RENAME_FILE")
                        .storageKey("key3")
                        .build(),
                // 8. EXECUTION_COMPLETED
                FlowEvent.builder()
                        .trackId(TRACK_ID).executionId(EXEC_ID)
                        .eventType("EXECUTION_COMPLETED")
                        .durationMs(1500L).status("COMPLETED")
                        .build()
        ));

        assertEquals("COMPLETED", actor.getStatus());
        assertEquals(3, actor.getCurrentStep());
        assertEquals("key3", actor.getCurrentStorageKey());
    }

    @Test
    void replayFromJournal_retryScenario_shouldTrackAttemptNumber() {
        actor.replayFromJournal(List.of(
                FlowEvent.builder()
                        .trackId(TRACK_ID).executionId(EXEC_ID)
                        .eventType("EXECUTION_STARTED")
                        .storageKey("key0")
                        .build(),
                FlowEvent.builder()
                        .trackId(TRACK_ID).executionId(EXEC_ID)
                        .eventType("STEP_STARTED")
                        .stepIndex(0).stepType("ENCRYPT_PGP")
                        .attemptNumber(1)
                        .build(),
                FlowEvent.builder()
                        .trackId(TRACK_ID).executionId(EXEC_ID)
                        .eventType("STEP_RETRYING")
                        .stepIndex(0).stepType("ENCRYPT_PGP")
                        .attemptNumber(2)
                        .build()
        ));

        assertEquals(2, actor.getAttemptNumber());
    }

    @Test
    void isResumable_whenProcessing_shouldReturnTrue() {
        actor.replayFromJournal(List.of(
                FlowEvent.builder()
                        .trackId(TRACK_ID).executionId(EXEC_ID)
                        .eventType("EXECUTION_STARTED")
                        .storageKey("key0")
                        .build()
        ));

        assertTrue(actor.isResumable());
    }

    @Test
    void isResumable_whenCompleted_shouldReturnFalse() {
        actor.replayFromJournal(List.of(
                FlowEvent.builder()
                        .trackId(TRACK_ID).executionId(EXEC_ID)
                        .eventType("EXECUTION_COMPLETED")
                        .status("COMPLETED")
                        .build()
        ));

        assertFalse(actor.isResumable());
    }

    @Test
    void isTerminal_whenFailed_shouldReturnTrue() {
        actor.replayFromJournal(List.of(
                FlowEvent.builder()
                        .trackId(TRACK_ID).executionId(EXEC_ID)
                        .eventType("EXECUTION_STARTED")
                        .storageKey("key0")
                        .build(),
                FlowEvent.builder()
                        .trackId(TRACK_ID).executionId(EXEC_ID)
                        .eventType("STEP_FAILED")
                        .stepIndex(0).stepType("COMPRESS_GZIP")
                        .errorMessage("Disk full")
                        .build()
        ));

        assertTrue(actor.isTerminal());
    }
}
