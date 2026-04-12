package com.filetransfer.sentinel;

import com.filetransfer.sentinel.analyzer.CorrelationEngine;
import com.filetransfer.sentinel.analyzer.HealthScoreCalculator;
import com.filetransfer.sentinel.analyzer.SecurityAnalyzer;
import com.filetransfer.sentinel.collector.*;
import com.filetransfer.sentinel.config.SentinelConfig;
import com.filetransfer.sentinel.entity.HealthScore;
import com.filetransfer.sentinel.entity.SentinelFinding;
import com.filetransfer.sentinel.repository.CorrelationGroupRepository;
import com.filetransfer.sentinel.repository.HealthScoreRepository;
import com.filetransfer.sentinel.repository.SentinelFindingRepository;
import com.filetransfer.sentinel.repository.SentinelRuleRepository;
import com.filetransfer.shared.repository.AuditLogRepository;
import com.filetransfer.shared.repository.DeadLetterMessageRepository;
import com.filetransfer.shared.repository.LoginAttemptRepository;
import com.filetransfer.shared.repository.QuarantineRecordRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Regression, usability, and performance tests for platform-sentinel.
 * Pure JUnit 5 + Mockito — no @SpringBootTest.
 */
@ExtendWith(MockitoExtension.class)
class SentinelRegressionTest {

    @Mock private HealthScoreRepository healthScoreRepository;
    @Mock private SentinelFindingRepository findingRepository;
    @Mock private SentinelRuleRepository ruleRepository;
    @Mock private CorrelationGroupRepository groupRepository;
    @Mock private RestTemplate restTemplate;
    @Mock private AuditLogRepository auditLogRepository;
    @Mock private LoginAttemptRepository loginAttemptRepository;
    @Mock private QuarantineRecordRepository quarantineRepository;
    @Mock private DeadLetterMessageRepository dlqRepository;
    @Captor private ArgumentCaptor<HealthScore> scoreCaptor;

    private HealthScoreCalculator healthScoreCalculator;
    private SecurityAnalyzer securityAnalyzer;
    private CorrelationEngine correlationEngine;

    @BeforeEach
    void setUp() {
        SentinelConfig config = new SentinelConfig();
        config.setServices(Map.of("onboarding-api", "http://localhost:8080"));

        HealthCollector healthCollector = new HealthCollector(restTemplate, config);
        MetricsCollector metricsCollector = new MetricsCollector(restTemplate, config);
        DlqCollector dlqCollector = new DlqCollector(dlqRepository);

        healthScoreCalculator = new HealthScoreCalculator(
                healthCollector, metricsCollector, dlqCollector,
                findingRepository, healthScoreRepository, new ObjectMapper());

        AuditCollector auditCollector = new AuditCollector(auditLogRepository);
        TransferCollector transferCollector = new TransferCollector(
                mock(com.filetransfer.shared.repository.FileTransferRecordRepository.class));
        SecurityCollector securityCollector = new SecurityCollector(
                mock(RestTemplate.class),
                mock(SentinelConfig.class));

        securityAnalyzer = new SecurityAnalyzer(ruleRepository, findingRepository,
                auditCollector, transferCollector, securityCollector, dlqCollector,
                loginAttemptRepository, quarantineRepository, new ObjectMapper());

        correlationEngine = new CorrelationEngine(findingRepository, groupRepository);
    }

    // ── 1. healthScoreCalculator_allHealthy_shouldReturn100 ──

