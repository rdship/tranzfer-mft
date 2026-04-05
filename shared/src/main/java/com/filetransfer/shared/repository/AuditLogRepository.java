package com.filetransfer.shared.repository;

import com.filetransfer.shared.entity.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
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
}
