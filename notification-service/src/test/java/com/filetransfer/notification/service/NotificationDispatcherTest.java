package com.filetransfer.notification.service;

import com.filetransfer.notification.channel.NotificationChannel;
import com.filetransfer.notification.channel.NotificationChannelFactory;
import com.filetransfer.notification.config.NotificationProperties;
import com.filetransfer.notification.dto.NotificationEvent;
import com.filetransfer.shared.entity.integration.NotificationLog;
import com.filetransfer.shared.entity.integration.NotificationRule;
import com.filetransfer.shared.entity.integration.NotificationTemplate;
import com.filetransfer.shared.repository.integration.NotificationLogRepository;
import com.filetransfer.shared.repository.integration.NotificationRuleRepository;
import com.filetransfer.shared.repository.integration.NotificationTemplateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for NotificationDispatcher.
 *
 * JDK 25 constraints: RuleMatcher, TemplateRenderer, NotificationChannelFactory
 * are concrete classes that cannot be mocked. We construct real instances backed
 * by mocked repositories/interfaces and wire everything manually.
 */
@ExtendWith(MockitoExtension.class)
class NotificationDispatcherTest {

    // Interfaces -- safe to @Mock on JDK 25
    @Mock
    private NotificationRuleRepository ruleRepository;

    @Mock
    private NotificationTemplateRepository templateRepository;

    @Mock
    private NotificationLogRepository logRepository;

    @Mock
    private NotificationChannel mockChannel;

    @Captor
    private ArgumentCaptor<NotificationLog> logCaptor;

    // Real instances (JDK 25: cannot mock concrete classes)
    private RuleMatcher ruleMatcher;
    private TemplateRenderer templateRenderer;
    private NotificationChannelFactory channelFactory;
    private NotificationProperties properties;
    private NotificationDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        // Wire real concrete instances
        ruleMatcher = new RuleMatcher(ruleRepository);
        templateRenderer = new TemplateRenderer();
        properties = new NotificationProperties();

        // Channel factory with a mock EMAIL channel
        lenient().when(mockChannel.getChannelType()).thenReturn("EMAIL");
        channelFactory = new NotificationChannelFactory(List.of(mockChannel));

