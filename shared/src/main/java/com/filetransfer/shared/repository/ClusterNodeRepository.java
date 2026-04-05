package com.filetransfer.shared.repository;

import com.filetransfer.shared.entity.ClusterNode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ClusterNodeRepository extends JpaRepository<ClusterNode, UUID> {

    Optional<ClusterNode> findByClusterId(String clusterId);

    List<ClusterNode> findByActiveTrue();

    boolean existsByClusterId(String clusterId);
}
