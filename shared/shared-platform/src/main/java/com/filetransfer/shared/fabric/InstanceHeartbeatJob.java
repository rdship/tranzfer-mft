package com.filetransfer.shared.fabric;

import com.filetransfer.fabric.FabricClient;
import com.filetransfer.fabric.config.FabricProperties;
import com.filetransfer.shared.entity.transfer.FabricInstance;
import com.filetransfer.shared.repository.transfer.FabricCheckpointRepository;
import com.filetransfer.shared.repository.transfer.FabricInstanceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.PostConstruct;
import java.net.InetAddress;
import java.time.Instant;
import java.util.UUID;

/**
 * Each pod writes its own row to fabric_instances every 30 seconds.
 * Used by:
 * - FabricObservabilityController /api/fabric/instances
 * - Dead-pod detection (instances whose last_heartbeat > 2min old are considered dead)
 *
 * This is intentionally per-instance - no ShedLock. Every pod heartbeats itself.
 *
 * <p>R117 (AOT safety): class-level {@code @ConditionalOnProperty(flow.rules.enabled)}
 * removed — evaluated at AOT build time, so a build without the property set
 * would silently exclude this bean regardless of runtime env vars. Now
 * unconditionally registered; {@link #heartbeat()} checks {@code flowRulesEnabled}
 * at each tick and early-returns when disabled. See {@code docs/AOT-SAFETY.md}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class InstanceHeartbeatJob {

    private final FabricInstanceRepository instanceRepo;
    private final FabricCheckpointRepository checkpointRepo;
    private final FabricProperties properties;
    private final FabricClient fabricClient;

    @Value("${spring.application.name:unknown-service}")
    private String serviceName;

    /** R117: runtime gate (replaces the old class-level @ConditionalOnProperty). */
    @Value("${flow.rules.enabled:false}")
    private boolean flowRulesEnabled;

    private String instanceId;
    private String host;
    private Instant startedAt;

    @PostConstruct
    void init() {
        try {
            this.host = System.getenv().getOrDefault("HOSTNAME", InetAddress.getLocalHost().getHostName());
        } catch (Exception e) {
            this.host = "unknown-" + UUID.randomUUID().toString().substring(0, 8);
        }
        this.instanceId = serviceName + "-" + host;
        this.startedAt = Instant.now();
        log.info("[InstanceHeartbeat] Initialized instance_id={} service={} host={}",
            instanceId, serviceName, host);
    }

    @Scheduled(fixedDelay = 30_000, initialDelay = 5_000)
    @Transactional
    public void heartbeat() {
        if (!flowRulesEnabled) return;
        if (!properties.isEnabled()) return;

        try {
            FabricInstance existing = instanceRepo.findById(instanceId).orElse(null);

            int inFlight = 0;
            try {
                inFlight = checkpointRepo.findByProcessingInstanceAndStatus(instanceId, "IN_PROGRESS").size();
            } catch (Exception e) {
                // non-critical
            }

            String status = fabricClient.isDistributed() ? "HEALTHY" : "DEGRADED";

            FabricInstance inst = existing != null ? existing : FabricInstance.builder()
                .instanceId(instanceId)
                .serviceName(serviceName)
                .host(host)
                .startedAt(startedAt)
                .lastHeartbeat(Instant.now())
                .status(status)
                .inFlightCount(inFlight)
                .build();

            inst.setLastHeartbeat(Instant.now());
            inst.setStatus(status);
            inst.setInFlightCount(inFlight);

            instanceRepo.save(inst);
        } catch (Exception e) {
            log.debug("[InstanceHeartbeat] Failed to write heartbeat: {}", e.getMessage());
        }
    }
}
