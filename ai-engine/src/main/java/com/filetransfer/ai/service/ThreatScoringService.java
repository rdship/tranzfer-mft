package com.filetransfer.ai.service;

import com.filetransfer.shared.entity.core.AuditLog;
import com.filetransfer.shared.repository.AuditLogRepository;
import jakarta.annotation.PostConstruct;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AI Phase 2: Real-time security threat scoring per operation.
 * Combines multiple signals into a 0-100 risk score.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ThreatScoringService {

    private final AuditLogRepository auditLogRepository;

    // Track known IPs per account — ConcurrentHashMap survives concurrent access;
    // warmed from recent audit logs on startup so IPs survive restarts
    private final Map<String, Set<String>> knownIps = new ConcurrentHashMap<>();

    @org.springframework.scheduling.annotation.Async
    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void warmIpCache() {
        try {
            List<AuditLog> recent = auditLogRepository.findAll(
                    PageRequest.of(0, 500, Sort.by(Sort.Direction.DESC, "timestamp"))).getContent();
            for (AuditLog entry : recent) {
                if (entry.getPrincipal() != null && entry.getIpAddress() != null) {
                    knownIps.computeIfAbsent(entry.getPrincipal(),
                            k -> Collections.synchronizedSet(new HashSet<>()))
                            .add(entry.getIpAddress());
                }
            }
            log.info("Warmed IP cache from audit logs: {} users, {} total IP entries",
                    knownIps.size(),
                    knownIps.values().stream().mapToInt(Set::size).sum());
        } catch (Exception e) {
            log.debug("Could not warm IP cache from audit logs: {}", e.getMessage());
        }
    }

    public ThreatScore score(String username, String ipAddress, String action,
                             String filename, Long fileSizeBytes, Instant timestamp) {
        int score = 0;
        List<String> factors = new ArrayList<>();

        // 1. New IP detection
        Set<String> ips = knownIps.computeIfAbsent(username, k -> Collections.synchronizedSet(new HashSet<>()));
        if (ipAddress != null && !ips.contains(ipAddress)) {
            score += 25;
            factors.add("First-time IP address: " + ipAddress + " (+25)");
            ips.add(ipAddress);
        }

        // 2. Unusual hour (outside 6am-10pm)
        if (timestamp != null) {
            int hour = timestamp.atZone(java.time.ZoneOffset.UTC).getHour();
            if (hour < 6 || hour > 22) {
                score += 15;
                factors.add("Activity at unusual hour: " + hour + ":00 UTC (+15)");
            }
        }

        // 3. Large file (> 100MB)
        if (fileSizeBytes != null && fileSizeBytes > 100 * 1024 * 1024) {
            score += 10;
            factors.add(String.format("Large file: %.1f MB (+10)", fileSizeBytes / (1024.0 * 1024.0)));
        }

        // 4. Rapid-fire operations (> 50 ops in last 5 min)
        List<AuditLog> recentLogs = auditLogRepository.findAll(
                PageRequest.of(0, 100, Sort.by(Sort.Direction.DESC, "timestamp"))).getContent();
        long recentOps = recentLogs.stream()
                .filter(l -> username.equals(l.getPrincipal()))
                .filter(l -> l.getTimestamp().isAfter(Instant.now().minus(5, ChronoUnit.MINUTES)))
                .count();
        if (recentOps > 50) {
            score += 20;
            factors.add("Rapid-fire: " + recentOps + " operations in 5 min (+20)");
        }

        // 5. Failed operations in last hour
        long recentFails = recentLogs.stream()
                .filter(l -> username.equals(l.getPrincipal()))
                .filter(l -> !l.isSuccess())
                .filter(l -> l.getTimestamp().isAfter(Instant.now().minus(1, ChronoUnit.HOURS)))
                .count();
        if (recentFails > 5) {
            score += 15;
            factors.add(recentFails + " failed operations in last hour (+15)");
        }

        // 6. Suspicious filename patterns
        if (filename != null) {
            String lower = filename.toLowerCase();
            if (lower.contains("..") || lower.contains("/etc/") || lower.contains("passwd")) {
                score += 30;
                factors.add("Suspicious filename pattern: path traversal attempt (+30)");
            }
            if (lower.endsWith(".exe") || lower.endsWith(".bat") || lower.endsWith(".ps1") || lower.endsWith(".sh")) {
                score += 10;
                factors.add("Executable file type (+10)");
            }
        }

        score = Math.min(100, score);
        String level = score >= 80 ? "CRITICAL" : score >= 50 ? "HIGH" : score >= 25 ? "MEDIUM" : "LOW";
        String action2 = score >= 80 ? "BLOCK" : score >= 50 ? "REVIEW" : "ALLOW";

        return ThreatScore.builder()
                .score(score).level(level).recommendedAction(action2)
                .factors(factors).username(username).timestamp(Instant.now())
                .build();
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class ThreatScore {
        private int score;
        private String level;
        private String recommendedAction;
        private List<String> factors;
        private String username;
        private Instant timestamp;
    }
}
