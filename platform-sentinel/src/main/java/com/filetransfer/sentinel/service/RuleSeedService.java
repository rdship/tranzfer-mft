package com.filetransfer.sentinel.service;

import com.filetransfer.sentinel.entity.SentinelRule;
import com.filetransfer.sentinel.repository.SentinelRuleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class RuleSeedService {

    private final SentinelRuleRepository ruleRepository;

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void seedDefaultRules() {
        List<SentinelRule> defaults = List.of(
                // Security rules
                rule("SECURITY", "login_failure_spike", "Login failures exceed threshold in window",
                        "HIGH", 10.0, 60, 30),
                rule("SECURITY", "account_lockout", "Account locked out due to repeated failures",
                        "MEDIUM", 1.0, 60, 60),
                rule("SECURITY", "config_change_burst", "Rapid configuration changes detected",
                        "MEDIUM", 5.0, 15, 30),
                rule("SECURITY", "failed_transfer_spike", "Transfer failure rate exceeds threshold",
                        "HIGH", 20.0, 60, 30),
                rule("SECURITY", "integrity_mismatch", "File checksum mismatch between source and destination",
                        "CRITICAL", 1.0, 60, 15),
                rule("SECURITY", "quarantine_surge", "Multiple files quarantined in short window",
                        "HIGH", 3.0, 60, 30),
                rule("SECURITY", "dlq_growth", "Dead letter queue accumulating unprocessed messages",
                        "MEDIUM", 10.0, 60, 60),
                rule("SECURITY", "screening_hit", "OFAC/sanctions screening match detected",
                        "CRITICAL", 1.0, 60, 15),

                // Performance rules
                rule("PERFORMANCE", "latency_degradation", "P95 latency increased significantly vs baseline",
                        "HIGH", 50.0, 60, 30),
                rule("PERFORMANCE", "error_rate_spike", "Transfer error rate exceeds threshold",
                        "HIGH", 10.0, 60, 30),
                rule("PERFORMANCE", "throughput_drop", "Transfer volume dropped significantly vs baseline",
                        "MEDIUM", 60.0, 60, 60),
                rule("PERFORMANCE", "service_unhealthy", "Service health check failed",
                        "CRITICAL", 1.0, 5, 10),
                rule("PERFORMANCE", "disk_usage_high", "Disk usage exceeds threshold",
                        "HIGH", 85.0, 60, 60),
                rule("PERFORMANCE", "connection_saturation", "Active connections near maximum capacity",
                        "MEDIUM", 80.0, 60, 30)
        );

        int seeded = 0;
        for (SentinelRule rule : defaults) {
            if (!ruleRepository.existsByName(rule.getName())) {
                ruleRepository.save(rule);
                seeded++;
            }
        }

        if (seeded > 0) {
            log.info("RuleSeedService: seeded {} default rules", seeded);
        } else {
            log.debug("RuleSeedService: all {} rules already exist", defaults.size());
        }
    }

    private SentinelRule rule(String analyzer, String name, String description,
                             String severity, double threshold, int window, int cooldown) {
        return SentinelRule.builder()
                .analyzer(analyzer)
                .name(name)
                .description(description)
                .severity(severity)
                .thresholdValue(threshold)
                .windowMinutes(window)
                .cooldownMinutes(cooldown)
                .build();
    }
}
