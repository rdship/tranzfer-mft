package com.filetransfer.shared.routing;

import com.filetransfer.shared.dto.FileUploadedEvent;
import com.filetransfer.shared.entity.TransferAccount;
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
            concurrency = "2-4"
    )
    public void onFileUploaded(FileUploadedEvent event) {
        MDC.put("trackId", event.getTrackId());
        try {
            log.info("[{}] Processing file upload event: user={} file={}",
                    event.getTrackId(), event.getUsername(), event.getFilename());

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
            log.error("[{}] File upload processing failed: {}", event.getTrackId(), e.getMessage(), e);
        } finally {
            MDC.remove("trackId");
        }
    }
}
