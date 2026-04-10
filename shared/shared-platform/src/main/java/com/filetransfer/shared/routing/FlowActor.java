package com.filetransfer.shared.routing;

import com.filetransfer.shared.entity.FlowEvent;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * Lightweight stateful actor for a single flow execution.
 * Runs on a virtual thread. State is rebuilt from the event journal on recovery.
 *
 * <p>This is the skeleton for the Netflix Maestro-inspired actor model.
 * Currently provides state reconstruction from events; full actor lifecycle
 * (event loop, suspension, resumption) will be implemented in a future phase.
 */
@Slf4j
public class FlowActor {

    @Getter private final String trackId;
    @Getter private int currentStep = 0;
    @Getter private String currentStorageKey;
    @Getter private String status = "PENDING";
    @Getter private int attemptNumber = 1;

    public FlowActor(String trackId) {
        this.trackId = trackId;
    }

    /**
     * Rebuild actor state from event journal — used on JVM restart.
     * Replays events in order to reconstruct the actor's current state
     * without re-executing any steps.
     */
    public synchronized void replayFromJournal(List<FlowEvent> events) {
        for (FlowEvent event : events) {
            switch (event.getEventType()) {
                case "EXECUTION_STARTED" -> {
                    this.status = "PROCESSING";
                    this.currentStorageKey = event.getStorageKey();
                    this.currentStep = 0;
                }
                case "STEP_STARTED" -> {
                    this.currentStep = event.getStepIndex() != null ? event.getStepIndex() : this.currentStep;
                    if (event.getAttemptNumber() != null) this.attemptNumber = event.getAttemptNumber();
                }
                case "STEP_COMPLETED" -> {
                    this.currentStorageKey = event.getStorageKey();
                    this.currentStep = (event.getStepIndex() != null ? event.getStepIndex() : this.currentStep) + 1;
                    this.attemptNumber = 1;
                }
                case "STEP_FAILED" -> {
                    this.status = "FAILED";
                }
                case "STEP_RETRYING" -> {
                    if (event.getAttemptNumber() != null) this.attemptNumber = event.getAttemptNumber();
                }
                case "EXECUTION_PAUSED" -> {
                    this.status = "PAUSED";
                }
                case "APPROVAL_RECEIVED" -> {
                    if ("APPROVED".equals(event.getStatus())) {
                        this.status = "PROCESSING";
                    }
                }
                case "EXECUTION_RESUMED" -> {
                    this.status = "PROCESSING";
                    if (event.getStepIndex() != null) this.currentStep = event.getStepIndex();
                }
                case "EXECUTION_COMPLETED" -> {
                    this.status = "COMPLETED";
                }
                case "EXECUTION_FAILED" -> {
                    this.status = "FAILED";
                }
                case "EXECUTION_RESTARTED" -> {
                    this.status = "PROCESSING";
                    this.currentStep = event.getStepIndex() != null ? event.getStepIndex() : 0;
                    this.attemptNumber = 1;
                }
                default -> log.debug("FlowActor: Unknown event type {}", event.getEventType());
            }
        }
        log.debug("FlowActor [{}]: Replayed {} events → status={}, step={}, key={}",
                trackId, events.size(), status, currentStep,
                currentStorageKey != null ? currentStorageKey.substring(0, Math.min(8, currentStorageKey.length())) : "null");
    }

    /** Check if the actor is in a resumable state. */
    public boolean isResumable() {
        return "PROCESSING".equals(status) || "PAUSED".equals(status);
    }

    /** Check if the actor has completed (terminal state). */
    public boolean isTerminal() {
        return "COMPLETED".equals(status) || "FAILED".equals(status) || "CANCELLED".equals(status);
    }

    @Override
    public String toString() {
        return "FlowActor[track=" + trackId + ", status=" + status + ", step=" + currentStep + "]";
    }
}
