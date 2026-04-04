package com.filetransfer.shared.repository;

import com.filetransfer.shared.entity.ServerConfig;
import com.filetransfer.shared.enums.ServiceType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ServerConfigRepository extends JpaRepository<ServerConfig, UUID> {
    List<ServerConfig> findByServiceTypeAndActiveTrue(ServiceType type);
    List<ServerConfig> findByActiveTrue();
}
