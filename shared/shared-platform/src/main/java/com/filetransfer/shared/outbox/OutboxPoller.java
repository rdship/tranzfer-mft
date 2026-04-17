package com.filetransfer.shared.outbox;

import com.filetransfer.shared.entity.core.ConfigEventOutbox;
import com.filetransfer.shared.repository.core.ConfigEventOutboxRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Drains the config_event_outbox table and publishes rows to RabbitMQ.
 * Marks rows as published on success; leaves them with incremented attempts
 * and last_error on failure for retry on the next tick.
 *
 * <p>Enable per-service by setting {@code outbox.poller.enabled=true}. Exactly
 * one service (normally onboarding-api) owns the outbox for config events.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "outbox.poller", name = "enabled", havingValue = "true")
public class OutboxPoller {

    private static final int BATCH_SIZE = 50;

    private final ConfigEventOutboxRepository repository;
    private final RabbitTemplate rabbitTemplate;

    @Value("${rabbitmq.exchange:file-transfer.events}")
    private String exchange;

    @Scheduled(fixedDelayString = "${outbox.poller.delay-ms:500}")
    @SchedulerLock(name = "outbox_drain", lockAtLeastFor = "PT0.4S", lockAtMostFor = "PT10S")
    @Transactional
    public void drain() {
        List<ConfigEventOutbox> batch = repository.findUnpublished(PageRequest.of(0, BATCH_SIZE));
        if (batch.isEmpty()) return;

        int ok = 0;
        for (ConfigEventOutbox row : batch) {
            try {
                rabbitTemplate.convertAndSend(exchange, row.getRoutingKey(), row.getPayload());
                row.setPublishedAt(Instant.now());
                row.setAttempts(row.getAttempts() + 1);
                row.setLastError(null);
                ok++;
            } catch (Exception e) {
                row.setAttempts(row.getAttempts() + 1);
                row.setLastError(e.getMessage());
                log.warn("Outbox publish failed id={} attempt={}: {}",
                        row.getId(), row.getAttempts(), e.getMessage());
            }
            repository.save(row);
        }
        if (ok > 0) {
            log.debug("Outbox drained {} events ({} batch)", ok, batch.size());
        }
    }

    /** Clean up successfully-published rows older than 24h. */
    @Scheduled(fixedDelayString = "${outbox.poller.cleanup-ms:3600000}")
    @SchedulerLock(name = "outbox_cleanup", lockAtLeastFor = "PT55M", lockAtMostFor = "PT59M")
    @Transactional
    public void cleanup() {
        Instant cutoff = Instant.now().minus(24, ChronoUnit.HOURS);
        int deleted = repository.deletePublishedBefore(cutoff);
        if (deleted > 0) {
            log.debug("Outbox cleanup removed {} published rows", deleted);
        }
    }
}
