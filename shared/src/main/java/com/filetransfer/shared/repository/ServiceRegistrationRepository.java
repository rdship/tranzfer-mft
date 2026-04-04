package com.filetransfer.shared.repository;

import com.filetransfer.shared.entity.ServiceRegistration;
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
