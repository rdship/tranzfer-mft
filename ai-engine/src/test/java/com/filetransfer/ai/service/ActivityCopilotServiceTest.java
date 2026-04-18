package com.filetransfer.ai.service;

import com.filetransfer.shared.entity.transfer.FileTransferRecord;
import com.filetransfer.shared.entity.transfer.FlowExecution;
import com.filetransfer.shared.entity.transfer.FlowStepSnapshot;
import com.filetransfer.shared.enums.FileTransferStatus;
import com.filetransfer.shared.repository.core.AuditLogRepository;
import com.filetransfer.shared.repository.transfer.FileTransferRecordRepository;
import com.filetransfer.shared.repository.transfer.FlowEventRepository;
import com.filetransfer.shared.repository.transfer.FlowExecutionRepository;
import com.filetransfer.shared.repository.transfer.FlowStepSnapshotRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * R107: pins the ActivityCopilotService contract so the UI + admins can rely
 * on deterministic output shape. These tests run entirely on stubs — no LLM,
 * no DB, no context — which matches the default deterministic path.
 */
class ActivityCopilotServiceTest {

    private FileTransferRecordRepository transferRecords;
    private FlowExecutionRepository flowExecutions;
    private FlowStepSnapshotRepository stepSnapshots;
    private FlowEventRepository flowEvents;
    private AuditLogRepository auditLogs;
    private ActivityCopilotService copilot;

    @BeforeEach
    void setUp() throws Exception {
        transferRecords = mock(FileTransferRecordRepository.class);
        flowExecutions = mock(FlowExecutionRepository.class);
        stepSnapshots = mock(FlowStepSnapshotRepository.class);
        flowEvents = mock(FlowEventRepository.class);
        auditLogs = mock(AuditLogRepository.class);

        when(auditLogs.findAll(any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));
        when(flowEvents.findByTrackIdOrderByCreatedAtAsc(any())).thenReturn(List.of());

        copilot = new ActivityCopilotService(transferRecords, auditLogs);
        // Inject nullable dependencies via reflection (required=false fields)
        setField("flowExecutions", flowExecutions);
        setField("stepSnapshots", stepSnapshots);
        setField("flowEvents", flowEvents);
    }

    private void setField(String name, Object value) throws Exception {
        Field f = ActivityCopilotService.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(copilot, value);
    }

    @Test
    void analyzeReturnsNotFoundWhenNothingKnown() {
        when(transferRecords.findByTrackId("TRZ-UNKNOWN")).thenReturn(Optional.empty());
        when(flowExecutions.findByTrackId("TRZ-UNKNOWN")).thenReturn(Optional.empty());
        when(stepSnapshots.findByTrackIdOrderByStepIndex("TRZ-UNKNOWN")).thenReturn(List.of());

        ActivityCopilotService.AnalysisResult r = copilot.analyze("TRZ-UNKNOWN");
        assertThat(r.currentState()).isEqualTo("NOT_FOUND");
        assertThat(r.milestones()).isEmpty();
    }

    @Test
    void diagnoseClassifiesNetworkErrorAndSuggestsRestartFromStep() {
        FlowStepSnapshot failed = FlowStepSnapshot.builder()
                .id(UUID.randomUUID()).trackId("TRZ-NET").stepIndex(2)
                .stepType("CONVERT_EDI").stepStatus("FAILED")
                .errorMessage("HTTP 502 from edi-converter — connection refused")
                .durationMs(410L).build();
        givenSteps("TRZ-NET", List.of(failed));

        ActivityCopilotService.DiagnosisResult d = copilot.diagnose("TRZ-NET");
        assertThat(d.category()).isEqualTo("NETWORK");
        assertThat(d.stepIndex()).isEqualTo(2);
        assertThat(d.recommendedActions()).anyMatch(a -> "RESTART_FROM_STEP".equals(a.action()));
        assertThat(d.recommendedActions().stream().filter(a -> "RESTART_FROM_STEP".equals(a.action())).findFirst().get()
                .apiPath()).contains("/restart/2");
    }

