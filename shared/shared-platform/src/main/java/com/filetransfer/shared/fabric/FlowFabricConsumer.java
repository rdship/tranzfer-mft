package com.filetransfer.shared.fabric;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.filetransfer.fabric.FabricEvent;
import com.filetransfer.fabric.config.FabricProperties;
import com.filetransfer.shared.entity.transfer.FileFlow;
import com.filetransfer.shared.entity.transfer.FlowExecution;
import com.filetransfer.shared.repository.transfer.FileFlowRepository;
import com.filetransfer.shared.repository.transfer.FlowExecutionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import jakarta.annotation.PostConstruct;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Consumer for flow.intake (and later flow.pipeline) topics.
 *
 * <p>When a message arrives, loads the FlowExecution and FileFlow from the DB and
 * delegates to {@link com.filetransfer.shared.routing.FlowProcessingEngine#executeFlowViaFabric}
 * which runs the exact same step-execution logic used by the SEDA worker thread.
 *
 * <p>Multiple pods run this consumer; Kafka distributes partitions across them for
 * automatic load balancing. If fabric is disabled or shared-fabric not on classpath,
 * this bean is still created but does nothing in {@link #init()}.
 */
@Component
@ConditionalOnProperty(name = "flow.rules.enabled", havingValue = "true", matchIfMissing = false)
@RequiredArgsConstructor
@Slf4j
public class FlowFabricConsumer {

    private final FabricProperties properties;
    private final FlowFabricBridge fabricBridge;
    private final ObjectMapper objectMapper;
    private final FlowExecutionRepository executionRepo;
    private final FileFlowRepository flowRepo;

    /** Lazy reference to FlowProcessingEngine — avoids circular injection at startup. */
    @Autowired
    @Lazy
    private com.filetransfer.shared.routing.FlowProcessingEngine flowProcessingEngine;

    @PostConstruct
    void init() {
        if (!properties.isEnabled() || !properties.getFlow().isConsume()) {
            log.info("[FlowFabricConsumer] Fabric flow consumption disabled, not subscribing");
            return;
        }

        String serviceName = System.getenv().getOrDefault("SERVICE_NAME", "onboarding-api");
        // Shared group — all pods of this service compete for intake partitions (load balance)
        String groupId = FabricGroupIds.shared(serviceName, FlowFabricBridge.TOPIC_FLOW_INTAKE);

        try {
            fabricBridge.subscribeIntake(groupId, this::onIntakeMessage);
            log.info("[FlowFabricConsumer] Subscribed to flow.intake (group={})", groupId);
        } catch (Exception e) {
            log.warn("[FlowFabricConsumer] Failed to subscribe to flow.intake — fabric disabled at runtime: {}",
                e.getMessage());
        }

        // Per-function step topics — each step type gets its own consumer group
        // This enables independent scaling: SCREEN workers scale separately from ENCRYPT workers
        // Processing + delivery queues — delivery split by protocol so one slow
        // SFTP partner can't block HTTP deliveries or Kafka event publishing
        String[] stepTypes = {
                // Security
                "SCREEN", "CHECKSUM_VERIFY",
                "ENCRYPT_PGP", "DECRYPT_PGP", "ENCRYPT_AES", "DECRYPT_AES",
                // Transform
                "COMPRESS_GZIP", "DECOMPRESS_GZIP", "COMPRESS_ZIP", "DECOMPRESS_ZIP",
                "CONVERT_EDI", "RENAME",
                // Delivery — per protocol (independent scaling + isolation)
                "MAILBOX", "FILE_DELIVERY",
                "DELIVER_SFTP", "DELIVER_FTP", "DELIVER_HTTP", "DELIVER_AS2", "DELIVER_KAFKA",
                // Custom
                "EXECUTE_SCRIPT"
        };
        for (String stepType : stepTypes) {
            try {
                String topic = "flow.step." + stepType;
                String group = FabricGroupIds.shared(serviceName, topic);
                fabricBridge.getClient().subscribe(topic, group, this::onPipelineMessage);
                log.debug("[FlowFabricConsumer] Subscribed to {} (group={})", topic, group);
            } catch (Exception e) {
                log.debug("[FlowFabricConsumer] Topic flow.step.{} not available: {}", stepType, e.getMessage());
            }
        }
        log.info("[FlowFabricConsumer] Per-function step pipeline active — {} step types subscribed", stepTypes.length);
    }

    /**
     * Per-step pipeline handler. Each message = one step to execute.
     * After execution, publishes the next step (or marks COMPLETED).
     * If this worker crashes, Kafka redelivers to another worker.
     */
    private void onPipelineMessage(FabricEvent event) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = event.payload(Map.class, objectMapper);
            String trackId = (String) payload.get("trackId");
            int stepIndex = payload.get("stepIndex") instanceof Number n ? n.intValue() : 0;
            String stepType = (String) payload.get("stepType");
            String inputKey = (String) payload.get("inputStorageKey");

            String accountIdStr = (String) payload.get("accountId");
            String virtualPath = (String) payload.get("virtualPath");

            log.info("[{}] Pipeline step {}: {} (input={} account={} path={})", trackId, stepIndex, stepType,
                    inputKey != null ? inputKey.substring(0, Math.min(12, inputKey.length())) : "null",
                    accountIdStr, virtualPath);

            Optional<FlowExecution> execOpt = executionRepo.findByTrackId(trackId);
            if (execOpt.isEmpty()) { log.warn("[{}] Execution not found", trackId); return; }

            FlowExecution exec = execOpt.get();

            // Load flow with steps eagerly — Kafka consumer runs outside Hibernate session,
            // lazy collections throw LazyInitializationException.
            UUID flowId = exec.getFlow() != null ? exec.getFlow().getId() : null;
            String flowIdStr = (String) payload.get("flowId");
            if (flowId == null && flowIdStr != null) flowId = UUID.fromString(flowIdStr);
            if (flowId == null) { log.error("[{}] No flow ID available", trackId); return; }

            FileFlow flow = flowRepo.findByIdWithSteps(flowId).orElse(null);
            if (flow == null) { log.error("[{}] Flow not found: {}", trackId, flowId); return; }
            if (stepIndex >= flow.getSteps().size()) { log.warn("[{}] Step {} out of range", trackId, stepIndex); return; }

            // Execute this single step — pass accountId + virtualPath for VFS INLINE fallback
            UUID accountId = accountIdStr != null ? UUID.fromString(accountIdStr) : null;
            flowProcessingEngine.executeSingleStep(exec, flow, stepIndex, inputKey, trackId,
                    accountId, virtualPath);

        } catch (Exception e) {
            log.error("Failed to process flow.pipeline message at offset {}: {}", event.getOffset(), e.getMessage(), e);
            throw new RuntimeException(e); // Trigger redelivery
        }
    }

    private void onIntakeMessage(FabricEvent event) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = event.payload(Map.class, objectMapper);
            String trackId = (String) payload.get("trackId");
            String flowIdStr = (String) payload.get("flowId");

            log.info("[{}] Received flow.intake from fabric (partition={}, offset={})",
                trackId, event.getPartition(), event.getOffset());

            Optional<FlowExecution> execOpt = executionRepo.findByTrackId(trackId);
            if (execOpt.isEmpty()) {
                log.warn("[{}] FlowExecution not found — skipping", trackId);
                return;
            }

            if (flowIdStr == null) {
                log.warn("[{}] No flowId in intake message — skipping", trackId);
                return;
            }

            Optional<FileFlow> flowOpt = flowRepo.findById(UUID.fromString(flowIdStr));
            if (flowOpt.isEmpty()) {
                log.warn("[{}] FileFlow not found for id={}", trackId, flowIdStr);
                return;
            }

            // Delegate to the engine — runs the same step loop as SEDA worker,
            // but does NOT re-publish to fabric (no infinite loop).
            flowProcessingEngine.executeFlowViaFabric(execOpt.get(), flowOpt.get());

        } catch (Exception e) {
            log.error("Failed to process flow.intake message at offset {}: {}",
                event.getOffset(), e.getMessage(), e);
            throw new RuntimeException(e); // Trigger redelivery
        }
    }
}
