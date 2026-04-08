package com.filetransfer.sentinel.analyzer;

import com.filetransfer.sentinel.collector.*;
import com.filetransfer.sentinel.entity.SentinelFinding;
import com.filetransfer.sentinel.entity.SentinelRule;
import com.filetransfer.sentinel.repository.SentinelFindingRepository;
import com.filetransfer.sentinel.repository.SentinelRuleRepository;
import com.filetransfer.shared.entity.AuditLog;
import com.filetransfer.shared.entity.FileTransferRecord;
import com.filetransfer.shared.entity.LoginAttempt;
import com.filetransfer.shared.enums.FileTransferStatus;
import com.filetransfer.shared.repository.AuditLogRepository;
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

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SecurityAnalyzerTest {

    @Mock private SentinelRuleRepository ruleRepository;
    @Mock private SentinelFindingRepository findingRepository;
    @Mock private AuditLogRepository auditLogRepository;
    @Mock private LoginAttemptRepository loginAttemptRepository;
    @Mock private QuarantineRecordRepository quarantineRepository;
    @Captor private ArgumentCaptor<SentinelFinding> findingCaptor;

    private SecurityAnalyzer analyzer;

    @BeforeEach
    void setUp() {
        AuditCollector auditCollector = new AuditCollector(auditLogRepository);
        TransferCollector transferCollector = new TransferCollector(
                mock(com.filetransfer.shared.repository.FileTransferRecordRepository.class));
        SecurityCollector securityCollector = new SecurityCollector(
                mock(org.springframework.web.client.RestTemplate.class),
                mock(com.filetransfer.sentinel.config.SentinelConfig.class));
        DlqCollector dlqCollector = new DlqCollector(
                mock(com.filetransfer.shared.repository.DeadLetterMessageRepository.class));

        analyzer = new SecurityAnalyzer(ruleRepository, findingRepository,
                auditCollector, transferCollector, securityCollector, dlqCollector,
                loginAttemptRepository, quarantineRepository, new ObjectMapper());
    }

    @Test
    void analyze_loginFailureSpike_createsFinding() {
        SentinelRule rule = SentinelRule.builder()
                .name("login_failure_spike").analyzer("SECURITY").severity("HIGH")
                .thresholdValue(2.0).windowMinutes(60).cooldownMinutes(30).enabled(true).build();
        when(ruleRepository.findByAnalyzerAndEnabledTrue("SECURITY")).thenReturn(List.of(rule));

        // Seed 5 LOGIN_FAIL audit logs
        List<AuditLog> logs = new java.util.ArrayList<>();
        for (int i = 0; i < 5; i++) {
            logs.add(AuditLog.builder().action("LOGIN_FAIL").timestamp(Instant.now()).build());
        }
        when(auditLogRepository.findAll()).thenReturn(logs);
        when(findingRepository.existsByAnalyzerAndRuleNameAndAffectedServiceAndTrackId(
                any(), any(), any(), any())).thenReturn(false);

        analyzer.analyze();

        verify(findingRepository).save(findingCaptor.capture());
        SentinelFinding finding = findingCaptor.getValue();
        assertEquals("SECURITY", finding.getAnalyzer());
        assertEquals("login_failure_spike", finding.getRuleName());
        assertEquals("HIGH", finding.getSeverity());
        assertTrue(finding.getTitle().contains("5 failures"));
    }

    @Test
    void analyze_accountLockout_createsFinding() {
        SentinelRule rule = SentinelRule.builder()
                .name("account_lockout").analyzer("SECURITY").severity("MEDIUM")
                .thresholdValue(1.0).windowMinutes(60).cooldownMinutes(60).enabled(true).build();
        when(ruleRepository.findByAnalyzerAndEnabledTrue("SECURITY")).thenReturn(List.of(rule));
        when(auditLogRepository.findAll()).thenReturn(List.of());

        LoginAttempt locked = new LoginAttempt();
        locked.setUsername("testuser");
        locked.setLockedUntil(Instant.now().plusSeconds(600));
        when(loginAttemptRepository.findByLockedUntilAfter(any())).thenReturn(List.of(locked));
        when(findingRepository.existsByAnalyzerAndRuleNameAndAffectedServiceAndTrackId(
                any(), any(), any(), any())).thenReturn(false);

        analyzer.analyze();

        verify(findingRepository).save(findingCaptor.capture());
        assertTrue(findingCaptor.getValue().getTitle().contains("1 accounts locked"));
    }

    @Test
    void analyze_ruleInCooldown_skips() {
        SentinelRule rule = SentinelRule.builder()
                .name("login_failure_spike").analyzer("SECURITY").severity("HIGH")
                .thresholdValue(2.0).windowMinutes(60).cooldownMinutes(30).enabled(true)
                .lastTriggered(Instant.now()).build(); // Just triggered — in cooldown
        when(ruleRepository.findByAnalyzerAndEnabledTrue("SECURITY")).thenReturn(List.of(rule));
        when(auditLogRepository.findAll()).thenReturn(List.of());

        analyzer.analyze();

        verify(findingRepository, never()).save(any());
    }

    @Test
    void analyze_belowThreshold_noFinding() {
        SentinelRule rule = SentinelRule.builder()
                .name("login_failure_spike").analyzer("SECURITY").severity("HIGH")
                .thresholdValue(10.0).windowMinutes(60).cooldownMinutes(30).enabled(true).build();
        when(ruleRepository.findByAnalyzerAndEnabledTrue("SECURITY")).thenReturn(List.of(rule));

        // Only 2 failures — below threshold of 10
        AuditLog log1 = AuditLog.builder().action("LOGIN_FAIL").timestamp(Instant.now()).build();
        AuditLog log2 = AuditLog.builder().action("LOGIN_FAIL").timestamp(Instant.now()).build();
        when(auditLogRepository.findAll()).thenReturn(List.of(log1, log2));

        analyzer.analyze();

        verify(findingRepository, never()).save(any());
    }

    @Test
    void analyze_noRulesEnabled_noFindings() {
        when(ruleRepository.findByAnalyzerAndEnabledTrue("SECURITY")).thenReturn(List.of());
        when(auditLogRepository.findAll()).thenReturn(List.of());

        analyzer.analyze();

        verify(findingRepository, never()).save(any());
    }
}
