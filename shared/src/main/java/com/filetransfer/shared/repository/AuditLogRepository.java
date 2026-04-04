package com.filetransfer.shared.repository;

import com.filetransfer.shared.entity.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {
    List<AuditLog> findByAccountIdOrderByTimestampDesc(UUID accountId);
}
