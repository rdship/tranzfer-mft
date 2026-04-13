package com.filetransfer.shared.routing;

import com.filetransfer.shared.audit.AuditService;
import com.filetransfer.shared.client.EncryptionServiceClient;
import com.filetransfer.shared.client.ForwarderServiceClient;
import com.filetransfer.shared.client.ScreeningServiceClient;
import com.filetransfer.shared.client.ServiceClientProperties;
import com.filetransfer.shared.client.StorageServiceClient;
import com.filetransfer.shared.connector.ConnectorDispatcher;
import com.filetransfer.shared.connector.PartnerWebhookDispatcher;
import com.filetransfer.shared.flow.FlowFunction;
import com.filetransfer.shared.flow.FlowFunctionContext;
import com.filetransfer.shared.flow.FlowFunctionRegistry;
import com.filetransfer.shared.flow.FlowStageManager;
import com.filetransfer.shared.event.FlowStepEvent;
import com.filetransfer.shared.vfs.FileRef;
import com.filetransfer.shared.vfs.VfsFlowBridge;
import com.filetransfer.shared.vfs.VirtualFileSystem;
import com.filetransfer.shared.cluster.ClusterService;
import com.filetransfer.shared.config.PlatformConfig;
import com.filetransfer.shared.entity.*;
import com.filetransfer.shared.enums.Protocol;
import com.filetransfer.shared.enums.ServiceType;
import com.filetransfer.shared.repository.*;
import com.filetransfer.shared.spiffe.SpiffeWorkloadClient;
import com.filetransfer.shared.util.TrackIdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.*;
import org.springframework.lang.Nullable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;
import java.util.zip.*;

