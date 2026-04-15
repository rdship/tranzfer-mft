package com.filetransfer.sentinel.analyzer;

import com.filetransfer.sentinel.collector.DlqCollector;
import com.filetransfer.sentinel.collector.HealthCollector;
import com.filetransfer.sentinel.collector.MetricsCollector;
import com.filetransfer.sentinel.config.SentinelConfig;
import com.filetransfer.sentinel.entity.HealthScore;
import com.filetransfer.sentinel.repository.HealthScoreRepository;
import com.filetransfer.sentinel.repository.SentinelFindingRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class HealthScoreCalculatorTest {

    @Mock private HealthScoreRepository healthScoreRepository;
    @Mock private SentinelFindingRepository findingRepository;
    @Mock private RestTemplate restTemplate;
    @Captor private ArgumentCaptor<HealthScore> scoreCaptor;

    private HealthScoreCalculator calculator;

    @BeforeEach
    void setUp() {
        SentinelConfig config = new SentinelConfig();
        config.setServices(Map.of("onboarding-api", "http://localhost:8080"));

        HealthCollector healthCollector = new HealthCollector(restTemplate, config);
        MetricsCollector metricsCollector = new MetricsCollector(restTemplate, config);
        DlqCollector dlqCollector = new DlqCollector(
                mock(com.filetransfer.shared.repository.transfer.DeadLetterMessageRepository.class));

        calculator = new HealthScoreCalculator(healthCollector, metricsCollector, dlqCollector,
                findingRepository, healthScoreRepository, new ObjectMapper());
    }

    @Test
    void calculate_allHealthy_perfectScore() {
        // All services UP
        when(restTemplate.getForObject(contains("/actuator/health"), any()))
                .thenReturn(Map.of("status", "UP"));
        // Analytics returns good data
        when(restTemplate.getForObject(contains("/analytics"), any()))
                .thenReturn(Map.of("successRateToday", 99.0, "totalTransfersToday", 100));
        // No findings
        when(findingRepository.countByStatusAndSeverity(any(), any())).thenReturn(0L);

        calculator.calculate();

        verify(healthScoreRepository).save(scoreCaptor.capture());
        HealthScore score = scoreCaptor.getValue();
        assertTrue(score.getOverallScore() >= 90, "Expected high score, got: " + score.getOverallScore());
        assertEquals(100, score.getInfrastructureScore());
    }

    @Test
    void calculate_servicesDown_lowInfraScore() {
        // Services DOWN
        when(restTemplate.getForObject(anyString(), any()))
                .thenThrow(new org.springframework.web.client.ResourceAccessException("Connection refused"));
        when(findingRepository.countByStatusAndSeverity(any(), any())).thenReturn(0L);

        calculator.calculate();

        verify(healthScoreRepository).save(scoreCaptor.capture());
        HealthScore score = scoreCaptor.getValue();
        assertTrue(score.getInfrastructureScore() < 100, "Expected degraded infra score");
    }

    @Test
    void calculate_criticalFindings_lowSecurityScore() {
        when(restTemplate.getForObject(anyString(), any()))
                .thenReturn(Map.of("status", "UP"));
        // 3 critical findings open
        when(findingRepository.countByStatusAndSeverity("OPEN", "CRITICAL")).thenReturn(3L);
        when(findingRepository.countByStatusAndSeverity("OPEN", "HIGH")).thenReturn(0L);
        when(findingRepository.countByStatusAndSeverity("OPEN", "MEDIUM")).thenReturn(0L);

        calculator.calculate();

        verify(healthScoreRepository).save(scoreCaptor.capture());
        HealthScore score = scoreCaptor.getValue();
        // 3 critical * 15 = 45 deduction → security score = 55
        assertEquals(55, score.getSecurityScore());
    }
}
