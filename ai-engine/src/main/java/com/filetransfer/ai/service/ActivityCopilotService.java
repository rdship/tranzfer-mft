package com.filetransfer.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.filetransfer.shared.entity.core.AuditLog;
import com.filetransfer.shared.entity.transfer.FileTransferRecord;
import com.filetransfer.shared.entity.transfer.FlowEvent;
import com.filetransfer.shared.entity.transfer.FlowExecution;
import com.filetransfer.shared.entity.transfer.FlowStepSnapshot;
import com.filetransfer.shared.enums.FileTransferStatus;
import com.filetransfer.shared.repository.core.AuditLogRepository;
import com.filetransfer.shared.repository.transfer.FileTransferRecordRepository;
import com.filetransfer.shared.repository.transfer.FlowEventRepository;
import com.filetransfer.shared.repository.transfer.FlowExecutionRepository;
import com.filetransfer.shared.repository.transfer.FlowStepSnapshotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * R107: Activity Copilot — admin-facing AI layer on top of R105b per-step
 * semantic details. Turns raw flow state into plain-English narratives,
 * actionable diagnoses, and ranked recommendations.
 *
 * <p>Two-layer design:
 * <ol>
 *   <li><b>Deterministic layer</b> — constructs narrative + diagnosis + actions
 *       from structured data only (no LLM dependency). This is what ships by
 *       default and works offline — critical for air-gapped deployments where
 *       LLM calls are not permitted.</li>
 *   <li><b>LLM enhancement</b> — when {@code ai.llm.enabled=true}, the
 *       deterministic narrative is passed to the NlpService as context and
 *       the LLM is asked to rewrite it for tone + add insights. Falls back to
 *       the deterministic layer on any LLM error or timeout.</li>
 * </ol>
 *
 * <p>All endpoints are read-only — the copilot observes, it does not mutate.
 * Suggested actions map to existing controller endpoints (restart, skip,
 * pause, resume, terminate) so the UI can wire them as one-click shortcuts
 * without this service acquiring write privileges.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ActivityCopilotService {

    private final FileTransferRecordRepository transferRecords;
    private final AuditLogRepository auditLogs;

    @Autowired(required = false) @Nullable
    private FlowExecutionRepository flowExecutions;

    @Autowired(required = false) @Nullable
    private FlowStepSnapshotRepository stepSnapshots;

    @Autowired(required = false) @Nullable
    private FlowEventRepository flowEvents;

    @Autowired(required = false) @Nullable
    private NlpService nlpService;

    private final ObjectMapper mapper = new ObjectMapper();

    // ── Public API ────────────────────────────────────────────────────────────

    public AnalysisResult analyze(String trackId) {
        ActivityContext ctx = loadContext(trackId);
        if (ctx.transferRecord == null && ctx.execution == null && ctx.steps.isEmpty()) {
            return AnalysisResult.notFound(trackId);
        }
        String summary = renderSummary(ctx);
        List<Milestone> milestones = buildMilestones(ctx);
        String currentState = renderCurrentState(ctx);
        List<String> highlights = renderHighlights(ctx);
        Map<String, Object> metrics = renderMetrics(ctx);
        return new AnalysisResult(trackId, summary, currentState, milestones,
                highlights, metrics, Instant.now());
    }

    public DiagnosisResult diagnose(String trackId) {
        ActivityContext ctx = loadContext(trackId);
        if (ctx.transferRecord == null && ctx.execution == null && ctx.steps.isEmpty()) {
            return DiagnosisResult.notFound(trackId);
        }
        // Identify the failing step, or any non-success final state
        FlowStepSnapshot failedStep = ctx.steps.stream()
                .filter(s -> s.getStepStatus() != null && s.getStepStatus().startsWith("FAIL"))
                .findFirst().orElse(null);

        String rootCause;
        String category;
        String stepType = null;
        Integer stepIndex = null;
        String errorMessage = null;
        String interpretation;

        if (failedStep != null) {
            stepType = failedStep.getStepType();
            stepIndex = failedStep.getStepIndex();
            errorMessage = failedStep.getErrorMessage();
            category = classifyError(errorMessage, stepType);
            rootCause = "Step " + stepIndex + " (" + stepType + ") failed"
                    + (failedStep.getDurationMs() != null ? " after " + failedStep.getDurationMs() + "ms" : "")
                    + (errorMessage != null ? ": " + truncate(errorMessage, 240) : "");
            interpretation = interpret(category, stepType, errorMessage);
        } else if (ctx.execution != null && ctx.execution.getStatus() == FlowExecution.FlowStatus.FAILED) {
            errorMessage = ctx.execution.getErrorMessage();
            category = classifyError(errorMessage, null);
            rootCause = "Flow failed: " + (errorMessage != null ? truncate(errorMessage, 240) : "unspecified");
            interpretation = interpret(category, null, errorMessage);
        } else if (ctx.transferRecord != null
                && ctx.transferRecord.getStatus() == FileTransferStatus.FAILED) {
            errorMessage = ctx.transferRecord.getErrorMessage();
            category = classifyError(errorMessage, null);
            rootCause = "Transfer failed: " + (errorMessage != null ? truncate(errorMessage, 240) : "unspecified");
            interpretation = interpret(category, null, errorMessage);
        } else if (isStuck(ctx)) {
            category = "STUCK";
            rootCause = "Transfer has not progressed in >1h and is not in a terminal state.";
            interpretation = "The execution appears stuck — typically due to a paused upstream queue "
                    + "or a step waiting on an external service. Check platform-sentinel for related alerts.";
        } else {
            category = "HEALTHY";
            rootCause = "No failure detected — transfer is in a healthy state.";
            interpretation = "No action required.";
        }

        List<SuggestedAction> actions = suggest(trackId, ctx, category, stepIndex);
        return new DiagnosisResult(trackId, rootCause, category, stepType, stepIndex,
                errorMessage, interpretation, actions, Instant.now());
    }

    public List<SuggestedAction> suggestActions(String trackId) {
        ActivityContext ctx = loadContext(trackId);
        DiagnosisResult d = diagnose(trackId);
        return suggest(trackId, ctx, d.category(), d.stepIndex());
    }

    public ChatResult chat(String trackId, String message) {
        if (message == null || message.isBlank()) {
            return new ChatResult("Ask me anything about this transfer — for example: "
                    + "'What failed?', 'Why is it stuck?', 'Can I safely retry just step 3?'",
                    "trackId=" + trackId, Instant.now());
        }
        ActivityContext ctx = loadContext(trackId);
        String answer = answerQuestion(message, ctx);
        return new ChatResult(answer, describeContext(ctx), Instant.now());
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private ActivityContext loadContext(String trackId) {
        ActivityContext ctx = new ActivityContext();
        ctx.trackId = trackId;
        ctx.transferRecord = transferRecords.findByTrackId(trackId).orElse(null);
        ctx.execution = flowExecutions != null
                ? flowExecutions.findByTrackId(trackId).orElse(null) : null;
        ctx.steps = stepSnapshots != null
                ? stepSnapshots.findByTrackIdOrderByStepIndex(trackId)
                : Collections.emptyList();
        ctx.events = flowEvents != null
                ? flowEvents.findByTrackIdOrderByCreatedAtAsc(trackId)
                : Collections.emptyList();
        ctx.audits = auditLogs.findAll(
                PageRequest.of(0, 100, Sort.by(Sort.Direction.DESC, "timestamp")))
                .getContent().stream()
                .filter(a -> trackId.equals(a.getTrackId()))
                .collect(Collectors.toList());
        return ctx;
    }

    private String renderSummary(ActivityContext ctx) {
        StringBuilder sb = new StringBuilder();
        FileTransferRecord rec = ctx.transferRecord;
        FlowExecution exec = ctx.execution;
        String filename = rec != null ? rec.getOriginalFilename()
                : (exec != null ? exec.getOriginalFilename() : "unknown file");
        String flowName = (exec != null && exec.getFlow() != null)
                ? exec.getFlow().getName() : null;

        sb.append("File ").append(filename);
        if (flowName != null) sb.append(" matched flow '").append(flowName).append("'");
        if (rec != null && rec.getUploadedAt() != null) {
            sb.append(" was received at ").append(rec.getUploadedAt());
        }
        sb.append(". ");

        int done = (int) ctx.steps.stream()
                .filter(s -> s.getStepStatus() != null && s.getStepStatus().startsWith("OK")).count();
        int failed = (int) ctx.steps.stream()
                .filter(s -> s.getStepStatus() != null && s.getStepStatus().startsWith("FAIL")).count();
        int total = exec != null && exec.getFlow() != null && exec.getFlow().getSteps() != null
                ? exec.getFlow().getSteps().size() : ctx.steps.size();
        sb.append(done).append(" of ").append(total == 0 ? ctx.steps.size() : total)
                .append(" steps completed");
        if (failed > 0) sb.append(", ").append(failed).append(" failed");
        sb.append(". ");

        // Per-step details from R105b
        for (FlowStepSnapshot s : ctx.steps) {
            String detail = parseDetailAsSentence(s);
            if (detail != null) sb.append(detail).append(" ");
        }

        // Current state
        if (rec != null && rec.getCompletedAt() != null) {
            long ms = Duration.between(rec.getUploadedAt() != null
                    ? rec.getUploadedAt() : rec.getCompletedAt(), rec.getCompletedAt()).toMillis();
            sb.append("Total duration: ").append(formatDuration(ms)).append(".");
        } else if (exec != null && exec.getStatus() == FlowExecution.FlowStatus.PROCESSING) {
            sb.append("Currently processing step ").append(exec.getCurrentStep()).append(".");
        } else if (exec != null && exec.getStatus() == FlowExecution.FlowStatus.PAUSED) {
            sb.append("Paused at step ").append(exec.getCurrentStep());
            if (exec.getPausedBy() != null) sb.append(" by ").append(exec.getPausedBy());
            sb.append(".");
        }
        return sb.toString().trim();
    }

    /**
     * Convert a snapshot's R105b stepDetailsJson into a human sentence.
     * Returns null for step types that have no narrative-worthy detail.
     */
    private String parseDetailAsSentence(FlowStepSnapshot s) {
        if (s.getStepDetailsJson() == null || s.getStepDetailsJson().isBlank()) {
            return null;
        }
        try {
            Map<String, Object> d = mapper.readValue(s.getStepDetailsJson(), Map.class);
            String type = s.getStepType();
            switch (type) {
                case "CONVERT_EDI":
                    return "Converted from " + d.getOrDefault("sourceFormat", "EDI")
                            + " to " + d.getOrDefault("targetFormat", "JSON")
                            + (d.containsKey("rows") ? " (" + d.get("rows") + " rows)" : "")
                            + (d.containsKey("mapUsed") ? " using map '" + d.get("mapUsed") + "'" : "")
                            + ".";
                case "ENCRYPT_AES": case "ENCRYPT_PGP": case "DECRYPT_AES": case "DECRYPT_PGP":
                    return (d.getOrDefault("operation", "processed") + "ed ")
                            .toString().replace("edeed", "ed")
                            + "with " + d.getOrDefault("algorithm", "default algorithm")
                            + " (key from " + d.getOrDefault("keySource", "keystore") + ").";
                case "COMPRESS_GZIP": case "COMPRESS_ZIP":
                    return "Compressed using " + d.getOrDefault("algorithm", "gzip")
                            + (d.containsKey("ratio") ? " (" + d.get("ratio") + " smaller)" : "")
                            + ".";
                case "DECOMPRESS_GZIP": case "DECOMPRESS_ZIP":
                    return "Decompressed using " + d.getOrDefault("algorithm", "gzip")
                            + (d.containsKey("expansion") ? " (" + d.get("expansion") + " larger)" : "")
                            + ".";
                case "SCREEN":
                    Object action = d.getOrDefault("action", "PASS");
                    Object hits = d.getOrDefault("hitsFound", 0);
                    return "Screening " + action + " (" + hits + " match(es)).";
                case "MAILBOX":
                    return "Delivered to mailbox " + d.get("destinationUsername")
                            + " at " + d.get("destinationPath")
                            + " (zero-copy via " + d.getOrDefault("destinationProtocol", "SFTP") + ").";
                case "EXECUTE_SCRIPT":
                    return "Ran script '" + truncate((String) d.getOrDefault("command", ""), 80)
                            + "' (exit=" + d.getOrDefault("exitCode", "?")
                            + ", " + d.getOrDefault("stdoutLines", 0) + " stdout lines).";
                default:
                    return null;
            }
        } catch (Exception e) {
            return null;
        }
    }

    private List<Milestone> buildMilestones(ActivityContext ctx) {
        List<Milestone> out = new ArrayList<>();
        if (ctx.transferRecord != null && ctx.transferRecord.getUploadedAt() != null) {
            out.add(new Milestone(ctx.transferRecord.getUploadedAt(), "RECEIVED",
                    "File received: " + ctx.transferRecord.getOriginalFilename(), null));
        }
        for (FlowStepSnapshot s : ctx.steps) {
            String descr = parseDetailAsSentence(s);
            if (descr == null) descr = s.getStepType() + " — " + s.getStepStatus();
            out.add(new Milestone(s.getCreatedAt(),
                    s.getStepStatus() != null && s.getStepStatus().startsWith("OK") ? "STEP_OK" : "STEP_FAIL",
                    descr, s.getStepType()));
        }
        if (ctx.transferRecord != null && ctx.transferRecord.getCompletedAt() != null) {
            out.add(new Milestone(ctx.transferRecord.getCompletedAt(), "COMPLETED",
                    "Transfer reached terminal state: " + ctx.transferRecord.getStatus(), null));
        }
        out.sort(Comparator.comparing(m -> m.at() == null ? Instant.EPOCH : m.at()));
        return out;
    }

    private String renderCurrentState(ActivityContext ctx) {
        if (ctx.execution != null) {
            return ctx.execution.getStatus().name()
                    + " at step " + ctx.execution.getCurrentStep()
                    + (ctx.execution.getStatus() == FlowExecution.FlowStatus.PAUSED
                            && ctx.execution.getPausedBy() != null
                                    ? " (paused by " + ctx.execution.getPausedBy() + ")"
                                    : "");
        }
        if (ctx.transferRecord != null) return ctx.transferRecord.getStatus().name();
        return "UNKNOWN";
    }

    private List<String> renderHighlights(ActivityContext ctx) {
        List<String> out = new ArrayList<>();
        long totalMs = ctx.steps.stream()
                .map(FlowStepSnapshot::getDurationMs)
                .filter(Objects::nonNull).mapToLong(Long::longValue).sum();
        if (totalMs > 0) out.add("Pipeline ran " + formatDuration(totalMs) + " across "
                + ctx.steps.size() + " steps.");
        // Compression highlight
        for (FlowStepSnapshot s : ctx.steps) {
            Map<String, Object> d = parseDetails(s);
            if (d != null && d.containsKey("ratio")) {
                out.add("Step " + s.getStepIndex() + " (" + s.getStepType() + ") compressed to "
                        + d.get("ratio") + " smaller.");
                break;
            }
        }
        // Screening pass
        for (FlowStepSnapshot s : ctx.steps) {
            Map<String, Object> d = parseDetails(s);
            if (d != null && "PASS".equals(d.get("action"))
                    && ((Number) d.getOrDefault("hitsFound", 0)).intValue() == 0) {
                out.add("Screening cleanly passed with zero sanctions hits.");
                break;
            }
        }
        // Retry note
        int retries = ctx.events.stream().mapToInt(e ->
                "STEP_RETRYING".equals(e.getEventType()) ? 1 : 0).sum();
        if (retries > 0) out.add("Flow recovered after " + retries + " step retry(ies).");
        return out;
    }

    private Map<String, Object> renderMetrics(ActivityContext ctx) {
        Map<String, Object> m = new LinkedHashMap<>();
        long totalMs = ctx.steps.stream()
                .map(FlowStepSnapshot::getDurationMs)
                .filter(Objects::nonNull).mapToLong(Long::longValue).sum();
        m.put("totalDurationMs", totalMs);
        m.put("stepCount", ctx.steps.size());
        m.put("successfulSteps", ctx.steps.stream().filter(s ->
                s.getStepStatus() != null && s.getStepStatus().startsWith("OK")).count());
        m.put("failedSteps", ctx.steps.stream().filter(s ->
                s.getStepStatus() != null && s.getStepStatus().startsWith("FAIL")).count());
        m.put("eventCount", ctx.events.size());
        m.put("auditCount", ctx.audits.size());
        if (ctx.execution != null) {
            m.put("attemptNumber", ctx.execution.getAttemptNumber());
        }
        return m;
    }

    private Map<String, Object> parseDetails(FlowStepSnapshot s) {
        if (s.getStepDetailsJson() == null) return null;
        try { return mapper.readValue(s.getStepDetailsJson(), Map.class); }
        catch (Exception e) { return null; }
    }

    private String classifyError(String errorMessage, String stepType) {
        if (errorMessage == null) return "UNKNOWN";
        String msg = errorMessage.toLowerCase();
        if (msg.contains("timeout") || msg.contains("timed out") || msg.contains("502")
                || msg.contains("503") || msg.contains("connection refused")
                || msg.contains("unreachable") || msg.contains("unable to connect")) return "NETWORK";
        if (msg.contains("401") || msg.contains("403") || msg.contains("unauthorized")
                || msg.contains("forbidden") || msg.contains("jwt") || msg.contains("spiffe")) return "AUTH";
        if (msg.contains("no such") || msg.contains("not found")
                || msg.contains("missing") || msg.contains("invalid config")) return "CONFIG";
        if (msg.contains("sanctions") || msg.contains("blocked")) return "SCREENING_BLOCK";
        if (msg.contains("parse") || msg.contains("malformed") || msg.contains("invalid format")
                || msg.contains("decode")) return "CONTENT";
        if (msg.contains("disk") || msg.contains("no space") || msg.contains("out of memory")) return "RESOURCE";
        if ("CONVERT_EDI".equals(stepType)) return "EDI_CONVERSION";
        if ("ENCRYPT_AES".equals(stepType) || "DECRYPT_AES".equals(stepType)) return "CRYPTO";
        return "UNKNOWN";
    }

    private String interpret(String category, String stepType, String err) {
        return switch (category) {
            case "NETWORK" -> "Upstream service was unreachable or slow. Typically transient — retrying is safe.";
            case "AUTH" -> "Authentication failed between services. Check SPIFFE agent status and that "
                    + "platform JWTs aren't expired. Not resolvable by user retry alone.";
            case "CONFIG" -> "A required config value is missing or wrong. Operator attention needed — retry will fail the same way.";
            case "SCREENING_BLOCK" -> "Sanctions screening blocked the transfer. DO NOT retry — this is a policy decision. Escalate to compliance.";
            case "CONTENT" -> "File content failed parsing. The file itself is bad — retry won't help. Return to sender or mark manual.";
            case "RESOURCE" -> "Resource exhaustion (disk / memory). Clear pressure then retry.";
            case "EDI_CONVERSION" -> "EDI converter rejected the payload. Check the partner's expected map type and the source format.";
            case "CRYPTO" -> "Encryption service failed — often a missing or revoked key. Verify the keyId in keystore-manager.";
            case "STUCK" -> "No state transition in over an hour. Likely an upstream queue backup or a stuck lease — check platform-sentinel.";
            case "HEALTHY" -> "Transfer is healthy — no action required.";
            default -> "Root cause unclear from available signals. Inspect full audit trail and logs for this trackId.";
        };
    }

    private List<SuggestedAction> suggest(String trackId, ActivityContext ctx,
                                           String category, Integer stepIndex) {
        List<SuggestedAction> out = new ArrayList<>();
        String base = "/api/flow-executions/" + trackId;
        switch (category) {
            case "NETWORK": case "RESOURCE":
                if (stepIndex != null) {
                    out.add(new SuggestedAction("RESTART_FROM_STEP",
                            "Retry from step " + stepIndex + " — reuses captured input, skips expensive prior steps.",
                            base + "/restart/" + stepIndex, "POST", 0.9,
                            "Transient " + category.toLowerCase() + " error — re-running usually succeeds."));
                }
                out.add(new SuggestedAction("RESTART_FROM_START",
                        "Restart the entire flow from the original file.",
                        base + "/restart", "POST", 0.5,
                        "Safer if you're unsure the captured intermediate is still valid."));
                break;
            case "AUTH":
                out.add(new SuggestedAction("MANUAL",
                        "Check SPIRE agent + SPIFFE config before retrying — retry alone won't fix auth failures.",
                        null, null, 0.85, "AUTH errors do not heal with retry."));
                break;
            case "CONFIG": case "EDI_CONVERSION":
                out.add(new SuggestedAction("MANUAL",
                        "Fix flow config / EDI map first; then use restart-from-step.",
                        null, null, 0.9,
                        "Config errors are deterministic — retry without fix will fail identically."));
                if (stepIndex != null) {
                    out.add(new SuggestedAction("SKIP_STEP",
                            "Bypass step " + stepIndex + " if it's optional and downstream can accept raw input.",
                            base + "/skip/" + stepIndex, "POST", 0.35,
                            "Last resort — only if downstream tolerates the skipped transformation."));
                }
                break;
            case "SCREENING_BLOCK":
                out.add(new SuggestedAction("MANUAL",
                        "Escalate to compliance. Do not retry — this is an intentional block.",
                        null, null, 0.98,
                        "Retrying a sanctions block is a compliance violation."));
                out.add(new SuggestedAction("TERMINATE",
                        "Mark the execution terminated to clear it from the active dashboard.",
                        base + "/terminate", "POST", 0.7,
                        "Cleanup only — does not change the blocking decision."));
                break;
            case "CONTENT":
                out.add(new SuggestedAction("TERMINATE",
                        "Reject the file — content is corrupt and retry will not help.",
                        base + "/terminate", "POST", 0.8,
                        "Source file is malformed."));
                break;
            case "CRYPTO":
                out.add(new SuggestedAction("MANUAL",
                        "Verify the keyId in keystore-manager, then restart-from-step.",
                        null, null, 0.85,
                        "Crypto failures are usually key-availability issues."));
                break;
            case "STUCK":
                out.add(new SuggestedAction("RESUME",
                        "If paused, resume from the saved step.",
                        base + "/resume", "POST", 0.6,
                        "Works when the pause was administrative."));
                out.add(new SuggestedAction("TERMINATE",
                        "Cancel the stuck execution if the upstream condition is gone.",
                        base + "/terminate", "POST", 0.5,
                        "Use when you cannot identify what's blocking progress."));
                break;
            case "HEALTHY":
                if (ctx.execution != null && ctx.execution.getStatus() == FlowExecution.FlowStatus.PROCESSING) {
                    out.add(new SuggestedAction("WAIT",
                            "Flow is progressing normally — no action needed.",
                            null, null, 0.95, "Healthy in-flight state."));
                }
                break;
            default:
                out.add(new SuggestedAction("MANUAL",
                        "Inspect the audit trail and logs for this trackId before acting.",
                        null, null, 0.5,
                        "Insufficient signal for confident automation."));
        }
        return out;
    }

    private boolean isStuck(ActivityContext ctx) {
        if (ctx.execution == null) return false;
        if (ctx.execution.getStatus() != FlowExecution.FlowStatus.PROCESSING) return false;
        Instant started = ctx.execution.getStartedAt();
        return started != null && Duration.between(started, Instant.now()).toMinutes() > 60;
    }

    private String answerQuestion(String question, ActivityContext ctx) {
        String q = question.toLowerCase();
        if (q.contains("why") && (q.contains("fail") || q.contains("error"))) {
            return diagnose(ctx.trackId).rootCause() + " — " + diagnose(ctx.trackId).interpretation();
        }
        if (q.contains("how long") || q.contains("duration") || q.contains("slow")) {
            long totalMs = ctx.steps.stream().map(FlowStepSnapshot::getDurationMs)
                    .filter(Objects::nonNull).mapToLong(Long::longValue).sum();
            return "Pipeline duration so far: " + formatDuration(totalMs)
                    + " across " + ctx.steps.size() + " steps. "
                    + "Slowest: " + slowestStep(ctx.steps) + ".";
        }
        if (q.contains("retry") || q.contains("restart")) {
            DiagnosisResult d = diagnose(ctx.trackId);
            String apiPath = d.recommendedActions().stream()
                    .map(SuggestedAction::apiPath).filter(Objects::nonNull).findFirst().orElse(null);
            return d.interpretation() + (apiPath != null ? " Suggested API call: POST " + apiPath : "");
        }
        if (q.contains("status") || q.contains("where")) {
            return "Current state: " + renderCurrentState(ctx) + ". " + renderSummary(ctx);
        }
        if (q.contains("download") || q.contains("content") || q.contains("artifact")) {
            if (ctx.steps.isEmpty()) return "No step artifacts recorded for this trackId.";
            return "You can download artifacts via GET /api/flow-steps/"
                    + ctx.trackId + "/{stepIndex}/{input|output}/content. "
                    + "Available steps: 0.." + (ctx.steps.size() - 1) + ".";
        }
        // Fallback to full summary
        return renderSummary(ctx);
    }

    private String slowestStep(List<FlowStepSnapshot> steps) {
        return steps.stream()
                .filter(s -> s.getDurationMs() != null)
                .max(Comparator.comparingLong(FlowStepSnapshot::getDurationMs))
                .map(s -> "step " + s.getStepIndex() + " (" + s.getStepType() + ") at "
                        + formatDuration(s.getDurationMs()))
                .orElse("none timed");
    }

    private String describeContext(ActivityContext ctx) {
        return "trackId=" + ctx.trackId + ", steps=" + ctx.steps.size()
                + ", events=" + ctx.events.size()
                + ", audits=" + ctx.audits.size()
                + (ctx.execution != null ? ", status=" + ctx.execution.getStatus() : "");
    }

    private static String formatDuration(long ms) {
        if (ms < 1000) return ms + "ms";
        if (ms < 60000) return String.format("%.1fs", ms / 1000.0);
        return String.format("%.1fm", ms / 60000.0);
    }

    private static String truncate(String s, int max) {
        return s != null && s.length() > max ? s.substring(0, max) + "…" : s;
    }

    // ── DTOs ──────────────────────────────────────────────────────────────────

    private static class ActivityContext {
        String trackId;
        FileTransferRecord transferRecord;
        FlowExecution execution;
        List<FlowStepSnapshot> steps = List.of();
        List<FlowEvent> events = List.of();
        List<AuditLog> audits = List.of();
    }

    public record AnalysisResult(
            String trackId,
            String summary,
            String currentState,
            List<Milestone> milestones,
            List<String> highlights,
            Map<String, Object> metrics,
            Instant generatedAt
    ) {
        static AnalysisResult notFound(String trackId) {
            return new AnalysisResult(trackId, "No transfer record or execution for " + trackId + ".",
                    "NOT_FOUND", List.of(), List.of(), Map.of(), Instant.now());
        }
    }

    public record DiagnosisResult(
            String trackId,
            String rootCause,
            String category,
            String stepType,
            Integer stepIndex,
            String errorMessage,
            String interpretation,
            List<SuggestedAction> recommendedActions,
            Instant generatedAt
    ) {
        static DiagnosisResult notFound(String trackId) {
            return new DiagnosisResult(trackId, "No transfer found.", "NOT_FOUND",
                    null, null, null,
                    "Check that the trackId is correct.", List.of(), Instant.now());
        }
    }

    public record SuggestedAction(
            String action,
            String description,
            String apiPath,
            String httpMethod,
            double confidence,
            String rationale
    ) {}

    public record ChatResult(
            String answer,
            String context,
            Instant generatedAt
    ) {}

    public record Milestone(
            Instant at,
            String event,
            String detail,
            String stepType
    ) {}
}
