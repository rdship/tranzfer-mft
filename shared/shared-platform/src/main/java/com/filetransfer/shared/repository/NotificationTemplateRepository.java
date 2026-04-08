package com.filetransfer.shared.repository;

import com.filetransfer.shared.entity.NotificationTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface NotificationTemplateRepository extends JpaRepository<NotificationTemplate, UUID> {

    Optional<NotificationTemplate> findByName(String name);

    List<NotificationTemplate> findByEventTypeAndChannelAndActiveTrue(String eventType, String channel);

    List<NotificationTemplate> findByEventTypeAndActiveTrue(String eventType);

    List<NotificationTemplate> findByActiveTrue();

    List<NotificationTemplate> findByChannel(String channel);
}
