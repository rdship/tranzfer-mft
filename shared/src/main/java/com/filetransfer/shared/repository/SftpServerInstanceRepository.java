package com.filetransfer.shared.repository;

import com.filetransfer.shared.entity.SftpServerInstance;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SftpServerInstanceRepository extends JpaRepository<SftpServerInstance, UUID> {
    Optional<SftpServerInstance> findByInstanceId(String instanceId);
    List<SftpServerInstance> findByActiveTrue();
    boolean existsByInstanceId(String instanceId);
}
