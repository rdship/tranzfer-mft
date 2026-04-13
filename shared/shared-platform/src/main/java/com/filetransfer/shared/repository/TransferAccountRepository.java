package com.filetransfer.shared.repository;

import com.filetransfer.shared.entity.core.TransferAccount;
import com.filetransfer.shared.enums.Protocol;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TransferAccountRepository extends JpaRepository<TransferAccount, UUID> {
    Optional<TransferAccount> findByUsernameAndProtocolAndActiveTrue(String username, Protocol protocol);
    Optional<TransferAccount> findByUsername(String username);
    boolean existsByUsername(String username);
    boolean existsByUsernameAndProtocol(String username, Protocol protocol);

    // Instance-aware lookup: find account assigned to a specific server instance OR unassigned (null)
    @org.springframework.data.jpa.repository.Query(
        "SELECT a FROM TransferAccount a WHERE a.username = :username AND a.protocol = :protocol " +
        "AND a.active = true AND (a.serverInstance = :instance OR a.serverInstance IS NULL)")
    Optional<TransferAccount> findByUsernameAndProtocolAndInstance(
        @org.springframework.data.repository.query.Param("username") String username,
        @org.springframework.data.repository.query.Param("protocol") Protocol protocol,
        @org.springframework.data.repository.query.Param("instance") String instance);

    // Find the specific server instance for a user (used by gateway routing)
    Optional<TransferAccount> findByUsernameAndProtocolAndActiveTrueAndServerInstance(
        String username, Protocol protocol, String serverInstance);

    // Partner-scoped queries
    List<TransferAccount> findByPartnerId(UUID partnerId);

    long countByPartnerId(UUID partnerId);

    // User-scoped queries (for GDPR deletion)
    List<TransferAccount> findByUserId(UUID userId);
}
