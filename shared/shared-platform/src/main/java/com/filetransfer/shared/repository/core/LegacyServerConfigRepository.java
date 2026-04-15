package com.filetransfer.shared.repository.core;

import com.filetransfer.shared.entity.core.LegacyServerConfig;
import com.filetransfer.shared.enums.Protocol;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface LegacyServerConfigRepository extends JpaRepository<LegacyServerConfig, UUID> {
    List<LegacyServerConfig> findByProtocolAndActiveTrue(Protocol protocol);
}
