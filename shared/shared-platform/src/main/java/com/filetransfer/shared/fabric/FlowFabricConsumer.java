package com.filetransfer.shared.fabric;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.filetransfer.fabric.FabricEvent;
import com.filetransfer.fabric.config.FabricProperties;
import com.filetransfer.shared.entity.FileFlow;
import com.filetransfer.shared.entity.FlowExecution;
import com.filetransfer.shared.repository.FileFlowRepository;
import com.filetransfer.shared.repository.FlowExecutionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

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
        String groupId = serviceName + "-flow-engine";

        try {
            fabricBridge.subscribeIntake(groupId, this::onIntakeMessage);
            log.info("[FlowFabricConsumer] Subscribed to flow.intake (group={})", groupId);
        } catch (Exception e) {
            log.warn("[FlowFabricConsumer] Failed to subscribe to flow.intake — fabric disabled at runtime: {}",
                e.getMessage());
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
