package com.filetransfer.shared.repository.core;

import com.filetransfer.shared.entity.core.ServerInstance;
import com.filetransfer.shared.enums.Protocol;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
