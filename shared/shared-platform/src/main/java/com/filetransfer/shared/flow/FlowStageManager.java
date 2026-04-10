package com.filetransfer.shared.flow;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Manages the three SEDA processing stages for the flow engine:
 * INTAKE (match rules) → PIPELINE (transform/process) → DELIVERY (route/deliver).
 *
 * <p>Each stage has a bounded queue and configurable thread count.
 * Currently the stages are passive containers — the FlowProcessingEngine
 * will be wired to submit work to them in a future phase.
 * For now, this provides the infrastructure and metrics endpoints.
 */
@Component @Slf4j
public class FlowStageManager {

    @Value("${flow.stage.intake.queue:1000}")
    private int intakeQueue;
    @Value("${flow.stage.intake.threads:16}")
    private int intakeThreads;

    @Value("${flow.stage.pipeline.queue:500}")
    private int pipelineQueue;
    @Value("${flow.stage.pipeline.threads:32}")
    private int pipelineThreads;

    @Value("${flow.stage.delivery.queue:2000}")
    private int deliveryQueue;
    @Value("${flow.stage.delivery.threads:16}")
    private int deliveryThreads;

    private ProcessingStage<Runnable> intakeStage;
    private ProcessingStage<Runnable> pipelineStage;
    private ProcessingStage<Runnable> deliveryStage;

    @PostConstruct
    public void init() {
        intakeStage = new ProcessingStage<>("intake", intakeQueue, intakeThreads, Runnable::run);
        pipelineStage = new ProcessingStage<>("pipeline", pipelineQueue, pipelineThreads, Runnable::run);
        deliveryStage = new ProcessingStage<>("delivery", deliveryQueue, deliveryThreads, Runnable::run);
        log.info("SEDA flow stages initialized: intake(q={},t={}), pipeline(q={},t={}), delivery(q={},t={})",
                intakeQueue, intakeThreads, pipelineQueue, pipelineThreads, deliveryQueue, deliveryThreads);
    }

    @PreDestroy
    public void shutdown() {
        intakeStage.shutdown();
        pipelineStage.shutdown();
        deliveryStage.shutdown();
    }

    public ProcessingStage<Runnable> intake() { return intakeStage; }
    public ProcessingStage<Runnable> pipeline() { return pipelineStage; }
    public ProcessingStage<Runnable> delivery() { return deliveryStage; }

    /** Aggregate metrics for all stages — for health endpoints. */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("intake", intakeStage.getStats());
        stats.put("pipeline", pipelineStage.getStats());
        stats.put("delivery", deliveryStage.getStats());
        return stats;
    }
}
