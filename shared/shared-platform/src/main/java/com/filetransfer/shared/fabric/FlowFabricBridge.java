package com.filetransfer.shared.fabric;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.filetransfer.fabric.FabricClient;
import com.filetransfer.fabric.config.FabricProperties;
import com.filetransfer.shared.entity.transfer.FabricCheckpoint;
import com.filetransfer.shared.entity.transfer.FileFlow;
import com.filetransfer.shared.entity.transfer.FlowExecution;
import com.filetransfer.shared.repository.transfer.FabricCheckpointRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Domain-specific wrapper around FabricClient for flow processing.
 *
 * Topics:
 * - flow.intake    — new file arrived, rule matching + first step
 * - flow.pipeline  — single step execution (published once per step)
 * - flow.delivery  — final delivery step
 * - flow.retry.scheduled — delayed retries (Phase 5)
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class FlowFabricBridge {

    public static final String TOPIC_FLOW_INTAKE = "flow.intake";
    public static final String TOPIC_FLOW_PIPELINE = "flow.pipeline";
    public static final String TOPIC_FLOW_DELIVERY = "flow.delivery";
    public static final String TOPIC_FLOW_RETRY = "flow.retry.scheduled";

    private final FabricClient fabricClient;
    private final FabricProperties properties;
    private final FabricCheckpointRepository checkpointRepo;
    private final ObjectMapper objectMapper;

    /** Hostname of this instance — used as processing_instance in checkpoints */
    private String instanceId;

    @jakarta.annotation.PostConstruct
    void init() {
        try {
            this.instanceId = System.getenv().getOrDefault("HOSTNAME",
                java.net.InetAddress.getLocalHost().getHostName());
        } catch (Exception e) {
            this.instanceId = "unknown-" + UUID.randomUUID().toString().substring(0, 8);
        }
        log.info("[FlowFabricBridge] Initialized with instance={}, distributed={}",
            instanceId, fabricClient.isDistributed());
    }

    public boolean isFabricActive() {
        return properties.isEnabled() && properties.getFlow().isPublish() && fabricClient.isDistributed();
    }

    /** Publish a new flow execution to the INTAKE topic. */
    public void publishIntake(FlowExecution execution, FileFlow flow) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("trackId", execution.getTrackId());
        payload.put("flowId", flow.getId() != null ? flow.getId().toString() : null);
        payload.put("flowName", flow.getName());
        payload.put("stepCount", flow.getSteps() != null ? flow.getSteps().size() : 0);
        payload.put("startedAt", Instant.now().toString());
        payload.put("instance", instanceId);

        fabricClient.publish(TOPIC_FLOW_INTAKE, execution.getTrackId(), payload);
        log.debug("[FabricBridge] Published intake for {}", execution.getTrackId());
    }

    /**
     * Publish a step work item to a per-function topic.
     *
     * <p>Each step type gets its own Kafka topic: {@code flow.step.SCREEN},
     * {@code flow.step.ENCRYPT_PGP}, {@code flow.step.COMPRESS_GZIP}, etc.
     * This makes each function independently scalable — add SCREEN workers
     * without affecting ENCRYPT throughput. One slow function can't block another.
     *
     * <p>Messages are keyed by trackId — Kafka guarantees ordering per key
     * within a partition. Different transfers (different trackIds) process
     * in parallel across partitions. One partner's bulk upload can't starve another.
     *
     * <p>Also publishes to the generic {@code flow.pipeline} topic for
     * cross-cutting consumers (monitoring, audit, observability dashboards).
     */
    public void publishStep(String trackId, int stepIndex, String stepType,
                             String inputStorageKey) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("trackId", trackId);
        payload.put("stepIndex", stepIndex);
        payload.put("stepType", stepType);
        payload.put("inputStorageKey", inputStorageKey);
        payload.put("publishedAt", Instant.now().toString());
        payload.put("instance", instanceId);

        // Per-function topic — enables independent scaling per step type
        String functionTopic = "flow.step." + stepType;
        fabricClient.publish(functionTopic, trackId, payload);

        // Generic pipeline topic — monitoring + observability
        fabricClient.publish(TOPIC_FLOW_PIPELINE, trackId, payload);

        log.debug("[FabricBridge] Step {}/{} for {} → {}", stepIndex, stepType, trackId, functionTopic);
    }

    // ============ CHECKPOINT MANAGEMENT ============

    /**
     * Write a checkpoint at the START of a step. Transactional.
     * Returns the checkpoint ID so the caller can update it on completion.
     */
    @Transactional
    public UUID startStep(String trackId, int stepIndex, String stepType, String inputStorageKey, Long inputBytes) {
        if (!properties.getCheckpoint().isEnabled()) return null;

        FabricCheckpoint cp = FabricCheckpoint.builder()
            .trackId(trackId)
            .stepIndex(stepIndex)
            .stepType(stepType)
            .status("IN_PROGRESS")
            .inputStorageKey(inputStorageKey)
            .inputSizeBytes(inputBytes)
            .processingInstance(instanceId)
            .claimedAt(Instant.now())
            .leaseExpiresAt(Instant.now().plus(properties.getFlow().getLeaseDurationSeconds(), ChronoUnit.SECONDS))
            .startedAt(Instant.now())
            .attemptNumber(1)
            .createdAt(Instant.now())
            .build();

        cp = checkpointRepo.save(cp);
        return cp.getId();
    }

    /** Mark a checkpoint COMPLETED. Transactional. */
    @Transactional
    public void completeStep(UUID checkpointId, String outputStorageKey, Long outputBytes) {
        if (checkpointId == null) return;
        checkpointRepo.findById(checkpointId).ifPresent(cp -> {
            cp.setStatus("COMPLETED");
            cp.setOutputStorageKey(outputStorageKey);
            cp.setOutputSizeBytes(outputBytes);
            cp.setCompletedAt(Instant.now());
            if (cp.getStartedAt() != null) {
                cp.setDurationMs(java.time.Duration.between(cp.getStartedAt(), cp.getCompletedAt()).toMillis());
            }
            checkpointRepo.save(cp);
        });
    }

    /** Mark a checkpoint FAILED. Transactional. */
    @Transactional
    public void failStep(UUID checkpointId, String errorCategory, String errorMessage) {
        if (checkpointId == null) return;
        checkpointRepo.findById(checkpointId).ifPresent(cp -> {
            cp.setStatus("FAILED");
            cp.setErrorCategory(errorCategory);
            cp.setErrorMessage(errorMessage);
            cp.setCompletedAt(Instant.now());
            if (cp.getStartedAt() != null) {
                cp.setDurationMs(java.time.Duration.between(cp.getStartedAt(), cp.getCompletedAt()).toMillis());
            }
            checkpointRepo.save(cp);
        });
    }

    // ============ CONSUMER SUBSCRIPTION ============

    /** Subscribe to the INTAKE topic. Called from consumer initialization. */
    public void subscribeIntake(String groupId, FabricClient.MessageHandler handler) {
        fabricClient.subscribe(TOPIC_FLOW_INTAKE, groupId, handler);
    }

    /** Subscribe to the PIPELINE topic. */
    public void subscribePipeline(String groupId, FabricClient.MessageHandler handler) {
        fabricClient.subscribe(TOPIC_FLOW_PIPELINE, groupId, handler);
    }

    public String getInstanceId() { return instanceId; }

    /** Expose the underlying FabricClient for per-function topic subscriptions. */
    public FabricClient getClient() { return fabricClient; }
}
