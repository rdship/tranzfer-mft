package com.filetransfer.shared.repository;

import com.filetransfer.shared.entity.core.ServiceRegistration;
import com.filetransfer.shared.enums.ServiceType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ServiceRegistrationRepository extends JpaRepository<ServiceRegistration, UUID> {

    Optional<ServiceRegistration> findByServiceInstanceId(String serviceInstanceId);

    List<ServiceRegistration> findByServiceTypeAndActiveTrue(ServiceType serviceType);

    /** Find active services of a given type within a specific cluster */
    List<ServiceRegistration> findByServiceTypeAndClusterIdAndActiveTrue(ServiceType serviceType, String clusterId);

    /** Find all active services in a specific cluster */
    List<ServiceRegistration> findByClusterIdAndActiveTrue(String clusterId);

    /** Find all active services across all clusters */
    List<ServiceRegistration> findByActiveTrue();

    /** Get distinct cluster IDs from active registrations */
    @Query("SELECT DISTINCT s.clusterId FROM ServiceRegistration s WHERE s.active = true ORDER BY s.clusterId")
    List<String> findDistinctActiveClusterIds();

    /** Count active services per cluster */
    @Query("SELECT s.clusterId, COUNT(s) FROM ServiceRegistration s WHERE s.active = true GROUP BY s.clusterId")
    List<Object[]> countActiveByCluster();

    @Modifying
    @Query("UPDATE ServiceRegistration s SET s.lastHeartbeat = :ts WHERE s.serviceInstanceId = :id")
    void updateHeartbeat(String id, Instant ts);

    @Modifying
    @Query("UPDATE ServiceRegistration s SET s.active = false WHERE s.serviceInstanceId = :id")
    void deactivate(String id);

    @Modifying
    @Query("UPDATE ServiceRegistration s SET s.active = false WHERE s.lastHeartbeat < :staleThreshold")
    void deactivateStale(Instant staleThreshold);
}
