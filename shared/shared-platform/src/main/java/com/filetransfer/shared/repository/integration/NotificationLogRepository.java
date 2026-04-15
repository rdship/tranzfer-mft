package com.filetransfer.shared.repository.integration;

import com.filetransfer.shared.entity.integration.NotificationLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface NotificationLogRepository extends JpaRepository<NotificationLog, UUID> {

    Page<NotificationLog> findAllByOrderBySentAtDesc(Pageable pageable);

    List<NotificationLog> findByEventType(String eventType);

    List<NotificationLog> findByStatus(String status);

    List<NotificationLog> findByChannel(String channel);

    List<NotificationLog> findByTrackId(String trackId);

    List<NotificationLog> findByStatusAndRetryCountLessThan(String status, int maxRetries);

    List<NotificationLog> findTop100ByOrderBySentAtDesc();

    long countByStatusAndSentAtAfter(String status, Instant after);
}
