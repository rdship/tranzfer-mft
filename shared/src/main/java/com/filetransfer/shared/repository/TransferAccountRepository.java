package com.filetransfer.shared.repository;

import com.filetransfer.shared.entity.TransferAccount;
import com.filetransfer.shared.enums.Protocol;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface TransferAccountRepository extends JpaRepository<TransferAccount, UUID> {
    Optional<TransferAccount> findByUsernameAndProtocolAndActiveTrue(String username, Protocol protocol);
    boolean existsByUsername(String username);
    boolean existsByUsernameAndProtocol(String username, Protocol protocol);
}
