package com.filetransfer.keystore.repository;

import com.filetransfer.keystore.entity.ManagedKey;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ManagedKeyRepository extends JpaRepository<ManagedKey, UUID> {
    Optional<ManagedKey> findByAliasAndActiveTrue(String alias);
    Optional<ManagedKey> findByAlias(String alias);
    List<ManagedKey> findByKeyTypeAndActiveTrue(String keyType);
    List<ManagedKey> findByOwnerServiceAndActiveTrue(String ownerService);
    List<ManagedKey> findByPartnerAccountAndActiveTrue(String partnerAccount);
    List<ManagedKey> findByActiveTrueAndExpiresAtBefore(Instant date);
    List<ManagedKey> findByActiveTrue();
    boolean existsByAlias(String alias);
}