        dispatcher = new NotificationDispatcher(
                ruleMatcher, templateRenderer, channelFactory,
                templateRepository, logRepository, properties
        );
    }

    private NotificationEvent sampleEvent() {
        return NotificationEvent.builder()
                .eventType("transfer.completed")
                .trackId("TRK-001")
                .account("acme")
                .filename("data.csv")
                .protocol("SFTP")
                .service("sftp-service")
                .severity("INFO")
                .payload(Map.of("fileSize", "1024"))
                .timestamp(Instant.parse("2026-01-15T10:30:00Z"))
                .build();
    }

    private NotificationRule sampleRule() {
        return NotificationRule.builder()
                .id(UUID.randomUUID())
                .name("transfer-email")
                .eventTypePattern("transfer.*")
                .channel("EMAIL")
                .recipients(List.of("admin@tranzfer.io"))
                .enabled(true)
                .build();
    }

    // ── processEvent ────────────────────────────────────────────────────

    @Nested
    @DisplayName("processEvent")
    class ProcessEventTests {

        @Test
        @DisplayName("no matching rules - returns without dispatching")
        void noMatchingRules_doesNotDispatch() {
            NotificationEvent event = sampleEvent();
            when(ruleRepository.findByEnabledTrue()).thenReturn(Collections.emptyList());

            dispatcher.processEvent(event);

            verifyNoInteractions(logRepository);
        }

        @Test
        @DisplayName("matching rule with conditions met - dispatches notification")
        void matchingRuleWithConditionsMet_dispatches() throws Exception {
            NotificationEvent event = sampleEvent();
            NotificationRule rule = sampleRule();

            NotificationTemplate template = NotificationTemplate.builder()
                    .id(UUID.randomUUID())
                    .name("transfer-complete-tpl")
                    .channel("EMAIL")
                    .subjectTemplate("Transfer Complete: ${filename}")
                    .bodyTemplate("File ${filename} transferred via ${protocol}")
                    .eventType("transfer.completed")
                    .build();

            when(ruleRepository.findByEnabledTrue()).thenReturn(List.of(rule));
            when(templateRepository.findByEventTypeAndChannelAndActiveTrue("transfer.completed", "EMAIL"))
                    .thenReturn(List.of(template));
            when(logRepository.save(any(NotificationLog.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            dispatcher.processEvent(event);

            verify(mockChannel).send(
                    eq("admin@tranzfer.io"),
                    eq("Transfer Complete: data.csv"),
                    eq("File data.csv transferred via SFTP"),
                    anyMap()
            );
            verify(logRepository).save(logCaptor.capture());
            NotificationLog saved = logCaptor.getValue();
            assertThat(saved.getStatus()).isEqualTo("SENT");
            assertThat(saved.getChannel()).isEqualTo("EMAIL");
            assertThat(saved.getRecipient()).isEqualTo("admin@tranzfer.io");
        }

        @Test
        @DisplayName("matching rule with conditions NOT met - skips dispatch")
        void matchingRuleConditionsNotMet_skips() {
            NotificationEvent event = sampleEvent();
            NotificationRule rule = NotificationRule.builder()
                    .id(UUID.randomUUID())
                    .name("critical-only")
                    .eventTypePattern("transfer.*")
                    .channel("EMAIL")
                    .recipients(List.of("admin@tranzfer.io"))
                    .enabled(true)
                    .conditions(Map.of("severity", "CRITICAL"))
                    .build();

            // Event has severity=INFO in payload, rule requires CRITICAL
            when(ruleRepository.findByEnabledTrue()).thenReturn(List.of(rule));

            dispatcher.processEvent(event);

            verifyNoInteractions(logRepository);
        }
    }

    // ── sendTestNotification ────────────────────────────────────────────

    @Nested
    @DisplayName("sendTestNotification")
    class SendTestNotificationTests {

        @Test
        @DisplayName("successful send returns log with SENT status")
        void successfulSend_sentStatus() throws Exception {
            when(logRepository.save(any(NotificationLog.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            NotificationLog result = dispatcher.sendTestNotification(
                    "EMAIL", "test@tranzfer.io", "Test Subject", "Test Body");

            verify(mockChannel).send("test@tranzfer.io", "Test Subject", "Test Body", Map.of());
            assertThat(result.getStatus()).isEqualTo("SENT");
            assertThat(result.getChannel()).isEqualTo("EMAIL");
            assertThat(result.getRecipient()).isEqualTo("test@tranzfer.io");
            assertThat(result.getEventType()).isEqualTo("test.notification");
            assertThat(result.getSentAt()).isNotNull();
        }

        @Test
        @DisplayName("channel throws exception returns log with FAILED status")
        void channelThrows_failedStatus() throws Exception {
            doThrow(new RuntimeException("SMTP connection refused"))
                    .when(mockChannel).send(anyString(), anyString(), anyString(), anyMap());
            when(logRepository.save(any(NotificationLog.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            NotificationLog result = dispatcher.sendTestNotification(
                    "EMAIL", "test@tranzfer.io", "Test Subject", "Test Body");

            assertThat(result.getStatus()).isEqualTo("FAILED");
            assertThat(result.getErrorMessage()).isEqualTo("SMTP connection refused");
        }
    }

    // ── retryFailedNotifications ────────────────────────────────────────

    @Nested
    @DisplayName("retryFailedNotifications")
    class RetryFailedNotificationsTests {

        @Test
        @DisplayName("no failed notifications - returns immediately")
        void noFailed_returns() {
            when(logRepository.findByStatusAndRetryCountLessThan("FAILED", 3))
                    .thenReturn(Collections.emptyList());

            dispatcher.retryFailedNotifications();

            verify(logRepository).findByStatusAndRetryCountLessThan("FAILED", 3);
            verify(logRepository, never()).save(any());
        }

        @Test
        @DisplayName("successful retry updates status to SENT")
        void successfulRetry_sentStatus() throws Exception {
            NotificationLog failedLog = NotificationLog.builder()
                    .id(UUID.randomUUID())
                    .eventType("transfer.completed")
                    .channel("EMAIL")
                    .recipient("admin@tranzfer.io")
                    .subject("Transfer Complete")
                    .status("FAILED")
                    .retryCount(1)
                    .trackId("TRK-001")
                    .build();

            when(logRepository.findByStatusAndRetryCountLessThan("FAILED", 3))
                    .thenReturn(List.of(failedLog));

            dispatcher.retryFailedNotifications();

            verify(mockChannel).send(eq("admin@tranzfer.io"), eq("Transfer Complete"),
                    contains("Retry of notification"), anyMap());
            verify(logRepository).save(logCaptor.capture());
            NotificationLog saved = logCaptor.getValue();
            assertThat(saved.getStatus()).isEqualTo("SENT");
            assertThat(saved.getSentAt()).isNotNull();
        }

        @Test
        @DisplayName("failed retry increments retryCount")
        void failedRetry_incrementsCount() throws Exception {
            NotificationLog failedLog = NotificationLog.builder()
                    .id(UUID.randomUUID())
                    .eventType("transfer.completed")
                    .channel("EMAIL")
                    .recipient("admin@tranzfer.io")
                    .subject("Transfer Complete")
                    .status("FAILED")
                    .retryCount(0)
                    .trackId("TRK-001")
                    .build();

            when(logRepository.findByStatusAndRetryCountLessThan("FAILED", 3))
                    .thenReturn(List.of(failedLog));
            doThrow(new RuntimeException("Still failing"))
                    .when(mockChannel).send(anyString(), anyString(), anyString(), anyMap());

            dispatcher.retryFailedNotifications();

            verify(logRepository).save(logCaptor.capture());
            NotificationLog saved = logCaptor.getValue();
            assertThat(saved.getRetryCount()).isEqualTo(1);
            assertThat(saved.getStatus()).isEqualTo("FAILED");
            assertThat(saved.getErrorMessage()).contains("Retry 1");
        }

        @Test
        @DisplayName("exhausted retries sets EXHAUSTED status")
        void exhaustedRetries_exhaustedStatus() throws Exception {
            NotificationLog failedLog = NotificationLog.builder()
                    .id(UUID.randomUUID())
                    .eventType("transfer.completed")
                    .channel("EMAIL")
                    .recipient("admin@tranzfer.io")
                    .subject("Transfer Complete")
                    .status("FAILED")
                    .retryCount(2)  // one more failure will hit maxAttempts=3
                    .trackId("TRK-001")
                    .build();

            when(logRepository.findByStatusAndRetryCountLessThan("FAILED", 3))
                    .thenReturn(List.of(failedLog));
            doThrow(new RuntimeException("Permanently broken"))
                    .when(mockChannel).send(anyString(), anyString(), anyString(), anyMap());

            dispatcher.retryFailedNotifications();

            verify(logRepository).save(logCaptor.capture());
            NotificationLog saved = logCaptor.getValue();
            assertThat(saved.getRetryCount()).isEqualTo(3);
            assertThat(saved.getStatus()).isEqualTo("EXHAUSTED");
        }
    }
}
