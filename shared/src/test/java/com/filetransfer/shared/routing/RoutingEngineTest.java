package com.filetransfer.shared.routing;

import com.filetransfer.shared.audit.AuditService;
import com.filetransfer.shared.cluster.ClusterService;
import com.filetransfer.shared.config.PlatformConfig;
import com.filetransfer.shared.connector.ConnectorDispatcher;
import com.filetransfer.shared.entity.*;
import com.filetransfer.shared.enums.FileTransferStatus;
import com.filetransfer.shared.repository.FileFlowRepository;
import com.filetransfer.shared.repository.FileTransferRecordRepository;
import com.filetransfer.shared.util.TrackIdGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RoutingEngineTest {

    private RoutingEvaluator evaluator;
    private FileTransferRecordRepository recordRepository;
    private ClusterService clusterService;
    private RestTemplate restTemplate;
    private TrackIdGenerator trackIdGenerator;
    private FlowProcessingEngine flowEngine;
    private FileFlowRepository flowRepository;
    private AuditService auditService;
    private AiClassificationClient aiClassifier;
    private ConnectorDispatcher connectorDispatcher;
    private PlatformConfig platformConfig;
    private RoutingEngine routingEngine;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        evaluator = mock(RoutingEvaluator.class);
        recordRepository = mock(FileTransferRecordRepository.class);
        clusterService = mock(ClusterService.class);
        restTemplate = mock(RestTemplate.class);
        trackIdGenerator = mock(TrackIdGenerator.class);
        flowEngine = mock(FlowProcessingEngine.class);
        flowRepository = mock(FileFlowRepository.class);
        auditService = mock(AuditService.class);
        aiClassifier = mock(AiClassificationClient.class);
        connectorDispatcher = mock(ConnectorDispatcher.class);
        platformConfig = new PlatformConfig();

        routingEngine = new RoutingEngine(
                evaluator, recordRepository, clusterService, restTemplate,
                trackIdGenerator, flowEngine, flowRepository, auditService,
                aiClassifier, connectorDispatcher, platformConfig
        );
    }

    @Test
    void embedTrackId_addsTrackIdToFilename() throws Exception {
        Method m = RoutingEngine.class.getDeclaredMethod("embedTrackId", String.class, String.class);
        m.setAccessible(true);

        assertEquals("report.csv#TRZ123", m.invoke(routingEngine, "report.csv", "TRZ123"));
    }

    @Test
    void embedTrackId_nullTrackId_returnsOriginal() throws Exception {
        Method m = RoutingEngine.class.getDeclaredMethod("embedTrackId", String.class, String.class);
        m.setAccessible(true);

        assertEquals("report.csv", m.invoke(routingEngine, "report.csv", null));
        assertEquals("report.csv", m.invoke(routingEngine, "report.csv", "  "));
    }

    @Test
    void matchesFlow_noPatterns_matchesEverything() throws Exception {
        Method m = RoutingEngine.class.getDeclaredMethod("matchesFlow", FileFlow.class, String.class, String.class);
        m.setAccessible(true);

        FileFlow flow = new FileFlow();
        flow.setFilenamePattern(null);
        flow.setSourcePath(null);

        assertTrue((boolean) m.invoke(routingEngine, flow, "test.csv", "/inbox/test.csv"));
    }

    @Test
    void matchesFlow_filenamePatternMatches() throws Exception {
        Method m = RoutingEngine.class.getDeclaredMethod("matchesFlow", FileFlow.class, String.class, String.class);
        m.setAccessible(true);

        FileFlow flow = new FileFlow();
        flow.setFilenamePattern(".*\\.csv");
        flow.setSourcePath(null);

        assertTrue((boolean) m.invoke(routingEngine, flow, "report.csv", "/inbox/report.csv"));
        assertFalse((boolean) m.invoke(routingEngine, flow, "report.pdf", "/inbox/report.pdf"));
    }

    @Test
    void matchesFlow_sourcePathMatches() throws Exception {
        Method m = RoutingEngine.class.getDeclaredMethod("matchesFlow", FileFlow.class, String.class, String.class);
        m.setAccessible(true);

        FileFlow flow = new FileFlow();
        flow.setFilenamePattern(null);
        flow.setSourcePath("/inbox");

        assertTrue((boolean) m.invoke(routingEngine, flow, "test.csv", "/home/user/inbox/test.csv"));
        assertFalse((boolean) m.invoke(routingEngine, flow, "test.csv", "/home/user/outbox/test.csv"));
    }

    @Test
    void onFileUploaded_noRoutingRulesOrFlows_doesNothing() throws IOException {
        TransferAccount account = mock(TransferAccount.class);
        when(account.getUsername()).thenReturn("testuser");

        Path testFile = tempDir.resolve("test.txt");
        Files.writeString(testFile, "hello");

        when(trackIdGenerator.generate()).thenReturn("TRZ000001");
        when(flowRepository.findMatchingFlows(account)).thenReturn(Collections.emptyList());
        when(aiClassifier.classify(any(), anyString(), anyBoolean()))
                .thenReturn(new AiClassificationClient.ClassificationDecision(true, "NONE", 0, null));
        when(evaluator.evaluate(any(), anyString(), anyString())).thenReturn(Collections.emptyList());

        routingEngine.onFileUploaded(account, "inbox/test.txt", testFile.toString());

        verify(recordRepository, never()).save(any());
    }

    @Test
    void onFileUploaded_aiBlocks_stopsRouting() throws IOException {
        TransferAccount account = mock(TransferAccount.class);
        when(account.getUsername()).thenReturn("testuser");

        Path testFile = tempDir.resolve("pci_data.csv");
        Files.writeString(testFile, "4111-1111-1111-1111");

        when(trackIdGenerator.generate()).thenReturn("TRZ000002");
        when(flowRepository.findMatchingFlows(account)).thenReturn(Collections.emptyList());
        when(aiClassifier.classify(any(), anyString(), anyBoolean()))
                .thenReturn(new AiClassificationClient.ClassificationDecision(
                        false, "CRITICAL", 95, "PCI data detected"));

        routingEngine.onFileUploaded(account, "inbox/pci_data.csv", testFile.toString());

        // Should log failure and dispatch event, not route
        verify(auditService).logFailure(eq(account), eq("TRZ000002"), eq("AI_BLOCKED"),
                anyString(), eq("PCI data detected"));
        verify(connectorDispatcher).dispatch(any());
        verify(evaluator, never()).evaluate(any(), anyString(), anyString());
    }

    @Test
    void onFileUploaded_withMatchingFlow_executesFlow() throws IOException {
        TransferAccount account = mock(TransferAccount.class);
        when(account.getUsername()).thenReturn("testuser");

        Path testFile = tempDir.resolve("report.csv");
        Files.writeString(testFile, "data");

        FileFlow flow = new FileFlow();
        flow.setName("compress-flow");
        flow.setFilenamePattern(".*\\.csv");
        flow.setSourcePath(null);
        flow.setSteps(List.of());

        FlowExecution exec = FlowExecution.builder()
                .status(FlowExecution.FlowStatus.COMPLETED)
                .currentFilePath(testFile.toString())
                .build();

        when(trackIdGenerator.generate()).thenReturn("TRZ000003");
        when(flowRepository.findMatchingFlows(account)).thenReturn(List.of(flow));
        when(flowEngine.executeFlow(eq(flow), eq("TRZ000003"), eq("report.csv"), eq(testFile.toString())))
                .thenReturn(exec);
        when(aiClassifier.classify(any(), anyString(), anyBoolean()))
                .thenReturn(new AiClassificationClient.ClassificationDecision(true, "NONE", 0, null));
        when(evaluator.evaluate(any(), anyString(), anyString())).thenReturn(Collections.emptyList());

        routingEngine.onFileUploaded(account, "inbox/report.csv", testFile.toString());

        verify(flowEngine).executeFlow(eq(flow), eq("TRZ000003"), eq("report.csv"), eq(testFile.toString()));
    }

    @Test
    void markFailed_setsStatusAndDispatchesEvent() throws Exception {
        FileTransferRecord record = FileTransferRecord.builder()
                .trackId("TRZ000004")
                .originalFilename("test.csv")
                .status(FileTransferStatus.PENDING)
                .build();

        when(recordRepository.save(any())).thenReturn(record);

        Method m = RoutingEngine.class.getDeclaredMethod("markFailed", FileTransferRecord.class, String.class);
        m.setAccessible(true);
        m.invoke(routingEngine, record, "Connection timeout");

        assertEquals(FileTransferStatus.FAILED, record.getStatus());
        assertEquals("Connection timeout", record.getErrorMessage());
        verify(recordRepository).save(record);
        verify(connectorDispatcher).dispatch(argThat(event ->
                "TRANSFER_FAILED".equals(event.getEventType())));
    }
}
