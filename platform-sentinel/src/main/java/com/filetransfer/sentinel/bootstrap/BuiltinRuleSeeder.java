package com.filetransfer.sentinel.bootstrap;

import com.filetransfer.sentinel.entity.SentinelRule;
import com.filetransfer.sentinel.repository.SentinelRuleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Inline seed for built-in Sentinel rules whose Flyway migrations are at risk
 * of being silently skipped because platform-sentinel's migration numbers
 * collide with shared-platform's in the shared {@code flyway_schema_history}
 * table. Until the cross-module Flyway range is reorganized, rules declared
 * here are guaranteed to exist after boot.
 *
 * <p>Idempotent — uses {@code INSERT ... ON CONFLICT DO NOTHING} semantics
 * via findByName + skip-if-present. Safe to run on every pod startup.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BuiltinRuleSeeder {

    private final SentinelRuleRepository ruleRepository;

    /**
     * Runs on {@code ApplicationReadyEvent} instead of {@code @PostConstruct}
     * (R97): the seed is idempotent and no caller depends on the rule existing
     * before Spring reports "Started". Keeps context refresh fast by deferring
     * this DB round-trip off the boot critical path.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void seed() {
        seedIfMissing(SentinelRule.builder()
                .name("listener_bind_failed")
                .analyzer("PERFORMANCE")
                .severity("HIGH")
                .cooldownMinutes(15)
                .enabled(true)
                .builtin(true)
                .description("Active ServerInstance listeners that failed to bind their configured port. "
                        + "Use POST /api/servers/{id}/rebind or GET /api/servers/port-suggestions.")
                .build());
    }

    private void seedIfMissing(SentinelRule candidate) {
        ruleRepository.findByName(candidate.getName()).ifPresentOrElse(
                existing -> log.debug("Built-in Sentinel rule '{}' already present", candidate.getName()),
                () -> {
                    ruleRepository.save(candidate);
                    log.info("Seeded built-in Sentinel rule: {}", candidate.getName());
                }
        );
    }
}
