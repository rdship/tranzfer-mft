package com.filetransfer.shared.repository;

import com.filetransfer.shared.entity.core.MigrationEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface MigrationEventRepository extends JpaRepository<MigrationEvent, UUID> {
    List<MigrationEvent> findByPartnerIdOrderByCreatedAtDesc(UUID partnerId);
    List<MigrationEvent> findByCreatedAtAfterOrderByCreatedAtDesc(Instant since);
    long countByEventType(String eventType);
}
