package com.filetransfer.notification;

import com.filetransfer.notification.channel.NotificationChannel;
import com.filetransfer.notification.channel.NotificationChannelFactory;
import com.filetransfer.notification.config.NotificationProperties;
import com.filetransfer.notification.dto.NotificationEvent;
import com.filetransfer.notification.service.NotificationDispatcher;
import com.filetransfer.notification.service.RuleMatcher;
import com.filetransfer.notification.service.TemplateRenderer;
import com.filetransfer.shared.entity.integration.NotificationLog;
import com.filetransfer.shared.entity.integration.NotificationRule;
import com.filetransfer.shared.entity.integration.NotificationTemplate;
import com.filetransfer.shared.repository.integration.NotificationLogRepository;
import com.filetransfer.shared.repository.integration.NotificationRuleRepository;
import com.filetransfer.shared.repository.integration.NotificationTemplateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Regression, usability, and performance tests for notification-service.
 * Pure JUnit 5 + Mockito — no Spring context.
 */
@ExtendWith(MockitoExtension.class)
class NotificationRegressionTest {

    @Mock private NotificationRuleRepository ruleRepository;
    @Mock private NotificationTemplateRepository templateRepository;
    @Mock private NotificationLogRepository logRepository;
    @Mock private NotificationChannel mockChannel;

    private TemplateRenderer templateRenderer;
    private RuleMatcher ruleMatcher;
    private NotificationDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        templateRenderer = new TemplateRenderer();
        ruleMatcher = new RuleMatcher(ruleRepository);

        lenient().when(mockChannel.getChannelType()).thenReturn("EMAIL");
        NotificationChannelFactory channelFactory = new NotificationChannelFactory(List.of(mockChannel));
        NotificationProperties properties = new NotificationProperties();

        dispatcher = new NotificationDispatcher(
                ruleMatcher, templateRenderer, channelFactory,
                templateRepository, logRepository, properties
        );
    }

    // ── Template rendering correctness ─────────────────────────────────

    @Test
    void templateRenderer_validTemplate_shouldRenderCorrectly() {
        String template = "Transfer of ${filename} via ${protocol} completed for ${account}";
        Map<String, Object> vars = Map.of(
                "filename", "data.csv",
                "protocol", "SFTP",
                "account", "acme-corp"
        );

        String result = templateRenderer.render(template, vars);

        assertEquals("Transfer of data.csv via SFTP completed for acme-corp", result);
    }

    // ── Template: missing variable should not crash ─────────────────────

    @Test
    void templateRenderer_missingVariable_shouldNotCrash() {
        String template = "Hello ${name}, your file ${filename} is ready";
        Map<String, Object> vars = Map.of("name", "John");

        // Should not throw NPE — missing variable replaced with empty string
        String result = assertDoesNotThrow(() -> templateRenderer.render(template, vars));
        assertEquals("Hello John, your file  is ready", result,
                "Missing variables should be replaced with empty string, not cause NPE");
    }

    // ── Rule matcher: condition evaluation ──────────────────────────────

    @Test
    void ruleMatcher_matchesCondition_shouldEvaluateCorrectly() {
        NotificationRule rule = NotificationRule.builder()
                .id(UUID.randomUUID())
                .name("critical-only")
                .eventTypePattern("transfer.*")
                .channel("EMAIL")
                .recipients(List.of("admin@test.com"))
                .conditions(Map.of("severity", "CRITICAL"))
                .build();

        // Should match when severity matches
        assertTrue(ruleMatcher.matchesConditions(rule, Map.of("severity", "CRITICAL")),
                "Condition should match when values are equal");

        // Should NOT match when severity differs
        assertFalse(ruleMatcher.matchesConditions(rule, Map.of("severity", "INFO")),
                "Condition should not match when values differ");
    }

    // ── Dispatcher: null recipient handling ─────────────────────────────

    @Test
    void dispatcher_nullRecipient_shouldHandleGracefully() throws Exception {
        when(logRepository.save(any(NotificationLog.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        // sendTestNotification with null recipient should not throw NPE
        NotificationLog result = assertDoesNotThrow(() ->
                        dispatcher.sendTestNotification("EMAIL", null, "Test Subject", "Test Body"),
                "Null recipient should not cause NPE");

        assertNotNull(result, "Should return a log entry even with null recipient");
    }

    // ── Template rendering performance ─────────────────────────────────

    @Test
    void templateRenderer_performance_1000Renders_shouldBeUnder100ms() {
        String template = "Transfer ${trackId}: file ${filename} via ${protocol} for ${account} at ${timestamp}";
        Map<String, Object> vars = Map.of(
                "trackId", "TRK-001",
                "filename", "data.csv",
                "protocol", "SFTP",
                "account", "acme-corp",
                "timestamp", Instant.now().toString()
        );

        // Warm up
        for (int i = 0; i < 50; i++) {
            templateRenderer.render(template, vars);
        }

        long start = System.nanoTime();
        for (int i = 0; i < 1000; i++) {
            templateRenderer.render(template, vars);
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        System.out.println("[BENCHMARK] 1000 template renders: " + elapsedMs + "ms");
        assertTrue(elapsedMs < 100,
                "1000 template renders took " + elapsedMs + "ms — must complete under 100ms");
    }
}
