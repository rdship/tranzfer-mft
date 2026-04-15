package com.filetransfer.sentinel.analyzer;

import com.filetransfer.sentinel.collector.HealthCollector;
import com.filetransfer.sentinel.collector.MetricsCollector;
import com.filetransfer.sentinel.collector.TransferCollector;
import com.filetransfer.sentinel.config.SentinelConfig;
import com.filetransfer.sentinel.entity.SentinelFinding;
import com.filetransfer.sentinel.entity.SentinelRule;
import com.filetransfer.sentinel.repository.SentinelFindingRepository;
import com.filetransfer.sentinel.repository.SentinelRuleRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PerformanceAnalyzerTest {

    @Mock private SentinelRuleRepository ruleRepository;
    @Mock private SentinelFindingRepository findingRepository;
    @Mock private RestTemplate restTemplate;
    @Captor private ArgumentCaptor<SentinelFinding> findingCaptor;

    private PerformanceAnalyzer analyzer;
    private HealthCollector healthCollector;

    @BeforeEach
    void setUp() {
        SentinelConfig config = new SentinelConfig();
        config.setServices(Map.of(
                "onboarding-api", "http://localhost:8080",
                "sftp-service", "http://localhost:8081"
        ));

        MetricsCollector metricsCollector = new MetricsCollector(restTemplate, config);
        TransferCollector transferCollector = new TransferCollector(
                mock(com.filetransfer.shared.repository.transfer.FileTransferRecordRepository.class));
        healthCollector = new HealthCollector(restTemplate, config);

        analyzer = new PerformanceAnalyzer(ruleRepository, findingRepository,
                metricsCollector, transferCollector, healthCollector, new ObjectMapper());
    }

    @Test
    void analyze_serviceDown_createsFinding() {
        SentinelRule rule = SentinelRule.builder()
                .name("service_unhealthy").analyzer("PERFORMANCE").severity("CRITICAL")
                .thresholdValue(1.0).windowMinutes(5).cooldownMinutes(10).enabled(true).build();
        when(ruleRepository.findByAnalyzerAndEnabledTrue("PERFORMANCE")).thenReturn(List.of(rule));

        // Simulate health check failure
        when(restTemplate.getForObject(contains("/actuator/health"), any()))
                .thenThrow(new org.springframework.web.client.ResourceAccessException("Connection refused"));
        when(restTemplate.getForObject(contains("/analytics"), any())).thenReturn(null);

        analyzer.analyze();

        verify(findingRepository).save(findingCaptor.capture());
        SentinelFinding finding = findingCaptor.getValue();
        assertEquals("service_unhealthy", finding.getRuleName());
        assertEquals("CRITICAL", finding.getSeverity());
        assertTrue(finding.getTitle().contains("DOWN"));
    }

    @Test
    void analyze_allServicesUp_noFinding() {
        SentinelRule rule = SentinelRule.builder()
                .name("service_unhealthy").analyzer("PERFORMANCE").severity("CRITICAL")
                .thresholdValue(1.0).windowMinutes(5).cooldownMinutes(10).enabled(true).build();
        when(ruleRepository.findByAnalyzerAndEnabledTrue("PERFORMANCE")).thenReturn(List.of(rule));

        // Simulate all healthy
        when(restTemplate.getForObject(anyString(), any()))
                .thenReturn(Map.of("status", "UP"));

        analyzer.analyze();

        verify(findingRepository, never()).save(any());
    }

    @Test
    void analyze_noRulesEnabled_noFindings() {
        when(ruleRepository.findByAnalyzerAndEnabledTrue("PERFORMANCE")).thenReturn(List.of());
        when(restTemplate.getForObject(anyString(), any())).thenReturn(null);

        analyzer.analyze();

        verify(findingRepository, never()).save(any());
    }
}
