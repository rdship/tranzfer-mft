package com.filetransfer.sentinel.analyzer;

import com.filetransfer.sentinel.entity.CorrelationGroup;
import com.filetransfer.sentinel.entity.SentinelFinding;
import com.filetransfer.sentinel.repository.CorrelationGroupRepository;
import com.filetransfer.sentinel.repository.SentinelFindingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CorrelationEngineTest {

    @Mock private SentinelFindingRepository findingRepository;
    @Mock private CorrelationGroupRepository groupRepository;
    @Captor private ArgumentCaptor<CorrelationGroup> groupCaptor;

    private CorrelationEngine engine;

    @BeforeEach
    void setUp() {
        engine = new CorrelationEngine(findingRepository, groupRepository);
    }

    @Test
    void correlate_twoFindingsWithinWindow_createsGroup() {
        Instant now = Instant.now();
        SentinelFinding f1 = SentinelFinding.builder()
                .id(UUID.randomUUID()).analyzer("PERFORMANCE").ruleName("service_unhealthy")
                .severity("CRITICAL").title("Service DOWN").status("OPEN")
                .affectedService("gateway-service").createdAt(now).build();
        SentinelFinding f2 = SentinelFinding.builder()
                .id(UUID.randomUUID()).analyzer("SECURITY").ruleName("failed_transfer_spike")
                .severity("HIGH").title("Failure spike").status("OPEN")
                .createdAt(now.plusSeconds(60)).build();

        when(findingRepository.findByStatusAndCreatedAtAfterOrderByCreatedAtDesc(any(), any()))
                .thenReturn(List.of(f1, f2));
        when(groupRepository.save(any())).thenAnswer(inv -> {
            CorrelationGroup g = inv.getArgument(0);
            g.setId(UUID.randomUUID());
            return g;
        });

        engine.correlate();

        verify(groupRepository).save(groupCaptor.capture());
        CorrelationGroup group = groupCaptor.getValue();
        assertEquals(2, group.getFindingCount());
        assertTrue(group.getRootCause().contains("Service outage"));
    }

    @Test
    void correlate_singleFinding_noGroup() {
        SentinelFinding f1 = SentinelFinding.builder()
                .id(UUID.randomUUID()).analyzer("SECURITY").ruleName("login_failure_spike")
                .severity("HIGH").title("Login failures").status("OPEN")
                .createdAt(Instant.now()).build();

        when(findingRepository.findByStatusAndCreatedAtAfterOrderByCreatedAtDesc(any(), any()))
                .thenReturn(List.of(f1));

        engine.correlate();

        verify(groupRepository, never()).save(any());
    }

    @Test
    void correlate_loginSpikePlusLockout_bruteForceRoot() {
        Instant now = Instant.now();
        SentinelFinding f1 = SentinelFinding.builder()
                .id(UUID.randomUUID()).analyzer("SECURITY").ruleName("login_failure_spike")
                .severity("HIGH").title("Login spike").status("OPEN").createdAt(now).build();
        SentinelFinding f2 = SentinelFinding.builder()
                .id(UUID.randomUUID()).analyzer("SECURITY").ruleName("account_lockout")
                .severity("MEDIUM").title("Account locked").status("OPEN")
                .createdAt(now.plusSeconds(30)).build();

        when(findingRepository.findByStatusAndCreatedAtAfterOrderByCreatedAtDesc(any(), any()))
                .thenReturn(List.of(f1, f2));
        when(groupRepository.save(any())).thenAnswer(inv -> {
            CorrelationGroup g = inv.getArgument(0);
            g.setId(UUID.randomUUID());
            return g;
        });

        engine.correlate();

        verify(groupRepository).save(groupCaptor.capture());
        assertTrue(groupCaptor.getValue().getRootCause().contains("brute-force"));
    }
}
