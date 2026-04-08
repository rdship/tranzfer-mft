package com.filetransfer.shared.cluster;

import com.filetransfer.shared.entity.ClusterNode;
import com.filetransfer.shared.entity.ServiceRegistration;
import com.filetransfer.shared.enums.ClusterCommunicationMode;
import com.filetransfer.shared.enums.ServiceType;
import com.filetransfer.shared.repository.ClusterNodeRepository;
import com.filetransfer.shared.repository.ServiceRegistrationRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Registers this service instance in the shared service_registrations table on startup,
 * sends a heartbeat every 30 s, and deactivates stale registrations.
 *
 * Include this bean via @ComponentScan("com.filetransfer.shared") in each service's Application class.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClusterRegistrationService {

    private final ServiceRegistrationRepository repository;
    private final ClusterNodeRepository clusterNodeRepository;
    private final ClusterContext clusterContext;

    @Value("${cluster.id:default-cluster}")
    private String clusterId;

    @Value("${cluster.host:localhost}")
    private String host;

    @Value("${cluster.service-type}")
    private ServiceType serviceType;

    @Value("${server.port:8080}")
    private int controlPort;

    @Value("${cluster.communication-mode:WITHIN_CLUSTER}")
    private String communicationModeStr;

    private String serviceInstanceId;

    @PostConstruct
    public void register() {
        serviceInstanceId = UUID.randomUUID().toString();

        clusterContext.setServiceInstanceId(serviceInstanceId);
        clusterContext.setClusterId(clusterId);

        ClusterCommunicationMode mode;
        try {
            mode = ClusterCommunicationMode.valueOf(communicationModeStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("Invalid cluster.communication-mode '{}', defaulting to WITHIN_CLUSTER", communicationModeStr);
            mode = ClusterCommunicationMode.WITHIN_CLUSTER;
        }
        clusterContext.setCommunicationMode(mode);

        // Register with retry — DB may still be accepting connections during cold start
        registerWithRetry(mode);
    }

    private void registerWithRetry(ClusterCommunicationMode mode) {
        int maxRetries = 5;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                doRegister(mode);
                return;
            } catch (Exception e) {
                if (attempt == maxRetries) {
                    log.error("Failed to register service after {} attempts: {}", maxRetries, e.getMessage());
                    return; // Don't block boot — heartbeat will retry registration
                }
                long backoff = attempt * 2000L;
                log.warn("Cluster registration attempt {}/{} failed (retrying in {}ms): {}",
                        attempt, maxRetries, backoff, e.getMessage());
                try { Thread.sleep(backoff); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    @Transactional
    public void doRegister(ClusterCommunicationMode mode) {
        ServiceRegistration reg = ServiceRegistration.builder()
                .serviceInstanceId(serviceInstanceId)
                .clusterId(clusterId)
                .serviceType(serviceType)
                .host(host)
                .controlPort(controlPort)
                .active(true)
                .lastHeartbeat(Instant.now())
                .build();

        repository.save(reg);

        ensureClusterNodeExists(mode);

        log.info("Registered service instance: id={} cluster={} type={} host={}:{} mode={}",
                serviceInstanceId, clusterId, serviceType, host, controlPort, mode);
    }

    private void ensureClusterNodeExists(ClusterCommunicationMode mode) {
        if (!clusterNodeRepository.existsByClusterId(clusterId)) {
            ClusterNode node = ClusterNode.builder()
                    .clusterId(clusterId)
                    .displayName(clusterId)
                    .description("Auto-registered by " + serviceType + " service")
                    .communicationMode(mode)
                    .build();
            clusterNodeRepository.save(node);
            log.info("Auto-registered cluster node: {}", clusterId);
        }
    }

    @Scheduled(fixedDelay = 30_000)
    @Transactional
    public void heartbeat() {
        repository.updateHeartbeat(serviceInstanceId, Instant.now());
        // deactivate any instances that haven't heartbeated in 2 minutes
        repository.deactivateStale(Instant.now().minus(2, ChronoUnit.MINUTES));

        // Sync communication mode from cluster_nodes table (picks up admin changes)
        clusterNodeRepository.findByClusterId(clusterId).ifPresent(node -> {
            if (node.getCommunicationMode() != clusterContext.getCommunicationMode()) {
                log.info("Communication mode synced from cluster_nodes: {} -> {}",
                        clusterContext.getCommunicationMode(), node.getCommunicationMode());
                clusterContext.setCommunicationMode(node.getCommunicationMode());
            }
        });
    }

    @PreDestroy
    @Transactional
    public void deregister() {
        repository.deactivate(serviceInstanceId);
        log.info("Deregistered service instance: {}", serviceInstanceId);
    }
}
