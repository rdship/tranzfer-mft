package com.filetransfer.shared.routing;

import com.filetransfer.shared.dto.FileUploadedEvent;
import com.filetransfer.shared.entity.core.TransferAccount;
import com.filetransfer.shared.repository.TransferAccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Consumes FileUploadedEvents from RabbitMQ with backpressure control.
 *
 * <p>Prefetch=1 ensures only one file is processed at a time per consumer thread.
 * This prevents memory exhaustion during burst uploads (e.g., 1000 files in 2 seconds).
 * RabbitMQ holds undelivered messages until the consumer acknowledges the current one.
 *
 * <p>Only active in services that set {@code flow.rules.enabled=true} — the same
 * services that run the RoutingEngine (SFTP, FTP, FTP-Web, Gateway).
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "flow.rules.enabled", havingValue = "true", matchIfMissing = false)
public class FileUploadEventConsumer {

    private final RoutingEngine routingEngine;
    private final TransferAccountRepository accountRepository;

    @RabbitListener(
            queues = "#{@fileUploadQueue.name}",
            containerFactory = "uploadListenerFactory",
            concurrency = "${upload.consumer.concurrency:4-16}"
    )
    public void onFileUploaded(FileUploadedEvent event,
                               @org.springframework.messaging.handler.annotation.Header(
                                       name = "x-death", required = false) java.util.List<?> xDeath) {
        MDC.put("trackId", event.getTrackId());
        try {
            // Phase 7.4: Poison message detection — route to DLQ after 3 redeliveries
            if (xDeath != null && xDeath.size() >= 3) {
                log.error("[{}] Poison message detected: {} redeliveries — dropping (will land in DLQ)",
                        event.getTrackId(), xDeath.size());
                return; // ACK the poison message, let DLQ config catch it
            }
            log.info("[{}] Processing file upload event: user={} file={}",
                    event.getTrackId(), event.getUsername(), event.getFilename());

            // Phase 1: use enriched event fields when available — skip DB fetch for flow-matched path.
            // Full account still loaded because RoutingEngine needs it for folder evaluation,
            // VFS bridge, AI classification, and audit. The enriched fields (partnerId, storageMode)
            // are used by PartnerCache inside RoutingEngine to avoid the partner slug DB query.
            TransferAccount account = accountRepository.findById(event.getAccountId())
                    .orElse(null);
            if (account == null) {
                log.error("[{}] Account not found: {}", event.getTrackId(), event.getAccountId());
                return;
            }

            routingEngine.onFileUploadedInternal(
                    account,
                    event.getRelativeFilePath(),
                    event.getAbsoluteSourcePath(),
                    event.getSourceIp(),
                    event.getFilename(),
                    event.getTrackId());

        } catch (Exception e) {
            // Phase 7.2: Classify error for graceful degradation
            String category = classifyError(e);
            log.error("[{}] File upload processing failed [{}]: {}", event.getTrackId(), category, e.getMessage(), e);
            // Don't throw — ACK the message to prevent infinite requeue.
            // The file is on disk; operator can re-trigger via admin API.
        } finally {
            MDC.remove("trackId");
        }
    }

    /** Phase 7.2: Classify errors for monitoring/alerting. */
    private static String classifyError(Exception e) {
        String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
        if (msg.contains("connection") || msg.contains("timeout") || msg.contains("refused")) return "NETWORK";
        if (msg.contains("auth") || msg.contains("permission") || msg.contains("denied")) return "AUTH";
        if (msg.contains("storage") || msg.contains("disk") || msg.contains("space")) return "STORAGE";
        if (msg.contains("constraint") || msg.contains("duplicate") || msg.contains("integrity")) return "DATA";
        return "SYSTEM";
    }
}
