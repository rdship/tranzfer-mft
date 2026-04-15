package com.filetransfer.shared.repository.security;

import com.filetransfer.shared.entity.security.ListenerSecurityPolicy;
import com.filetransfer.shared.enums.SecurityTier;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ListenerSecurityPolicyRepository extends JpaRepository<ListenerSecurityPolicy, UUID> {
    Optional<ListenerSecurityPolicy> findByServerInstanceIdAndActiveTrue(UUID serverInstanceId);
    Optional<ListenerSecurityPolicy> findByExternalDestinationIdAndActiveTrue(UUID externalDestinationId);
    List<ListenerSecurityPolicy> findByActiveTrue();
    List<ListenerSecurityPolicy> findBySecurityTierAndActiveTrue(SecurityTier tier);
}