    @Test
    void diagnoseClassifiesScreeningBlockAndWarnsAgainstRetry() {
        FlowStepSnapshot blocked = FlowStepSnapshot.builder()
                .id(UUID.randomUUID()).trackId("TRZ-BLK").stepIndex(1)
                .stepType("SCREEN").stepStatus("FAILED")
                .errorMessage("SANCTIONS HIT: 3 match(es) found.").build();
        givenSteps("TRZ-BLK", List.of(blocked));

        ActivityCopilotService.DiagnosisResult d = copilot.diagnose("TRZ-BLK");
        assertThat(d.category()).isEqualTo("SCREENING_BLOCK");
        assertThat(d.recommendedActions().get(0).action()).isEqualTo("MANUAL");
        assertThat(d.recommendedActions()).noneMatch(a -> "RESTART_FROM_STEP".equals(a.action()));
    }

    @Test
    void analyzeBuildsNarrativeFromR105bStepDetails() {
        FileTransferRecord rec = FileTransferRecord.builder()
                .id(UUID.randomUUID()).trackId("TRZ-OK").originalFilename("po850.edi")
                .sourceFilePath("/in").destinationFilePath("/out")
                .status(FileTransferStatus.COMPLETED)
                .uploadedAt(Instant.now().minusSeconds(30))
                .completedAt(Instant.now()).build();
        FlowStepSnapshot edi = FlowStepSnapshot.builder()
                .id(UUID.randomUUID()).trackId("TRZ-OK").stepIndex(0)
                .stepType("CONVERT_EDI").stepStatus("OK").durationMs(340L)
                .stepDetailsJson("{\"sourceFormat\":\"X12\",\"targetFormat\":\"JSON\",\"rows\":12,\"mapUsed\":\"ACME-850-v3\"}")
                .build();
        when(transferRecords.findByTrackId("TRZ-OK")).thenReturn(Optional.of(rec));
        when(flowExecutions.findByTrackId("TRZ-OK")).thenReturn(Optional.empty());
        when(stepSnapshots.findByTrackIdOrderByStepIndex("TRZ-OK")).thenReturn(List.of(edi));

        ActivityCopilotService.AnalysisResult r = copilot.analyze("TRZ-OK");
        assertThat(r.summary()).contains("po850.edi")
                .contains("X12")
                .contains("JSON")
                .contains("12 rows");
        assertThat(r.metrics()).containsEntry("successfulSteps", 1L);
        assertThat(r.milestones()).hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    void chatAnswersRetryQuestionWithAnActionableApiPath() {
        FlowStepSnapshot failed = FlowStepSnapshot.builder()
                .id(UUID.randomUUID()).trackId("TRZ-CHAT").stepIndex(3)
                .stepType("FILE_DELIVERY").stepStatus("FAILED")
                .errorMessage("connection timeout to partner SFTP").build();
        givenSteps("TRZ-CHAT", List.of(failed));

        ActivityCopilotService.ChatResult c = copilot.chat("TRZ-CHAT", "Can I safely retry this?");
        assertThat(c.answer()).contains("retry").contains("/restart/3");
    }

    @Test
    void chatHandlesBlankMessageWithOnboardingPrompt() {
        givenSteps("TRZ-HELLO", List.of());
        ActivityCopilotService.ChatResult c = copilot.chat("TRZ-HELLO", "");
        assertThat(c.answer()).contains("Ask me anything");
    }

    @Test
    void suggestForHealthyProcessingRecommendsWait() {
        FlowExecution exec = FlowExecution.builder()
                .id(UUID.randomUUID()).trackId("TRZ-PROG").originalFilename("f.dat")
                .status(FlowExecution.FlowStatus.PROCESSING)
                .startedAt(Instant.now().minusSeconds(5))
                .currentStep(1).build();
        when(transferRecords.findByTrackId("TRZ-PROG")).thenReturn(Optional.empty());
        when(flowExecutions.findByTrackId("TRZ-PROG")).thenReturn(Optional.of(exec));
        when(stepSnapshots.findByTrackIdOrderByStepIndex("TRZ-PROG")).thenReturn(List.of());

        List<ActivityCopilotService.SuggestedAction> s = copilot.suggestActions("TRZ-PROG");
        assertThat(s).anyMatch(a -> "WAIT".equals(a.action()));
    }

    private void givenSteps(String trackId, List<FlowStepSnapshot> steps) {
        when(transferRecords.findByTrackId(trackId)).thenReturn(Optional.empty());
        when(flowExecutions.findByTrackId(trackId)).thenReturn(Optional.empty());
        when(stepSnapshots.findByTrackIdOrderByStepIndex(trackId)).thenReturn(steps);
    }
}
