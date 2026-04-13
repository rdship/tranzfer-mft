package com.filetransfer.sentinel.collector;

import com.filetransfer.shared.entity.core.AuditLog;
import com.filetransfer.shared.repository.AuditLogRepository;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuditCollector {

    private final AuditLogRepository auditLogRepository;

    @Getter
    private volatile List<AuditLog> recentLogs = List.of();

    public void collect(int windowMinutes) {
        try {
            Instant cutoff = Instant.now().minus(windowMinutes, ChronoUnit.MINUTES);
            recentLogs = auditLogRepository.findAll().stream()
                    .filter(l -> l.getTimestamp() != null && l.getTimestamp().isAfter(cutoff))
                    .toList();
            log.debug("AuditCollector: {} logs in last {} min", recentLogs.size(), windowMinutes);
        } catch (Exception e) {
            log.warn("AuditCollector failed: {}", e.getMessage());
        }
    }

    public long countByAction(String action) {
        return recentLogs.stream()
                .filter(l -> action.equals(l.getAction()))
                .count();
    }

    public long countFailedLogins() {
        return recentLogs.stream()
                .filter(l -> "LOGIN_FAIL".equals(l.getAction()))
                .count();
    }

    public long countConfigChanges() {
        return recentLogs.stream()
                .filter(l -> "CONFIG_CHANGE".equals(l.getAction()))
                .count();
    }
}
