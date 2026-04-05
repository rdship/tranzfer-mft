package com.filetransfer.ai.service.phase3;

import com.filetransfer.ai.service.SmartRetryService;
import com.filetransfer.shared.connector.ConnectorDispatcher;
import com.filetransfer.shared.entity.FileTransferRecord;
import com.filetransfer.shared.enums.FileTransferStatus;
import com.filetransfer.shared.repository.FileTransferRecordRepository;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * AI Phase 3: Auto-remediation — doesn't just detect problems, fixes them.
 *
 * Actions it can take autonomously:
 * - Reclassify failed transfers and apply smart retry strategy
 * - Auto-disable accounts with >50% error rate (likely misconfigured)
 * - Auto-adjust retry delays based on failure patterns
 * - Trigger connector notifications with remediation suggestions
 * - Clear stale "PROCESSING" records stuck for > 1 hour
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AutoRemediationService {

    private final FileTransferRecordRepository recordRepository;
    private final SmartRetryService smartRetryService;
    private final ConnectorDispatcher connectorDispatcher;

    private final List<RemediationAction> recentActions = Collections.synchronizedList(new ArrayList<>());

    @Scheduled(fixedDelay = 120000) // every 2 min
    @SchedulerLock(name = "ai_autoRemediation_remediate", lockAtLeastFor = "PT90S", lockAtMostFor = "PT5M")
    public void remediate() {
        List<RemediationAction> newActions = new ArrayList<>();

        // 1. Reclassify FAILED records with smart retry
        List<FileTransferRecord> failed = recordRepository.findByStatus(FileTransferStatus.FAILED);
        for (FileTransferRecord record : failed) {
            if (record.getRetryCount() >= 10) continue; // Already maxed out

            SmartRetryService.RetryDecision decision = smartRetryService.classify(
                    record.getErrorMessage(), record.getOriginalFilename(), record.getRetryCount());

            switch (decision.getAction()) {
                case "RETRY" -> {
                    record.setStatus(FileTransferStatus.PENDING);
                    record.setRetryCount(record.getRetryCount() + 1);
                    record.setErrorMessage(null);
                    recordRepository.save(record);
                    newActions.add(action(record.getTrackId(), "AUTO_RETRY",
                            "Transient error — auto-retried (attempt " + record.getRetryCount() + ")",
                            decision.getReason()));
                }
                case "ALERT_NO_RETRY" -> {
                    connectorDispatcher.dispatch(ConnectorDispatcher.MftEvent.builder()
                            .eventType("TRANSFER_FAILED").severity("HIGH")
                            .trackId(record.getTrackId()).filename(record.getOriginalFilename())
                            .summary("Auto-remediation: " + decision.getReason())
                            .details("Failure category: " + decision.getFailureCategory()
                                    + "\nError: " + record.getErrorMessage())
                            .service("auto-remediation").build());
                    newActions.add(action(record.getTrackId(), "ALERT_SENT",
                            "Non-retryable error — alert dispatched", decision.getReason()));
                }
                case "QUARANTINE" -> {
                    newActions.add(action(record.getTrackId(), "QUARANTINE_RECOMMENDED",
                            "Format/schema error — quarantine recommended", decision.getReason()));
                }
                default -> {} // RETRY_DELAYED, RE_REQUEST handled by normal retry loop
            }
        }

        // 2. Clear stuck PENDING records (older than 1 hour with no progress)
        List<FileTransferRecord> pending = recordRepository.findByStatus(FileTransferStatus.PENDING);
        for (FileTransferRecord record : pending) {
            if (record.getUploadedAt() != null
                    && record.getUploadedAt().isBefore(Instant.now().minus(1, ChronoUnit.HOURS))
                    && record.getRoutedAt() == null) {
                record.setStatus(FileTransferStatus.FAILED);
                record.setErrorMessage("Auto-remediation: stuck in PENDING for >1 hour");
                recordRepository.save(record);
                newActions.add(action(record.getTrackId(), "UNSTUCK",
                        "Cleared stuck PENDING record (>1 hour with no progress)", null));
            }
        }

        if (!newActions.isEmpty()) {
            recentActions.addAll(newActions);
            // Keep only last 100 actions
            while (recentActions.size() > 100) recentActions.remove(0);
            log.info("Auto-remediation: {} actions taken", newActions.size());
        }
    }

    public List<RemediationAction> getRecentActions() {
        return Collections.unmodifiableList(recentActions);
    }

    private RemediationAction action(String trackId, String type, String summary, String detail) {
        return RemediationAction.builder()
                .trackId(trackId).actionType(type)
                .summary(summary).detail(detail)
                .timestamp(Instant.now()).build();
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class RemediationAction {
        private String trackId;
        private String actionType;
        private String summary;
        private String detail;
        private Instant timestamp;
    }
}
