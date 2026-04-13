package com.filetransfer.shared.repository;

import com.filetransfer.shared.entity.integration.NotificationRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface NotificationRuleRepository extends JpaRepository<NotificationRule, UUID> {

    Optional<NotificationRule> findByName(String name);

    List<NotificationRule> findByEnabledTrue();

    List<NotificationRule> findByChannel(String channel);

    List<NotificationRule> findByEventTypePatternAndEnabledTrue(String eventTypePattern);
}
