package com.filetransfer.shared.repository.security;

import com.filetransfer.shared.entity.security.TotpConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface TotpConfigRepository extends JpaRepository<TotpConfig, UUID> {
    Optional<TotpConfig> findByUsername(String username);
}
