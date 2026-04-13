package com.filetransfer.shared.repository;

import com.filetransfer.shared.entity.transfer.DeliveryEndpoint;
import com.filetransfer.shared.enums.DeliveryProtocol;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DeliveryEndpointRepository extends JpaRepository<DeliveryEndpoint, UUID> {

    List<DeliveryEndpoint> findByActiveTrue();

    List<DeliveryEndpoint> findByProtocolAndActiveTrue(DeliveryProtocol protocol);

    Optional<DeliveryEndpoint> findByNameAndActiveTrue(String name);

    boolean existsByName(String name);

    @Query("SELECT d FROM DeliveryEndpoint d WHERE d.active = true AND d.tags LIKE %:tag%")
    List<DeliveryEndpoint> findByTagAndActiveTrue(String tag);

    List<DeliveryEndpoint> findByIdInAndActiveTrue(List<UUID> ids);

    @Query("SELECT COUNT(d) FROM DeliveryEndpoint d WHERE d.active = true")
    long countActive();

    @Query("SELECT d.protocol, COUNT(d) FROM DeliveryEndpoint d WHERE d.active = true GROUP BY d.protocol")
    List<Object[]> countActiveByProtocol();

    // Partner-scoped queries
    List<DeliveryEndpoint> findByPartnerId(UUID partnerId);

    long countByPartnerId(UUID partnerId);
}
