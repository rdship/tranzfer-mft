package com.filetransfer.onboarding.controller;

import com.filetransfer.shared.entity.FlowApproval;
import com.filetransfer.shared.entity.FlowExecution;
import com.filetransfer.shared.repository.FlowExecutionRepository;
import com.filetransfer.shared.routing.FlowApprovalService;
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

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.cache.annotation.Cacheable;

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
    private final FlowApprovalService approvalService;

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

    /**
     * Bulk-restart multiple executions in one request.
     * Only restarts executions in a restartable state (FAILED, CANCELLED, UNMATCHED).
     * Each restart is @Async — returns immediately with a queued count.
     */
    @PostMapping("/bulk-restart")
    @PreAuthorize(Roles.OPERATOR)
    public ResponseEntity<Map<String, Object>> bulkRestart(
            @RequestBody Map<String, List<String>> body,
            @AuthenticationPrincipal UserDetails user) {

        List<String> trackIds = body.getOrDefault("trackIds", List.of());
        if (trackIds.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "trackIds list is required and must not be empty"));
        }
        if (trackIds.size() > 100) {
            return ResponseEntity.badRequest().body(Map.of("error", "Bulk restart limited to 100 executions per request"));
        }

        String principal = user != null ? user.getUsername() : "api";
        List<String> queued  = new ArrayList<>();
        List<String> skipped = new ArrayList<>();

        for (String trackId : trackIds) {
            try {
                validateRestartable(trackId);
                restartService.restartFromBeginning(trackId, principal);
                queued.add(trackId);
                log.info("[{}] BULK-RESTART queued by {}", trackId, principal);
            } catch (Exception e) {
                skipped.add(trackId);
                log.warn("[{}] BULK-RESTART skipped: {}", trackId, e.getMessage());
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("queued", queued.size());
        result.put("skipped", skipped.size());
        result.put("queuedIds", queued);
        result.put("skippedIds", skipped);
        result.put("requestedBy", principal);
        result.put("message", queued.size() + " execution(s) queued for restart" +
                (skipped.isEmpty() ? "" : "; " + skipped.size() + " skipped (not in restartable state)"));

        return ResponseEntity.accepted().body(result);
    }

    /**
     * Skip step {@code step} and resume from step+1 using the skipped step's input file.
     * Use when a step is permanently broken (wrong config, bad script) and you want the
     * file to bypass it unchanged. Requires a FlowStepSnapshot to exist for the step.
     */
    @PostMapping("/{trackId}/skip/{step}")
    @PreAuthorize(Roles.OPERATOR)
    public ResponseEntity<Map<String, Object>> skipStep(
            @PathVariable String trackId,
            @PathVariable int step,
            @AuthenticationPrincipal UserDetails user) {

        validateRestartable(trackId);
        String principal = user != null ? user.getUsername() : "api";
        log.info("[{}] SKIP step {} requested by {}", trackId, step, principal);

        restartService.skipStep(trackId, step, principal);

        return ResponseEntity.accepted().body(Map.of(
                "status", "SKIP_QUEUED",
                "trackId", trackId,
                "skippedStep", step,
                "resumeAtStep", step + 1,
                "requestedBy", principal,
                "message", "Step " + step + " will be skipped. Resuming from step " + (step + 1) + ". Poll GET /api/flow-executions/" + trackId + " for status."));
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

    // ── Approval gates ────────────────────────────────────────────────────────

    /**
     * List all executions currently waiting for admin sign-off (status=PENDING approval).
     */
    @GetMapping("/pending-approvals")
    @PreAuthorize(Roles.VIEWER)
    public ResponseEntity<List<FlowApproval>> pendingApprovals() {
        return ResponseEntity.ok(approvalService.getPendingApprovals());
    }

    /**
     * Approve an APPROVE step gate — resumes the flow from the next step asynchronously.
     * Body: {@code { "stepIndex": 2, "note": "Looks good" }}
     */
    @PostMapping("/{trackId}/approve")
    @PreAuthorize(Roles.OPERATOR)
    public ResponseEntity<Map<String, Object>> approve(
            @PathVariable String trackId,
            @org.springframework.web.bind.annotation.RequestBody Map<String, Object> body,
            @AuthenticationPrincipal UserDetails user) {

        int stepIndex = Integer.parseInt(body.getOrDefault("stepIndex", "0").toString());
        String note   = (String) body.getOrDefault("note", "");
        String principal = user != null ? user.getUsername() : "api";
        log.info("[{}] APPROVE at step {} by {}", trackId, stepIndex, principal);

        try {
            approvalService.approve(trackId, stepIndex, principal, note);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }

        return ResponseEntity.accepted().body(Map.of(
                "status", "APPROVED",
                "trackId", trackId,
                "stepIndex", stepIndex,
                "approvedBy", principal,
                "message", "Flow approved. Resuming from step " + (stepIndex + 1) + "."));
    }

    /**
     * Reject an APPROVE step gate — cancels the execution immediately.
     * Body: {@code { "stepIndex": 2, "note": "Invalid file format" }}
     */
    @PostMapping("/{trackId}/reject")
    @PreAuthorize(Roles.OPERATOR)
    public ResponseEntity<Map<String, Object>> reject(
            @PathVariable String trackId,
            @org.springframework.web.bind.annotation.RequestBody Map<String, Object> body,
            @AuthenticationPrincipal UserDetails user) {

        int stepIndex = Integer.parseInt(body.getOrDefault("stepIndex", "0").toString());
        String note   = (String) body.getOrDefault("note", "");
        String principal = user != null ? user.getUsername() : "api";
        log.info("[{}] REJECT at step {} by {}", trackId, stepIndex, principal);

        try {
            approvalService.reject(trackId, stepIndex, principal, note);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }

        return ResponseEntity.ok(Map.of(
                "status", "REJECTED",
                "trackId", trackId,
                "stepIndex", stepIndex,
                "rejectedBy", principal,
                "message", "Flow rejected and cancelled."));
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

    /**
     * Live dashboard stats — counts of flow executions by status.
     * Used by the Dashboard "Live Activity" gauge; refreshes every 5 seconds.
     */
    /**
     * Live dashboard stats — single GROUP BY query, Redis-cached for 5 s.
     * All onboarding-api replicas share the same Redis entry; DB is hit once
     * per 5 s window regardless of replica count or request concurrency.
     */
    @GetMapping("/live-stats")
    @PreAuthorize(Roles.VIEWER)
    @Cacheable(value = "live-stats", key = "'all'")
    public ResponseEntity<Map<String, Object>> getLiveStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("processing", 0L);
        stats.put("pending",    0L);
        stats.put("paused",     0L);
        stats.put("failed",     0L);
        for (Object[] row : executionRepo.countLiveStatuses()) {
            String status = (String) row[0];
            long   count  = ((Number) row[1]).longValue();
            stats.put(status.toLowerCase(), count);
        }
        return ResponseEntity.ok(stats);
    }

    // ── Scheduled retry ───────────────────────────────────────────────────────

    /**
     * Schedule this execution to be retried at a specific time.
     * Body: {@code { "scheduledAt": "2026-04-09T02:00:00Z" }}
     */
    @PostMapping("/{trackId}/schedule-retry")
    @PreAuthorize(Roles.OPERATOR)
    public ResponseEntity<Map<String, Object>> scheduleRetry(
            @PathVariable String trackId,
            @RequestBody Map<String, String> body,
            @AuthenticationPrincipal UserDetails user) {

        FlowExecution exec = executionRepo.findByTrackId(trackId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No execution for trackId=" + trackId));

        String scheduledAtStr = body.get("scheduledAt");
        if (scheduledAtStr == null || scheduledAtStr.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "scheduledAt (ISO-8601) is required");
        }

        Instant scheduledAt;
        try {
            scheduledAt = Instant.parse(scheduledAtStr);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid scheduledAt — use ISO-8601 (e.g. 2026-04-09T02:00:00Z)");
        }

        if (scheduledAt.isBefore(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "scheduledAt must be in the future");
        }
        if (exec.getStatus() == FlowExecution.FlowStatus.PROCESSING ||
            exec.getStatus() == FlowExecution.FlowStatus.COMPLETED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Cannot schedule retry for a " + exec.getStatus() + " execution");
        }

        String principal = user != null ? user.getUsername() : "api";
        exec.setScheduledRetryAt(scheduledAt);
        exec.setScheduledRetryBy(principal);
        executionRepo.save(exec);

        log.info("[{}] SCHEDULE-RETRY at {} by {}", trackId, scheduledAt, principal);
        return ResponseEntity.ok(Map.of(
                "status", "SCHEDULED",
                "trackId", trackId,
                "scheduledAt", scheduledAt.toString(),
                "scheduledBy", principal,
                "message", "Execution will be retried at " + scheduledAt));
    }

    /**
     * Cancel a previously scheduled retry without restarting the execution.
     */
    @DeleteMapping("/{trackId}/schedule-retry")
    @PreAuthorize(Roles.OPERATOR)
    public ResponseEntity<Map<String, Object>> cancelScheduledRetry(
            @PathVariable String trackId,
            @AuthenticationPrincipal UserDetails user) {

        FlowExecution exec = executionRepo.findByTrackId(trackId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No execution for trackId=" + trackId));

        if (exec.getScheduledRetryAt() == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No scheduled retry for trackId=" + trackId);
        }

        exec.setScheduledRetryAt(null);
        exec.setScheduledRetryBy(null);
        executionRepo.save(exec);

        String principal = user != null ? user.getUsername() : "api";
        log.info("[{}] SCHEDULE-RETRY cancelled by {}", trackId, principal);
        return ResponseEntity.ok(Map.of("status", "CANCELLED", "trackId", trackId));
    }

    /**
     * List all executions with a pending scheduled retry, sorted by scheduled time ascending.
     */
    @GetMapping("/scheduled-retries")
    @PreAuthorize(Roles.VIEWER)
    public ResponseEntity<List<Map<String, Object>>> getScheduledRetries() {
        List<Map<String, Object>> result = executionRepo.findAllScheduled().stream()
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("trackId",        e.getTrackId());
                    m.put("originalFilename", e.getOriginalFilename());
                    m.put("flowName",       e.getFlow() != null ? e.getFlow().getName() : null);
                    m.put("status",         e.getStatus().name());
                    m.put("scheduledAt",    e.getScheduledRetryAt() != null ? e.getScheduledRetryAt().toString() : null);
                    m.put("scheduledBy",    e.getScheduledRetryBy());
                    m.put("attemptNumber",  e.getAttemptNumber());
                    return m;
                })
                .toList();
        return ResponseEntity.ok(result);
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