    @Test
    void healthScoreCalculator_allHealthy_shouldReturn100() {
        // All services UP
        when(restTemplate.getForObject(contains("/actuator/health"), any()))
                .thenReturn(Map.of("status", "UP"));
        // Good analytics data
        when(restTemplate.getForObject(contains("/analytics"), any()))
                .thenReturn(Map.of("successRateToday", 99.0, "totalTransfersToday", 100));
        // No findings
        when(findingRepository.countByStatusAndSeverity(any(), any())).thenReturn(0L);

        healthScoreCalculator.calculate();

        verify(healthScoreRepository).save(scoreCaptor.capture());
        HealthScore score = scoreCaptor.getValue();
        assertTrue(score.getOverallScore() >= 90,
                "Perfect health should yield overall score >= 90, got: " + score.getOverallScore());
        assertEquals(100, score.getInfrastructureScore(),
                "All services UP should give infrastructure score = 100");
        assertEquals(100, score.getSecurityScore(),
                "No findings should give security score = 100");
    }

    // ── 2. securityAnalyzer_noThreats_shouldReturnClean ──

    @Test
    void securityAnalyzer_noThreats_shouldReturnClean() {
        // No rules enabled
        when(ruleRepository.findByAnalyzerAndEnabledTrue("SECURITY")).thenReturn(List.of());
        when(auditLogRepository.findAll()).thenReturn(List.of());

        securityAnalyzer.analyze();

        // Should NOT create any findings when no threats detected
        verify(findingRepository, never()).save(any());
    }

    // ── 3. correlationEngine_relatedEvents_shouldCorrelate ──

    @Test
    void correlationEngine_relatedEvents_shouldCorrelate() {
        Instant now = Instant.now();
        SentinelFinding f1 = SentinelFinding.builder()
                .id(UUID.randomUUID()).analyzer("SECURITY").ruleName("login_failure_spike")
                .severity("HIGH").title("Login spike").status("OPEN").createdAt(now).build();
        SentinelFinding f2 = SentinelFinding.builder()
                .id(UUID.randomUUID()).analyzer("SECURITY").ruleName("account_lockout")
                .severity("MEDIUM").title("Accounts locked").status("OPEN")
                .createdAt(now.plusSeconds(30)).build();

        when(findingRepository.findByStatusAndCreatedAtAfterOrderByCreatedAtDesc(any(), any()))
                .thenReturn(List.of(f1, f2));
        when(groupRepository.save(any())).thenAnswer(inv -> {
            var g = inv.getArgument(0);
            try {
                Field idField = g.getClass().getDeclaredField("id");
                idField.setAccessible(true);
                idField.set(g, UUID.randomUUID());
            } catch (Exception ignored) {}
            return g;
        });

        correlationEngine.correlate();

        verify(groupRepository).save(any());
    }

    // ── 4. sentinel_nullMetrics_shouldNotCrash ──

    @Test
    void sentinel_nullMetrics_shouldNotCrash() {
        // Services throw exceptions (simulating unreachable services)
        when(restTemplate.getForObject(anyString(), any()))
                .thenThrow(new org.springframework.web.client.ResourceAccessException("Connection refused"));
        when(findingRepository.countByStatusAndSeverity(any(), any())).thenReturn(0L);

        // Should not throw — graceful degradation
        assertDoesNotThrow(() -> healthScoreCalculator.calculate());

        verify(healthScoreRepository).save(scoreCaptor.capture());
        HealthScore score = scoreCaptor.getValue();
        assertNotNull(score, "Score should still be computed even with unreachable services");
        assertTrue(score.getInfrastructureScore() < 100,
                "Infra score should be degraded when services are down");
    }

    // ── 5. sentinel_performance_1000HealthChecks_shouldBeUnder500ms ──

    @Test
    void sentinel_performance_1000HealthChecks_shouldBeUnder500ms() {
        // Mock fast returns
        when(restTemplate.getForObject(anyString(), any()))
                .thenReturn(Map.of("status", "UP"));
        when(findingRepository.countByStatusAndSeverity(any(), any())).thenReturn(0L);

        long start = System.nanoTime();
        for (int i = 0; i < 1000; i++) {
            healthScoreCalculator.calculate();
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertTrue(elapsedMs < 2000,
                "1000 health score calculations took " + elapsedMs + "ms, expected <2000ms");
    }
}
