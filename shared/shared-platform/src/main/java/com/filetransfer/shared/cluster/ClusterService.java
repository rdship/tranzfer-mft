package com.filetransfer.shared.cluster;

import com.filetransfer.shared.entity.core.ServiceRegistration;
import com.filetransfer.shared.enums.ClusterCommunicationMode;
import com.filetransfer.shared.enums.ServiceType;
import com.filetransfer.shared.repository.ServiceRegistrationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Central service for cluster-aware operations.
 *
 * Provides service discovery filtered by the current cluster communication mode:
 * - WITHIN_CLUSTER: only returns services in the same cluster as this instance
 * - CROSS_CLUSTER:  returns services from all clusters
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClusterService {

    private final ClusterContext clusterContext;
    private final ServiceRegistrationRepository registrationRepository;

    /**
     * Find active services of the given type, respecting the current communication mode.
     * This is the primary discovery method used by routing and inter-service communication.
     */
    @Transactional(readOnly = true)
    public List<ServiceRegistration> discoverServices(ServiceType serviceType) {
        ClusterCommunicationMode mode = clusterContext.getCommunicationMode();
        if (mode == ClusterCommunicationMode.WITHIN_CLUSTER) {
            return registrationRepository.findByServiceTypeAndClusterIdAndActiveTrue(
                    serviceType, clusterContext.getClusterId());
        }
        return registrationRepository.findByServiceTypeAndActiveTrue(serviceType);
    }

    /**
     * Find a single active service of the given type (first available),
     * respecting cluster communication mode. Returns empty if none found.
     */
    @Transactional(readOnly = true)
    public Optional<ServiceRegistration> discoverService(ServiceType serviceType) {
        return discoverServices(serviceType).stream().findFirst();
    }

    /**
     * Check if a given service registration is "local" to this instance.
     * In WITHIN_CLUSTER mode: local means same service instance.
     * In CROSS_CLUSTER mode: local means same service instance.
     * (The local/remote distinction is about the instance, not the cluster.)
     */
    public boolean isLocalService(ServiceRegistration service) {
        return service.getServiceInstanceId().equals(clusterContext.getServiceInstanceId());
    }

    /**
     * Check if a service registration belongs to the same cluster as this instance.
     */
    public boolean isSameCluster(ServiceRegistration service) {
        return service.getClusterId().equals(clusterContext.getClusterId());
    }

    /**
     * Get all distinct cluster IDs with active services.
     */
    @Transactional(readOnly = true)
    public List<String> listClusters() {
        return registrationRepository.findDistinctActiveClusterIds();
    }

    /**
     * Get all active services in a specific cluster.
     */
    @Transactional(readOnly = true)
    public List<ServiceRegistration> getServicesInCluster(String clusterId) {
        return registrationRepository.findByClusterIdAndActiveTrue(clusterId);
    }

    /**
     * Get all active services grouped by cluster ID.
     */
    @Transactional(readOnly = true)
    public Map<String, List<ServiceRegistration>> getServicesByCluster() {
        return registrationRepository.findByActiveTrue().stream()
                .collect(Collectors.groupingBy(ServiceRegistration::getClusterId,
                        LinkedHashMap::new, Collectors.toList()));
    }

    /**
     * Get a summary: cluster ID → count of active services.
     */
    @Transactional(readOnly = true)
    public Map<String, Long> getClusterServiceCounts() {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Object[] row : registrationRepository.countActiveByCluster()) {
            counts.put((String) row[0], (Long) row[1]);
        }
        return counts;
    }

    /**
     * Update the communication mode at runtime.
     * This only affects this service instance. To persist across restarts,
     * update the platform setting or application.yml.
     */
    public void setCommunicationMode(ClusterCommunicationMode mode) {
        ClusterCommunicationMode prev = clusterContext.getCommunicationMode();
        clusterContext.setCommunicationMode(mode);
        log.info("Cluster communication mode changed: {} -> {} (cluster={})",
                prev, mode, clusterContext.getClusterId());
    }

    public ClusterCommunicationMode getCommunicationMode() {
        return clusterContext.getCommunicationMode();
    }

    public String getClusterId() {
        return clusterContext.getClusterId();
    }

    public String getServiceInstanceId() {
        return clusterContext.getServiceInstanceId();
    }
}
