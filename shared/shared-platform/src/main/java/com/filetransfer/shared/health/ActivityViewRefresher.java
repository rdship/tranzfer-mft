package com.filetransfer.shared.health;

import com.filetransfer.shared.repository.TransferActivityViewRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Refreshes the Activity Monitor materialized view every 30 seconds.
 * CONCURRENTLY refresh allows reads during refresh (no lock).
 * Only activates in services that have the repository available.
 */
@Slf4j
@Component
@org.springframework.context.annotation.Lazy(false)  // Must be eager — @Scheduled refresh timer
@ConditionalOnBean(TransferActivityViewRepository.class)
@ConditionalOnProperty(name = "activity.view.refresh.enabled", havingValue = "true", matchIfMissing = false)
public class ActivityViewRefresher {

    @Autowired
    private TransferActivityViewRepository viewRepo;

    // Phase 6: observability — track refresh stats for pipeline health API
    private volatile java.time.Instant lastRefreshAt;
    private volatile long lastRefreshDurationMs;
    private volatile long refreshCount;
    private volatile String lastError;

    @Scheduled(fixedDelay = 30_000, initialDelay = 45_000)
    public void refresh() {
        long start = System.currentTimeMillis();
        try {
            viewRepo.refresh();
            lastRefreshDurationMs = System.currentTimeMillis() - start;
            lastRefreshAt = java.time.Instant.now();
            refreshCount++;
            lastError = null;
            log.debug("Activity materialized view refreshed in {}ms", lastRefreshDurationMs);
        } catch (Exception e) {
            lastRefreshDurationMs = System.currentTimeMillis() - start;
            lastError = e.getMessage();
            log.debug("Activity view refresh skipped: {}", e.getMessage());
        }
    }

    /** Phase 6: stats for pipeline health endpoint. */
    public java.util.Map<String, Object> getStats() {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("enabled", true);
        m.put("intervalMs", 30000);
        m.put("refreshCount", refreshCount);
        m.put("lastRefreshAt", lastRefreshAt != null ? lastRefreshAt.toString() : null);
        m.put("lastDurationMs", lastRefreshDurationMs);
        if (lastError != null) m.put("lastError", lastError);
        return m;
    }
}
