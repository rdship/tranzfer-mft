package com.filetransfer.shared.cluster;

import com.filetransfer.shared.entity.ServiceRegistration;
import com.filetransfer.shared.enums.ServiceType;
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
    private final ClusterContext clusterContext;

    @Value("${cluster.id:default-cluster}")
    private String clusterId;

    @Value("${cluster.host:localhost}")
    private String host;

    @Value("${cluster.service-type}")
    private ServiceType serviceType;

    @Value("${server.port:8080}")
    private int controlPort;

    private String serviceInstanceId;

    @PostConstruct
    @Transactional
    public void register() {
        serviceInstanceId = UUID.randomUUID().toString();

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

        clusterContext.setServiceInstanceId(serviceInstanceId);
        clusterContext.setClusterId(clusterId);

        log.info("Registered service instance: id={} cluster={} type={} host={}:{}",
                serviceInstanceId, clusterId, serviceType, host, controlPort);
    }

    @Scheduled(fixedDelay = 30_000)
    @Transactional
    public void heartbeat() {
        repository.updateHeartbeat(serviceInstanceId, Instant.now());
        // deactivate any instances that haven't heartbeated in 2 minutes
        repository.deactivateStale(Instant.now().minus(2, ChronoUnit.MINUTES));
    }

    @PreDestroy
    @Transactional
    public void deregister() {
        repository.deactivate(serviceInstanceId);
        log.info("Deregistered service instance: {}", serviceInstanceId);
    }
}
