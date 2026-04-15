package com.filetransfer.shared.repository.core;

import com.filetransfer.shared.entity.core.ServerAccountAssignment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ServerAccountAssignmentRepository extends JpaRepository<ServerAccountAssignment, UUID> {

    /** All (enabled or not) assignments for a server — for the admin view. */
    @Query("SELECT a FROM ServerAccountAssignment a JOIN FETCH a.transferAccount WHERE a.serverInstance.id = :serverId ORDER BY a.createdAt DESC")
    List<ServerAccountAssignment> findByServerInstanceId(@Param("serverId") UUID serverId);

    /** Active assignments only — used by SFTP/FTP auth to check access. */
    @Query("SELECT a FROM ServerAccountAssignment a JOIN FETCH a.transferAccount WHERE a.serverInstance.id = :serverId AND a.enabled = true")
    List<ServerAccountAssignment> findEnabledByServerInstanceId(@Param("serverId") UUID serverId);

    /** All servers a given account is assigned to — for the account management view. */
    @Query("SELECT a FROM ServerAccountAssignment a JOIN FETCH a.serverInstance WHERE a.transferAccount.id = :accountId ORDER BY a.serverInstance.name")
    List<ServerAccountAssignment> findByTransferAccountId(@Param("accountId") UUID accountId);

    /** Specific assignment between one server and one account. */
    @Query("SELECT a FROM ServerAccountAssignment a WHERE a.serverInstance.id = :serverId AND a.transferAccount.id = :accountId")
    Optional<ServerAccountAssignment> findByServerAndAccount(@Param("serverId") UUID serverId, @Param("accountId") UUID accountId);

    /** Check access without loading the full entity — used in hot auth path. */
    @Query("SELECT COUNT(a) > 0 FROM ServerAccountAssignment a WHERE a.serverInstance.instanceId = :instanceId AND a.transferAccount.username = :username AND a.enabled = true")
    boolean isAccountAuthorized(@Param("instanceId") String instanceId, @Param("username") String username);

    /** Count of assigned (enabled) accounts per server — for the server list summary. */
    @Query("SELECT a.serverInstance.id, COUNT(a) FROM ServerAccountAssignment a WHERE a.enabled = true GROUP BY a.serverInstance.id")
    List<Object[]> countEnabledPerServer();

    boolean existsByServerInstanceIdAndTransferAccountId(UUID serverId, UUID accountId);
}
