package com.filetransfer.ai.service.edi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.filetransfer.ai.entity.edi.ConversionMap;
import com.filetransfer.ai.entity.edi.MappingCorrectionSession;
import com.filetransfer.ai.entity.edi.MappingCorrectionSession.Status;
import com.filetransfer.ai.repository.edi.ConversionMapRepository;
import com.filetransfer.ai.repository.edi.MappingCorrectionSessionRepository;
import com.filetransfer.ai.repository.edi.TrainingSessionRepository;
import com.filetransfer.ai.service.edi.MappingCorrectionInterpreter.FieldMappingDto;
import com.filetransfer.ai.service.edi.MappingCorrectionService.*;
import com.filetransfer.shared.client.ServiceClientProperties;
import com.filetransfer.shared.config.PlatformConfig;
import com.filetransfer.shared.repository.FileFlowRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests MappingCorrectionService session lifecycle:
 * start → correct → approve/reject/expire.
 *
 * Mocks repositories (interfaces, safe under JDK 25).
 * Uses real instances for concrete classes (Interpreter, TrainedMapStore, etc).
 */
@ExtendWith(MockitoExtension.class)
class MappingCorrectionServiceTest {

    @Mock private MappingCorrectionSessionRepository sessionRepo;
    @Mock private ConversionMapRepository conversionMapRepo;
    @Mock private TrainingSessionRepository trainingSessionRepo;
    @Mock private FileFlowRepository flowRepository;

    private MappingCorrectionService service;
    private ObjectMapper objectMapper;

    private static final String PARTNER_ID = "partner-acme";
    private static final String SAMPLE_EDI =
            "ISA*00*          *00*          *ZZ*ACME           *ZZ*GLOBALSUP      *240101*1200*U*00501*000000001*0*P*>~"
            + "ST*850*0001~BEG*00*NE*PO-123**20240101~"
            + "NM1*BY*1*Acme Corp*John*Doe~"
            + "SE*4*0001~IEA*1*000000001~";

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();

        // Real concrete instances (can't mock under JDK 25)
        MappingCorrectionInterpreter interpreter = new MappingCorrectionInterpreter();
        FieldEmbeddingEngine embeddingEngine = new FieldEmbeddingEngine();
        EdiMapTrainingEngine trainingEngine = new EdiMapTrainingEngine(embeddingEngine);
        TrainedMapStore mapStore = new TrainedMapStore(conversionMapRepo, trainingSessionRepo, objectMapper);
        ServiceClientProperties serviceProps = new ServiceClientProperties();
        PlatformConfig platformConfig = new PlatformConfig();

