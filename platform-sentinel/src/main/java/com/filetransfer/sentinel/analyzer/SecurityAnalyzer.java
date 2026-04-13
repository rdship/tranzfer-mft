package com.filetransfer.sentinel.analyzer;

import com.filetransfer.sentinel.collector.*;
import com.filetransfer.sentinel.entity.SentinelFinding;
import com.filetransfer.sentinel.entity.SentinelRule;
import com.filetransfer.sentinel.repository.SentinelFindingRepository;
import com.filetransfer.sentinel.repository.SentinelRuleRepository;
import com.filetransfer.shared.entity.core.AuditLog;
import com.filetransfer.shared.entity.transfer.FileTransferRecord;
import com.filetransfer.shared.entity.security.LoginAttempt;
import com.filetransfer.shared.repository.LoginAttemptRepository;
import com.filetransfer.shared.repository.QuarantineRecordRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class SecurityAnalyzer {

    private static final String ANALYZER = "SECURITY";

    private final SentinelRuleRepository ruleRepository;
    private final SentinelFindingRepository findingRepository;
    private final AuditCollector auditCollector;
    private final TransferCollector transferCollector;
    private final SecurityCollector securityCollector;
    private final DlqCollector dlqCollector;
    private final LoginAttemptRepository loginAttemptRepository;
    private final QuarantineRecordRepository quarantineRepository;
    private final ObjectMapper objectMapper;

    @Scheduled(fixedDelay = 300000)
    @SchedulerLock(name = "sentinel_securityAnalyzer", lockAtLeastFor = "PT4M", lockAtMostFor = "PT9M")
    @Transactional
    public void analyze() {
        log.info("SecurityAnalyzer: starting analysis cycle");

        // Collect fresh data
        auditCollector.collect(60);
        transferCollector.collect(60);
        securityCollector.collect();
        dlqCollector.collect();

        List<SentinelRule> rules = ruleRepository.findByAnalyzerAndEnabledTrue(ANALYZER);
        int findings = 0;

        for (SentinelRule rule : rules) {
            if (rule.isInCooldown()) continue;
            try {
                SentinelFinding finding = evaluate(rule);
                if (finding != null) {
                    findingRepository.save(finding);
                    rule.setLastTriggered(Instant.now());
                    ruleRepository.save(rule);
                    findings++;
                }
            } catch (Exception e) {
                log.warn("SecurityAnalyzer: rule '{}' failed: {}", rule.getName(), e.getMessage());
            }
        }

        log.info("SecurityAnalyzer: cycle complete, {} new findings", findings);
    }

    private SentinelFinding evaluate(SentinelRule rule) {
        return switch (rule.getName()) {
            case "login_failure_spike" -> checkLoginFailures(rule);
            case "account_lockout" -> checkAccountLockouts(rule);
            case "config_change_burst" -> checkConfigChanges(rule);
            case "failed_transfer_spike" -> checkFailedTransfers(rule);
            case "integrity_mismatch" -> checkIntegrityMismatches(rule);
            case "quarantine_surge" -> checkQuarantineSurge(rule);
            case "dlq_growth" -> checkDlqGrowth(rule);
            case "screening_hit" -> checkScreeningHits(rule);
            default -> null;
        };
    }

    private SentinelFinding checkLoginFailures(SentinelRule rule) {
        long count = auditCollector.countFailedLogins();
        double threshold = rule.getThresholdValue() != null ? rule.getThresholdValue() : 10;
        if (count <= threshold) return null;

        return buildFinding(rule,
                String.format("Login failure spike: %d failures in last %d min", count, rule.getWindowMinutes()),
                String.format("Detected %d failed login attempts in the last %d minutes, exceeding threshold of %.0f. " +
                        "This may indicate a brute-force attack or misconfigured client.", count, rule.getWindowMinutes(), threshold),
                evidence("failedLogins", count, "threshold", threshold));
    }

    private SentinelFinding checkAccountLockouts(SentinelRule rule) {
        List<LoginAttempt> locked = loginAttemptRepository.findByLockedUntilAfter(Instant.now());
        if (locked.isEmpty()) return null;

        List<String> usernames = locked.stream().map(LoginAttempt::getUsername).toList();
        return buildFinding(rule,
                String.format("Account lockout: %d accounts locked", locked.size()),
                String.format("The following accounts are currently locked out: %s. Review for potential brute-force attempts.",
                        String.join(", ", usernames)),
                evidence("lockedAccounts", usernames, "count", locked.size()));
    }

    private SentinelFinding checkConfigChanges(SentinelRule rule) {
        long count = auditCollector.countConfigChanges();
        double threshold = rule.getThresholdValue() != null ? rule.getThresholdValue() : 5;
        if (count <= threshold) return null;

        return buildFinding(rule,
                String.format("Config change burst: %d changes in last %d min", count, rule.getWindowMinutes()),
                String.format("Detected %d configuration changes in %d minutes. Rapid config changes may indicate " +
                        "unauthorized access or automation errors.", count, rule.getWindowMinutes()),
                evidence("configChanges", count, "threshold", threshold));
    }

    private SentinelFinding checkFailedTransfers(SentinelRule rule) {
        double rate = transferCollector.getFailureRate();
        double threshold = rule.getThresholdValue() != null ? rule.getThresholdValue() : 20;
        if (rate <= threshold || transferCollector.countTotal() < 5) return null;

        return buildFinding(rule,
                String.format("Transfer failure spike: %.1f%% failure rate", rate),
                String.format("Transfer failure rate is %.1f%% (%d failed / %d total) in the last %d minutes, " +
                        "exceeding threshold of %.0f%%.", rate, transferCollector.countFailed(),
                        transferCollector.countTotal(), rule.getWindowMinutes(), threshold),
                evidence("failureRate", rate, "failed", transferCollector.countFailed(),
                        "total", transferCollector.countTotal(), "threshold", threshold));
    }

    private SentinelFinding checkIntegrityMismatches(SentinelRule rule) {
        List<FileTransferRecord> mismatches = transferCollector.getIntegrityMismatches();
        if (mismatches.isEmpty()) return null;

        FileTransferRecord first = mismatches.get(0);
        return buildFinding(rule,
                String.format("Integrity mismatch: %d files with checksum mismatch", mismatches.size()),
                String.format("File integrity violation detected. %d file(s) have source/destination checksum mismatches. " +
                        "First affected: %s (track: %s). Source: %s, Dest: %s",
                        mismatches.size(), first.getOriginalFilename(), first.getTrackId(),
                        truncate(first.getSourceChecksum()), truncate(first.getDestinationChecksum())),
                evidence("count", mismatches.size(), "firstTrackId", first.getTrackId(),
                        "firstFile", first.getOriginalFilename()),
                first.getTrackId());
    }

    private SentinelFinding checkQuarantineSurge(SentinelRule rule) {
        long count;
        try {
            count = quarantineRepository.countByStatus("QUARANTINED");
        } catch (Exception e) {
            return null;
        }
        double threshold = rule.getThresholdValue() != null ? rule.getThresholdValue() : 3;
        if (count <= threshold) return null;

        return buildFinding(rule,
                String.format("Quarantine surge: %d files quarantined", count),
                String.format("Detected %d quarantined files (threshold: %.0f). Multiple quarantine events may indicate " +
                        "a malware campaign or overly aggressive DLP rules.", count, threshold),
                evidence("quarantined", count, "threshold", threshold));
    }

    private SentinelFinding checkDlqGrowth(SentinelRule rule) {
        long count = dlqCollector.getPendingCount();
        double threshold = rule.getThresholdValue() != null ? rule.getThresholdValue() : 10;
        if (count <= threshold) return null;

        return buildFinding(rule,
                String.format("DLQ growth: %d pending messages", count),
                String.format("Dead letter queue has %d pending messages (threshold: %.0f). " +
                        "Growing DLQ indicates message processing failures that need investigation.", count, threshold),
                evidence("pendingDlq", count, "threshold", threshold));
    }

    private SentinelFinding checkScreeningHits(SentinelRule rule) {
        long hits = securityCollector.getScreeningHits();
        if (hits <= 0) return null;

        return buildFinding(rule,
                String.format("Screening hit: %d OFAC/sanctions matches", hits),
                String.format("OFAC/sanctions screening detected %d hit(s). These require immediate compliance review.", hits),
                evidence("screeningHits", hits));
    }

    // --- helpers ---

    private SentinelFinding buildFinding(SentinelRule rule, String title, String description, String evidence) {
        return buildFinding(rule, title, description, evidence, null);
    }

    private SentinelFinding buildFinding(SentinelRule rule, String title, String description, String evidence, String trackId) {
        // Dedup check
        if (findingRepository.existsByAnalyzerAndRuleNameAndAffectedServiceAndTrackId(
                ANALYZER, rule.getName(), null, trackId)) {
            return null;
        }

        return SentinelFinding.builder()
                .analyzer(ANALYZER)
                .ruleName(rule.getName())
                .severity(rule.getSeverity())
                .title(title)
                .description(description)
                .evidence(evidence)
                .trackId(trackId)
                .build();
    }

    private String evidence(Object... kvPairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < kvPairs.length - 1; i += 2) {
            map.put(String.valueOf(kvPairs[i]), kvPairs[i + 1]);
        }
        map.put("analyzedAt", Instant.now().toString());
        try {
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            return "{}";
        }
    }

    private String truncate(String s) {
        return s != null && s.length() > 12 ? s.substring(0, 12) + "..." : s;
    }
}
