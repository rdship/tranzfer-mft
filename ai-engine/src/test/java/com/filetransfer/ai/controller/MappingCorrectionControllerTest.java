package com.filetransfer.ai.controller;

import com.filetransfer.ai.service.edi.MappingCorrectionInterpreter.FieldMappingDto;
import com.filetransfer.ai.service.edi.MappingCorrectionService;
import com.filetransfer.ai.service.edi.MappingCorrectionService.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests MappingCorrectionController validation and delegation.
 *
 * Uses a stub subclass of MappingCorrectionService because JDK 25
 * prevents Mockito from mocking concrete classes via Byte Buddy.
 */
class MappingCorrectionControllerTest {

    private StubCorrectionService stubService;
    private MappingCorrectionController controller;

    @BeforeEach
    void setUp() {
        stubService = new StubCorrectionService();
        controller = new MappingCorrectionController(stubService);
    }

    // ===================================================================
    // startSession validation
    // ===================================================================

    @Test
    void startSession_missingPartnerId_throws400() {
        StartRequest request = StartRequest.builder()
                .sourceFormat("X12").targetFormat("JSON")
                .sampleInputContent("ISA*00*...").build();

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.startSession(request));
        assertTrue(ex.getMessage().contains("partnerId"));
    }

    @Test
    void startSession_missingSourceFormat_throws400() {
        StartRequest request = StartRequest.builder()
                .partnerId("acme").targetFormat("JSON")
                .sampleInputContent("ISA*00*...").build();

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.startSession(request));
        assertTrue(ex.getMessage().contains("sourceFormat"));
    }

    @Test
    void startSession_missingTargetFormat_throws400() {
        StartRequest request = StartRequest.builder()
                .partnerId("acme").sourceFormat("X12")
                .sampleInputContent("ISA*00*...").build();

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.startSession(request));
        assertTrue(ex.getMessage().contains("targetFormat"));
    }

    @Test
    void startSession_missingSampleInput_throws400() {
        StartRequest request = StartRequest.builder()
                .partnerId("acme").sourceFormat("X12").targetFormat("JSON").build();

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.startSession(request));
        assertTrue(ex.getMessage().contains("sampleInputContent"));
    }

    @Test
    void startSession_validRequest_delegatesToService() {
        StartRequest request = StartRequest.builder()
                .partnerId("acme").sourceFormat("X12").targetFormat("JSON")
                .sampleInputContent("ISA*00*...").build();

        SessionResponse result = controller.startSession(request);

        assertNotNull(result.getSessionId());
        assertEquals("ACTIVE", result.getStatus());
        assertTrue(stubService.startSessionCalled);
    }

    // ===================================================================
    // submitCorrection validation
    // ===================================================================

    @Test
    void submitCorrection_missingPartnerId_throws400() {
        MappingCorrectionController.SubmitCorrectionRequest req =
                new MappingCorrectionController.SubmitCorrectionRequest(null, "fix something");

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.submitCorrection(UUID.randomUUID(), req));
        assertTrue(ex.getMessage().contains("partnerId"));
    }

    @Test
    void submitCorrection_missingInstruction_throws400() {
        MappingCorrectionController.SubmitCorrectionRequest req =
                new MappingCorrectionController.SubmitCorrectionRequest("acme", "");

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.submitCorrection(UUID.randomUUID(), req));
        assertTrue(ex.getMessage().contains("instruction"));
    }

    @Test
    void submitCorrection_valid_delegatesToService() {
        UUID sessionId = UUID.randomUUID();
        MappingCorrectionController.SubmitCorrectionRequest req =
                new MappingCorrectionController.SubmitCorrectionRequest("acme", "Map NM1*03 to name");

        CorrectionResult result = controller.submitCorrection(sessionId, req);

        assertTrue(result.isApplied());
        assertTrue(stubService.submitCorrectionCalled);
        assertEquals(sessionId, stubService.lastSessionId);
        assertEquals("acme", stubService.lastPartnerId);
        assertEquals("Map NM1*03 to name", stubService.lastInstruction);
    }

    // ===================================================================
    // approve / reject
    // ===================================================================

    @Test
    void approve_delegatesToService() {
        UUID sessionId = UUID.randomUUID();

        ApprovalResult result = controller.approve(sessionId, "acme");

        assertNotNull(result.getNewMapId());
        assertEquals(95, result.getConfidence());
        assertTrue(stubService.approveCalled);
    }

    @Test
    void reject_delegatesToService() {
        UUID sessionId = UUID.randomUUID();

        assertDoesNotThrow(() -> controller.reject(sessionId, "acme"));
        assertTrue(stubService.rejectCalled);
    }

    // ===================================================================
    // listSessions
    // ===================================================================

    @Test
    void listSessions_missingPartnerId_returnsAllSessions() {
        // Controller contract (see Javadoc on listSessions): admin-side callers
        // can omit partnerId to list every session across partners. Empty-string
        // and null both mean "all sessions" — the controller delegates to the
        // service layer which scopes by partner only when non-blank. Test was
        // previously asserting the older strict-validation behaviour.
        List<SessionSummary> result = controller.listSessions("");

        assertNotNull(result);
        assertTrue(stubService.listSessionsCalled);
    }

    @Test
    void listSessions_valid_delegatesToService() {
        List<SessionSummary> result = controller.listSessions("acme");

        assertEquals(1, result.size());
        assertEquals("ACTIVE", result.get(0).getStatus());
        assertTrue(stubService.listSessionsCalled);
    }

    // ===================================================================
    // health
    // ===================================================================

    @Test
    void health_returnsUp() {
        var health = controller.health();
        assertEquals("UP", health.get("status"));
        assertEquals("mapping-correction", health.get("service"));
    }

    // ===================================================================
    // Stub subclass — JDK 25 compatible (no Mockito mocking of concrete classes)
    // ===================================================================

    private static class StubCorrectionService extends MappingCorrectionService {

        boolean startSessionCalled;
        boolean submitCorrectionCalled;
        boolean approveCalled;
        boolean rejectCalled;
        boolean listSessionsCalled;

        UUID lastSessionId;
        String lastPartnerId;
        String lastInstruction;

        StubCorrectionService() {
            super(null, null, null, null, null, null, null, null);
        }

        @Override
        public SessionResponse startSession(StartRequest request) {
            startSessionCalled = true;
            return SessionResponse.builder()
                    .sessionId(UUID.randomUUID()).status("ACTIVE")
                    .mapKey("X12:*→JSON:*@" + request.getPartnerId())
                    .correctionCount(0)
                    .currentMappings(List.of()).currentTestOutput("{}")
                    .message("Session started").createdAt(Instant.now())
                    .expiresAt(Instant.now().plusSeconds(86400)).build();
        }

        @Override
        public CorrectionResult submitCorrection(UUID sessionId, String partnerId, String instruction) {
            submitCorrectionCalled = true;
            lastSessionId = sessionId;
            lastPartnerId = partnerId;
            lastInstruction = instruction;
            return CorrectionResult.builder()
                    .sessionId(sessionId).applied(true).summary("Modified").build();
        }

        @Override
        public ApprovalResult approve(UUID sessionId, String partnerId) {
            approveCalled = true;
            return ApprovalResult.builder()
                    .newMapId(UUID.randomUUID()).mapKey("X12:850→JSON:*@" + partnerId)
                    .newMapVersion(1).confidence(95).flowUpdated(false)
                    .message("Approved").build();
        }

        @Override
        public void reject(UUID sessionId, String partnerId) {
            rejectCalled = true;
        }

        @Override
        public List<SessionSummary> listSessions(String partnerId) {
            listSessionsCalled = true;
            return List.of(
                    SessionSummary.builder().sessionId(UUID.randomUUID())
                            .mapKey("X12:850→JSON:*@" + partnerId).status("ACTIVE")
                            .correctionCount(2).createdAt(Instant.now()).build()
            );
        }
    }
}