        service = new MappingCorrectionService(
                sessionRepo, interpreter, mapStore, trainingEngine,
                flowRepository, serviceProps, platformConfig, objectMapper);
    }

    // ===================================================================
    // startSession
    // ===================================================================

    @Test
    void startSession_createsNewSession() {
        when(sessionRepo.findByPartnerIdAndMapKeyAndStatus(anyString(), anyString(), eq(Status.ACTIVE)))
                .thenReturn(Optional.empty());
        // No existing map
        when(conversionMapRepo.findByMapKeyAndActiveTrue(anyString())).thenReturn(Optional.empty());
        // Save returns the same session with ID set
        when(sessionRepo.save(any(MappingCorrectionSession.class))).thenAnswer(inv -> {
            MappingCorrectionSession s = inv.getArgument(0);
            if (s.getId() == null) s.setId(UUID.randomUUID());
            if (s.getCreatedAt() == null) s.setCreatedAt(Instant.now());
            if (s.getExpiresAt() == null) s.setExpiresAt(Instant.now().plusSeconds(86400));
            return s;
        });

        StartRequest request = StartRequest.builder()
                .partnerId(PARTNER_ID)
                .sourceFormat("X12").sourceType("850")
                .targetFormat("JSON")
                .sampleInputContent(SAMPLE_EDI)
                .build();

        SessionResponse response = service.startSession(request);

        assertNotNull(response.getSessionId());
        assertEquals("ACTIVE", response.getStatus());
        assertTrue(response.getMessage().contains("empty mappings"));
        assertNotNull(response.getCreatedAt());
        verify(sessionRepo, times(2)).save(any()); // once for create, once for test output update
    }

    @Test
    void startSession_expiresExistingActiveSession() {
        MappingCorrectionSession existingSession = MappingCorrectionSession.builder()
                .id(UUID.randomUUID()).partnerId(PARTNER_ID)
                .mapKey("X12:850→JSON:*@partner-acme")
                .status(Status.ACTIVE).build();

        when(sessionRepo.findByPartnerIdAndMapKeyAndStatus(anyString(), anyString(), eq(Status.ACTIVE)))
                .thenReturn(Optional.of(existingSession));
        when(conversionMapRepo.findByMapKeyAndActiveTrue(anyString())).thenReturn(Optional.empty());
        when(sessionRepo.save(any(MappingCorrectionSession.class))).thenAnswer(inv -> {
            MappingCorrectionSession s = inv.getArgument(0);
            if (s.getId() == null) s.setId(UUID.randomUUID());
            if (s.getCreatedAt() == null) s.setCreatedAt(Instant.now());
            if (s.getExpiresAt() == null) s.setExpiresAt(Instant.now().plusSeconds(86400));
            return s;
        });

        service.startSession(StartRequest.builder()
                .partnerId(PARTNER_ID).sourceFormat("X12").sourceType("850")
                .targetFormat("JSON").sampleInputContent(SAMPLE_EDI).build());

        // Existing session should have been expired
        assertEquals(Status.EXPIRED, existingSession.getStatus());
    }

    @Test
    void startSession_withExistingMap_clonesFieldMappings() throws Exception {
        String existingMappingsJson = objectMapper.writeValueAsString(List.of(
                Map.of("sourceField", "BEG*03", "targetField", "poNumber",
                        "transform", "DIRECT", "confidence", 90)));

        ConversionMap existingMap = ConversionMap.builder()
                .id(UUID.randomUUID())
                .mapKey("X12:850→JSON:*@partner-acme")
                .version(3).fieldMappingsJson(existingMappingsJson)
                .build();

        when(sessionRepo.findByPartnerIdAndMapKeyAndStatus(anyString(), anyString(), eq(Status.ACTIVE)))
                .thenReturn(Optional.empty());
        // Return partner-specific map
        when(conversionMapRepo.findByMapKeyAndActiveTrue("X12:850→JSON:*@partner-acme"))
                .thenReturn(Optional.of(existingMap));
        when(sessionRepo.save(any())).thenAnswer(inv -> {
            MappingCorrectionSession s = inv.getArgument(0);
            if (s.getId() == null) s.setId(UUID.randomUUID());
            if (s.getCreatedAt() == null) s.setCreatedAt(Instant.now());
            if (s.getExpiresAt() == null) s.setExpiresAt(Instant.now().plusSeconds(86400));
            return s;
        });

        SessionResponse response = service.startSession(StartRequest.builder()
                .partnerId(PARTNER_ID).sourceFormat("X12").sourceType("850")
                .targetFormat("JSON").sampleInputContent(SAMPLE_EDI).build());

        assertTrue(response.getMessage().contains("existing map"));
        assertEquals(1, response.getCurrentMappings().size());
        assertEquals("poNumber", response.getCurrentMappings().get(0).getTargetField());
    }

    // ===================================================================
    // submitCorrection
    // ===================================================================

    @Test
    void submitCorrection_appliesSwapChange() throws Exception {
        String mappingsJson = objectMapper.writeValueAsString(List.of(
                Map.of("sourceField", "NM1*02", "targetField", "companyName",
                        "transform", "DIRECT", "confidence", 85)));

        MappingCorrectionSession session = MappingCorrectionSession.builder()
                .id(UUID.randomUUID()).partnerId(PARTNER_ID)
                .mapKey("X12:850→JSON:*@partner-acme")
                .status(Status.ACTIVE)
                .sourceFormat("X12").sourceType("850").targetFormat("JSON")
                .currentFieldMappingsJson(mappingsJson)
                .correctionHistory("[]")
                .sampleInputContent(SAMPLE_EDI)
                .correctionCount(0)
                .build();

        when(sessionRepo.findByIdAndPartnerId(session.getId(), PARTNER_ID))
                .thenReturn(Optional.of(session));
        when(sessionRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CorrectionResult result = service.submitCorrection(
                session.getId(), PARTNER_ID, "NM1*03 not NM1*02");

        assertTrue(result.isApplied());
        assertNotNull(result.getSummary());
        assertEquals(1, result.getChanges().size());
        assertEquals("MODIFY", result.getChanges().get(0).getAction());
        assertNotNull(result.getNextStepHint());

        // Session should have been updated
        assertEquals(1, session.getCorrectionCount());
    }

    @Test
    void submitCorrection_addNewMapping() throws Exception {
        MappingCorrectionSession session = MappingCorrectionSession.builder()
                .id(UUID.randomUUID()).partnerId(PARTNER_ID)
                .mapKey("X12:850→JSON:*@partner-acme")
                .status(Status.ACTIVE)
                .sourceFormat("X12").sourceType("850").targetFormat("JSON")
                .currentFieldMappingsJson("[]")
                .correctionHistory("[]")
                .sampleInputContent(SAMPLE_EDI)
                .correctionCount(0)
                .build();

        when(sessionRepo.findByIdAndPartnerId(session.getId(), PARTNER_ID))
                .thenReturn(Optional.of(session));
        when(sessionRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CorrectionResult result = service.submitCorrection(
                session.getId(), PARTNER_ID, "Map NM1*03 to companyName");

        assertTrue(result.isApplied());
        assertEquals("ADD", result.getChanges().get(0).getAction());
    }

    @Test
    void submitCorrection_unrecognizedInstruction_returnsClarification() throws Exception {
        MappingCorrectionSession session = MappingCorrectionSession.builder()
                .id(UUID.randomUUID()).partnerId(PARTNER_ID)
                .mapKey("X12:850→JSON:*@partner-acme")
                .status(Status.ACTIVE)
                .sourceFormat("X12").sourceType("850").targetFormat("JSON")
                .currentFieldMappingsJson("[]")
                .correctionHistory("[]")
                .sampleInputContent(SAMPLE_EDI)
                .correctionCount(0)
                .build();

        when(sessionRepo.findByIdAndPartnerId(session.getId(), PARTNER_ID))
                .thenReturn(Optional.of(session));

        CorrectionResult result = service.submitCorrection(
                session.getId(), PARTNER_ID, "please do something ambiguous");

        assertFalse(result.isApplied());
        assertTrue(result.isClarificationNeeded());
    }

    @Test
    void submitCorrection_sessionNotActive_throws() throws Exception {
        MappingCorrectionSession session = MappingCorrectionSession.builder()
                .id(UUID.randomUUID()).partnerId(PARTNER_ID)
                .status(Status.APPROVED).build();

        when(sessionRepo.findByIdAndPartnerId(session.getId(), PARTNER_ID))
                .thenReturn(Optional.of(session));

        assertThrows(IllegalStateException.class, () ->
                service.submitCorrection(session.getId(), PARTNER_ID, "some correction"));
    }

    @Test
    void submitCorrection_sessionNotFound_throws() {
        UUID fakeId = UUID.randomUUID();
        when(sessionRepo.findByIdAndPartnerId(fakeId, PARTNER_ID))
                .thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () ->
                service.submitCorrection(fakeId, PARTNER_ID, "some correction"));
    }

    // ===================================================================
    // approve
    // ===================================================================

    @Test
    void approve_createsNewConversionMap() throws Exception {
        String mappingsJson = objectMapper.writeValueAsString(List.of(
                Map.of("sourceField", "NM1*03", "targetField", "companyName",
                        "transform", "DIRECT", "confidence", 95)));

        MappingCorrectionSession session = MappingCorrectionSession.builder()
                .id(UUID.randomUUID()).partnerId(PARTNER_ID)
                .mapKey("X12:850→JSON:*@partner-acme")
                .status(Status.ACTIVE)
                .sourceFormat("X12").sourceType("850").targetFormat("JSON")
                .currentFieldMappingsJson(mappingsJson)
                .correctionCount(2)
                .build();

        when(sessionRepo.findByIdAndPartnerId(session.getId(), PARTNER_ID))
                .thenReturn(Optional.of(session));
        when(sessionRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // TrainedMapStore.storeTrainingResult calls:
        when(conversionMapRepo.findMaxVersionByMapKey(anyString())).thenReturn(Optional.of(1));
        when(conversionMapRepo.findByMapKeyAndActiveTrue(anyString())).thenReturn(Optional.empty());
        doNothing().when(conversionMapRepo).deactivateAllByMapKey(anyString());
        when(conversionMapRepo.save(any(ConversionMap.class))).thenAnswer(inv -> {
            ConversionMap m = inv.getArgument(0);
            if (m.getId() == null) m.setId(UUID.randomUUID());
            return m;
        });
        when(trainingSessionRepo.save(any())).thenAnswer(inv -> {
            // Simulate @PrePersist setting startedAt (needed by markCompleted)
            com.filetransfer.ai.entity.edi.TrainingSession ts = inv.getArgument(0);
            if (ts.getStartedAt() == null) {
                ts.setStartedAt(java.time.Instant.now().minusSeconds(1));
            }
            return ts;
        });

        ApprovalResult result = service.approve(session.getId(), PARTNER_ID);

        assertNotNull(result.getNewMapId());
        assertEquals("X12:850→JSON:*@partner-acme", result.getMapKey());
        assertEquals(2, result.getNewMapVersion()); // prev was 1, new is 2
        assertEquals(95, result.getConfidence());
        assertTrue(result.getMessage().contains("v2"));

        // Session should be APPROVED
        assertEquals(Status.APPROVED, session.getStatus());
        verify(conversionMapRepo).save(any(ConversionMap.class));
    }

    @Test
    void approve_emptyMappings_throws() throws Exception {
        MappingCorrectionSession session = MappingCorrectionSession.builder()
                .id(UUID.randomUUID()).partnerId(PARTNER_ID)
                .status(Status.ACTIVE)
                .currentFieldMappingsJson("[]")
                .build();

        when(sessionRepo.findByIdAndPartnerId(session.getId(), PARTNER_ID))
                .thenReturn(Optional.of(session));

        assertThrows(IllegalStateException.class, () ->
                service.approve(session.getId(), PARTNER_ID));
    }

    // ===================================================================
    // reject
    // ===================================================================

    @Test
    void reject_setsStatusRejected() {
        MappingCorrectionSession session = MappingCorrectionSession.builder()
                .id(UUID.randomUUID()).partnerId(PARTNER_ID)
                .status(Status.ACTIVE).build();

        when(sessionRepo.findByIdAndPartnerId(session.getId(), PARTNER_ID))
                .thenReturn(Optional.of(session));
        when(sessionRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.reject(session.getId(), PARTNER_ID);

        assertEquals(Status.REJECTED, session.getStatus());
    }

    // ===================================================================
    // expireStaleSessions
    // ===================================================================

    @Test
    void expireStaleSessions_marksExpired() {
        MappingCorrectionSession stale1 = MappingCorrectionSession.builder()
                .id(UUID.randomUUID()).status(Status.ACTIVE)
                .expiresAt(Instant.now().minusSeconds(3600)).build();
        MappingCorrectionSession stale2 = MappingCorrectionSession.builder()
                .id(UUID.randomUUID()).status(Status.ACTIVE)
                .expiresAt(Instant.now().minusSeconds(7200)).build();

        when(sessionRepo.findByStatusAndExpiresAtBefore(eq(Status.ACTIVE), any()))
                .thenReturn(List.of(stale1, stale2));
        when(sessionRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.expireStaleSessions();

        assertEquals(Status.EXPIRED, stale1.getStatus());
        assertEquals(Status.EXPIRED, stale2.getStatus());
        verify(sessionRepo, times(2)).save(any());
    }

    @Test
    void expireStaleSessions_nothingToExpire() {
        when(sessionRepo.findByStatusAndExpiresAtBefore(eq(Status.ACTIVE), any()))
                .thenReturn(List.of());

        service.expireStaleSessions();

        verify(sessionRepo, never()).save(any());
    }

    // ===================================================================
    // listSessions
    // ===================================================================

    @Test
    void listSessions_returnsSummaries() {
        MappingCorrectionSession s1 = MappingCorrectionSession.builder()
                .id(UUID.randomUUID()).partnerId(PARTNER_ID)
                .mapKey("X12:850→JSON:*@partner-acme")
                .status(Status.APPROVED).correctionCount(3)
                .createdAt(Instant.now().minusSeconds(3600)).build();

        when(sessionRepo.findByPartnerIdOrderByCreatedAtDesc(PARTNER_ID))
                .thenReturn(List.of(s1));

        List<SessionSummary> sessions = service.listSessions(PARTNER_ID);

        assertEquals(1, sessions.size());
        assertEquals("APPROVED", sessions.get(0).getStatus());
        assertEquals(3, sessions.get(0).getCorrectionCount());
    }

    // ===================================================================
    // Multiple correction rounds
    // ===================================================================

    @Test
    void multipleCorrectionRounds_accumulateChanges() throws Exception {
        MappingCorrectionSession session = MappingCorrectionSession.builder()
                .id(UUID.randomUUID()).partnerId(PARTNER_ID)
                .mapKey("X12:850→JSON:*@partner-acme")
                .status(Status.ACTIVE)
                .sourceFormat("X12").sourceType("850").targetFormat("JSON")
                .currentFieldMappingsJson("[]")
                .correctionHistory("[]")
                .sampleInputContent(SAMPLE_EDI)
                .correctionCount(0)
                .build();

        when(sessionRepo.findByIdAndPartnerId(session.getId(), PARTNER_ID))
                .thenReturn(Optional.of(session));
        when(sessionRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // Round 1: Add mapping
        CorrectionResult r1 = service.submitCorrection(
                session.getId(), PARTNER_ID, "Map NM1*03 to companyName");
        assertTrue(r1.isApplied());
        assertEquals(1, session.getCorrectionCount());

        // Round 2: Add another mapping
        CorrectionResult r2 = service.submitCorrection(
                session.getId(), PARTNER_ID, "Map BEG*03 to poNumber");
        assertTrue(r2.isApplied());
        assertEquals(2, session.getCorrectionCount());

        // Verify accumulated mappings
        List<FieldMappingDto> currentMappings = new ObjectMapper().readValue(
                session.getCurrentFieldMappingsJson(),
                new com.fasterxml.jackson.core.type.TypeReference<List<FieldMappingDto>>() {});
        assertEquals(2, currentMappings.size());
    }
}
