package com.filetransfer.onboarding.controller;

import com.filetransfer.shared.entity.FabricCheckpoint;
import com.filetransfer.shared.entity.FabricInstance;
import com.filetransfer.shared.repository.FabricCheckpointRepository;
import com.filetransfer.shared.repository.FabricInstanceRepository;
import com.filetransfer.shared.security.Roles;
import lombok.RequiredArgsConstructor;
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
     * Find stuck work items (lease expired but still IN_PROGRESS).
     */
    @GetMapping("/stuck")
    public List<Map<String, Object>> stuck() {
        List<FabricCheckpoint> stuckCps = checkpointRepo.findStuckCheckpoints(Instant.now());
        List<Map<String, Object>> result = new ArrayList<>();
        for (FabricCheckpoint cp : stuckCps) {
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
            result.add(entry);
        }
        return result;
    }

    /**
     * Recent latency summary (last hour).
     */
    @GetMapping("/latency")
    public Map<String, Object> latency() {
        List<FabricCheckpoint> recent = checkpointRepo.findRecentCompleted(Instant.now().minus(1, ChronoUnit.HOURS));

        if (recent.isEmpty()) {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("sampleCount", 0);
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
        out.put("byStepType", stats);
        out.put("since", Instant.now().minus(1, ChronoUnit.HOURS));
        return out;
    }
}
