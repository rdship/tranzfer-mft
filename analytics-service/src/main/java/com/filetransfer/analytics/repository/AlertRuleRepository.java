package com.filetransfer.analytics.repository;

import com.filetransfer.analytics.entity.AlertRule;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface AlertRuleRepository extends JpaRepository<AlertRule, UUID> {
    List<AlertRule> findByEnabledTrue();
    List<AlertRule> findByServiceTypeAndEnabledTrue(String serviceType);
}
