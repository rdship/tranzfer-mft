package com.filetransfer.shared.repository;

import com.filetransfer.shared.entity.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {

    @Query("SELECT a FROM AuditLog a " +
           "LEFT JOIN FETCH a.account " +
           "WHERE a.account.id = :accountId " +
           "ORDER BY a.timestamp DESC")
    List<AuditLog> findByAccountIdOrderByTimestampDesc(@Param("accountId") UUID accountId);

    /** GDPR: anonymize principal in audit logs for a deleted user */
    @Modifying
    @Query("UPDATE AuditLog a SET a.principal = :anonymized WHERE a.principal = :principal")
    int anonymizePrincipal(@Param("principal") String principal,
                           @Param("anonymized") String anonymized);

    /** GDPR: detach audit logs from a transfer account (set FK to null) */
    @Modifying
    @Query("UPDATE AuditLog a SET a.account = null WHERE a.account.id = :accountId")
    int detachFromAccount(@Param("accountId") UUID accountId);
}
