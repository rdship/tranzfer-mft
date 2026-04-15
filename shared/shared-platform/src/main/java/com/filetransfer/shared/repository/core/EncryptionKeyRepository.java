package com.filetransfer.shared.repository.core;

import com.filetransfer.shared.entity.core.EncryptionKey;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EncryptionKeyRepository extends JpaRepository<EncryptionKey, UUID> {
    List<EncryptionKey> findByAccountIdAndActiveTrue(UUID accountId);
    Optional<EncryptionKey> findByIdAndActiveTrue(UUID id);
}
