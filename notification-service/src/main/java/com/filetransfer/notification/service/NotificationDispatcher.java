package com.filetransfer.notification.service;

import com.filetransfer.notification.channel.NotificationChannel;
import com.filetransfer.notification.channel.NotificationChannelFactory;
import com.filetransfer.notification.config.NotificationProperties;
import com.filetransfer.notification.dto.NotificationEvent;
import com.filetransfer.shared.entity.integration.NotificationLog;
import com.filetransfer.shared.entity.integration.NotificationRule;
import com.filetransfer.shared.entity.integration.NotificationTemplate;
import com.filetransfer.shared.repository.integration.NotificationLogRepository;
import com.filetransfer.shared.repository.integration.NotificationTemplateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

/**
 * Core notification dispatcher.
 * Receives events, matches them against rules, renders templates,
 * and dispatches via the appropriate channel.
 * All dispatches are logged for auditing and retry.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationDispatcher {

    private final RuleMatcher ruleMatcher;
    private final TemplateRenderer templateRenderer;
    private final NotificationChannelFactory channelFactory;
    private final NotificationTemplateRepository templateRepository;
    private final NotificationLogRepository logRepository;
    private final NotificationProperties properties;

    /**
     * Process an incoming event: match rules, render templates, dispatch notifications.
     * This is the main entry point called by the RabbitMQ consumer.
     */
    @Async("taskExecutor")
    public void processEvent(NotificationEvent event) {
        String eventType = event.getEventType();
        log.debug("Processing notification event: type={} trackId={}", eventType, event.getTrackId());

        List<NotificationRule> matchingRules = ruleMatcher.findMatchingRules(eventType);
        if (matchingRules.isEmpty()) {
            log.debug("No matching notification rules for event type: {}", eventType);
            return;
        }

        Map<String, Object> variables = buildVariableMap(event);

        for (NotificationRule rule : matchingRules) {
            if (!ruleMatcher.matchesConditions(rule, event.getPayload())) {
                log.debug("Rule {} conditions not met for event {}", rule.getName(), eventType);
                continue;
            }

            try {
                dispatchForRule(rule, eventType, variables, event.getTrackId());
            } catch (Exception e) {
                log.error("Failed to dispatch notification for rule={} event={}: {}",
                        rule.getName(), eventType, e.getMessage());
            }
        }
    }

    /**
     * Dispatch notifications for a single matched rule.
     */
    private void dispatchForRule(NotificationRule rule, String eventType,
                                  Map<String, Object> variables, String trackId) {
        String channel = rule.getChannel();

        // Find template: use rule's linked template, or find by event type + channel
        NotificationTemplate template = rule.getTemplate();
        if (template == null) {
            List<NotificationTemplate> templates = templateRepository
                    .findByEventTypeAndChannelAndActiveTrue(eventType, channel);
            if (!templates.isEmpty()) {
                template = templates.get(0);
            }
        }

        // Render subject and body
        String subject = null;
        String body;
        if (template != null) {
            subject = templateRenderer.render(template.getSubjectTemplate(), variables);
            body = templateRenderer.render(template.getBodyTemplate(), variables);
        } else {
            // Fallback: simple default message
            subject = "TranzFer MFT: " + eventType;
            body = "Event: " + eventType + "\nTrack ID: " + trackId + "\nTimestamp: " + Instant.now();
        }

        // Dispatch to each recipient
        for (String recipient : rule.getRecipients()) {
            dispatchSingle(channel, recipient, subject, body, eventType, trackId, rule.getId());
        }
    }

    /**
     * Send a single notification and log the result.
     */
    private void dispatchSingle(String channelType, String recipient, String subject,
                                 String body, String eventType, String trackId, UUID ruleId) {
        NotificationLog logEntry = NotificationLog.builder()
                .eventType(eventType)
                .channel(channelType)
                .recipient(recipient)
                .subject(subject)
                .status("PENDING")
                .trackId(trackId)
                .ruleId(ruleId)
                .build();

        try {
            NotificationChannel channel = channelFactory.getChannel(channelType);
            channel.send(recipient, subject, body, Map.of());

            logEntry.setStatus("SENT");
            logEntry.setSentAt(Instant.now());
            log.info("Notification sent: channel={} recipient={} event={} trackId={}",
                    channelType, recipient, eventType, trackId);
        } catch (Exception e) {
            logEntry.setStatus("FAILED");
            logEntry.setErrorMessage(e.getMessage());
            log.warn("Notification failed: channel={} recipient={} event={} error={}",
                    channelType, recipient, eventType, e.getMessage());
        }

        logRepository.save(logEntry);
    }

    /**
     * Send a test notification directly (not triggered by an event).
     */
    public NotificationLog sendTestNotification(String channelType, String recipient,
                                                  String subject, String body) {
        NotificationLog logEntry = NotificationLog.builder()
                .eventType("test.notification")
                .channel(channelType)
                .recipient(recipient)
                .subject(subject)
                .status("PENDING")
                .trackId("TEST")
                .build();

        try {
            NotificationChannel channel = channelFactory.getChannel(channelType);
            channel.send(recipient, subject, body, Map.of());

            logEntry.setStatus("SENT");
            logEntry.setSentAt(Instant.now());
            log.info("Test notification sent: channel={} recipient={}", channelType, recipient);
        } catch (Exception e) {
            logEntry.setStatus("FAILED");
            logEntry.setErrorMessage(e.getMessage());
            log.warn("Test notification failed: channel={} recipient={} error={}",
                    channelType, recipient, e.getMessage());
        }

        return logRepository.save(logEntry);
    }

    /**
     * Retry failed notifications that haven't exceeded max retries.
     * Runs every 60 seconds.
     */
    @Scheduled(fixedDelayString = "${notification.retry.delay-seconds:60}000")
    @SchedulerLock(name = "retryFailedNotifications", lockAtLeastFor = "55s", lockAtMostFor = "300s")
    public void retryFailedNotifications() {
        int maxAttempts = properties.getRetry().getMaxAttempts();
        List<NotificationLog> failedLogs = logRepository
                .findByStatusAndRetryCountLessThan("FAILED", maxAttempts);

        if (failedLogs.isEmpty()) return;

        log.info("Retrying {} failed notifications", failedLogs.size());

        for (NotificationLog logEntry : failedLogs) {
            try {
                NotificationChannel channel = channelFactory.getChannel(logEntry.getChannel());
                channel.send(logEntry.getRecipient(), logEntry.getSubject(),
                        "Retry of notification for event: " + logEntry.getEventType(), Map.of());

                logEntry.setStatus("SENT");
                logEntry.setSentAt(Instant.now());
                log.info("Retry succeeded: id={} channel={} recipient={}",
                        logEntry.getId(), logEntry.getChannel(), logEntry.getRecipient());
            } catch (Exception e) {
                logEntry.setRetryCount(logEntry.getRetryCount() + 1);
                logEntry.setErrorMessage("Retry " + logEntry.getRetryCount() + ": " + e.getMessage());
                if (logEntry.getRetryCount() >= maxAttempts) {
                    logEntry.setStatus("EXHAUSTED");
                    log.warn("Notification retries exhausted: id={} channel={} recipient={}",
                            logEntry.getId(), logEntry.getChannel(), logEntry.getRecipient());
                }
            }
            logRepository.save(logEntry);
        }
    }

    /**
     * Build the variable map for template rendering from a notification event.
     */
    private Map<String, Object> buildVariableMap(NotificationEvent event) {
        Map<String, Object> variables = new HashMap<>();
        variables.put("eventType", nullSafe(event.getEventType()));
        variables.put("trackId", nullSafe(event.getTrackId()));
        variables.put("account", nullSafe(event.getAccount()));
        variables.put("filename", nullSafe(event.getFilename()));
        variables.put("protocol", nullSafe(event.getProtocol()));
        variables.put("service", nullSafe(event.getService()));
        variables.put("severity", nullSafe(event.getSeverity()));
        variables.put("timestamp", event.getTimestamp() != null ? event.getTimestamp().toString() : Instant.now().toString());

        // Merge payload fields (event-specific data) into variables
        if (event.getPayload() != null) {
            event.getPayload().forEach((key, value) ->
                    variables.put(key, value != null ? String.valueOf(value) : ""));
        }

        return variables;
    }

    private String nullSafe(String value) {
        return value != null ? value : "";
    }
}
