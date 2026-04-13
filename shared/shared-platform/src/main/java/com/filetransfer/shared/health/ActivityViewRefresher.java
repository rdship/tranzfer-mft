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
@ConditionalOnBean(TransferActivityViewRepository.class)
@ConditionalOnProperty(name = "activity.view.refresh.enabled", havingValue = "true", matchIfMissing = false)
public class ActivityViewRefresher {

    @Autowired
    private TransferActivityViewRepository viewRepo;

    @Scheduled(fixedDelay = 30_000, initialDelay = 45_000)
    public void refresh() {
        try {
            viewRepo.refresh();
            log.debug("Activity materialized view refreshed");
        } catch (Exception e) {
            // First boot: view may not exist yet (migration hasn't run)
            log.debug("Activity view refresh skipped: {}", e.getMessage());
        }
    }
}
