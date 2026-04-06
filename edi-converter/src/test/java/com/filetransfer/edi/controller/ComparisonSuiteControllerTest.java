package com.filetransfer.edi.controller;

import com.filetransfer.edi.service.ComparisonSuiteService;
import com.filetransfer.edi.service.ComparisonSuiteService.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ComparisonSuiteController: request validation, delegation to service,
 * error handling, and response format.
 *
 * Uses stub subclass pattern (JDK 25 compatible — no Mockito on concrete classes).
 */
class ComparisonSuiteControllerTest {

    private StubComparisonService stubService;
    private ComparisonSuiteController controller;

    @BeforeEach
    void setUp() {
        stubService = new StubComparisonService();
        controller = new ComparisonSuiteController(stubService);
    }

    // ========================================================================
    // PREPARE — validation
    // ========================================================================

    @Test
    void prepare_missingAllPaths_returnsBadRequest() {
        CompareRequest request = CompareRequest.builder().build();
        ResponseEntity<?> response = controller.prepareComparison(request);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void prepare_missingSourceOutput_returnsBadRequest() {
        CompareRequest request = CompareRequest.builder()
                .sourceInputPath("/a").targetInputPath("/b").targetOutputPath("/c")
                .build();
        ResponseEntity<?> response = controller.prepareComparison(request);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void prepare_withMappingFilePath_skipsPathValidation() {
        stubService.nextPreview = readyPreview();
        CompareRequest request = CompareRequest.builder()
                .mappingFilePath("/path/to/mapping.csv")
                .build();
        ResponseEntity<?> response = controller.prepareComparison(request);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(stubService.prepareCalled);
    }

    @Test
    void prepare_allPathsProvided_delegatesToService() {
        stubService.nextPreview = readyPreview();
        CompareRequest request = CompareRequest.builder()
                .sourceInputPath("/a").sourceOutputPath("/b")
                .targetInputPath("/c").targetOutputPath("/d")
                .build();
        ResponseEntity<?> response = controller.prepareComparison(request);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(stubService.prepareCalled);
    }

    @Test
    void prepare_serviceReturnsError_returnsBadRequest() {
        stubService.nextPreview = ComparePreview.builder()
                .sessionId(UUID.randomUUID()).status("ERROR").message("Bad paths").build();
        CompareRequest request = CompareRequest.builder()
                .sourceInputPath("/a").sourceOutputPath("/b")
                .targetInputPath("/c").targetOutputPath("/d")
                .build();
        ResponseEntity<?> response = controller.prepareComparison(request);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    // ========================================================================
    // EXECUTE
    // ========================================================================

    @Test
    void execute_validSession_returnsReport() {
        stubService.nextReport = sampleReport();
        UUID sessionId = UUID.randomUUID();
        ResponseEntity<?> response = controller.executeComparison(sessionId);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(stubService.executeCalled);
        assertEquals(sessionId, stubService.lastSessionId);
    }

    @Test
    void execute_unknownSession_returns404() {
        stubService.executeThrows = true;
        ResponseEntity<?> response = controller.executeComparison(UUID.randomUUID());
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    // ========================================================================
    // GET REPORT
    // ========================================================================

    @Test
    void getReport_existingReport_returnsOk() {
        stubService.nextReport = sampleReport();
        ResponseEntity<?> response = controller.getReport(UUID.randomUUID());
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(stubService.getReportCalled);
    }

    @Test
    void getReport_noReport_returns404() {
        stubService.nextReport = null;
        ResponseEntity<?> response = controller.getReport(UUID.randomUUID());
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    // ========================================================================
    // GET REPORT SUMMARY
    // ========================================================================

    @Test
    void getReportSummary_existingReport_returnsTextPlain() {
        stubService.nextReport = sampleReport();
        stubService.nextSummary = "REPORT SUMMARY TEXT";
        ResponseEntity<?> response = controller.getReportSummary(UUID.randomUUID());
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("REPORT SUMMARY TEXT", response.getBody());
    }

    @Test
    void getReportSummary_noReport_returns404() {
        stubService.nextReport = null;
        ResponseEntity<?> response = controller.getReportSummary(UUID.randomUUID());
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    // ========================================================================
    // GET SESSION
    // ========================================================================

    @Test
    void getSession_existingSession_returnsPreview() {
        stubService.nextSessionPreview = readyPreview();
        ResponseEntity<?> response = controller.getSession(UUID.randomUUID());
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(stubService.getPreviewCalled);
    }

    @Test
    void getSession_unknownSession_returns404() {
        stubService.nextSessionPreview = null;
        ResponseEntity<?> response = controller.getSession(UUID.randomUUID());
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    // ========================================================================
    // HELPERS
    // ========================================================================

    private ComparePreview readyPreview() {
        return ComparePreview.builder()
                .sessionId(UUID.randomUUID()).status("READY")
                .totalSourceFiles(3).totalTargetFiles(3).matchedPairs(3)
                .filePairs(List.of()).unmatchedSource(List.of()).unmatchedTarget(List.of())
                .message("Found 3 matched pairs.").build();
    }

    private CompareReport sampleReport() {
        return CompareReport.builder()
                .reportId(UUID.randomUUID()).generatedAt(Instant.now()).durationMs(150)
                .totalPairsCompared(3).identicalPairs(2).differentPairs(1).errorPairs(0)
                .results(List.of()).mapSummaries(List.of()).recommendations(List.of())
                .overallVerdict("NEEDS_WORK — 2/3 pairs identical").build();
    }

    // ========================================================================
    // STUB — JDK 25 compatible (no Mockito on concrete class)
    // ========================================================================

    private static class StubComparisonService extends ComparisonSuiteService {

        boolean prepareCalled;
        boolean executeCalled;
        boolean getReportCalled;
        boolean getPreviewCalled;
        boolean executeThrows;
        UUID lastSessionId;

        ComparePreview nextPreview;
        ComparePreview nextSessionPreview;
        CompareReport nextReport;
        String nextSummary;

        StubComparisonService() {
            super(null, null, null, null);
        }

        @Override
        public ComparePreview prepareComparison(CompareRequest request) {
            prepareCalled = true;
            return nextPreview;
        }

        @Override
        public ComparePreview prepareFromCsvContent(String csvContent, String reportOutputPath) {
            prepareCalled = true;
            return nextPreview;
        }

        @Override
        public CompareReport executeComparison(UUID sessionId) {
            executeCalled = true;
            lastSessionId = sessionId;
            if (executeThrows) throw new IllegalArgumentException("Session not found: " + sessionId);
            return nextReport;
        }

        @Override
        public CompareReport getReport(UUID sessionId) {
            getReportCalled = true;
            return nextReport;
        }

        @Override
        public ComparePreview getPreview(UUID sessionId) {
            getPreviewCalled = true;
            return nextSessionPreview;
        }

        @Override
        public String buildHumanReadableSummary(CompareReport report) {
            return nextSummary != null ? nextSummary : "SUMMARY";
        }
    }
}
