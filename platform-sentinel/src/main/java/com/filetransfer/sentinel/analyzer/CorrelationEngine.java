package com.filetransfer.sentinel.analyzer;

import com.filetransfer.sentinel.entity.CorrelationGroup;
import com.filetransfer.sentinel.entity.SentinelFinding;
import com.filetransfer.sentinel.repository.CorrelationGroupRepository;
import com.filetransfer.sentinel.repository.SentinelFindingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class CorrelationEngine {

    private static final int WINDOW_MINUTES = 5;

    private final SentinelFindingRepository findingRepository;
    private final CorrelationGroupRepository groupRepository;

    @Scheduled(fixedDelay = 600000)
    @SchedulerLock(name = "sentinel_correlationEngine", lockAtLeastFor = "PT8M", lockAtMostFor = "PT15M")
    @Transactional
    public void correlate() {
        log.info("CorrelationEngine: starting correlation cycle");

        Instant cutoff = Instant.now().minus(30, ChronoUnit.MINUTES);
        List<SentinelFinding> openFindings = findingRepository
                .findByStatusAndCreatedAtAfterOrderByCreatedAtDesc("OPEN", cutoff);

        if (openFindings.size() < 2) {
            log.debug("CorrelationEngine: fewer than 2 open findings, skipping");
            return;
        }

        // Group findings that occurred within WINDOW_MINUTES of each other
        List<List<SentinelFinding>> groups = groupByTimeProximity(openFindings);

        int correlated = 0;
        for (List<SentinelFinding> group : groups) {
            if (group.size() < 2) continue;
            // Skip if all findings already belong to the same correlation group
            Set<UUID> existingGroups = group.stream()
                    .map(SentinelFinding::getCorrelationGroupId)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());
            if (existingGroups.size() == 1) continue;

            String title = buildGroupTitle(group);
            String rootCause = inferRootCause(group);

            CorrelationGroup cg = CorrelationGroup.builder()
                    .title(title)
                    .rootCause(rootCause)
                    .findingCount(group.size())
                    .build();
            cg = groupRepository.save(cg);

            for (SentinelFinding f : group) {
                f.setCorrelationGroupId(cg.getId());
                findingRepository.save(f);
            }
            correlated++;
        }

        log.info("CorrelationEngine: cycle complete, {} new correlation groups", correlated);
    }

    private List<List<SentinelFinding>> groupByTimeProximity(List<SentinelFinding> findings) {
        List<List<SentinelFinding>> groups = new ArrayList<>();
        Set<UUID> assigned = new HashSet<>();

        for (int i = 0; i < findings.size(); i++) {
            if (assigned.contains(findings.get(i).getId())) continue;

            List<SentinelFinding> group = new ArrayList<>();
            group.add(findings.get(i));
            assigned.add(findings.get(i).getId());

            Instant windowStart = findings.get(i).getCreatedAt().minus(WINDOW_MINUTES, ChronoUnit.MINUTES);
            Instant windowEnd = findings.get(i).getCreatedAt().plus(WINDOW_MINUTES, ChronoUnit.MINUTES);

            for (int j = i + 1; j < findings.size(); j++) {
                SentinelFinding candidate = findings.get(j);
                if (assigned.contains(candidate.getId())) continue;
                if (candidate.getCreatedAt().isAfter(windowStart) && candidate.getCreatedAt().isBefore(windowEnd)) {
                    group.add(candidate);
                    assigned.add(candidate.getId());
                }
            }

            groups.add(group);
        }

        return groups;
    }

    private String buildGroupTitle(List<SentinelFinding> group) {
        Set<String> analyzers = group.stream().map(SentinelFinding::getAnalyzer).collect(Collectors.toSet());
        Set<String> rules = group.stream().map(SentinelFinding::getRuleName).collect(Collectors.toSet());
        return String.format("Correlated: %d findings across %s (%s)",
                group.size(), String.join("+", analyzers), String.join(", ", rules));
    }

    private String inferRootCause(List<SentinelFinding> group) {
        Set<String> rules = group.stream().map(SentinelFinding::getRuleName).collect(Collectors.toSet());

        if (rules.contains("service_unhealthy") && rules.contains("failed_transfer_spike")) {
            String downService = group.stream()
                    .filter(f -> "service_unhealthy".equals(f.getRuleName()))
                    .map(SentinelFinding::getAffectedService)
                    .findFirst().orElse("unknown");
            return String.format("Service outage (%s) causing transfer failures and downstream impact", downService);
        }
        if (rules.contains("login_failure_spike") && rules.contains("account_lockout")) {
            return "Possible brute-force attack causing login failures and account lockouts";
        }
        if (rules.contains("error_rate_spike") && rules.contains("dlq_growth")) {
            return "Systematic processing errors causing elevated failure rates and DLQ accumulation";
        }
        if (rules.contains("integrity_mismatch") && rules.contains("quarantine_surge")) {
            return "Data integrity issues triggering quarantine and checksum failures";
        }

        return String.format("Multiple concurrent anomalies detected: %s", String.join(", ", rules));
    }
}
