package com.filetransfer.shared.repository.core;

import com.filetransfer.shared.entity.core.ServerInstance;
import com.filetransfer.shared.enums.Protocol;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ServerInstanceRepository extends JpaRepository<ServerInstance, UUID> {
    Optional<ServerInstance> findByInstanceId(String instanceId);
    List<ServerInstance> findByActiveTrue();
    boolean existsByInstanceId(String instanceId);

    // Protocol-specific queries
    List<ServerInstance> findByProtocol(Protocol protocol);
    List<ServerInstance> findByProtocolAndActiveTrue(Protocol protocol);

    /**
     * Ports already claimed by active listeners on the given host — used to
     * suggest free alternatives when the admin picks a conflicting port.
     */
    @Query("SELECT si.internalPort FROM ServerInstance si " +
           "WHERE si.internalHost = :host AND si.active = true " +
           "  AND si.internalPort BETWEEN :low AND :high")
    List<Integer> findUsedPortsInRange(@Param("host") String host,
                                       @Param("low") int low,
                                       @Param("high") int high);

    /** Active listener matching host+port, if any. */
    Optional<ServerInstance> findByInternalHostAndInternalPortAndActiveTrue(String host, int port);
}
