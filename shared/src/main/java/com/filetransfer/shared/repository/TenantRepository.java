package com.filetransfer.shared.repository;

import com.filetransfer.shared.entity.Tenant;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface TenantRepository extends JpaRepository<Tenant, UUID> {
    Optional<Tenant> findBySlugAndActiveTrue(String slug);
    Optional<Tenant> findByCustomDomainAndActiveTrue(String domain);
    boolean existsBySlug(String slug);
}
