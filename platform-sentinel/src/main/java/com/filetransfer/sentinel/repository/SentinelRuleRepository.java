package com.filetransfer.sentinel.repository;

import com.filetransfer.sentinel.entity.SentinelRule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SentinelRuleRepository extends JpaRepository<SentinelRule, UUID> {

    List<SentinelRule> findByAnalyzerAndEnabledTrue(String analyzer);

    Optional<SentinelRule> findByName(String name);

    boolean existsByName(String name);
}
