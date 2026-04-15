package com.filetransfer.shared.repository.security;

import com.filetransfer.shared.entity.security.DlpPolicy;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DlpPolicyRepository extends JpaRepository<DlpPolicy, UUID> {

    List<DlpPolicy> findByActiveTrueOrderByCreatedAtDesc();

    Optional<DlpPolicy> findByName(String name);

    List<DlpPolicy> findAllByOrderByCreatedAtDesc();
}
