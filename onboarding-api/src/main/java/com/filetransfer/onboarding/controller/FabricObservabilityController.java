package com.filetransfer.onboarding.controller;

import com.filetransfer.shared.entity.transfer.FabricCheckpoint;
import com.filetransfer.shared.entity.transfer.FabricInstance;
import com.filetransfer.shared.repository.FabricCheckpointRepository;
import com.filetransfer.shared.repository.FabricInstanceRepository;
import com.filetransfer.shared.security.Roles;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Observability API for the Flow Fabric.
 * Answers "where is file X right now?" and "what is each instance doing?"
 */
@RestController
@RequestMapping("/api/fabric")
@RequiredArgsConstructor
@PreAuthorize(Roles.OPERATOR)
public class FabricObservabilityController {

    private final FabricCheckpointRepository checkpointRepo;
    private final FabricInstanceRepository instanceRepo;

    /**
     * Full per-step timeline for a trackId.
     * This is the headline endpoint for "where is file X right now?"
     */
    @GetMapping("/track/{trackId}/timeline")
    public Map<String, Object> timeline(@PathVariable String trackId) {
        List<FabricCheckpoint> checkpoints = checkpointRepo.findByTrackIdOrderByStepIndexAsc(trackId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("trackId", trackId);
        result.put("stepCount", checkpoints.size());

        // Determine current state from latest checkpoint
        FabricCheckpoint latest = checkpoints.isEmpty() ? null : checkpoints.get(checkpoints.size() - 1);
        result.put("currentStatus", latest != null ? latest.getStatus() : "NOT_FOUND");
        result.put("currentStep", latest != null ? latest.getStepIndex() : null);
        result.put("currentInstance", latest != null ? latest.getProcessingInstance() : null);

        // Total duration = first.started -> last.completed (if done) or now (if in-progress)
        long totalMs = 0;
        if (!checkpoints.isEmpty()) {
            FabricCheckpoint first = checkpoints.get(0);
            Instant end = latest.getCompletedAt() != null ? latest.getCompletedAt() : Instant.now();
            if (first.getStartedAt() != null) {
                totalMs = Duration.between(first.getStartedAt(), end).toMillis();
            }
        }
        result.put("totalDurationMs", totalMs);

        // Map checkpoints to step entries
        List<Map<String, Object>> steps = new ArrayList<>();
        for (FabricCheckpoint cp : checkpoints) {
            Map<String, Object> step = new LinkedHashMap<>();
            step.put("index", cp.getStepIndex());
            step.put("type", cp.getStepType());
            step.put("status", cp.getStatus());
            step.put("instance", cp.getProcessingInstance());
            step.put("inputKey", cp.getInputStorageKey());
            step.put("outputKey", cp.getOutputStorageKey());
            step.put("inputSizeBytes", cp.getInputSizeBytes());
            step.put("outputSizeBytes", cp.getOutputSizeBytes());
            step.put("startedAt", cp.getStartedAt());
            step.put("completedAt", cp.getCompletedAt());
            step.put("durationMs", cp.getDurationMs());
            step.put("attemptNumber", cp.getAttemptNumber());
            if (cp.getErrorMessage() != null) {
                step.put("errorCategory", cp.getErrorCategory());
                step.put("errorMessage", cp.getErrorMessage());
            }
            if (cp.getLeaseExpiresAt() != null && "IN_PROGRESS".equals(cp.getStatus())) {
                long leaseRemainingMs = Duration.between(Instant.now(), cp.getLeaseExpiresAt()).toMillis();
                step.put("leaseExpiresAt", cp.getLeaseExpiresAt());
                step.put("leaseRemainingMs", leaseRemainingMs);
                step.put("isStuck", leaseRemainingMs < 0);
            }
            steps.add(step);
        }
        result.put("steps", steps);
        return result;
    }

    /**
     * Aggregate queue depth per step type.
     * Used for dashboards.
     */
    @GetMapping("/queues")
    public Map<String, Object> queues() {
        List<Object[]> counts = checkpointRepo.countInProgressByStepType();
        Map<String, Long> depths = new LinkedHashMap<>();
        for (Object[] row : counts) {
            depths.put((String) row[0], (Long) row[1]);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("inProgressByStepType", depths);
        out.put("timestamp", Instant.now());
        return out;
    }

    /**
     * List all active instances with their work.
     */
    @GetMapping("/instances")
    public Map<String, Object> instances() {
        List<FabricInstance> all = instanceRepo.findAll();
        Instant deadThreshold = Instant.now().minus(2, ChronoUnit.MINUTES);

        List<Map<String, Object>> active = new ArrayList<>();
        List<Map<String, Object>> dead = new ArrayList<>();

        for (FabricInstance inst : all) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("instanceId", inst.getInstanceId());
            entry.put("serviceName", inst.getServiceName());
            entry.put("host", inst.getHost());
            entry.put("startedAt", inst.getStartedAt());
            entry.put("lastHeartbeat", inst.getLastHeartbeat());
            entry.put("status", inst.getStatus());
            entry.put("inFlightCount", inst.getInFlightCount());

            if (inst.getLastHeartbeat() != null && inst.getLastHeartbeat().isBefore(deadThreshold)) {
                dead.add(entry);
            } else {
                active.add(entry);
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("active", active);
        out.put("dead", dead);
        out.put("total", all.size());
        return out;
    }

    /**
     * Find stuck work items (lease expired but still IN_PROGRESS), paginated.
     * Defaults: page=0, size=50. Max size clamped at 500 to avoid unbounded dumps.
     */
    @GetMapping("/stuck")
    public Map<String, Object> stuck(@RequestParam(defaultValue = "0") int page,
                                     @RequestParam(defaultValue = "50") int size) {
        int clampedSize = Math.max(1, Math.min(size, 500));
        Page<FabricCheckpoint> stuckPage = checkpointRepo.findStuckCheckpoints(
            Instant.now(), PageRequest.of(Math.max(0, page), clampedSize));

        List<Map<String, Object>> items = new ArrayList<>();
        for (FabricCheckpoint cp : stuckPage.getContent()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("trackId", cp.getTrackId());
            entry.put("stepIndex", cp.getStepIndex());
            entry.put("stepType", cp.getStepType());
            entry.put("instance", cp.getProcessingInstance());
            entry.put("startedAt", cp.getStartedAt());
            entry.put("leaseExpiresAt", cp.getLeaseExpiresAt());
            if (cp.getLeaseExpiresAt() != null) {
                entry.put("stuckForMs", Duration.between(cp.getLeaseExpiresAt(), Instant.now()).toMillis());
            }
            items.add(entry);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        out.put("page", stuckPage.getNumber());
        out.put("size", stuckPage.getSize());
        out.put("totalElements", stuckPage.getTotalElements());
        out.put("totalPages", stuckPage.getTotalPages());
        return out;
    }

    /**
     * Recent latency summary. Bounds the sample size to avoid loading millions of rows
     * into memory on large deployments. Default sample cap is 10k rows from the last hour.
     *
     * @param hours  time window in hours (default 1, clamped to 1..24)
     * @param sample max sample rows to include in percentile calculation (default 10000, clamped to 100..50000)
     */
    /** List recent checkpoints with pagination. Addresses H15 — /api/fabric/checkpoints was missing. */
    @GetMapping("/checkpoints")
    public org.springframework.data.domain.Page<FabricCheckpoint> checkpoints(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return checkpointRepo.findAll(
                org.springframework.data.domain.PageRequest.of(page, Math.min(size, 200),
                        org.springframework.data.domain.Sort.by("createdAt").descending()));
    }

    @GetMapping("/latency")
    public Map<String, Object> latency(@RequestParam(defaultValue = "1") int hours,
                                       @RequestParam(defaultValue = "10000") int sample) {
        int clampedHours = Math.max(1, Math.min(hours, 24));
        int clampedSample = Math.max(100, Math.min(sample, 50_000));
        Instant since = Instant.now().minus(clampedHours, ChronoUnit.HOURS);

        Page<FabricCheckpoint> recentPage = checkpointRepo.findRecentCompleted(
            since, PageRequest.of(0, clampedSample));
        List<FabricCheckpoint> recent = recentPage.getContent();

        if (recent.isEmpty()) {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("sampleCount", 0);
            empty.put("totalInWindow", recentPage.getTotalElements());
            empty.put("message", "No recent completed steps");
            return empty;
        }

        // Group by step type, compute percentiles
        Map<String, List<Long>> byType = new HashMap<>();
        for (FabricCheckpoint cp : recent) {
            if (cp.getDurationMs() != null && cp.getDurationMs() > 0) {
                byType.computeIfAbsent(cp.getStepType(), k -> new ArrayList<>()).add(cp.getDurationMs());
            }
        }

        Map<String, Object> stats = new LinkedHashMap<>();
        for (var entry : byType.entrySet()) {
            List<Long> durations = entry.getValue();
            Collections.sort(durations);
            Map<String, Object> typeStats = new LinkedHashMap<>();
            typeStats.put("count", durations.size());
            typeStats.put("min", durations.get(0));
            typeStats.put("max", durations.get(durations.size() - 1));
            typeStats.put("p50", durations.get((int) (durations.size() * 0.5)));
            typeStats.put("p95", durations.get(Math.min(durations.size() - 1, (int) (durations.size() * 0.95))));
            typeStats.put("p99", durations.get(Math.min(durations.size() - 1, (int) (durations.size() * 0.99))));
            stats.put(entry.getKey(), typeStats);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sampleCount", recent.size());
        out.put("totalInWindow", recentPage.getTotalElements());
        out.put("byStepType", stats);
        out.put("since", since);
        out.put("windowHours", clampedHours);
        return out;
    }
}