/**
 * Core file processing pipeline engine.
 * Evaluates matching flows for incoming files and executes each step in order.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FlowProcessingEngine {

    private final FileFlowRepository flowRepository;
    private final FlowExecutionRepository executionRepository;
    private final FlowApprovalRepository approvalRepository;
    private final TrackIdGenerator trackIdGenerator;
    private final TransferAccountRepository accountRepository;
    private final DeliveryEndpointRepository deliveryEndpointRepository;
    private final ClusterService clusterService;
    private final RestTemplate restTemplate;
    private final PlatformConfig platformConfig;
    private final StorageServiceClient storageClient;
    private final ApplicationEventPublisher eventPublisher;

    @Autowired(required = false)
    @Nullable
    private SpiffeWorkloadClient spiffeWorkloadClient;
    private final ServiceClientProperties serviceProps;

    /** Injected only when VFS is on the classpath (shared-platform). Null for PHYSICAL-only deployments. */
    @Autowired(required = false)
    @Nullable
    private VfsFlowBridge vfsBridge;

    /** Admin-level connector dispatch (Slack, PagerDuty, etc.). Optional — null if not configured. */
    @Autowired(required = false)
    @Nullable
    private ConnectorDispatcher connectorDispatcher;

    /** Partner-level webhook dispatch. Optional — null if no webhooks configured. */
    @Autowired(required = false)
    @Nullable
    private PartnerWebhookDispatcher partnerWebhookDispatcher;

    /** Encryption-service typed client — multipart upload, no Base64 heap load. */
    @Autowired(required = false)
    @Nullable
    private EncryptionServiceClient encryptionClient;

    /** Append-only event journal for flow execution audit trail and actor recovery. */
    @Autowired(required = false)
    @Nullable
    private FlowEventJournal flowEventJournal;

    /** SEDA stage manager for bounded-queue flow execution. Optional — runs synchronously if absent. */
    @Autowired(required = false)
    @Nullable
    private FlowStageManager stageManager;

    /** Plugin function registry. When present, custom/plugin FlowFunctions are tried for unknown step types. */
    @Autowired(required = false)
    @Nullable
    private FlowFunctionRegistry functionRegistry;

    /** RabbitMQ template for publishing lifecycle events. Optional — null when AMQP is not configured. */
    @Autowired(required = false)
    @Nullable
    private org.springframework.amqp.rabbit.core.RabbitTemplate rabbitTemplate;

    /** Audit service for PCI DSS 10.x compliant logging. Optional — null if not configured. */
    @Autowired(required = false)
    @Nullable
    private AuditService auditService;

    /**
     * Dynamic Flow Fabric bridge. Optional — null when shared-fabric not on classpath
     * or fabric is disabled. When non-null and active, executeFlow/executeFlowRef publish
     * to the flow.intake topic instead of submitting to SEDA. A FlowFabricConsumer elsewhere
     * picks up the message and calls {@link #executeFlowViaFabric}/{@link #executeFlowRefViaFabric}.
     */
    @Autowired(required = false)
    @Nullable
    private com.filetransfer.shared.fabric.FlowFabricBridge fabricBridge;

    /**
     * Fabric configuration properties. Optional — null when shared-fabric not configured.
     * Used to gate checkpoint writes on fabric.checkpoint.enabled.
     */
    @Autowired(required = false)
    @Nullable
    private com.filetransfer.fabric.config.FabricProperties fabricProperties;

    /**
     * Execute a specific flow for a file. Creates execution record and processes each step.
     */
    @Transactional
    public FlowExecution executeFlow(FileFlow flow, String trackId, String filename, String inputPath) {
        return executeFlow(flow, trackId, filename, inputPath, null);
    }

    /**
     * Execute a specific flow with matched criteria snapshot for audit trail.
     * When SEDA stages are available, step execution is submitted to the INTAKE
     * stage (bounded-queue, virtual-thread pool) and this method returns immediately
     * with status PROCESSING. When SEDA is absent, runs synchronously as before.
     */
    @Transactional
    public FlowExecution executeFlow(FileFlow flow, String trackId, String filename,
                                      String inputPath, com.filetransfer.shared.matching.MatchCriteria matchedCriteria) {
        FlowExecution exec = FlowExecution.builder()
                .trackId(trackId)
                .flow(flow)
                .originalFilename(filename)
                .currentFilePath(inputPath)
                .status(FlowExecution.FlowStatus.PROCESSING)
                .matchedCriteria(matchedCriteria)
                .stepResults(new ArrayList<>())
                .build();
        exec = executionRepository.save(exec);

        // ── Journal: record execution start ──
        if (flowEventJournal != null) {
            flowEventJournal.recordExecutionStarted(trackId, exec.getId(), null, flow.getSteps().size());
        }

        // ── Per-function step pipeline: publish STEP 0 to its function topic ──
        // No for loop. The queue drives the iteration.
        // Step 0 executes → publishes step 1 → step 1 executes → publishes step 2 → ...
        // Only metadata in the queue (~200 bytes). File stays in storage-manager.
        if (fabricBridge != null && fabricBridge.isFabricActive() && !flow.getSteps().isEmpty()) {
            try {
                FileFlow.FlowStep firstStep = flow.getSteps().get(0);
                String initialKey = exec.getCurrentStorageKey() != null
                        ? exec.getCurrentStorageKey()
                        : exec.getInitialStorageKey();
                if (initialKey != null) {
                    fabricBridge.publishStep(trackId, 0, firstStep.getType(), initialKey);
                    log.info("[{}] Flow '{}' → published step 0 ({}) to per-function pipeline",
                            trackId, flow.getName(), firstStep.getType());
                    return exec;
                }
                // No storage key yet (PHYSICAL mode without checkpoint) — fall through to SEDA
                log.debug("[{}] No storage key for pipeline — falling back to SEDA/sync", trackId);
            } catch (Exception e) {
                log.warn("[{}] Pipeline publish failed, falling back to SEDA: {}", trackId, e.getMessage());
            }
        }

        if (stageManager != null) {
            final FlowExecution sedaExec = exec;
            boolean submitted = stageManager.submit("INTAKE", () -> executeFlowSteps(sedaExec, flow, trackId, filename, inputPath));
            if (!submitted) {
                log.error("[{}] SEDA INTAKE queue full — executing synchronously", trackId);
                executeFlowSteps(exec, flow, trackId, filename, inputPath);
            } else {
                log.info("[{}] Flow '{}' submitted to SEDA INTAKE stage", trackId, flow.getName());
            }
            return exec;
        }

        // ── Synchronous fallback (no SEDA) ──
        executeFlowSteps(exec, flow, trackId, filename, inputPath);
        return exec;
    }

    /**
     * Entry point invoked by {@link com.filetransfer.shared.fabric.FlowFabricConsumer}
     * when a flow.intake message is received from the fabric. Routes to the correct
     * step-execution path (PHYSICAL vs VIRTUAL) based on whether the execution carries
     * a storage key or a local file path — WITHOUT re-publishing to the fabric.
     *
     * This method does the exact same work as the SEDA worker runnable; the only
     * difference is dispatch (fabric consumer thread vs SEDA pool).
     */
    public void executeFlowViaFabric(FlowExecution exec, FileFlow flow) {
        String trackId = exec.getTrackId();
        String filename = exec.getOriginalFilename();
        log.info("[{}] Executing flow '{}' via fabric consumer", trackId, flow.getName());

        // VIRTUAL mode: execution carries a storage key — no local path
        if (exec.getCurrentStorageKey() != null) {
            // Reconstruct a minimal FileRef from execution state.
            // Size is unknown here (-1) — executeFlowRefSteps re-reads from storage as needed.
            UUID accountId = null;
            try {
                if (flow.getSourceAccount() != null) {
                    accountId = flow.getSourceAccount().getId();
                }
            } catch (Exception ignore) {}
            FileRef ref = new FileRef(
                exec.getCurrentStorageKey(),
                "/" + (filename != null ? filename : "unknown"),
                accountId,
                -1L,
                trackId,
                null,
                "STANDARD"
            );
            executeFlowRefSteps(exec, flow, trackId, filename, ref, exec.getCurrentStep());
            return;
        }

        // PHYSICAL mode: execution carries a local file path
        String inputPath = exec.getCurrentFilePath();
        executeFlowSteps(exec, flow, trackId, filename, inputPath);
    }

    /**
     * Execute a SINGLE step via the Kafka pipeline (saga pattern).
     * No for loop — the queue is the iterator. Each step is one message.
     * After this step completes, publishes the NEXT step message to flow.pipeline.
     * On last step, marks the flow COMPLETED.
     *
     * <p>Kafka partitions by trackId — each transfer's steps are ordered within
     * their partition, but different transfers execute in parallel. One partner
     * can't block another.
     */
    public void executeSingleStep(FlowExecution exec, FileFlow flow, int stepIndex,
                                   String inputStorageKey, String trackId) {
        String filename = exec.getOriginalFilename();
        FileFlow.FlowStep step = flow.getSteps().get(stepIndex);
        Map<String, String> stepCfg = step.getConfig() != null ? step.getConfig() : Map.of();

        log.info("[{}] Step {}/{} ({}) — executing via pipeline", trackId, stepIndex + 1,
                flow.getSteps().size(), step.getType());

        try {
            // Build FileRef from storage key
            UUID accountId = null;
            try { if (flow.getSourceAccount() != null) accountId = flow.getSourceAccount().getId(); } catch (Exception ignore) {}
            FileRef ref = new FileRef(inputStorageKey, "/" + (filename != null ? filename : "unknown"),
                    accountId, -1L, trackId, null, "STANDARD");

            // Execute this one step — only metadata in the queue, file streamed on consumption
            long start = System.currentTimeMillis();
            StepOutcome outcome = processStepRef(step, inputStorageKey,
                    "/" + (filename != null ? filename : "unknown"), -1L, ref, trackId, stepIndex);
            long duration = System.currentTimeMillis() - start;

            String outputKey = outcome.storageKey();

            // Checkpoint
            exec.setCurrentStep(stepIndex + 1);
            exec.setCurrentStorageKey(outputKey);
            executionRepository.save(exec);

            // FlowStepEvent for audit snapshot
            eventPublisher.publishEvent(new com.filetransfer.shared.event.FlowStepEvent(
                    trackId, exec.getId(), stepIndex, step.getType(), "OK",
                    inputStorageKey, outputKey, null, outcome.virtualPath(),
                    0L, outcome.sizeBytes(), duration, null));

            log.info("[{}] Step {}/{} ({}) completed in {}ms — output={}",
                    trackId, stepIndex + 1, flow.getSteps().size(), step.getType(),
                    duration, outputKey.substring(0, Math.min(12, outputKey.length())));

            // Publish NEXT step or mark COMPLETED
            if (stepIndex + 1 < flow.getSteps().size()) {
                FileFlow.FlowStep nextStep = flow.getSteps().get(stepIndex + 1);
                String nextStepType = resolveStepTopic(nextStep);
                if (fabricBridge != null) {
                    fabricBridge.publishStep(trackId, stepIndex + 1, nextStepType, outputKey);
                }
            } else {
                // Last step — flow completed
                exec.setStatus(FlowExecution.FlowStatus.COMPLETED);
                exec.setCompletedAt(Instant.now());
                executionRepository.save(exec);
                dispatchFlowEvent("FLOW_COMPLETED", exec, flow);
                log.info("[{}] Flow '{}' COMPLETED via pipeline ({} steps)", trackId, flow.getName(), flow.getSteps().size());
            }

        } catch (Exception e) {
            log.error("[{}] Step {}/{} ({}) FAILED: {}", trackId, stepIndex + 1,
                    flow.getSteps().size(), step.getType(), e.getMessage());
            exec.setStatus(FlowExecution.FlowStatus.FAILED);
            exec.setErrorMessage("Step " + stepIndex + " (" + step.getType() + "): " + e.getMessage());
            exec.setCompletedAt(Instant.now());
            executionRepository.save(exec);
            dispatchFlowEvent("FLOW_FAILED", exec, flow);
        }
    }

    /**
     * Internal: execute all flow steps for a PHYSICAL-mode flow execution.
     * Called synchronously or from SEDA INTAKE stage worker thread.
     */
    private void executeFlowSteps(FlowExecution exec, FileFlow flow, String trackId,
                                   String filename, String inputPath) {
      try {
        String currentFile = inputPath;
        List<FlowExecution.StepResult> results = new ArrayList<>();

        // ── Checkpoint initial input to storage-manager for restart-from-beginning ──
        if (storageClient != null && inputPath != null) {
            try {
                java.nio.file.Path inPath = java.nio.file.Paths.get(inputPath);
                if (java.nio.file.Files.exists(inPath)) {
                    long inSize = java.nio.file.Files.size(inPath);
                    java.util.Map<String, Object> stored = storageClient.storeStream(
                            java.nio.file.Files.newInputStream(inPath), inSize,
                            inPath.getFileName().toString(),
                            exec.getTrackId(), trackId);
                    String initialKey = (String) stored.get("sha256");
                    exec.setInitialStorageKey(initialKey);
                    exec.setCurrentStorageKey(initialKey);
                    log.debug("[{}] Initial input checkpointed to storage: {}", trackId, initialKey);
                }
            } catch (Exception e) {
                log.debug("[{}] Initial checkpoint to storage failed (non-fatal): {}", trackId, e.getMessage());
            }
        }

        for (int i = 0; i < flow.getSteps().size(); i++) {
            FileFlow.FlowStep step = flow.getSteps().get(i);
            Map<String, String> stepCfg = step.getConfig() != null ? step.getConfig() : Map.of();

            // ── APPROVE step: pause for admin sign-off (pass-through in PHYSICAL mode) ──
            if ("APPROVE".equalsIgnoreCase(step.getType())) {
                FlowApproval approval = FlowApproval.builder()
                        .executionId(exec.getId())
                        .trackId(trackId)
                        .flowName(flow.getName())
                        .originalFilename(filename)
                        .stepIndex(i)
                        .requiredApprovers(stepCfg.get("requiredApprovers"))
                        .build();
                approvalRepository.save(approval);
                exec.setStatus(FlowExecution.FlowStatus.PAUSED);
                exec.setCurrentStep(i);
                exec.setStepResults(results);
                executionRepository.save(exec);
                if (flowEventJournal != null) {
                    flowEventJournal.recordExecutionPaused(trackId, exec.getId(), i, "awaiting admin approval");
                }
                log.info("[{}] Flow PAUSED at APPROVE step {} — awaiting admin sign-off", trackId, i);
                return;
            }

            int maxStepRetries = Integer.parseInt(stepCfg.getOrDefault("retryCount", "0"));
            boolean stepSucceeded = false;

            for (int attempt = 0; attempt <= maxStepRetries; attempt++) {
                long start = System.currentTimeMillis();
                // ── Fabric checkpoint start (best-effort, never breaks execution) ──
                UUID fabricCheckpointId = null;
                if (fabricBridge != null && fabricProperties != null
                        && fabricProperties.getCheckpoint().isEnabled()) {
                    try {
                        long inputSize = -1L;
                        try {
                            if (currentFile != null) {
                                java.nio.file.Path p = java.nio.file.Paths.get(currentFile);
                                if (java.nio.file.Files.exists(p)) {
                                    inputSize = java.nio.file.Files.size(p);
                                }
                            }
                        } catch (Exception ignore) {}
                        fabricCheckpointId = fabricBridge.startStep(
                                trackId, i, step.getType(), null,
                                inputSize >= 0 ? inputSize : null);
                    } catch (Exception cpEx) {
                        log.debug("[{}] Fabric checkpoint start failed: {}", trackId, cpEx.getMessage());
                    }
                }
                try {
                    if (attempt > 0) {
                        // Exponential backoff between step retries: 2s, 4s, 8s...
                        long backoff = 2000L * (1L << (attempt - 1));
                        log.info("[{}] Step {}/{} ({}) retry {}/{} — waiting {}ms",
                                trackId, i + 1, flow.getSteps().size(), step.getType(),
                                attempt, maxStepRetries, backoff);
                        Thread.sleep(Math.min(backoff, 30000));
                    }
                    if (flowEventJournal != null) {
                        flowEventJournal.recordStepStarted(trackId, exec.getId(), i, step.getType(), null, attempt + 1);
                    }
                    String outputFile = processStep(step, currentFile, trackId, i);
                    long duration = System.currentTimeMillis() - start;
                    // ── Fabric checkpoint complete ──
                    if (fabricCheckpointId != null) {
                        try {
                            long outSize = -1L;
                            try {
                                if (outputFile != null) {
                                    java.nio.file.Path p = java.nio.file.Paths.get(outputFile);
                                    if (java.nio.file.Files.exists(p)) {
                                        outSize = java.nio.file.Files.size(p);
                                    }
                                }
                            } catch (Exception ignore) {}
                            fabricBridge.completeStep(fabricCheckpointId, null,
                                    outSize >= 0 ? outSize : null);
                        } catch (Exception cpEx) {
                            log.debug("[{}] Fabric checkpoint complete failed: {}", trackId, cpEx.getMessage());
                        }
                    }
                    String stepStatus = attempt > 0 ? "OK_AFTER_RETRY_" + attempt : "OK";
                    results.add(FlowExecution.StepResult.builder()
                            .stepIndex(i).stepType(step.getType())
                            .status(stepStatus)
                            .inputFile(currentFile).outputFile(outputFile)
                            .durationMs(duration).build());
                    if (flowEventJournal != null) {
                        flowEventJournal.recordStepCompleted(trackId, exec.getId(), i, step.getType(), null, 0, duration);
                    }
                    // ── Phase 4.1: Lazy checkpointing — only for critical steps ──
                    // ALWAYS: FORWARD_*, FILE_DELIVERY, APPROVE, last step (delivery points)
                    // ON_FAILURE: ENCRYPT_*, COMPRESS_*, TRANSFORM (re-runnable from input)
                    // NEVER: SCREEN, AUDIT_LOG, WEBHOOK, RENAME (stateless/metadata-only)
                    String checkpointKey = null;
                    long checkpointSize = 0L;
                    boolean isLastStep = (i == flow.getSteps().size() - 1);
                    boolean shouldCheckpoint = isLastStep || shouldAlwaysCheckpoint(step.getType());
                    if (shouldCheckpoint && storageClient != null && outputFile != null) {
                        try {
                            java.nio.file.Path outPath = java.nio.file.Paths.get(outputFile);
                            if (java.nio.file.Files.exists(outPath)) {
                                checkpointSize = java.nio.file.Files.size(outPath);
                                java.util.Map<String, Object> stored = storageClient.storeStream(
                                        java.nio.file.Files.newInputStream(outPath), checkpointSize,
                                        outPath.getFileName().toString(),
                                        exec.getTrackId(), trackId);
                                checkpointKey = (String) stored.get("sha256");
                            }
                        } catch (Exception cpEx) {
                            log.debug("[{}] Step {} checkpoint to storage failed (non-fatal): {}",
                                    trackId, i, cpEx.getMessage());
                        }
                    }

                    // ── FlowStepEvent snapshot with durable storage key ──
                    String snapInKey = exec.getCurrentStorageKey();
                    eventPublisher.publishEvent(new FlowStepEvent(
                            trackId, exec.getId(), i, step.getType(), stepStatus,
                            snapInKey, checkpointKey,
                            currentFile, outputFile,
                            0L, checkpointSize,
                            duration, null));
                    // ── Audit log for step completion ──
                    if (auditService != null) {
                        auditService.logFlowStep(trackId, step.getType(), currentFile, outputFile, true, duration, null);
                    }
                    currentFile = outputFile;
                    exec.setCurrentStep(i + 1);
                    exec.setCurrentFilePath(currentFile);
                    if (checkpointKey != null) {
                        exec.setCurrentStorageKey(checkpointKey);
                    }
                    log.info("[{}] Step {}/{} ({}) completed in {}ms{}",
                            trackId, i + 1, flow.getSteps().size(), step.getType(), duration,
                            attempt > 0 ? " (after " + attempt + " retries)" : "");
                    stepSucceeded = true;
                    break;
                } catch (Exception e) {
                    long duration = System.currentTimeMillis() - start;
                    // ── Fabric checkpoint fail (best-effort) ──
                    if (fabricCheckpointId != null) {
                        try {
                            fabricBridge.failStep(fabricCheckpointId, classifyError(e), e.getMessage());
                        } catch (Exception cpEx) {
                            log.debug("[{}] Fabric checkpoint fail failed: {}", trackId, cpEx.getMessage());
                        }
                    }
                    if (attempt < maxStepRetries && isRetryableStepError(e)) {
                        if (flowEventJournal != null) {
                            long backoff = 2000L * (1L << attempt);
                            flowEventJournal.recordStepRetrying(trackId, exec.getId(), i, step.getType(), attempt + 2, Math.min(backoff, 30000));
                        }
                        log.warn("[{}] Step {}/{} ({}) attempt {} failed (retryable): {}",
                                trackId, i + 1, flow.getSteps().size(), step.getType(), attempt + 1, e.getMessage());
                        continue;
                    }
                    // Final failure — no more retries
                    results.add(FlowExecution.StepResult.builder()
                            .stepIndex(i).stepType(step.getType())
                            .status("FAILED").inputFile(currentFile)
                            .durationMs(duration).error(e.getMessage()).build());
                    // ── FlowStepEvent failure snapshot (PHYSICAL mode) ──
                    eventPublisher.publishEvent(new FlowStepEvent(
                            trackId, exec.getId(), i, step.getType(), "FAILED",
                            currentFile, null,
                            currentFile, null,
                            0L, 0L,
                            duration, e.getMessage()));
                    // ── Audit log for step failure ──
                    if (auditService != null) {
                        auditService.logFlowStep(trackId, step.getType(), currentFile, null, false, duration, e.getMessage());
                    }
                    exec.setStatus(FlowExecution.FlowStatus.FAILED);
                    exec.setErrorMessage("Step " + i + " (" + step.getType() + ") failed"
                            + (attempt > 0 ? " after " + (attempt + 1) + " attempts" : "")
                            + ": " + e.getMessage());
                    exec.setStepResults(results);
                    exec.setCompletedAt(Instant.now());
                    executionRepository.save(exec);
                    if (flowEventJournal != null) {
                        flowEventJournal.recordStepFailed(trackId, exec.getId(), i, step.getType(), e.getMessage(), attempt + 1);
                        flowEventJournal.recordExecutionFailed(trackId, exec.getId(), exec.getErrorMessage());
                    }
                    dispatchFlowEvent("FLOW_FAILED", exec, flow);
                    log.error("[{}] Step {}/{} ({}) FAILED: {}", trackId, i + 1, flow.getSteps().size(),
                            step.getType(), e.getMessage());
                    return;
                }
            }
            // ── Termination check between steps (PHYSICAL mode) ──
            FlowExecution fresh = executionRepository.findById(exec.getId()).orElse(null);
            if (fresh != null && fresh.isTerminationRequested()) {
                exec.setStatus(FlowExecution.FlowStatus.CANCELLED);
                exec.setErrorMessage("Terminated by " + fresh.getTerminatedBy() + " after step " + i);
                exec.setStepResults(results);
                exec.setCompletedAt(Instant.now());
                executionRepository.save(exec);
                log.info("[{}] Flow CANCELLED by {} after step {}", trackId, fresh.getTerminatedBy(), i);
                return;
            }
            if (!stepSucceeded) {
                exec.setStatus(FlowExecution.FlowStatus.FAILED);
                exec.setErrorMessage("Step " + i + " (" + step.getType() + ") exhausted all retries");
                exec.setStepResults(results);
                exec.setCompletedAt(Instant.now());
                executionRepository.save(exec);
                dispatchFlowEvent("FLOW_FAILED", exec, flow);
                return;
            }
        }

        exec.setStatus(FlowExecution.FlowStatus.COMPLETED);
        exec.setStepResults(results);
        exec.setCompletedAt(Instant.now());
        executionRepository.save(exec);
        if (flowEventJournal != null) {
            long totalDuration = exec.getStartedAt() != null
                    ? Instant.now().toEpochMilli() - exec.getStartedAt().toEpochMilli() : 0;
            flowEventJournal.recordExecutionCompleted(trackId, exec.getId(), totalDuration, results.size());
        }
        // ── Audit log for flow completion ──
        if (auditService != null) {
            auditService.logFlowComplete(trackId, flow.getName(), true, null);
        }
        dispatchFlowEvent("FLOW_COMPLETED", exec, flow);
        log.info("[{}] Flow '{}' completed successfully for '{}'", trackId, flow.getName(), filename);
      } catch (Exception e) {
        // Outer safety net: persist FAILED status so executions never get stuck in PROCESSING
        log.error("[{}] Unhandled exception in flow '{}': {}", trackId, flow.getName(), e.getMessage(), e);
        try {
            exec.setStatus(FlowExecution.FlowStatus.FAILED);
            exec.setErrorMessage("Unhandled error: " + e.getMessage());
            exec.setCompletedAt(Instant.now());
            executionRepository.save(exec);
            dispatchFlowEvent("FLOW_FAILED", exec, flow);
        } catch (Exception inner) {
            log.error("[{}] Failed to persist FAILED status: {}", trackId, inner.getMessage());
        }
      }
    }

    /**
     * Fire FLOW_COMPLETED or FLOW_FAILED to both the admin ConnectorDispatcher
     * and the partner-level PartnerWebhookDispatcher. Both are optional — null-safe.
     */
    private void dispatchFlowEvent(String eventType, FlowExecution exec, FileFlow flow) {
        String flowName = (flow != null && flow.getName() != null) ? flow.getName() : "unknown";
        if (connectorDispatcher != null) {
            try {
                connectorDispatcher.dispatch(ConnectorDispatcher.MftEvent.builder()
                        .eventType(eventType)
                        .severity("FLOW_FAILED".equals(eventType) ? "HIGH" : "INFO")
                        .trackId(exec.getTrackId())
                        .filename(exec.getOriginalFilename())
                        .summary(("FLOW_FAILED".equals(eventType) ? "Flow failed: " : "Flow completed: ") + flowName)
                        .details(exec.getErrorMessage() != null ? exec.getErrorMessage() : flowName)
                        .service("flow-engine")
                        .build());
            } catch (Exception e) {
                log.debug("[{}] ConnectorDispatcher skipped: {}", exec.getTrackId(), e.getMessage());
            }
        }
        if (partnerWebhookDispatcher != null) {
            try {
                partnerWebhookDispatcher.dispatch(eventType, exec, flowName);
            } catch (Exception e) {
                log.debug("[{}] PartnerWebhookDispatcher skipped: {}", exec.getTrackId(), e.getMessage());
            }
        }
        // Publish lifecycle event to RabbitMQ for event-driven consumers
        publishLifecycleEvent(eventType, exec.getTrackId(), Map.of(
                "flowName", flowName,
                "filename", exec.getOriginalFilename() != null ? exec.getOriginalFilename() : "",
                "status", exec.getStatus() != null ? exec.getStatus().name() : "",
                "error", exec.getErrorMessage() != null ? exec.getErrorMessage() : ""
        ));
    }

    /**
     * Publish a transfer lifecycle event to the RabbitMQ event bus.
     * Uses the shared exchange (file-transfer.events) with routing key transfer.{eventType}.
     * No-op when RabbitTemplate is absent (e.g. in unit tests or non-AMQP deployments).
     */
    private void publishLifecycleEvent(String eventType, String trackId, Map<String, Object> data) {
        if (rabbitTemplate == null) return;
        try {
            Map<String, Object> event = new LinkedHashMap<>(data);
            event.put("eventType", eventType);
            event.put("trackId", trackId);
            event.put("timestamp", Instant.now().toString());
            rabbitTemplate.convertAndSend("file-transfer.events",
                    "transfer." + eventType.toLowerCase(), event);
        } catch (Exception e) {
            log.debug("Failed to publish lifecycle event {}: {}", eventType, e.getMessage());
        }
    }

    private String processStep(FileFlow.FlowStep step, String inputPath, String trackId, int stepIndex) throws Exception {
        if (step.getType() == null || step.getType().isBlank()) {
            throw new IllegalArgumentException("Flow step " + stepIndex + " has no type defined");
        }
        Path input = Paths.get(inputPath);
        Path workDir = input.getParent().resolve(".flow-work");
        Files.createDirectories(workDir);
        Map<String, String> cfg = step.getConfig() != null ? step.getConfig() : Map.of();

        return switch (step.getType().toUpperCase()) {
            case "COMPRESS_GZIP" -> compressGzip(input, workDir);
            case "DECOMPRESS_GZIP" -> decompressGzip(input, workDir);
            case "COMPRESS_ZIP" -> compressZip(input, workDir);
            case "DECOMPRESS_ZIP" -> decompressZip(input, workDir);
            case "ENCRYPT_PGP" -> callEncryptionService(input, workDir, "pgp", "encrypt", cfg);
            case "DECRYPT_PGP" -> callEncryptionService(input, workDir, "pgp", "decrypt", cfg);
            case "ENCRYPT_AES" -> callEncryptionService(input, workDir, "aes", "encrypt", cfg);
            case "DECRYPT_AES" -> callEncryptionService(input, workDir, "aes", "decrypt", cfg);
            case "RENAME" -> renameFile(input, workDir, cfg, trackId);
            case "SCREEN" -> callScreeningService(input, trackId, cfg);
            case "CHECKSUM_VERIFY" -> verifyChecksum(input, cfg, trackId);
            case "EXECUTE_SCRIPT" -> executeScript(input, workDir, cfg, trackId);
            case "MAILBOX" -> executeMailbox(input, cfg, trackId);
            case "FILE_DELIVERY" -> executeFileDelivery(input, cfg, trackId);
            case "CONVERT_EDI" -> callEdiConverter(input, workDir, cfg, trackId);
            case "ROUTE"   -> inputPath; // Route is handled by RoutingEngine after flow completes
            case "APPROVE" -> inputPath; // Handled above loop; pass-through if reached
            default -> {
                // Try FlowFunctionRegistry for custom/plugin functions
                if (functionRegistry != null) {
                    Optional<FlowFunction> fn = functionRegistry.get(step.getType());
                    if (fn.isPresent()) {
                        FlowFunctionContext ctx = new FlowFunctionContext(
                                input, workDir, cfg, trackId,
                                input.getFileName().toString());
                        log.info("[{}] Executing plugin function: {} (step {})",
                                trackId, step.getType(), stepIndex);
                        yield fn.get().executePhysical(ctx);
                    }
                }
                throw new IllegalArgumentException("Unknown step type: " + step.getType());
            }
        };
    }

    // --- Step implementations ---

    private String compressGzip(Path input, Path workDir) throws IOException {
        Path output = workDir.resolve(input.getFileName() + ".gz");
        try (InputStream in = Files.newInputStream(input);
             GZIPOutputStream gzOut = new GZIPOutputStream(Files.newOutputStream(output))) {
            in.transferTo(gzOut);
        }
        return output.toString();
    }

    private String decompressGzip(Path input, Path workDir) throws IOException {
        String name = input.getFileName().toString();
        if (name.endsWith(".gz")) name = name.substring(0, name.length() - 3);
        Path output = workDir.resolve(name);
        try (GZIPInputStream gzIn = new GZIPInputStream(Files.newInputStream(input));
             OutputStream out = Files.newOutputStream(output)) {
            gzIn.transferTo(out);
        }
        return output.toString();
    }

    private String compressZip(Path input, Path workDir) throws IOException {
        Path output = workDir.resolve(input.getFileName() + ".zip");
        try (ZipOutputStream zipOut = new ZipOutputStream(Files.newOutputStream(output))) {
            zipOut.putNextEntry(new ZipEntry(input.getFileName().toString()));
            Files.copy(input, zipOut);
            zipOut.closeEntry();
        }
        return output.toString();
    }

    private static final long MAX_ZIP_ENTRIES = 1000;
    private static final long MAX_ZIP_TOTAL_SIZE = 512L * 1024 * 1024; // 512MB zip bomb guard

    private String decompressZip(Path input, Path workDir) throws IOException {
        String lastEntry = null;
        Path normalizedWorkDir = workDir.toAbsolutePath().normalize();
        long entryCount = 0;
        long totalBytes = 0;

        try (ZipInputStream zipIn = new ZipInputStream(Files.newInputStream(input))) {
            ZipEntry entry;
            while ((entry = zipIn.getNextEntry()) != null) {
                if (++entryCount > MAX_ZIP_ENTRIES) {
                    throw new IOException("ZIP archive exceeds maximum entry count (" + MAX_ZIP_ENTRIES + ")");
                }
                if (!entry.isDirectory()) {
                    // ZipSlip protection: ensure resolved path stays within workDir
                    Path out = normalizedWorkDir.resolve(entry.getName()).normalize();
                    if (!out.startsWith(normalizedWorkDir)) {
                        throw new IOException("ZIP entry escapes target directory: " + entry.getName());
                    }
                    Files.createDirectories(out.getParent());
                    try (OutputStream fout = Files.newOutputStream(out)) {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = zipIn.read(buf)) != -1) {
                            totalBytes += n;
                            if (totalBytes > MAX_ZIP_TOTAL_SIZE) {
                                throw new IOException("ZIP archive exceeds maximum uncompressed size ("
                                        + MAX_ZIP_TOTAL_SIZE / (1024 * 1024) + "MB)");
                            }
                            fout.write(buf, 0, n);
                        }
                    }
                    lastEntry = out.toString();
                }
                zipIn.closeEntry();
            }
        }
        if (lastEntry == null) throw new IOException("ZIP archive was empty");
        return lastEntry;
    }

    private String callEncryptionService(Path input, Path workDir, String algo, String operation,
                                          Map<String, String> cfg) throws Exception {
        String encryptionUrl = serviceProps.getEncryptionService().getUrl();

        // Support both keyId (UUID) and keyAlias (human-readable string from keystore-manager)
        String keyId = cfg.get("keyId");
        String keyAlias = cfg.get("keyAlias");
        String name = input.getFileName().toString();
        if (operation.equals("decrypt") && name.endsWith(".enc")) {
            name = name.substring(0, name.length() - 4);
        } else if (operation.equals("encrypt")) {
            name = name + ".enc";
        }
        Path output = workDir.resolve(name);

        if ((keyId == null || keyId.isBlank()) && (keyAlias == null || keyAlias.isBlank())) {
            // No key configured — pass through (allows flows without encryption keys)
            log.warn("No keyId/keyAlias configured for {} step — passing through unchanged", operation);
            Files.copy(input, output, StandardCopyOption.REPLACE_EXISTING);
            return output.toString();
        }

        // Use multipart upload to encryption-service — file streams from disk, no Base64 heap load.
        // EncryptionServiceClient.encryptFile/decryptFile uses FileSystemResource (streaming).
        log.info("Calling encryption-service: {} {} (keyId={}) [multipart]", operation.toUpperCase(), algo, keyId);
        try {
            UUID keyUuid = UUID.fromString(keyId);
            byte[] resultBytes;
            if (encryptionClient != null) {
                resultBytes = "encrypt".equals(operation)
                        ? encryptionClient.encryptFile(keyUuid, input)
                        : encryptionClient.decryptFile(keyUuid, input);
            } else {
                // Fallback: raw REST call with multipart (same result, no resilience wrapper)
                var headers = new org.springframework.http.HttpHeaders();
                headers.setContentType(org.springframework.http.MediaType.MULTIPART_FORM_DATA);
                var body = new org.springframework.util.LinkedMultiValueMap<String, Object>();
                body.add("file", new org.springframework.core.io.FileSystemResource(input.toFile()));
                var entity = new org.springframework.http.HttpEntity<>(body, headers);
                var resp = restTemplate.postForEntity(
                        encryptionUrl + "/api/encrypt/" + operation + "?keyId=" + keyId,
                        entity, byte[].class);
                resultBytes = resp.getBody();
            }
            Files.write(output, resultBytes);
            log.info("{}({}) complete: {} -> {} ({} bytes)", operation.toUpperCase(), algo,
                    input.getFileName(), output.getFileName(), resultBytes.length);
        } catch (Exception e) {
            // SECURITY: Never pass through unencrypted — encryption was explicitly configured.
            throw new RuntimeException("Encryption step failed (keyId=" + keyId + "): " + e.getMessage(), e);
        }
        return output.toString();
    }

    private String renameFile(Path input, Path workDir, Map<String, String> cfg, String trackId) throws IOException {
        String pattern = cfg.getOrDefault("pattern", "${filename}");
        String originalName = input.getFileName().toString();
        String baseName = originalName.contains(".") ?
                originalName.substring(0, originalName.lastIndexOf('.')) : originalName;
        String ext = originalName.contains(".") ?
                originalName.substring(originalName.lastIndexOf('.')) : "";

        String newName = pattern
                .replace("${filename}", originalName)
                .replace("${basename}", baseName)
                .replace("${ext}", ext)
                .replace("${trackid}", trackId)
                .replace("${timestamp}", String.valueOf(System.currentTimeMillis()));

        Path output = workDir.resolve(newName);
        Files.copy(input, output, StandardCopyOption.REPLACE_EXISTING);
        return output.toString();
    }

    private static final Pattern SAFE_SHELL_ARG = Pattern.compile("^[a-zA-Z0-9._/\\-]+$");

    /** Shell operators that indicate command chaining / injection in templates. */
    private static final Pattern TEMPLATE_INJECTION =
            Pattern.compile("[;|&`!<>]|\\$\\(|\\.\\.|\\.\\\\");

    /** Shell-escape a value to prevent command injection. */
    private static String shellEscape(String value) {
        if (SAFE_SHELL_ARG.matcher(value).matches()) return value;
        // Single-quote the value, escaping any embedded single quotes
        return "'" + value.replace("'", "'\\''") + "'";
    }

    /**
     * Execute a shell script as a flow step. The script receives the input file path
     * as an argument. If it exits non-zero, the flow fails.
     * Config: {"command": "python3 /scripts/validate.py ${file}", "timeoutSeconds": "60"}
     */
    private String executeScript(Path input, Path workDir, Map<String, String> cfg, String trackId) throws Exception {
        String cmdTemplate = cfg.getOrDefault("command", "echo ${file}");
        int timeout = Integer.parseInt(cfg.getOrDefault("timeoutSeconds", "300"));

        // Validate the command template itself — strip known placeholders, then check remainder
        String templateCheck = cmdTemplate
                .replace("${file}", "").replace("${trackid}", "").replace("${workdir}", "");
        if (TEMPLATE_INJECTION.matcher(templateCheck).find()) {
            throw new SecurityException("[" + trackId + "] Script template contains disallowed shell operators");
        }

        // Validate the script path (first token) is a safe path
        String scriptPath = cmdTemplate.split("\\s+")[0];
        if (!SAFE_SHELL_ARG.matcher(scriptPath).matches()) {
            throw new SecurityException("[" + trackId + "] Script path contains disallowed characters: " + scriptPath);
        }

        // Shell-escape all interpolated values to prevent command injection
        String cmd = cmdTemplate
                .replace("${file}", shellEscape(input.toAbsolutePath().toString()))
                .replace("${trackid}", shellEscape(trackId))
                .replace("${workdir}", shellEscape(workDir.toAbsolutePath().toString()));

        log.info("[{}] Executing script: {}", trackId, cmd);
        ProcessBuilder pb = new ProcessBuilder("sh", "-c", cmd);
        pb.redirectErrorStream(true);
        pb.directory(workDir.toFile());
        Process proc = pb.start();

        String output;
        try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(proc.getInputStream()))) {
            output = reader.lines().collect(java.util.stream.Collectors.joining("\n"));
        }

        boolean finished = proc.waitFor(timeout, java.util.concurrent.TimeUnit.SECONDS);
        if (!finished) { proc.destroyForcibly(); throw new RuntimeException("Script timed out after " + timeout + "s"); }
        if (proc.exitValue() != 0) throw new RuntimeException("Script exit code " + proc.exitValue() + ": " + output);

        log.info("[{}] Script completed (exit 0)", trackId);
        // If script produced an output file, use it; otherwise pass through
        String outputFile = cfg.get("outputFile");
        if (outputFile != null) {
            Path out = Paths.get(outputFile.replace("${workdir}", workDir.toAbsolutePath().toString()));
            if (Files.exists(out)) return out.toString();
        }
        return input.toString();
    }

    /**
     * Call the screening-service to scan the file against OFAC/AML sanctions lists.
     * If a HIT is found, throws an exception to BLOCK the transfer.
     */
    private String callScreeningService(Path input, String trackId, Map<String, String> cfg) throws Exception {
        String screeningUrl = serviceProps.getScreeningService().getUrl();

        log.info("[{}] Screening file against sanctions lists...", trackId);

        try {
            // Call screening service with multipart file upload
            org.springframework.core.io.FileSystemResource fileResource =
                    new org.springframework.core.io.FileSystemResource(input.toFile());
            org.springframework.util.LinkedMultiValueMap<String, Object> body = new org.springframework.util.LinkedMultiValueMap<>();
            body.add("file", fileResource);

            org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
            headers.setContentType(org.springframework.http.MediaType.MULTIPART_FORM_DATA);
            addInternalAuth(headers, "screening-service");

            String url = screeningUrl + "/api/v1/screening/scan?trackId=" + trackId;
            if (cfg.containsKey("columns")) url += "&columns=" + cfg.get("columns");

            org.springframework.http.HttpEntity<org.springframework.util.MultiValueMap<String, Object>> request =
                    new org.springframework.http.HttpEntity<>(body, headers);
            org.springframework.http.ResponseEntity<java.util.Map> response =
                    restTemplate.postForEntity(url, request, java.util.Map.class);

            if (response.getBody() != null) {
                String outcome = (String) response.getBody().get("outcome");
                String action = (String) response.getBody().get("actionTaken");
                int hitsFound = response.getBody().get("hitsFound") instanceof Number n ? n.intValue() : 0;

                log.info("[{}] Screening result: {} ({} hits, action={})", trackId, outcome, hitsFound, action);

                if ("BLOCKED".equals(action)) {
                    throw new SecurityException("SANCTIONS HIT: File blocked by screening service. "
                            + hitsFound + " match(es) found against OFAC/AML sanctions lists.");
                }
            }
        } catch (SecurityException se) {
            throw se; // re-throw blocking exceptions
        } catch (Exception e) {
            // Screening service unreachable — configurable behavior
            String onFailure = cfg.getOrDefault("onFailure", "PASS");
            if ("BLOCK".equals(onFailure)) {
                throw new Exception("Screening service unreachable — blocking as configured. " + e.getMessage());
            }
            log.warn("[{}] Screening service unreachable: {} — allowing transfer (graceful degradation)", trackId, e.getMessage());
            // ── Audit: screening bypass under graceful degradation ──
            if (auditService != null) {
                auditService.logAction("system", "SCREENING_BYPASSED", true, null,
                        Map.of("trackId", trackId, "reason", "Screening service unreachable — file allowed under graceful degradation"));
            }
        }

        return input.toString(); // File passes through unchanged
    }

    /**
     * CHECKSUM_VERIFY step — computes SHA-256 of the file and compares against an expected value.
     * If expectedChecksum is in config, fails on mismatch. Otherwise just logs the checksum.
     * Config: {"algorithm": "SHA-256"} or {"expectedChecksum": "abc123..."}
     */
    private String verifyChecksum(Path input, Map<String, String> cfg, String trackId) throws Exception {
        String algorithm = cfg.getOrDefault("algorithm", "SHA-256");
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance(algorithm);

        try (var is = Files.newInputStream(input)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) != -1) digest.update(buf, 0, n);
        }

        String checksum = java.util.HexFormat.of().formatHex(digest.digest());
        log.info("[{}] CHECKSUM_VERIFY: {} = {}", trackId, algorithm, checksum);

        String expected = cfg.get("expectedChecksum");
        if (expected != null && !expected.isBlank()) {
            if (!expected.equalsIgnoreCase(checksum)) {
                throw new SecurityException(String.format(
                        "CHECKSUM MISMATCH: expected %s but computed %s (algorithm=%s)",
                        expected, checksum, algorithm));
            }
            log.info("[{}] CHECKSUM_VERIFY: match confirmed", trackId);
        }

        // Log to audit trail
        if (auditService != null) {
            auditService.logAction("flow-engine", "CHECKSUM_VERIFY", true, trackId,
                    Map.of("algorithm", algorithm, "checksum", checksum,
                            "verified", expected != null && !expected.isBlank()));
        }

        return input.toString();
    }

    /**
     * MAILBOX step — delivers the file to a destination user's outbox within the platform.
     * Config: {"destinationUsername": "bob", "protocol": "SFTP"}
     * The file is always placed in the destination user's outbox folder.
     */
    private String executeMailbox(Path input, Map<String, String> cfg, String trackId) throws Exception {
        String destUsername = cfg.get("destinationUsername");
        if (destUsername == null || destUsername.isBlank()) {
            throw new IllegalArgumentException("MAILBOX step requires 'destinationUsername' in config");
        }

        String protocolStr = cfg.getOrDefault("protocol", "SFTP");
        Protocol protocol;
        try {
            protocol = Protocol.valueOf(protocolStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            protocol = Protocol.SFTP;
        }
        final Protocol resolvedProtocol = protocol;

        TransferAccount destAccount = accountRepository
                .findByUsernameAndProtocolAndActiveTrue(destUsername, resolvedProtocol)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Destination account not found: " + destUsername + " (" + resolvedProtocol + ")"));

        // Build outbox path: {homeDir}/outbox/{filename}
        String homeDir = destAccount.getHomeDir();
        String outboxDir = homeDir + "/outbox";
        String filename = input.getFileName().toString();
        Path outboxPath = Paths.get(outboxDir, filename);

        // Determine which service hosts this account
        ServiceType serviceType = protocolToServiceType(protocol);
        Optional<ServiceRegistration> destService = clusterService.discoverService(serviceType);

        if (destService.isEmpty() || clusterService.isLocalService(destService.get())) {
            // Local delivery — copy file directly to outbox
            Files.createDirectories(outboxPath.getParent());
            Files.copy(input, outboxPath, StandardCopyOption.REPLACE_EXISTING);
            log.info("[{}] MAILBOX: delivered locally to {} outbox: {}", trackId, destUsername, outboxPath);
            // Audit the mailbox delivery leg
            if (auditService != null) {
                try {
                    auditService.logFileRoute(trackId, input.toString(), outboxPath.toString(), outboxPath);
                } catch (Exception e) {
                    log.warn("[{}] Failed to create MAILBOX delivery audit record: {}", trackId, e.getMessage());
                }
            }
        } else {
            // Remote delivery — multipart POST to the destination service (streaming, no heap copy)
            ServiceRegistration svc = destService.get();
            org.springframework.core.io.FileSystemResource fileResource =
                    new org.springframework.core.io.FileSystemResource(input.toFile());

            String url = "http://" + svc.getHost() + ":" + svc.getControlPort()
                    + "/internal/files/receive";

            org.springframework.util.LinkedMultiValueMap<String, Object> body = new org.springframework.util.LinkedMultiValueMap<>();
            body.add("file", fileResource);
            body.add("destinationUsername", destUsername);
            body.add("destinationAbsolutePath", outboxPath.toString());
            body.add("originalFilename", filename);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);
            addInternalAuth(headers, svc.getHost());
            HttpEntity<org.springframework.util.MultiValueMap<String, Object>> entity = new HttpEntity<>(body, headers);

            restTemplate.postForEntity(url, entity, Void.class);
            log.info("[{}] MAILBOX: forwarded to {}:{} for user {}", trackId,
                    svc.getHost(), svc.getControlPort(), destUsername);
            // Audit the remote mailbox delivery leg
            if (auditService != null) {
                try {
                    auditService.logFileRoute(trackId, input.toString(), outboxPath.toString(), input);
                } catch (Exception e) {
                    log.warn("[{}] Failed to create MAILBOX delivery audit record: {}", trackId, e.getMessage());
                }
            }
        }

        return input.toString(); // Pass through — file delivered as side effect
    }

    /**
     * FILE_DELIVERY step — delivers the file to one or many external delivery endpoints.
     * Config: {"deliveryEndpointIds": "uuid1,uuid2,uuid3"}
     * Each endpoint is called via the external-forwarder-service.
     */
    private String executeFileDelivery(Path input, Map<String, String> cfg, String trackId) throws Exception {
        String endpointIdsStr = cfg.get("deliveryEndpointIds");
        if (endpointIdsStr == null || endpointIdsStr.isBlank()) {
            throw new IllegalArgumentException("FILE_DELIVERY step requires 'deliveryEndpointIds' in config");
        }

        List<UUID> endpointIds = Arrays.stream(endpointIdsStr.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(UUID::fromString)
                .toList();

        List<DeliveryEndpoint> endpoints = deliveryEndpointRepository.findByIdInAndActiveTrue(endpointIds);

        if (endpoints.isEmpty()) {
            throw new IllegalArgumentException("No active delivery endpoints found for IDs: " + endpointIdsStr);
        }

        String forwarderUrl = serviceProps.getForwarderService().getUrl();
        String filename = input.getFileName().toString();

        // Stream file as multipart instead of Base64 in heap — prevents OOM on large files
        org.springframework.core.io.FileSystemResource fileResource =
                new org.springframework.core.io.FileSystemResource(input.toFile());

        int successCount = 0;
        List<String> failures = new ArrayList<>();

        for (DeliveryEndpoint ep : endpoints) {
            try {
                String url = forwarderUrl + "/api/forward/deliver/" + ep.getId() + "/file"
                        + "?filename=" + java.net.URLEncoder.encode(filename, "UTF-8")
                        + "&trackId=" + java.net.URLEncoder.encode(trackId != null ? trackId : "", "UTF-8");

                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.MULTIPART_FORM_DATA);
                addInternalAuth(headers, "external-forwarder-service");
                org.springframework.util.LinkedMultiValueMap<String, Object> body = new org.springframework.util.LinkedMultiValueMap<>();
                body.add("file", fileResource);
                HttpEntity<org.springframework.util.MultiValueMap<String, Object>> entity = new HttpEntity<>(body, headers);

                restTemplate.postForEntity(url, entity, Map.class);
                successCount++;
                log.info("[{}] FILE_DELIVERY: delivered to '{}' ({}://{}:{}) OK",
                        trackId, ep.getName(), ep.getProtocol(), ep.getHost(), ep.getPort());
            } catch (Exception e) {
                String msg = ep.getName() + ": " + e.getMessage();
                failures.add(msg);
                log.error("[{}] FILE_DELIVERY: failed for '{}': {}", trackId, ep.getName(), e.getMessage());
            }
        }

        if (successCount == 0) {
            throw new RuntimeException("FILE_DELIVERY failed for all " + endpoints.size()
                    + " endpoints: " + String.join("; ", failures));
        }

        if (!failures.isEmpty()) {
            log.warn("[{}] FILE_DELIVERY: {}/{} endpoints succeeded, {} failed: {}",
                    trackId, successCount, endpoints.size(), failures.size(), failures);
        }

        log.info("[{}] FILE_DELIVERY: completed — {}/{} endpoints delivered", trackId, successCount, endpoints.size());

        // Return detailed result so StepResult captures partial failure info
        if (!failures.isEmpty()) {
            return String.format("SUCCESS:%d,FAILED:%d|%s",
                    successCount, failures.size(), String.join(";", failures));
        }
        return input.toString(); // Pass through — delivery is a side effect
    }

    /**
     * Determine if a step failure is transient and worth retrying.
     * Network errors, timeouts, and temporary service unavailability are retryable.
     * Auth errors, format errors, and security blocks are not.
     */
    private boolean isRetryableStepError(Exception e) {
        String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
        // Not retryable: auth, format, security, sanctions
        if (msg.contains("permission denied") || msg.contains("auth") || msg.contains("401") || msg.contains("403")) return false;
        if (msg.contains("sanctions") || msg.contains("blocked") || msg.contains("ofac")) return false;
        if (msg.contains("schema") || msg.contains("format error") || msg.contains("malformed")) return false;
        if (msg.contains("key expired") || msg.contains("key not found")) return false;
        if (e instanceof IllegalArgumentException) return false;
        // Retryable: network, timeout, temporary errors
        return true;
    }

    /**
     * Classify an exception for fabric checkpoint error tracking.
     * Maps exception type / message keywords to a short category token.
     */
    private String classifyError(Throwable e) {
        if (e == null) return "UNKNOWN";
        String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
        if (msg.contains("auth") || msg.contains("credential")) return "AUTH";
        if (msg.contains("key") || msg.contains("expired")) return "KEY_EXPIRED";
        if (msg.contains("network") || msg.contains("connection") || msg.contains("timeout")) return "NETWORK";
        if (msg.contains("permission") || msg.contains("denied")) return "PERMISSION";
        if (msg.contains("format") || msg.contains("parse")) return "FORMAT";
        return "UNKNOWN";
    }

    private void addInternalAuth(HttpHeaders headers, String targetService) {
        if (spiffeWorkloadClient != null && spiffeWorkloadClient.isAvailable()) {
            String token = spiffeWorkloadClient.getJwtSvidFor(targetService);
            if (token != null) headers.setBearerAuth(token);
        }
    }

    private ServiceType protocolToServiceType(Protocol protocol) {
        return switch (protocol) {
            case SFTP -> ServiceType.SFTP;
            case FTP -> ServiceType.FTP;
            case FTP_WEB -> ServiceType.FTP_WEB;
            case HTTPS -> ServiceType.FTP_WEB;
            case AS2, AS4 -> ServiceType.SFTP; // AS2/AS4 route through platform storage
        };
    }

    /**
     * CONVERT_EDI step — calls the EDI Converter service to convert the file using map-based conversion.
     * Config: {"targetType": "PURCHASE_ORDER_INH", "targetFormat": "JSON|XML|CSV", "partnerId": "optional"}
     *
     * <p>If {@code targetType} is set, uses the new map-based endpoint ({@code /api/v1/convert/convert/map})
     * which selects the best conversion map for the source→target document type pair.
     * Falls back to the legacy trained endpoint when only {@code targetFormat} is provided.
     */
    private String callEdiConverter(Path input, Path workDir, Map<String, String> cfg, String trackId) throws Exception {
        String converterUrl = serviceProps.getEdiConverter().getUrl();
        String targetType   = cfg.get("targetType");
        String targetFormat = cfg.getOrDefault("targetFormat", "JSON");
        String partnerId    = cfg.get("partnerId");

        String content = Files.readString(input);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("content", content);

        String endpoint;
        if (targetType != null && !targetType.isBlank()) {
            // Map-based conversion — document-type-aware
            body.put("targetType", targetType);
            if (partnerId != null && !partnerId.isBlank()) body.put("partnerId", partnerId);
            endpoint = converterUrl + "/api/v1/convert/convert/map";
        } else {
            // Legacy format-based conversion
            body.put("targetFormat", targetFormat);
            if (partnerId != null && !partnerId.isBlank()) body.put("partnerId", partnerId);
            endpoint = converterUrl + "/api/v1/convert/trained";
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        addInternalAuth(headers, "edi-converter");

        @SuppressWarnings("unchecked")
        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                endpoint, HttpMethod.POST, new HttpEntity<>(body, headers),
                (Class<Map<String, Object>>) (Class<?>) Map.class);

        Map<String, Object> respBody = response.getBody();
        String output = respBody != null ? (String) respBody.get("output") : "";
        String mapUsed = respBody != null ? (String) respBody.get("mapUsed") : null;
        Object confidence = respBody != null ? respBody.get("confidence") : null;

        String ext = switch (targetFormat.toUpperCase()) {
            case "JSON" -> ".json";
            case "XML" -> ".xml";
            case "CSV" -> ".csv";
            case "YAML" -> ".yaml";
            default -> ".txt";
        };

        String baseName = input.getFileName().toString();
        if (baseName.contains(".")) baseName = baseName.substring(0, baseName.lastIndexOf('.'));
        Path outputFile = workDir.resolve(baseName + ext);
        Files.writeString(outputFile, output);

        log.info("[{}] CONVERT_EDI: {} -> {} (targetType={}, targetFormat={}, partnerId={}, mapUsed={}, confidence={})",
                trackId, input.getFileName(), outputFile.getFileName(), targetType, targetFormat, partnerId, mapUsed, confidence);
        return outputFile.toString();
    }

    // ══════════════════════════════════════════════════════════════════════════════════
    // VIRTUAL-mode pipeline — ephemeral streaming agent, zero local disk I/O
    // ══════════════════════════════════════════════════════════════════════════════════

    /**
     * Execute a flow for a VIRTUAL-mode account.
     *
     * <p>Each transform step reads from / writes to storage-manager as a streaming
     * operation. No local disk is touched. A single {@link FlowExecution} record is
     * written at the start (status=PROCESSING) and updated once at the end
     * (COMPLETED or FAILED) — minimising DB round-trips per step.
     */
    @Transactional
    public FlowExecution executeFlowRef(FileFlow flow, String trackId, String filename,
                                         FileRef ref,
                                         com.filetransfer.shared.matching.MatchCriteria matchedCriteria) {
        return executeFlowRef(flow, trackId, filename, ref, matchedCriteria, null, 0);
    }

    /**
     * Execute a flow from a specific step (used by restart-from-step).
     * When SEDA stages are available, step execution is submitted to the INTAKE
     * stage and this method returns immediately with status PROCESSING.
     *
     * @param existingExec if non-null, update this record rather than creating a new one
     * @param startFromStep 0 = full run; N = skip first N steps, start at step N
     */
    @Transactional
    public FlowExecution executeFlowRef(FileFlow flow, String trackId, String filename,
                                         FileRef ref,
                                         com.filetransfer.shared.matching.MatchCriteria matchedCriteria,
                                         FlowExecution existingExec,
                                         int startFromStep) {
        FlowExecution exec;
        if (existingExec != null) {
            exec = existingExec;
            exec.setStatus(FlowExecution.FlowStatus.PROCESSING);
            exec.setCurrentStorageKey(ref.storageKey());
            exec.setCurrentStep(startFromStep);
            exec.setErrorMessage(null);
            exec.setCompletedAt(null);
            exec.setStepResults(new ArrayList<>());
            exec.setTerminationRequested(false);
        } else {
            exec = FlowExecution.builder()
                    .trackId(trackId).flow(flow).originalFilename(filename)
                    .currentStorageKey(ref.storageKey())
                    .initialStorageKey(ref.storageKey())
                    .status(FlowExecution.FlowStatus.PROCESSING)
                    .matchedCriteria(matchedCriteria).stepResults(new ArrayList<>())
                    .build();
        }
        exec = executionRepository.save(exec);

        // ── Per-function step pipeline (VIRTUAL mode) ──
        if (fabricBridge != null && fabricBridge.isFabricActive() && !flow.getSteps().isEmpty()) {
            try {
                FileFlow.FlowStep firstStep = flow.getSteps().get(startFromStep);
                fabricBridge.publishStep(trackId, startFromStep, firstStep.getType(), ref.storageKey());
                log.info("[{}] Flow '{}' (VIRTUAL) → step {} ({}) published to per-function pipeline",
                        trackId, flow.getName(), startFromStep, firstStep.getType());
                return exec;
            } catch (Exception e) {
                log.warn("[{}] Pipeline publish failed (VIRTUAL), falling back to SEDA: {}", trackId, e.getMessage());
            }
        }

        if (stageManager != null) {
            final FlowExecution sedaExec = exec;
            boolean submitted = stageManager.submit("INTAKE", () -> executeFlowRefSteps(sedaExec, flow, trackId, filename, ref, startFromStep));
            if (!submitted) {
                log.error("[{}] SEDA INTAKE queue full — executing synchronously (VIRTUAL)", trackId);
                executeFlowRefSteps(exec, flow, trackId, filename, ref, startFromStep);
            } else {
                log.info("[{}] Flow '{}' submitted to SEDA INTAKE stage (VIRTUAL)", trackId, flow.getName());
            }
            return exec;
        }

        // ── Synchronous fallback (no SEDA) ──
        executeFlowRefSteps(exec, flow, trackId, filename, ref, startFromStep);
        return exec;
    }

    /**
     * Internal: execute all flow steps for a VIRTUAL-mode flow execution.
     * Called synchronously or from SEDA INTAKE stage worker thread.
     */
    private void executeFlowRefSteps(FlowExecution exec, FileFlow flow, String trackId,
                                      String filename, FileRef ref, int startFromStep) {
      try {
        String currentKey  = ref.storageKey();
        String currentPath = ref.virtualPath();
        long   currentSize = ref.sizeBytes();
        List<FlowExecution.StepResult> results = new ArrayList<>();

        // ── Journal: record execution start (VIRTUAL) ──
        if (flowEventJournal != null && startFromStep == 0) {
            flowEventJournal.recordExecutionStarted(trackId, exec.getId(), currentKey, flow.getSteps().size());
        }

        for (int i = startFromStep; i < flow.getSteps().size(); i++) {
            FileFlow.FlowStep step = flow.getSteps().get(i);
            Map<String, String> stepCfg = step.getConfig() != null ? step.getConfig() : Map.of();

            // ── APPROVE step: record pass-through snapshot, pause for admin sign-off ──
            if ("APPROVE".equalsIgnoreCase(step.getType())) {
                // Snapshot with identical in/out keys — so resume can find the key via step index
                eventPublisher.publishEvent(new FlowStepEvent(
                        trackId, exec.getId(), i, "APPROVE", "PENDING_APPROVAL",
                        currentKey, currentKey,
                        currentPath, currentPath,
                        currentSize, currentSize,
                        0L, null));
                FlowApproval approval = FlowApproval.builder()
                        .executionId(exec.getId())
                        .trackId(trackId)
                        .flowName(flow.getName())
                        .originalFilename(filename)
                        .stepIndex(i)
                        .pausedStorageKey(currentKey)
                        .pausedVirtualPath(currentPath)
                        .pausedSizeBytes(currentSize)
                        .requiredApprovers(stepCfg.get("requiredApprovers"))
                        .build();
                approvalRepository.save(approval);
                exec.setStatus(FlowExecution.FlowStatus.PAUSED);
                exec.setCurrentStep(i);
                exec.setStepResults(results);
                executionRepository.save(exec);
                log.info("[{}] Flow PAUSED at APPROVE step {} (VIRTUAL) — awaiting admin sign-off, key={}",
                        trackId, i, abbrev(currentKey));
                return;
            }

            int maxRetries = Integer.parseInt(stepCfg.getOrDefault("retryCount", "0"));
            boolean stepSucceeded = false;

            for (int attempt = 0; attempt <= maxRetries; attempt++) {
                long start = System.currentTimeMillis();
                // ── Fabric checkpoint start (VIRTUAL mode) ──
                UUID fabricCheckpointId = null;
                if (fabricBridge != null && fabricProperties != null
                        && fabricProperties.getCheckpoint().isEnabled()) {
                    try {
                        fabricCheckpointId = fabricBridge.startStep(
                                trackId, i, step.getType(), currentKey,
                                currentSize >= 0 ? currentSize : null);
                    } catch (Exception cpEx) {
                        log.debug("[{}] Fabric checkpoint start failed: {}", trackId, cpEx.getMessage());
                    }
                }
                try {
                    if (attempt > 0) {
                        long backoff = 2000L * (1L << (attempt - 1));
                        log.info("[{}] Step {}/{} ({}) retry {}/{} — waiting {}ms",
                                trackId, i + 1, flow.getSteps().size(), step.getType(),
                                attempt, maxRetries, backoff);
                        Thread.sleep(Math.min(backoff, 30000));
                    }
                    if (flowEventJournal != null) {
                        flowEventJournal.recordStepStarted(trackId, exec.getId(), i, step.getType(), currentKey, attempt + 1);
                    }
                    StepOutcome outcome = processStepRef(step, currentKey, currentPath,
                            currentSize, ref, trackId, i);
                    long duration = System.currentTimeMillis() - start;
                    // ── Fabric checkpoint complete (VIRTUAL mode) ──
                    if (fabricCheckpointId != null) {
                        try {
                            fabricBridge.completeStep(fabricCheckpointId,
                                    outcome.storageKey(),
                                    outcome.sizeBytes() >= 0 ? outcome.sizeBytes() : null);
                        } catch (Exception cpEx) {
                            log.debug("[{}] Fabric checkpoint complete failed: {}", trackId, cpEx.getMessage());
                        }
                    }
                    results.add(FlowExecution.StepResult.builder()
                            .stepIndex(i).stepType(step.getType())
                            .status(attempt > 0 ? "OK_AFTER_RETRY_" + attempt : "OK")
                            .inputFile(currentPath).outputFile(outcome.virtualPath())
                            .durationMs(duration).build());
                    if (flowEventJournal != null) {
                        flowEventJournal.recordStepCompleted(trackId, exec.getId(), i, step.getType(), outcome.storageKey(), outcome.sizeBytes(), duration);
                    }
                    // ── Audit log for step completion (VIRTUAL) ──
                    if (auditService != null) {
                        auditService.logFlowStep(trackId, step.getType(), currentPath, outcome.virtualPath(), true, duration, null);
                    }
                    // ── fire-and-forget snapshot (async, non-blocking) ──────────────
                    final String snapInKey  = currentKey;
                    final String snapInPath = currentPath;
                    final long   snapInSize = currentSize;
                    currentKey  = outcome.storageKey();
                    currentPath = outcome.virtualPath();
                    currentSize = outcome.sizeBytes();
                    exec.setCurrentStep(i + 1);
                    exec.setCurrentStorageKey(currentKey);
                    eventPublisher.publishEvent(new FlowStepEvent(
                            trackId, exec.getId(), i, step.getType(),
                            attempt > 0 ? "OK_AFTER_RETRY_" + attempt : "OK",
                            snapInKey, currentKey,
                            snapInPath, currentPath,
                            snapInSize, currentSize,
                            duration, null));
                    log.info("[{}] Step {}/{} ({}) completed in {}ms — key={}",
                            trackId, i + 1, flow.getSteps().size(), step.getType(),
                            duration, abbrev(currentKey));
                    stepSucceeded = true;
                    break;
                } catch (Exception e) {
                    long duration = System.currentTimeMillis() - start;
                    // ── Fabric checkpoint fail (VIRTUAL mode) ──
                    if (fabricCheckpointId != null) {
                        try {
                            fabricBridge.failStep(fabricCheckpointId, classifyError(e), e.getMessage());
                        } catch (Exception cpEx) {
                            log.debug("[{}] Fabric checkpoint fail failed: {}", trackId, cpEx.getMessage());
                        }
                    }
                    if (attempt < maxRetries && isRetryableStepError(e)) {
                        if (flowEventJournal != null) {
                            long backoff = 2000L * (1L << attempt);
                            flowEventJournal.recordStepRetrying(trackId, exec.getId(), i, step.getType(), attempt + 2, Math.min(backoff, 30000));
                        }
                        log.warn("[{}] Step {}/{} ({}) attempt {} failed (retryable): {}",
                                trackId, i + 1, flow.getSteps().size(), step.getType(),
                                attempt + 1, e.getMessage());
                        continue;
                    }
                    results.add(FlowExecution.StepResult.builder()
                            .stepIndex(i).stepType(step.getType()).status("FAILED")
                            .inputFile(currentPath).durationMs(duration)
                            .error(e.getMessage()).build());
                    // ── fire-and-forget failure snapshot ──────────────────────────
                    eventPublisher.publishEvent(new FlowStepEvent(
                            trackId, exec.getId(), i, step.getType(), "FAILED",
                            currentKey, null,
                            currentPath, null,
                            currentSize, 0L,
                            duration, e.getMessage()));
                    if (flowEventJournal != null) {
                        flowEventJournal.recordStepFailed(trackId, exec.getId(), i, step.getType(), e.getMessage(), attempt + 1);
                        flowEventJournal.recordExecutionFailed(trackId, exec.getId(), exec.getErrorMessage());
                    }
                    // ── Audit log for step failure (VIRTUAL) ──
                    if (auditService != null) {
                        auditService.logFlowStep(trackId, step.getType(), currentPath, null, false, duration, e.getMessage());
                    }
                    exec.setStatus(FlowExecution.FlowStatus.FAILED);
                    exec.setErrorMessage("Step " + i + " (" + step.getType() + ") failed"
                            + (attempt > 0 ? " after " + (attempt + 1) + " attempts" : "")
                            + ": " + e.getMessage());
                    exec.setStepResults(results);
                    exec.setCompletedAt(Instant.now());
                    executionRepository.save(exec);
                    log.error("[{}] Step {}/{} ({}) FAILED: {}",
                            trackId, i + 1, flow.getSteps().size(), step.getType(), e.getMessage());
                    return;
                }
            }
            // ── termination check between steps (non-blocking poll) ───────────
            FlowExecution fresh = executionRepository.findById(exec.getId()).orElse(null);
            if (fresh != null && fresh.isTerminationRequested()) {
                exec.setStatus(FlowExecution.FlowStatus.CANCELLED);
                exec.setErrorMessage("Terminated by " + fresh.getTerminatedBy() + " after step " + i);
                exec.setStepResults(results);
                exec.setCompletedAt(Instant.now());
                executionRepository.save(exec);
                log.info("[{}] Flow CANCELLED by {} after step {}", trackId, fresh.getTerminatedBy(), i);
                return;
            }
            if (!stepSucceeded) {
                exec.setStatus(FlowExecution.FlowStatus.FAILED);
                exec.setErrorMessage("Step " + i + " (" + step.getType() + ") exhausted all retries");
                exec.setStepResults(results);
                exec.setCompletedAt(Instant.now());
                executionRepository.save(exec);
                dispatchFlowEvent("FLOW_FAILED", exec, flow);
                return;
            }
        }

        exec.setStatus(FlowExecution.FlowStatus.COMPLETED);
        exec.setCurrentStorageKey(currentKey);
        exec.setStepResults(results);
        exec.setCompletedAt(Instant.now());
        executionRepository.save(exec);
        if (flowEventJournal != null) {
            long totalDuration = exec.getStartedAt() != null
                    ? Instant.now().toEpochMilli() - exec.getStartedAt().toEpochMilli() : 0;
            flowEventJournal.recordExecutionCompleted(trackId, exec.getId(), totalDuration, results.size());
        }
        // ── Audit log for flow completion (VIRTUAL) ──
        if (auditService != null) {
            auditService.logFlowComplete(trackId, flow.getName(), true, null);
        }
        dispatchFlowEvent("FLOW_COMPLETED", exec, flow);
        log.info("[{}] Flow '{}' completed (VIRTUAL) for '{}' — final key={}",
                trackId, flow.getName(), filename, abbrev(currentKey));
      } catch (Exception e) {
        log.error("[{}] Unhandled exception in flow '{}' (VIRTUAL): {}", trackId, flow.getName(), e.getMessage(), e);
        try {
            exec.setStatus(FlowExecution.FlowStatus.FAILED);
            exec.setErrorMessage("Unhandled error: " + e.getMessage());
            exec.setCompletedAt(Instant.now());
            executionRepository.save(exec);
            dispatchFlowEvent("FLOW_FAILED", exec, flow);
        } catch (Exception inner) {
            log.error("[{}] Failed to persist FAILED status: {}", trackId, inner.getMessage());
        }
      }
    }

    /**
     * Resolves the actual queue topic for a step. For FILE_DELIVERY, routes to
     * protocol-specific queue based on the endpoint config. One slow SFTP partner
     * can't block HTTP or Kafka deliveries — they're separate queues.
     */
    private String resolveStepTopic(FileFlow.FlowStep step) {
        String type = step.getType().toUpperCase();
        if (!"FILE_DELIVERY".equals(type)) return type;

        // Route to protocol-specific delivery queue
        Map<String, String> cfg = step.getConfig();
        if (cfg == null) return type;

        String endpointIds = cfg.get("deliveryEndpointIds");
        if (endpointIds == null || endpointIds.isBlank()) return type;

        // Resolve first endpoint's protocol
        try {
            UUID firstId = UUID.fromString(endpointIds.split(",")[0].trim());
            return deliveryEndpointRepository.findById(firstId)
                    .map(ep -> switch (ep.getProtocol().name().toUpperCase()) {
                        case "SFTP" -> "DELIVER_SFTP";
                        case "FTP", "FTPS" -> "DELIVER_FTP";
                        case "HTTP", "HTTPS", "API" -> "DELIVER_HTTP";
                        case "AS2" -> "DELIVER_AS2";
                        case "KAFKA" -> "DELIVER_KAFKA";
                        default -> "FILE_DELIVERY";
                    })
                    .orElse("FILE_DELIVERY");
        } catch (Exception e) {
            return "FILE_DELIVERY"; // fallback to generic
        }
    }

    /** Carries the new storage key + virtual path after a VIRTUAL-mode step completes. */
    private record StepOutcome(String storageKey, String virtualPath, long sizeBytes) {}

    private StepOutcome processStepRef(FileFlow.FlowStep step, String storageKey,
                                        String virtualPath, long sizeBytes, FileRef origin,
                                        String trackId, int stepIndex) throws Exception {
        if (step.getType() == null || step.getType().isBlank()) {
            throw new IllegalArgumentException("Flow step " + stepIndex + " has no type defined");
        }
        Map<String, String> cfg = step.getConfig() != null ? step.getConfig() : Map.of();
        return switch (step.getType().toUpperCase()) {
            case "COMPRESS_GZIP"   -> refCompressGzip(storageKey, virtualPath, origin, trackId);
            case "DECOMPRESS_GZIP" -> refDecompressGzip(storageKey, virtualPath, origin, trackId);
            case "COMPRESS_ZIP"    -> refCompressZip(storageKey, virtualPath, origin, trackId);
            case "DECOMPRESS_ZIP"  -> refDecompressZip(storageKey, virtualPath, origin, trackId);
            case "ENCRYPT_AES", "DECRYPT_AES",
                 "ENCRYPT_PGP", "DECRYPT_PGP" ->
                    refEncryptDecrypt(storageKey, virtualPath, origin, trackId, step.getType(), cfg);
            case "RENAME"          -> refRename(storageKey, virtualPath, sizeBytes, origin, trackId, cfg);
            case "SCREEN"          -> refScreen(storageKey, virtualPath, sizeBytes, origin, trackId, cfg);
            case "MAILBOX"         -> refMailbox(storageKey, virtualPath, sizeBytes, origin, trackId, cfg);
            case "FILE_DELIVERY"   -> refFileDelivery(storageKey, virtualPath, sizeBytes, origin, trackId, cfg);
            case "CONVERT_EDI"     -> refConvertEdi(storageKey, virtualPath, origin, trackId, cfg);
            case "EXECUTE_SCRIPT"  -> throw new UnsupportedOperationException(
                    "EXECUTE_SCRIPT is not supported for VIRTUAL-mode accounts");
            case "ROUTE"           -> new StepOutcome(storageKey, virtualPath, sizeBytes);
            case "APPROVE"         -> new StepOutcome(storageKey, virtualPath, sizeBytes); // handled above loop; pass-through if reached
            default -> {
                // Try FlowFunctionRegistry for custom/plugin functions (physical-mode fallback for VIRTUAL accounts).
                // Plugin receives a temporary materialized copy; result is stored back into storage-manager.
                if (functionRegistry != null) {
                    Optional<FlowFunction> fn = functionRegistry.get(step.getType());
                    if (fn.isPresent()) {
                        log.info("[{}] Executing plugin function (VIRTUAL): {} (step {})",
                                trackId, step.getType(), stepIndex);
                        // Stream from CAS → temp file (no full byte[] in heap)
                        String filename = VirtualFileSystem.nameOf(virtualPath);
                        Path tempInput = materializeFromCas(storageKey, filename);
                        Path tempDir = tempInput.getParent();

                        FlowFunctionContext ctx = new FlowFunctionContext(
                                tempInput, tempDir, cfg, trackId, filename);
                        String outputPathStr = fn.get().executePhysical(ctx);
                        Path outputPath = Paths.get(outputPathStr);

                        // If plugin returned the same file, pass through unchanged
                        if (outputPath.equals(tempInput)) {
                            yield new StepOutcome(storageKey, virtualPath, sizeBytes);
                        }

                        // Store the output back into storage-manager
                        byte[] outputBytes = Files.readAllBytes(outputPath);
                        String newFilename = outputPath.getFileName().toString();
                        String dir = virtualPath.substring(0, virtualPath.lastIndexOf('/') + 1);
                        String newVirtualPath = dir + newFilename;
                        Map<String, Object> stored = storageClient.storeStream(
                                new ByteArrayInputStream(outputBytes), outputBytes.length,
                                newFilename, origin.accountId().toString(), trackId);
                        String newKey  = (String) stored.get("sha256");
                        long   newSize = ((Number) stored.get("sizeBytes")).longValue();
                        vfsBridge.registerRef(origin.accountId(), newVirtualPath,
                                newKey, newSize, trackId, origin.contentType());

                        // Clean up temp files
                        try { Files.deleteIfExists(outputPath); Files.deleteIfExists(tempInput); Files.deleteIfExists(tempDir); }
                        catch (IOException ignored) {}

                        yield new StepOutcome(newKey, newVirtualPath, newSize);
                    }
                }
                throw new IllegalArgumentException("Unknown step type: " + step.getType());
            }
        };
    }

    // ── VIRTUAL step implementations ──────────────────────────────────────────────

    /**
     * COMPRESS_GZIP — read raw bytes → compress in a virtual thread via PipedStream →
     * stream to storage-manager. One read, one write, no intermediate buffer beyond the pipe.
     */
    private StepOutcome refCompressGzip(String storageKey, String virtualPath,
                                         FileRef origin, String trackId) throws IOException {
        // Phase 4.2: stream from CAS → temp file → read (avoids HTTP client double-buffer)
        Path tempCas = materializeFromCas(storageKey, VirtualFileSystem.nameOf(virtualPath));
        byte[] raw = readAndCleanup(tempCas);
        String newPath = virtualPath + ".gz";
        String filename = VirtualFileSystem.nameOf(newPath);

        PipedOutputStream pipedOut = new PipedOutputStream();
        PipedInputStream  pipedIn  = new PipedInputStream(pipedOut, 65536);
        Thread.ofVirtual().name("gzip-" + trackId).start(() -> {
            try (GZIPOutputStream gz = new GZIPOutputStream(pipedOut)) {
                gz.write(raw);
            } catch (IOException e) {
                log.warn("[{}] COMPRESS_GZIP pipe error: {}", trackId, e.getMessage());
                try { pipedOut.close(); } catch (IOException ignored) {}
            }
        });

        Map<String, Object> result = storageClient.storeStream(
                pipedIn, -1L, filename, origin.accountId().toString(), trackId);
        String newKey  = (String) result.get("sha256");
        long   newSize = ((Number) result.get("sizeBytes")).longValue();
        vfsBridge.registerRef(origin.accountId(), newPath, newKey, newSize, trackId, "application/gzip");
        return new StepOutcome(newKey, newPath, newSize);
    }

    /**
     * DECOMPRESS_GZIP — wrap compressed bytes in GZIPInputStream in a virtual thread →
     * pipe decompressed stream to storage-manager.
     */
    private StepOutcome refDecompressGzip(String storageKey, String virtualPath,
                                           FileRef origin, String trackId) throws IOException {
        Path tempCas = materializeFromCas(storageKey, VirtualFileSystem.nameOf(virtualPath));
        byte[] compressed = readAndCleanup(tempCas);
        String newPath = virtualPath.endsWith(".gz")
                ? virtualPath.substring(0, virtualPath.length() - 3) : virtualPath + ".ungz";
        String filename = VirtualFileSystem.nameOf(newPath);

        PipedOutputStream pipedOut = new PipedOutputStream();
        PipedInputStream  pipedIn  = new PipedInputStream(pipedOut, 65536);
        Thread.ofVirtual().name("gunzip-" + trackId).start(() -> {
            try (GZIPInputStream gzIn = new GZIPInputStream(new ByteArrayInputStream(compressed));
                 OutputStream out = pipedOut) {
                gzIn.transferTo(out);
            } catch (IOException e) {
                log.warn("[{}] DECOMPRESS_GZIP pipe error: {}", trackId, e.getMessage());
                try { pipedOut.close(); } catch (IOException ignored) {}
            }
        });

        Map<String, Object> result = storageClient.storeStream(
                pipedIn, -1L, filename, origin.accountId().toString(), trackId);
        String newKey  = (String) result.get("sha256");
        long   newSize = ((Number) result.get("sizeBytes")).longValue();
        vfsBridge.registerRef(origin.accountId(), newPath, newKey, newSize, trackId, origin.contentType());
        return new StepOutcome(newKey, newPath, newSize);
    }

    /** COMPRESS_ZIP — zip raw bytes in a virtual thread → pipe to storage-manager. */
    private StepOutcome refCompressZip(String storageKey, String virtualPath,
                                        FileRef origin, String trackId) throws IOException {
        Path tempCas = materializeFromCas(storageKey, VirtualFileSystem.nameOf(virtualPath));
        byte[] raw = readAndCleanup(tempCas);
        String entryName = VirtualFileSystem.nameOf(virtualPath);
        String newPath   = virtualPath + ".zip";
        String filename  = VirtualFileSystem.nameOf(newPath);

        PipedOutputStream pipedOut = new PipedOutputStream();
        PipedInputStream  pipedIn  = new PipedInputStream(pipedOut, 65536);
        Thread.ofVirtual().name("zip-" + trackId).start(() -> {
            try (ZipOutputStream zipOut = new ZipOutputStream(pipedOut)) {
                zipOut.putNextEntry(new ZipEntry(entryName));
                zipOut.write(raw);
                zipOut.closeEntry();
            } catch (IOException e) {
                log.warn("[{}] COMPRESS_ZIP pipe error: {}", trackId, e.getMessage());
                try { pipedOut.close(); } catch (IOException ignored) {}
            }
        });

        Map<String, Object> result = storageClient.storeStream(
                pipedIn, -1L, filename, origin.accountId().toString(), trackId);
        String newKey  = (String) result.get("sha256");
        long   newSize = ((Number) result.get("sizeBytes")).longValue();
        vfsBridge.registerRef(origin.accountId(), newPath, newKey, newSize, trackId, "application/zip");
        return new StepOutcome(newKey, newPath, newSize);
    }

    /** DECOMPRESS_ZIP — extract first file entry in-memory → store-stream to storage-manager. */
    private StepOutcome refDecompressZip(String storageKey, String virtualPath,
                                          FileRef origin, String trackId) throws IOException {
        Path tempCas = materializeFromCas(storageKey, VirtualFileSystem.nameOf(virtualPath));
        byte[] compressed = readAndCleanup(tempCas);
        String newPath = virtualPath.endsWith(".zip")
                ? virtualPath.substring(0, virtualPath.length() - 4) : virtualPath + ".unzip";
        String filename = VirtualFileSystem.nameOf(newPath);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipInputStream zipIn = new ZipInputStream(new ByteArrayInputStream(compressed))) {
            ZipEntry entry;
            long totalBytes = 0;
            while ((entry = zipIn.getNextEntry()) != null) {
                if (!entry.isDirectory()) {
                    byte[] buf = new byte[8192]; int n;
                    while ((n = zipIn.read(buf)) != -1) {
                        totalBytes += n;
                        if (totalBytes > MAX_ZIP_TOTAL_SIZE)
                            throw new IOException("ZIP exceeds max uncompressed size ("
                                    + MAX_ZIP_TOTAL_SIZE / (1024 * 1024) + " MB)");
                        baos.write(buf, 0, n);
                    }
                    break; // first non-directory entry only
                }
                zipIn.closeEntry();
            }
        }
        byte[] decompressed = baos.toByteArray();
        if (decompressed.length == 0) throw new IOException("ZIP archive was empty");

        Map<String, Object> result = storageClient.storeStream(
                new ByteArrayInputStream(decompressed), decompressed.length,
                filename, origin.accountId().toString(), trackId);
        String newKey  = (String) result.get("sha256");
        long   newSize = ((Number) result.get("sizeBytes")).longValue();
        vfsBridge.registerRef(origin.accountId(), newPath, newKey, newSize, trackId, origin.contentType());
        return new StepOutcome(newKey, newPath, newSize);
    }

    /**
     * ENCRYPT/DECRYPT — fetch bytes from storage-manager → call encryption-service
     * with Base64 payload (in-memory, no disk) → stream result back to storage-manager.
     * AesService lives in encryption-service, not shared, so we call the REST API.
     */
    private StepOutcome refEncryptDecrypt(String storageKey, String virtualPath, FileRef origin,
                                           String trackId, String stepType,
                                           Map<String, String> cfg) throws Exception {
        String[] parts     = stepType.toUpperCase().split("_"); // e.g. ENCRYPT_AES
        String   operation = parts[0].toLowerCase();            // encrypt | decrypt
        String   keyId     = cfg.get("keyId");
        String   name      = VirtualFileSystem.nameOf(virtualPath);
        String   newName;
        if ("decrypt".equals(operation) && name.endsWith(".enc"))
            newName = name.substring(0, name.length() - 4);
        else if ("encrypt".equals(operation))
            newName = name + ".enc";
        else
            newName = name;
        String dir     = virtualPath.substring(0, virtualPath.lastIndexOf('/') + 1);
        String newPath = dir + newName;

        if (keyId == null || keyId.isBlank()) {
            log.warn("[{}] No keyId for {} step — passing through unchanged", trackId, operation);
            return new StepOutcome(storageKey, newPath, -1);
        }

        // Materialize CAS object to temp file → multipart to encryption-service → store result back to CAS.
        // No Base64 encoding. Temp file cleaned up after use.
        log.info("[{}] {} (VIRTUAL) via encryption-service [multipart] (keyId={})", trackId, stepType, keyId);
        Path tempDir = Files.createTempDirectory("flow-encrypt-");
        Path tempInput = tempDir.resolve(VirtualFileSystem.nameOf(virtualPath));
        try {
            // Stream CAS → temp file (only place bytes hit disk, ~64KB buffer)
            try (java.io.OutputStream out = Files.newOutputStream(tempInput)) {
                storageClient.streamToOutput(storageKey, out);
            }

            // Multipart upload to encryption-service (streams from temp file, no heap load)
            byte[] resultBytes;
            UUID keyUuid = UUID.fromString(keyId);
            if (encryptionClient != null) {
                resultBytes = "encrypt".equals(operation)
                        ? encryptionClient.encryptFile(keyUuid, tempInput)
                        : encryptionClient.decryptFile(keyUuid, tempInput);
            } else {
                var headers = new HttpHeaders();
                headers.setContentType(MediaType.MULTIPART_FORM_DATA);
                addInternalAuth(headers, "encryption-service");
                var body = new org.springframework.util.LinkedMultiValueMap<String, Object>();
                body.add("file", new org.springframework.core.io.FileSystemResource(tempInput.toFile()));
                var resp = restTemplate.postForEntity(
                        serviceProps.getEncryptionService().getUrl() + "/api/encrypt/" + operation + "?keyId=" + keyId,
                        new HttpEntity<>(body, headers), byte[].class);
                resultBytes = resp.getBody();
            }

            Map<String, Object> stored = storageClient.storeStream(
                    new ByteArrayInputStream(resultBytes), resultBytes.length,
                    newName, origin.accountId().toString(), trackId);
            String newKey  = (String) stored.get("sha256");
            long   newSize = ((Number) stored.get("sizeBytes")).longValue();
            String ct      = "encrypt".equals(operation) ? "application/octet-stream" : origin.contentType();
            vfsBridge.registerRef(origin.accountId(), newPath, newKey, newSize, trackId, ct);
            return new StepOutcome(newKey, newPath, newSize);
        } finally {
            // Clean up temp file — bytes are in CAS now
            try { Files.deleteIfExists(tempInput); } catch (Exception ignored) {}
            try { Files.deleteIfExists(tempDir); } catch (Exception ignored) {}
        }
    }

    /**
     * RENAME — pure VFS metadata operation. Same storageKey, new virtual path.
     * Zero bytes moved, zero network I/O beyond the single DB write.
     */
    private StepOutcome refRename(String storageKey, String virtualPath, long sizeBytes,
                                   FileRef origin, String trackId,
                                   Map<String, String> cfg) throws IOException {
        String pattern  = cfg.getOrDefault("pattern", "${filename}");
        String name     = VirtualFileSystem.nameOf(virtualPath);
        String baseName = name.contains(".") ? name.substring(0, name.lastIndexOf('.')) : name;
        String ext      = name.contains(".") ? name.substring(name.lastIndexOf('.'))    : "";
        String newName  = pattern
                .replace("${filename}", name).replace("${basename}", baseName)
                .replace("${ext}", ext).replace("${trackid}", trackId)
                .replace("${timestamp}", String.valueOf(System.currentTimeMillis()));
        String dir     = virtualPath.substring(0, virtualPath.lastIndexOf('/') + 1);
        String newPath = dir + newName;
        vfsBridge.registerRef(origin.accountId(), newPath, storageKey, sizeBytes, trackId, origin.contentType());
        log.debug("[{}] RENAME (VIRTUAL): {} → {}", trackId, virtualPath, newPath);
        return new StepOutcome(storageKey, newPath, sizeBytes);
    }

    /**
     * SCREEN — fetch bytes, send to screening-service via ByteArrayResource (no disk).
     * Returns same storageKey (file is unchanged); throws SecurityException on BLOCKED.
     */
    private StepOutcome refScreen(String storageKey, String virtualPath, long sizeBytes,
                                   FileRef origin, String trackId,
                                   Map<String, String> cfg) throws Exception {
        log.info("[{}] SCREEN (VIRTUAL) key={}", trackId, abbrev(storageKey));
        Path screenTemp = null;
        try {
            // Stream from CAS → temp file (no full byte[] in heap)
            final String displayName = VirtualFileSystem.nameOf(virtualPath);
            screenTemp = materializeFromCas(storageKey, displayName);
            org.springframework.util.LinkedMultiValueMap<String, Object> body =
                    new org.springframework.util.LinkedMultiValueMap<>();
            body.add("file", new org.springframework.core.io.FileSystemResource(screenTemp.toFile()));
            HttpHeaders screenHdr = new HttpHeaders();
            screenHdr.setContentType(MediaType.MULTIPART_FORM_DATA);
            addInternalAuth(screenHdr, "screening-service");
            String url = serviceProps.getScreeningService().getUrl()
                    + "/api/v1/screening/scan?trackId=" + trackId;
            if (cfg.containsKey("columns")) url += "&columns=" + cfg.get("columns");
            @SuppressWarnings("unchecked")
            ResponseEntity<Map<String, Object>> resp = restTemplate.postForEntity(
                    url, new HttpEntity<>(body, screenHdr),
                    (Class<Map<String, Object>>) (Class<?>) Map.class);
            if (resp.getBody() != null) {
                String action = (String) resp.getBody().get("actionTaken");
                int hits = resp.getBody().get("hitsFound") instanceof Number n ? n.intValue() : 0;
                log.info("[{}] SCREEN result: {} hits={}", trackId, action, hits);
                if ("BLOCKED".equals(action))
                    throw new SecurityException("SANCTIONS HIT: " + hits + " match(es) found.");
            }
        } catch (SecurityException se) {
            throw se;
        } catch (Exception e) {
            if ("BLOCK".equals(cfg.getOrDefault("onFailure", "PASS")))
                throw new Exception("Screening unreachable — blocking as configured: " + e.getMessage());
            log.warn("[{}] SCREEN unreachable: {} — allowing (graceful degradation)", trackId, e.getMessage());
            // ── Audit: screening bypass under graceful degradation (VIRTUAL) ──
            if (auditService != null) {
                auditService.logAction("system", "SCREENING_BYPASSED", true, null,
                        Map.of("trackId", trackId, "reason", "Screening service unreachable — file allowed under graceful degradation"));
            }
        } finally {
            // Cleanup screen temp file
            if (screenTemp != null) {
                try { Files.deleteIfExists(screenTemp); } catch (Exception ignored) {}
                try { Files.deleteIfExists(screenTemp.getParent()); } catch (Exception ignored) {}
            }
        }
        return new StepOutcome(storageKey, virtualPath, sizeBytes);
    }

    /**
     * MAILBOX — zero-copy cross-account delivery.
     * Creates one new VirtualEntry row pointing to the same storageKey. No bytes copied.
     */
    private StepOutcome refMailbox(String storageKey, String virtualPath, long sizeBytes,
                                    FileRef origin, String trackId,
                                    Map<String, String> cfg) throws Exception {
        String destUsername = cfg.get("destinationUsername");
        if (destUsername == null || destUsername.isBlank())
            throw new IllegalArgumentException("MAILBOX step requires 'destinationUsername'");
        Protocol proto;
        try { proto = Protocol.valueOf(cfg.getOrDefault("protocol", "SFTP").toUpperCase()); }
        catch (IllegalArgumentException ignored) { proto = Protocol.SFTP; }
        final Protocol resolvedProtocol = proto;
        TransferAccount dest = accountRepository
                .findByUsernameAndProtocolAndActiveTrue(destUsername, resolvedProtocol)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Destination account not found: " + destUsername + " (" + resolvedProtocol + ")"));
        String filename = VirtualFileSystem.nameOf(virtualPath);
        String destPath = "/outbox/" + filename;
        FileRef src = new FileRef(storageKey, virtualPath, origin.accountId(),
                sizeBytes, trackId, origin.contentType(), "STANDARD");
        vfsBridge.linkToAccount(src, dest.getId(), destPath, trackId);
        log.info("[{}] MAILBOX (VIRTUAL): zero-copy → account={} path={}", trackId, destUsername, destPath);
        return new StepOutcome(storageKey, virtualPath, sizeBytes);
    }

    /**
     * FILE_DELIVERY — stream file from storage-manager CAS → forwarder-service → partner.
     *
     * <p>Uses streaming multipart POST (no Base64, no full heap load). Bytes flow:
     * {@code CAS disk → PipedStream → multipart HTTP → forwarder → partner SFTP/FTP/AS2}.
     * Memory overhead: ~64 KB pipe buffer per delivery (was: 133% of file size with Base64).
     */
    private StepOutcome refFileDelivery(String storageKey, String virtualPath, long sizeBytes,
                                         FileRef origin, String trackId,
                                         Map<String, String> cfg) throws Exception {
        String endpointIdsStr = cfg.get("deliveryEndpointIds");
        if (endpointIdsStr == null || endpointIdsStr.isBlank())
            throw new IllegalArgumentException("FILE_DELIVERY step requires 'deliveryEndpointIds'");
        List<UUID> ids = Arrays.stream(endpointIdsStr.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).map(UUID::fromString).toList();
        List<DeliveryEndpoint> endpoints = deliveryEndpointRepository.findByIdInAndActiveTrue(ids);
        if (endpoints.isEmpty())
            throw new IllegalArgumentException("No active delivery endpoints for: " + endpointIdsStr);

        String filename  = VirtualFileSystem.nameOf(virtualPath);
        String fwdUrl    = serviceProps.getForwarderService().getUrl();
        int ok = 0; List<String> failures = new ArrayList<>();

        for (DeliveryEndpoint ep : endpoints) {
            try {
                // Stream CAS → PipedStream → multipart POST (zero full-heap load)
                java.io.PipedOutputStream pipedOut = new java.io.PipedOutputStream();
                java.io.PipedInputStream  pipedIn  = new java.io.PipedInputStream(pipedOut, 65536);

                // Virtual thread: pump CAS bytes into the pipe
                Thread.ofVirtual().name("delivery-pump-" + trackId).start(() -> {
                    try {
                        storageClient.streamToOutput(storageKey, pipedOut);
                    } catch (Exception e) {
                        log.error("[{}] CAS stream pump failed: {}", trackId, e.getMessage());
                    } finally {
                        try { pipedOut.close(); } catch (Exception ignored) {}
                    }
                });

                // Streaming InputStreamResource — Spring reads from pipe on demand
                org.springframework.core.io.InputStreamResource streamResource =
                        new org.springframework.core.io.InputStreamResource(pipedIn) {
                            @Override public long contentLength() { return sizeBytes; }
                            @Override public String getFilename() { return filename; }
                        };

                String url = fwdUrl + "/api/forward/deliver/" + ep.getId()
                        + "?filename=" + java.net.URLEncoder.encode(filename, "UTF-8")
                        + "&trackId=" + java.net.URLEncoder.encode(trackId != null ? trackId : "", "UTF-8");
                HttpHeaders fwdHdr = new HttpHeaders();
                fwdHdr.setContentType(MediaType.MULTIPART_FORM_DATA);
                addInternalAuth(fwdHdr, "external-forwarder-service");
                org.springframework.util.LinkedMultiValueMap<String, Object> body =
                        new org.springframework.util.LinkedMultiValueMap<>();
                body.add("file", streamResource);
                restTemplate.postForEntity(url, new HttpEntity<>(body, fwdHdr), Map.class);
                ok++;
                log.info("[{}] FILE_DELIVERY (VIRTUAL/streaming) to '{}' OK", trackId, ep.getName());
            } catch (Exception e) {
                failures.add(ep.getName() + ": " + e.getMessage());
                log.error("[{}] FILE_DELIVERY (VIRTUAL) failed for '{}': {}", trackId, ep.getName(), e.getMessage());
            }
        }
        if (ok == 0)
            throw new RuntimeException("FILE_DELIVERY failed for all " + endpoints.size()
                    + " endpoints: " + String.join("; ", failures));
        return new StepOutcome(storageKey, virtualPath, sizeBytes);
    }

    /**
     * CONVERT_EDI — fetch text content from storage-manager → call EDI converter →
     * stream result back to storage-manager. No disk I/O.
     *
     * <p>Supports new map-based conversion via {@code targetType} config, with fallback to
     * legacy format-based conversion when only {@code targetFormat} is provided.
     */
    private StepOutcome refConvertEdi(String storageKey, String virtualPath, FileRef origin,
                                       String trackId, Map<String, String> cfg) throws Exception {
        String targetType   = cfg.get("targetType");
        String targetFormat = cfg.getOrDefault("targetFormat", "JSON");
        String partnerId    = cfg.get("partnerId");

        // Phase 4.3: stream from CAS → temp file → read (avoids HTTP double-buffer)
        Path tempCas = materializeFromCas(storageKey, VirtualFileSystem.nameOf(virtualPath));
        byte[] raw = readAndCleanup(tempCas);
        String content = new String(raw, java.nio.charset.StandardCharsets.UTF_8);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("content", content);

        String endpoint;
        if (targetType != null && !targetType.isBlank()) {
            body.put("targetType", targetType);
            if (partnerId != null && !partnerId.isBlank()) body.put("partnerId", partnerId);
            endpoint = serviceProps.getEdiConverter().getUrl() + "/api/v1/convert/convert/map";
        } else {
            body.put("targetFormat", targetFormat);
            if (partnerId != null && !partnerId.isBlank()) body.put("partnerId", partnerId);
            endpoint = serviceProps.getEdiConverter().getUrl() + "/api/v1/convert/trained";
        }

        HttpHeaders ediHdr = new HttpHeaders();
        ediHdr.setContentType(MediaType.APPLICATION_JSON);
        addInternalAuth(ediHdr, "edi-converter");
        @SuppressWarnings("unchecked")
        ResponseEntity<Map<String, Object>> resp = restTemplate.exchange(
                endpoint, HttpMethod.POST, new HttpEntity<>(body, ediHdr),
                (Class<Map<String, Object>>) (Class<?>) Map.class);

        Map<String, Object> respBody = resp.getBody();
        String output     = respBody != null ? (String) respBody.get("output") : "";
        String mapUsed    = respBody != null ? (String) respBody.get("mapUsed") : null;
        Object confidence = respBody != null ? respBody.get("confidence") : null;

        String ext = switch (targetFormat.toUpperCase()) {
            case "JSON" -> ".json"; case "XML" -> ".xml";
            case "CSV"  -> ".csv";  case "YAML" -> ".yaml"; default -> ".txt";
        };
        String baseName = VirtualFileSystem.nameOf(virtualPath);
        if (baseName.contains(".")) baseName = baseName.substring(0, baseName.lastIndexOf('.'));
        String newName = baseName + ext;
        String dir     = virtualPath.substring(0, virtualPath.lastIndexOf('/') + 1);
        String newPath = dir + newName;

        byte[] outputBytes = output.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String ct = switch (targetFormat.toUpperCase()) {
            case "JSON" -> "application/json"; case "XML" -> "application/xml";
            case "CSV"  -> "text/csv";         default    -> "text/plain";
        };
        Map<String, Object> stored = storageClient.storeStream(
                new ByteArrayInputStream(outputBytes), outputBytes.length,
                newName, origin.accountId().toString(), trackId);
        String newKey  = (String) stored.get("sha256");
        long   newSize = ((Number) stored.get("sizeBytes")).longValue();
        vfsBridge.registerRef(origin.accountId(), newPath, newKey, newSize, trackId, ct);
        log.info("[{}] CONVERT_EDI (VIRTUAL): {} -> {} (targetType={}, format={}, mapUsed={}, confidence={})",
                trackId, virtualPath, newPath, targetType, targetFormat, mapUsed, confidence);
        return new StepOutcome(newKey, newPath, newSize);
    }

    /**
     * Phase 4.1: Determine checkpoint strategy by step type.
     * ALWAYS checkpoint: delivery steps (irreversible external I/O) + APPROVE (pause point).
     * Other steps are re-runnable from earlier checkpoint — skip storage push to save I/O.
     */
    private static boolean shouldAlwaysCheckpoint(String stepType) {
        if (stepType == null) return false;
        return switch (stepType.toUpperCase()) {
            case "FILE_DELIVERY", "FORWARD_SFTP", "FORWARD_FTP", "FORWARD_AS2",
                 "FORWARD_HTTP", "DELIVER_SFTP", "DELIVER_FTP", "DELIVER_HTTP",
                 "DELIVER_AS2", "DELIVER_KAFKA", "MAILBOX", "APPROVE" -> true;
            default -> false;
        };
    }

    private static String abbrev(String key) {
        if (key == null) return "null";
        return key.length() > 8 ? key.substring(0, 8) + "…" : key;
    }

    /**
     * Materialize a CAS object to a temporary file via streaming (no full byte[] in heap).
     * Caller MUST delete the returned file when done (use try-finally).
     * Memory: ~64 KB buffer (was: entire file in byte[]).
     */
    private Path materializeFromCas(String storageKey, String filename) throws IOException {
        Path tempDir = Files.createTempDirectory("flow-cas-");
        Path tempFile = tempDir.resolve(filename);
        try (java.io.OutputStream out = Files.newOutputStream(tempFile)) {
            storageClient.streamToOutput(storageKey, out);
        }
        return tempFile;
    }

    /** Read temp file content — used after materialization for steps that need byte[]. */
    private byte[] readAndCleanup(Path tempFile) throws IOException {
        try {
            return Files.readAllBytes(tempFile);
        } finally {
            try { Files.deleteIfExists(tempFile); } catch (Exception ignored) {}
            try { Files.deleteIfExists(tempFile.getParent()); } catch (Exception ignored) {}
        }
    }

}
