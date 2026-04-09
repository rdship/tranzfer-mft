package com.filetransfer.onboarding.controller;

import com.filetransfer.shared.entity.FlowExecution;
import com.filetransfer.shared.repository.FlowExecutionRepository;
import com.filetransfer.shared.routing.FlowRestartService;
import com.filetransfer.shared.security.Roles;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * Admin-facing REST API for flow execution lifecycle management.
 *
 * <p>All mutating operations (restart, terminate) require OPERATOR role and
 * write an audit record via {@link FlowRestartService}.
 *
 * <h3>Endpoints</h3>
 * <pre>
 * GET  /api/flow-executions/{trackId}                   — execution detail + attempt history
 * POST /api/flow-executions/{trackId}/restart            — restart from beginning (@Async)
 * POST /api/flow-executions/{trackId}/restart/{step}     — restart from step N (@Async)
 * POST /api/flow-executions/{trackId}/terminate          — cancel / request termination
 * </pre>
 */
@Slf4j
@RestController
@RequestMapping("/api/flow-executions")
@RequiredArgsConstructor
public class FlowExecutionController {

    private final FlowExecutionRepository executionRepo;
    private final FlowRestartService restartService;

    // ── Read ─────────────────────────────────────────────────────────────────

    @GetMapping("/{trackId}")
    @PreAuthorize(Roles.VIEWER)
    public ResponseEntity<FlowExecution> get(@PathVariable String trackId) {
        return executionRepo.findByTrackId(trackId)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No execution for trackId=" + trackId));
    }

    // ── Restart from beginning ────────────────────────────────────────────────

    /**
     * Restart the entire flow from the original input file.
     * Returns immediately (fire-and-forget async); poll GET /{trackId} for progress.
     */
    @PostMapping("/{trackId}/restart")
    @PreAuthorize(Roles.OPERATOR)
    public ResponseEntity<Map<String, Object>> restart(
            @PathVariable String trackId,
            @AuthenticationPrincipal UserDetails user) {

        validateRestartable(trackId);
        String principal = user != null ? user.getUsername() : "api";
        log.info("[{}] RESTART requested by {}", trackId, principal);

        restartService.restartFromBeginning(trackId, principal); // @Async — returns immediately

        return ResponseEntity.accepted().body(Map.of(
                "status", "RESTART_QUEUED",
                "trackId", trackId,
                "fromStep", 0,
                "requestedBy", principal,
                "message", "Restart queued. Poll GET /api/flow-executions/" + trackId + " for status."));
    }

    // ── Restart from specific step ────────────────────────────────────────────

    /**
     * Restart from step {@code step} using that step's captured input file.
     * Useful when only one step fails (e.g. EDI conversion) — skips the expensive
     * compress/encrypt steps that already succeeded.
     */
    @PostMapping("/{trackId}/restart/{step}")
    @PreAuthorize(Roles.OPERATOR)
    public ResponseEntity<Map<String, Object>> restartFromStep(
            @PathVariable String trackId,
            @PathVariable int step,
            @AuthenticationPrincipal UserDetails user) {

        if (step < 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "step must be >= 0");
        validateRestartable(trackId);
        String principal = user != null ? user.getUsername() : "api";
        log.info("[{}] RESTART from step {} requested by {}", trackId, step, principal);

        restartService.restartFromStep(trackId, step, principal); // @Async

        return ResponseEntity.accepted().body(Map.of(
                "status", "RESTART_QUEUED",
                "trackId", trackId,
                "fromStep", step,
                "requestedBy", principal,
                "message", "Restart from step " + step + " queued."));
    }

    // ── Terminate ────────────────────────────────────────────────────────────

    /**
     * Terminate (cancel) a flow execution.
     * - PROCESSING: sets termination flag, agent exits after current step.
     * - FAILED/PAUSED: flipped to CANCELLED immediately.
     * - COMPLETED/CANCELLED: 409 Conflict.
     */
    @PostMapping("/{trackId}/terminate")
    @PreAuthorize(Roles.OPERATOR)
    public ResponseEntity<Map<String, Object>> terminate(
            @PathVariable String trackId,
            @AuthenticationPrincipal UserDetails user) {

        String principal = user != null ? user.getUsername() : "api";
        log.info("[{}] TERMINATE requested by {}", trackId, principal);

        try {
            restartService.terminate(trackId, principal);
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }

        FlowExecution exec = executionRepo.findByTrackId(trackId).orElseThrow();
        return ResponseEntity.ok(Map.of(
                "status", exec.getStatus().name(),
                "trackId", trackId,
                "terminatedBy", principal,
                "message", exec.getStatus() == FlowExecution.FlowStatus.PROCESSING
                        ? "Termination requested. Agent will exit after current step."
                        : "Execution cancelled."));
    }

    // ── Attempt history ───────────────────────────────────────────────────────

    /**
     * Previous attempt summaries (failures, restarts). Useful for debugging
     * "why did this transfer fail 3 times before succeeding?".
     */
    @GetMapping("/{trackId}/history")
    @PreAuthorize(Roles.VIEWER)
    public ResponseEntity<List<Map<String, Object>>> history(@PathVariable String trackId) {
        FlowExecution exec = executionRepo.findByTrackId(trackId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No execution for trackId=" + trackId));
        List<Map<String, Object>> hist = exec.getAttemptHistory();
        return ResponseEntity.ok(hist != null ? hist : List.of());
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private void validateRestartable(String trackId) {
        FlowExecution exec = executionRepo.findByTrackId(trackId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No execution for trackId=" + trackId));
        if (exec.getStatus() == FlowExecution.FlowStatus.PROCESSING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Cannot restart a PROCESSING execution. Terminate it first.");
        }
        if (exec.getStatus() == FlowExecution.FlowStatus.COMPLETED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Execution is COMPLETED. Only failed or cancelled executions can be restarted.");
        }
    }
}
