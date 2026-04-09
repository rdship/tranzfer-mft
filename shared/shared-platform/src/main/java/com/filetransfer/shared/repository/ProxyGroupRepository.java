package com.filetransfer.shared.repository;

import com.filetransfer.shared.entity.ProxyGroup;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProxyGroupRepository extends JpaRepository<ProxyGroup, UUID> {
    List<ProxyGroup> findByActiveTrueOrderByRoutingPriorityAscNameAsc();
    Optional<ProxyGroup> findByNameAndActiveTrue(String name);
    boolean existsByName(String name);
    List<ProxyGroup> findByTypeAndActiveTrue(String type);
}
