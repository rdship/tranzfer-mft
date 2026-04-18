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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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
 *
 * <p>R120 (restored from R117 over-reach): class-level
 * {@code @ConditionalOnProperty(flow.rules.enabled)} re-applied. It wasn't
 * just a feature toggle — per the docker-compose {@code x-routing-env}
 * design, lightweight services (encryption, license, keystore, storage,
 * screening, notification) deliberately do NOT set {@code FLOW_RULES_ENABLED}
 * so routing beans never load there — narrower @EnableJpaRepositories
 * scope works, fewer entities scanned, faster boot. R117's removal made
 * the consumer unconditional and crashed 6 services that don't declare
 * {@link com.filetransfer.shared.repository.transfer.FlowExecutionRepository}
 * in their JPA scope (R118→R119 No Medal).
 *
 * <p>The R119 {@code @Autowired(required=false)} on {@link #fabricBridge}
 * is kept as belt-and-braces for AOT builds. {@link #flowRulesEnabled} and
 * init()'s bridge null-check are both kept as runtime safety nets. See
 * {@code docs/AOT-SAFETY.md} for the full retrofit-vs-revert decision tree.
 */
@Component
@ConditionalOnProperty(name = "flow.rules.enabled", havingValue = "true", matchIfMissing = false)
@RequiredArgsConstructor
@Slf4j
public class FlowFabricConsumer {

    private final FabricProperties properties;
    private final ObjectMapper objectMapper;
    private final FlowExecutionRepository executionRepo;
    private final FileFlowRepository flowRepo;

    /**
     * R119 P0 fix: {@link FlowFabricBridge} is still class-level
     * {@code @ConditionalOnProperty(flow.rules.enabled)}, so if AOT or any
     * profile omits that property the bridge bean is absent. R117 made THIS
     * class unconditional — constructor-injection on a conditional dep then
     * cascades to a context-refresh crash. Switched to optional field
     * injection so absence is tolerated (we null-check at {@link #init}).
     * Matches the dev-team consolidated-report pattern: every consumer of a
     * @ConditionalOnProperty(matchIfMissing=false) bean must declare the
     * reference as optional ({@code @Autowired(required=false)} /
     * {@code Optional<T>} / {@code ObjectProvider<T>}).
     */
    @Autowired(required = false)
    private FlowFabricBridge fabricBridge;

    /** Lazy reference to FlowProcessingEngine — avoids circular injection at startup. */
    @Autowired
    @Lazy
    private com.filetransfer.shared.routing.FlowProcessingEngine flowProcessingEngine;

    /** R117: runtime gate (replaces the old class-level @ConditionalOnProperty). */
    @Value("${flow.rules.enabled:false}")
    private boolean flowRulesEnabled;

    @PostConstruct
    void init() {
        if (!flowRulesEnabled) {
            log.info("[FlowFabricConsumer] flow.rules.enabled=false — not subscribing");
            return;
        }
        if (fabricBridge == null) {
            // R119: FlowFabricBridge is still @ConditionalOnProperty-gated,
            // so it can be absent even when flow.rules.enabled=true if the
            // AOT build evaluated it without the property. Fail soft — log
            // and return instead of crashing context refresh.
            log.warn("[FlowFabricConsumer] FlowFabricBridge not in context (likely AOT build without flow.rules.enabled). Fabric consumption disabled.");
            return;
        }
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

        subscribeStepTopicsInParallel(serviceName, stepTypes);
        log.info("[FlowFabricConsumer] Per-function step pipeline active — {} step types subscribed", stepTypes.length);
    }

    /**
     * R94 boot-time optimization — invokes 20 {@code subscribe()} calls on a
     * small thread pool instead of serially. Preserves the contract: every
     * subscribe() completes before this method returns, so consumers are
     * fully wired before Spring context refresh finishes. No feature change —
     * independent scaling per step-type (one consumer group per topic) and
     * handler identity remain unchanged.
     *
     * <p>Pre-creates all 20 topics in one batch up-front ({@link FabricClient#ensureTopics})
     * so each subscribe() skips its own topic-ensure (Option 2 of the R94 design).
     *
     * <p>Parallelism is tunable via {@code fabric.flow.subscribe-parallelism}
     * (default 8). Set to 1 for serial rollback.
     */
    private void subscribeStepTopicsInParallel(String serviceName, String[] stepTypes) {
        // Option 2: batch-create all step topics in a single AdminClient round-trip.
        List<String> allTopics = Arrays.stream(stepTypes)
                .map(s -> "flow.step." + s)
                .toList();
        try {
            fabricBridge.getClient().ensureTopics(allTopics);
        } catch (Exception e) {
            log.debug("[FlowFabricConsumer] Batch topic pre-create failed — will fall back to per-subscribe ensure: {}",
                    e.getMessage());
        }

        int parallelism = Math.max(1, Math.min(
                properties.getFlow().getSubscribeParallelism(), stepTypes.length));
        if (parallelism == 1) {
            // Rollback path — pure sequential, unchanged from pre-R94 behaviour.
            for (String stepType : stepTypes) subscribeOneStepTopic(serviceName, stepType);
            return;
        }

        ExecutorService pool = Executors.newFixedThreadPool(parallelism, r -> {
            Thread t = new Thread(r, "fabric-subscribe-init");
            t.setDaemon(true);
            return t;
        });
        CountDownLatch done = new CountDownLatch(stepTypes.length);
        AtomicInteger succeeded = new AtomicInteger();
        long start = System.nanoTime();
        try {
            for (String stepType : stepTypes) {
                pool.submit(() -> {
                    try {
                        subscribeOneStepTopic(serviceName, stepType);
                        succeeded.incrementAndGet();
                    } finally {
                        done.countDown();
                    }
                });
            }
            // Barrier — every consumer must be wired before init() returns,
            // otherwise Spring marks this bean Started while the pipeline is
            // still plumbing itself and the first inbound message is dropped.
            if (!done.await(60, TimeUnit.SECONDS)) {
                log.warn("[FlowFabricConsumer] Parallel subscribe barrier timeout — some topics may still be wiring");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[FlowFabricConsumer] Parallel subscribe interrupted");
        } finally {
            pool.shutdown();
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        log.info("[FlowFabricConsumer] Subscribed {} of {} step topics in {} ms (parallelism={})",
                succeeded.get(), stepTypes.length, elapsedMs, parallelism);
    }

    private void subscribeOneStepTopic(String serviceName, String stepType) {
        try {
            String topic = "flow.step." + stepType;
            String group = FabricGroupIds.shared(serviceName, topic);
            fabricBridge.getClient().subscribe(topic, group, this::onPipelineMessage);
            log.debug("[FlowFabricConsumer] Subscribed to {} (group={})", topic, group);
        } catch (Exception e) {
            log.debug("[FlowFabricConsumer] Topic flow.step.{} not available: {}", stepType, e.getMessage());
        }
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
