package com.filetransfer.shared.routing;

import com.filetransfer.shared.audit.AuditService;
import com.filetransfer.shared.connector.ConnectorDispatcher;
import com.filetransfer.shared.entity.FileTransferRecord;
import com.filetransfer.shared.enums.FileTransferStatus;
import com.filetransfer.shared.repository.FileTransferRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Guaranteed delivery with smart retry — no file goes missing.
 *
 * PCI DSS compliance:
 * - Retries failed transfers with exponential backoff + jitter
 * - Classifies failures to determine retry strategy (transient vs permanent)
 * - Moves permanently failed files to quarantine (never deletes)
 * - Verifies SHA-256 checksums at source and destination
 * - Logs every retry attempt in the audit trail
 *
 * Retry schedule (exponential backoff with jitter):
 *   Attempt 1: ~30s, Attempt 2: ~1m, Attempt 3: ~2m, Attempt 4: ~4m,
 *   Attempt 5: ~8m, Attempt 6: ~16m, Attempt 7-10: capped at ~30m
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GuaranteedDeliveryService {

    private static final int MAX_RETRIES = 10;
    private static final String QUARANTINE_DIR = "/data/quarantine";
    private static final long BASE_DELAY_SECONDS = 30;
    private static final long MAX_DELAY_SECONDS = 1800; // 30 min cap
    private static final double JITTER_FACTOR = 0.25;  // +/- 25% randomization

    private final FileTransferRecordRepository recordRepository;
    private final AuditService auditService;
    private final ConnectorDispatcher connectorDispatcher;

    /**
     * Scan for FAILED records and retry them with exponential backoff.
     * Files are NEVER deleted — they either succeed or go to quarantine.
     */
    @Scheduled(fixedDelay = 30000) // check every 30s for tighter retry responsiveness
    @SchedulerLock(name = "guaranteedDelivery_retryFailed", lockAtLeastFor = "PT15S", lockAtMostFor = "PT5M")
    public void retryFailedTransfers() {
        List<FileTransferRecord> failed = recordRepository.findByStatus(FileTransferStatus.FAILED);

        for (FileTransferRecord record : failed) {
            if (record.getRetryCount() >= MAX_RETRIES) {
                quarantine(record);
                continue;
            }

            // Smart retry classification based on error message
            RetryAction action = classifyFailure(record.getErrorMessage(), record.getRetryCount());

            if (action == RetryAction.NO_RETRY) {
                log.info("[{}] Non-retryable failure ({}), alerting admin: {}",
                        record.getTrackId(), classifyCategory(record.getErrorMessage()),
                        record.getOriginalFilename());
                alertAdmin(record);
                continue;
            }

            if (action == RetryAction.QUARANTINE) {
                quarantine(record);
                continue;
            }

            // Check if enough time has elapsed for this retry attempt (exponential backoff)
            long requiredDelay = computeBackoffDelay(record.getRetryCount());
            if (record.getUpdatedAt() != null) {
                long elapsed = Duration.between(record.getUpdatedAt(), Instant.now()).getSeconds();
                if (elapsed < requiredDelay) {
                    continue; // not yet time for this retry
                }
            }

            int attempt = record.getRetryCount() + 1;
            log.info("[{}] Smart retry attempt {}/{} for {} (category={}, backoff={}s)",
                    record.getTrackId(), attempt, MAX_RETRIES, record.getOriginalFilename(),
                    classifyCategory(record.getErrorMessage()), requiredDelay);

            record.setRetryCount(attempt);
            record.setStatus(FileTransferStatus.PENDING);
            record.setErrorMessage(null);
            recordRepository.save(record);

            auditService.logFlowStep(record.getTrackId(), "SMART_RETRY",
                    record.getSourceFilePath(), record.getDestinationFilePath(),
                    true, 0, "Retry attempt " + attempt + "/" + MAX_RETRIES
                            + " (category=" + classifyCategory(record.getErrorMessage())
                            + ", backoff=" + requiredDelay + "s)");
        }
    }

    /**
     * Verify file integrity: compare source and destination checksums.
     * Runs on completed transfers.
     */
    @Scheduled(fixedDelay = 300000) // every 5 min
    @SchedulerLock(name = "guaranteedDelivery_verifyIntegrity", lockAtLeastFor = "PT4M", lockAtMostFor = "PT14M")
    public void verifyIntegrity() {
        List<FileTransferRecord> completed = recordRepository.findByStatus(FileTransferStatus.IN_OUTBOX);

        for (FileTransferRecord record : completed) {
            if (record.getSourceChecksum() == null || record.getDestinationChecksum() == null) {
                try {
                    Path destPath = Paths.get(record.getDestinationFilePath());
                    if (Files.exists(destPath)) {
                        String destHash = AuditService.sha256(destPath);
                        record.setDestinationChecksum(destHash);
                        recordRepository.save(record);

                        if (record.getSourceChecksum() != null && !record.getSourceChecksum().equals(destHash)) {
                            log.error("[{}] INTEGRITY MISMATCH: source={} dest={}",
                                    record.getTrackId(), record.getSourceChecksum(), destHash);
                            auditService.logFailure(null, record.getTrackId(), "INTEGRITY_FAIL",
                                    record.getDestinationFilePath(),
                                    "Checksum mismatch: source=" + record.getSourceChecksum() + " dest=" + destHash);
                        }
                    }
                } catch (Exception e) {
                    log.warn("Integrity check failed for {}: {}", record.getTrackId(), e.getMessage());
                }
            }
        }
    }

    // --- Smart Retry Classification ---

    enum RetryAction { RETRY, NO_RETRY, QUARANTINE }

    /**
     * Classify a failure to determine the best retry strategy.
     * Integrates the same logic as SmartRetryService but operates inline
     * so the shared module doesn't depend on ai-engine.
     */
    RetryAction classifyFailure(String errorMessage, int retryCount) {
        if (errorMessage == null) return RetryAction.RETRY;
        String lower = errorMessage.toLowerCase();

        // Auth/permission errors — retrying won't help
        if (lower.contains("auth") || lower.contains("permission denied") ||
                lower.contains("access denied") || lower.contains("401") || lower.contains("403")) {
            return RetryAction.NO_RETRY;
        }

        // Encryption key errors — admin must intervene
        if (lower.contains("key expired") || lower.contains("key not found") ||
                lower.contains("decrypt failed")) {
            return RetryAction.NO_RETRY;
        }

        // Format/schema errors — quarantine for manual review
        if (lower.contains("schema") || lower.contains("format error") ||
                lower.contains("parse error") || lower.contains("malformed")) {
            return RetryAction.QUARANTINE;
        }

        // Sanctions hit — quarantine (compliance: never retry blocked files)
        if (lower.contains("sanctions") || lower.contains("ofac") || lower.contains("blocked")) {
            return RetryAction.QUARANTINE;
        }

        // Everything else (network, storage, unknown) — retry with backoff
        return RetryAction.RETRY;
    }

    String classifyCategory(String errorMessage) {
        if (errorMessage == null) return "UNKNOWN";
        String lower = errorMessage.toLowerCase();
        if (lower.contains("timeout") || lower.contains("connection")) return "NETWORK_TRANSIENT";
        if (lower.contains("auth") || lower.contains("permission") || lower.contains("401")) return "AUTH_FAILURE";
        if (lower.contains("disk") || lower.contains("space") || lower.contains("quota")) return "STORAGE_FULL";
        if (lower.contains("checksum") || lower.contains("integrity")) return "INTEGRITY_FAILURE";
        if (lower.contains("key expired") || lower.contains("decrypt")) return "ENCRYPTION_KEY";
        if (lower.contains("schema") || lower.contains("format") || lower.contains("parse")) return "FORMAT_ERROR";
        return "UNKNOWN";
    }

    /**
     * Compute exponential backoff delay with jitter.
     * delay = min(BASE * 2^retry, MAX) * (1 +/- JITTER)
     */
    long computeBackoffDelay(int retryCount) {
        long exponential = BASE_DELAY_SECONDS * (1L << Math.min(retryCount, 10));
        long capped = Math.min(exponential, MAX_DELAY_SECONDS);
        // Add jitter: +/- 25%
        double jitter = 1.0 + (ThreadLocalRandom.current().nextDouble() * 2 * JITTER_FACTOR - JITTER_FACTOR);
        return Math.max(1, (long) (capped * jitter));
    }

    /**
     * Alert admin for non-retryable failures (auth, encryption key issues).
     */
    private void alertAdmin(FileTransferRecord record) {
        connectorDispatcher.dispatch(ConnectorDispatcher.MftEvent.builder()
                .eventType("NON_RETRYABLE_FAILURE").severity("HIGH")
                .trackId(record.getTrackId())
                .filename(record.getOriginalFilename())
                .summary("Non-retryable failure: " + classifyCategory(record.getErrorMessage()))
                .details("Error: " + record.getErrorMessage()
                        + "\nCategory: " + classifyCategory(record.getErrorMessage())
                        + "\nManual intervention required.")
                .service("guaranteed-delivery").build());
    }

    /**
     * Move a permanently failed file to quarantine — NEVER delete.
     */
    private void quarantine(FileTransferRecord record) {
        log.error("[{}] Quarantining after {} retries (category={}): {}",
                record.getTrackId(), record.getRetryCount(),
                classifyCategory(record.getErrorMessage()), record.getOriginalFilename());

        try {
            Path quarantine = Paths.get(QUARANTINE_DIR, record.getTrackId());
            Files.createDirectories(quarantine);

            Path sourceFile = Paths.get(record.getSourceFilePath());
            if (Files.exists(sourceFile)) {
                Files.copy(sourceFile, quarantine.resolve(sourceFile.getFileName()));
            }

            if (record.getArchiveFilePath() != null) {
                Path archiveFile = Paths.get(record.getArchiveFilePath());
                if (Files.exists(archiveFile)) {
                    Files.copy(archiveFile, quarantine.resolve("archive_" + archiveFile.getFileName()));
                }
            }

            record.setStatus(FileTransferStatus.FAILED);
            record.setErrorMessage("QUARANTINED after " + record.getRetryCount() + " retries ("
                    + classifyCategory(record.getErrorMessage()) + "). File preserved at " + quarantine);
            recordRepository.save(record);

            auditService.logFailure(null, record.getTrackId(), "FILE_QUARANTINE",
                    quarantine.toString(), "Quarantined: " + classifyCategory(record.getErrorMessage())
                            + ". File preserved for manual recovery.");

            connectorDispatcher.dispatch(ConnectorDispatcher.MftEvent.builder()
                    .eventType("QUARANTINE").severity("CRITICAL").trackId(record.getTrackId())
                    .filename(record.getOriginalFilename())
                    .summary("File quarantined: " + classifyCategory(record.getErrorMessage()))
                    .details("File preserved at " + quarantine + ". Category: "
                            + classifyCategory(record.getErrorMessage())
                            + ". Manual intervention required.")
                    .service("guaranteed-delivery").build());

        } catch (Exception e) {
            log.error("[{}] Quarantine failed: {}", record.getTrackId(), e.getMessage());
        }
    }
}
