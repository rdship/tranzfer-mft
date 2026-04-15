package com.filetransfer.shared.repository.integration;

import com.filetransfer.shared.entity.integration.WebhookConnector;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface WebhookConnectorRepository extends JpaRepository<WebhookConnector, UUID> {
    List<WebhookConnector> findByActiveTrue();

    /**
     * Atomic counter increment — updates only lastTriggered + totalNotifications.
     * Replaces the SELECT + full-entity UPDATE cycle of repository.save().
     */
    @Transactional
    @Modifying
    @Query("UPDATE WebhookConnector c SET c.lastTriggered = :now, c.totalNotifications = c.totalNotifications + 1 WHERE c.id = :id")
    void incrementNotificationCount(@Param("id") UUID id, @Param("now") Instant now);
}
