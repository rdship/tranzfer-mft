package com.filetransfer.edi.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.filetransfer.edi.parser.FormatDetector;
import com.filetransfer.edi.parser.UniversalEdiParser;
import com.filetransfer.edi.service.ComparisonSuiteService.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ComparisonSuiteService: directory scanning, file matching,
 * EDI/JSON/text comparison, map aggregation, recommendations, and report generation.
 *
 * Uses real instances (no mocks) — JDK 25 compatible.
 * Creates temp directories with sample EDI/JSON files for each test.
 */
class ComparisonSuiteServiceTest {

    private ComparisonSuiteService service;

    @TempDir
    Path tempDir;

    // Sample X12 850 Purchase Order
    private static final String SAMPLE_X12_A =
            "ISA*00*          *00*          *ZZ*SENDER         *ZZ*RECEIVER       *210101*1200*U*00401*000000001*0*P*~\n" +
            "GS*PO*SENDER*RECEIVER*20210101*1200*1*X*004010~\n" +
            "ST*850*0001~\n" +
            "BEG*00*NE*PO-001**20210101~\n" +
            "NM1*BY*1*Smith*John~\n" +
            "PO1*1*100*EA*5.00**VP*WIDGET-A~\n" +
            "SE*5*0001~\n" +
            "GE*1*1~\n" +
            "IEA*1*000000001~";

    // Same structure, different values (modified NM1, PO1 quantity)
    private static final String SAMPLE_X12_B =
            "ISA*00*          *00*          *ZZ*SENDER         *ZZ*RECEIVER       *210101*1200*U*00401*000000001*0*P*~\n" +
            "GS*PO*SENDER*RECEIVER*20210101*1200*1*X*004010~\n" +
            "ST*850*0001~\n" +
            "BEG*00*NE*PO-001**20210101~\n" +
            "NM1*BY*1*Jones*Jane~\n" +
            "PO1*1*200*EA*5.00**VP*WIDGET-A~\n" +
            "SE*5*0001~\n" +
            "GE*1*1~\n" +
            "IEA*1*000000001~";

    private static final String SAMPLE_JSON_A = """
            {
              "header": {
                "sender": "ACME",
                "receiver": "PARTNER-1",
                "date": "20260406"
              },
              "items": [
                {"id": "1", "quantity": "100", "price": "5.00"}
              ]
            }""";

    private static final String SAMPLE_JSON_B = """
            {
              "header": {
                "sender": "ACME",
                "receiver": "PARTNER-1",
                "date": "2026-04-06"
              },
              "items": [
                {"id": "1", "quantity": "200", "price": "5.00"}
              ]
            }""";

    @BeforeEach
    void setUp() {
        FormatDetector detector = new FormatDetector();
        UniversalEdiParser parser = new UniversalEdiParser(detector);
        SemanticDiffEngine diffEngine = new SemanticDiffEngine(parser);
        ObjectMapper objectMapper = new ObjectMapper();
        service = new ComparisonSuiteService(parser, detector, diffEngine, objectMapper);
    }

    // ========================================================================
    // PREPARE — directory scanning
    // ========================================================================

    @Test
    void prepare_withValidDirectories_matchesFilesByName() throws IOException {
        Path sourceInput = createDir("source-input");
        Path sourceOutput = createDir("source-output");
        Path targetInput = createDir("target-input");
        Path targetOutput = createDir("target-output");

        writeFile(sourceInput, "order.edi", SAMPLE_X12_A);
        writeFile(sourceOutput, "order.edi", SAMPLE_X12_A);
        writeFile(targetInput, "order.edi", SAMPLE_X12_A);
        writeFile(targetOutput, "order.edi", SAMPLE_X12_B);

        CompareRequest request = CompareRequest.builder()
                .sourceInputPath(sourceInput.toString())
                .sourceOutputPath(sourceOutput.toString())
                .targetInputPath(targetInput.toString())
                .targetOutputPath(targetOutput.toString())
                .build();

        ComparePreview preview = service.prepareComparison(request);

        assertEquals("READY", preview.getStatus());
        assertEquals(1, preview.getMatchedPairs());
        assertEquals(1, preview.getFilePairs().size());
        assertNotNull(preview.getSessionId());
        assertTrue(preview.getUnmatchedSource().isEmpty());
        assertTrue(preview.getUnmatchedTarget().isEmpty());
    }

    @Test
    void prepare_withMultipleFiles_matchesCorrectly() throws IOException {
        Path sourceOutput = createDir("source-out");
        Path targetOutput = createDir("target-out");
        Path sourceInput = createDir("source-in");
        Path targetInput = createDir("target-in");

        writeFile(sourceOutput, "file1.json", SAMPLE_JSON_A);
        writeFile(sourceOutput, "file2.json", SAMPLE_JSON_A);
        writeFile(sourceOutput, "file3.json", SAMPLE_JSON_A);
        writeFile(targetOutput, "file1.json", SAMPLE_JSON_B);
        writeFile(targetOutput, "file2.json", SAMPLE_JSON_B);
        // file3 missing in target

        CompareRequest request = CompareRequest.builder()
                .sourceInputPath(sourceInput.toString())
                .sourceOutputPath(sourceOutput.toString())
                .targetInputPath(targetInput.toString())
                .targetOutputPath(targetOutput.toString())
                .build();

        ComparePreview preview = service.prepareComparison(request);

        assertEquals("READY", preview.getStatus());
        assertEquals(2, preview.getMatchedPairs());
        assertEquals(1, preview.getUnmatchedSource().size());
        assertEquals("file3.json", preview.getUnmatchedSource().get(0));
    }

    @Test
    void prepare_withNoMatchingFiles_returnsNeedsMapping() throws IOException {
        Path sourceOutput = createDir("src-out");
        Path targetOutput = createDir("tgt-out");
        Path sourceInput = createDir("src-in");
        Path targetInput = createDir("tgt-in");

        writeFile(sourceOutput, "alpha.json", SAMPLE_JSON_A);
        writeFile(targetOutput, "beta.json", SAMPLE_JSON_B);

        CompareRequest request = CompareRequest.builder()
                .sourceInputPath(sourceInput.toString())
                .sourceOutputPath(sourceOutput.toString())
                .targetInputPath(targetInput.toString())
                .targetOutputPath(targetOutput.toString())
                .build();

        ComparePreview preview = service.prepareComparison(request);

        assertEquals("NEEDS_MAPPING", preview.getStatus());
        assertEquals(0, preview.getMatchedPairs());
    }

    @Test
    void prepare_withInvalidPaths_returnsError() {
        CompareRequest request = CompareRequest.builder()
                .sourceInputPath("/nonexistent/path")
                .sourceOutputPath("/nonexistent/path2")
                .targetInputPath("/nonexistent/path3")
                .targetOutputPath("/nonexistent/path4")
                .build();

        ComparePreview preview = service.prepareComparison(request);
        assertEquals("ERROR", preview.getStatus());
    }

    // ========================================================================
    // PREPARE — CSV mapping file
    // ========================================================================

    @Test
    void prepareFromCsv_loadsExplicitMappings() throws IOException {
        Path sourceOutput = createDir("csv-src-out");
        Path targetOutput = createDir("csv-tgt-out");

        Path fileA = writeFile(sourceOutput, "order-a.json", SAMPLE_JSON_A);
        Path fileB = writeFile(targetOutput, "order-b.json", SAMPLE_JSON_B);

        String csvContent = "sourceInputFile,sourceOutputFile,targetInputFile,targetOutputFile,mapLabel\n"
                + ",\"" + fileA + "\",,\"" + fileB + "\",X12:850\n";

        ComparePreview preview = service.prepareFromCsvContent(csvContent, null);

        assertEquals("READY", preview.getStatus());
        assertEquals(1, preview.getMatchedPairs());
        assertEquals("X12:850", preview.getFilePairs().get(0).getMapLabel());
    }

    @Test
    void prepareFromCsv_skipsMissingFiles() throws IOException {
        String csvContent = "sourceInputFile,sourceOutputFile,targetInputFile,targetOutputFile\n"
                + ",/nonexistent/a.json,,/nonexistent/b.json\n";

        ComparePreview preview = service.prepareFromCsvContent(csvContent, null);

        // No valid pairs — should have error
        assertEquals("ERROR", preview.getStatus());
    }

    // ========================================================================
    // EXECUTE — identical files
    // ========================================================================

    @Test
    void execute_identicalFiles_reportsIdentical() throws IOException {
        Path sourceOutput = createDir("exec-src");
        Path targetOutput = createDir("exec-tgt");
        Path sourceInput = createDir("exec-src-in");
        Path targetInput = createDir("exec-tgt-in");

        writeFile(sourceOutput, "same.json", SAMPLE_JSON_A);
        writeFile(targetOutput, "same.json", SAMPLE_JSON_A);

        CompareRequest request = CompareRequest.builder()
                .sourceInputPath(sourceInput.toString())
                .sourceOutputPath(sourceOutput.toString())
                .targetInputPath(targetInput.toString())
                .targetOutputPath(targetOutput.toString())
                .build();

        ComparePreview preview = service.prepareComparison(request);
        CompareReport report = service.executeComparison(preview.getSessionId());

        assertEquals(1, report.getTotalPairsCompared());
        assertEquals(1, report.getIdenticalPairs());
        assertEquals(0, report.getDifferentPairs());
        assertEquals(0, report.getErrorPairs());
        assertTrue(report.getOverallVerdict().startsWith("PERFECT"));
    }

    // ========================================================================
    // EXECUTE — JSON comparison
    // ========================================================================

    @Test
    void execute_differentJsonFiles_detectsFieldDiffs() throws IOException {
        Path sourceOutput = createDir("json-src");
        Path targetOutput = createDir("json-tgt");
        Path sourceInput = createDir("json-src-in");
        Path targetInput = createDir("json-tgt-in");

        writeFile(sourceOutput, "order.json", SAMPLE_JSON_A);
        writeFile(targetOutput, "order.json", SAMPLE_JSON_B);

        CompareRequest request = CompareRequest.builder()
                .sourceInputPath(sourceInput.toString())
                .sourceOutputPath(sourceOutput.toString())
                .targetInputPath(targetInput.toString())
                .targetOutputPath(targetOutput.toString())
                .build();

        ComparePreview preview = service.prepareComparison(request);
        CompareReport report = service.executeComparison(preview.getSessionId());

        assertEquals(1, report.getTotalPairsCompared());
        assertEquals(0, report.getIdenticalPairs());
        assertEquals(1, report.getDifferentPairs());

        FileCompareResult result = report.getResults().get(0);
        assertFalse(result.isOutputsIdentical());
        assertTrue(result.getFieldDifferences() > 0);
        assertNotEquals("IDENTICAL", result.getVerdict());

        // Should find date and quantity diffs (date at header.date, quantity at items[0].quantity)
        boolean foundDateDiff = result.getFieldDiffs().stream()
                .anyMatch(d -> d.getFieldName() != null && d.getFieldName().contains("date"));
        boolean foundQuantityDiff = result.getFieldDiffs().stream()
                .anyMatch(d -> d.getFieldName() != null && d.getFieldName().contains("quantity"));
        assertTrue(foundDateDiff, "Should detect date format difference: " + result.getFieldDiffs());
        assertTrue(foundQuantityDiff, "Should detect quantity difference: " + result.getFieldDiffs());
    }

    @Test
    void execute_jsonWithMissingFields_detectsAddedAndRemoved() throws IOException {
        String jsonA = "{\"name\": \"Alice\", \"age\": 30}";
        String jsonB = "{\"name\": \"Alice\", \"email\": \"alice@test.com\"}";

        Path sourceOutput = createDir("missing-src");
        Path targetOutput = createDir("missing-tgt");
        Path sourceInput = createDir("missing-src-in");
        Path targetInput = createDir("missing-tgt-in");

        writeFile(sourceOutput, "user.json", jsonA);
        writeFile(targetOutput, "user.json", jsonB);

        ComparePreview preview = service.prepareComparison(CompareRequest.builder()
                .sourceInputPath(sourceInput.toString())
                .sourceOutputPath(sourceOutput.toString())
                .targetInputPath(targetInput.toString())
                .targetOutputPath(targetOutput.toString())
                .build());

        CompareReport report = service.executeComparison(preview.getSessionId());
        FileCompareResult result = report.getResults().get(0);

        assertTrue(result.getFieldsRemoved() > 0, "Should detect removed 'age' field");
        assertTrue(result.getFieldsAdded() > 0, "Should detect added 'email' field");
    }

    // ========================================================================
    // EXECUTE — EDI comparison
    // ========================================================================

    @Test
    void execute_differentEdiFiles_usesSemanticDiff() throws IOException {
        Path sourceOutput = createDir("edi-src");
        Path targetOutput = createDir("edi-tgt");
        Path sourceInput = createDir("edi-src-in");
        Path targetInput = createDir("edi-tgt-in");

        writeFile(sourceOutput, "order.edi", SAMPLE_X12_A);
        writeFile(targetOutput, "order.edi", SAMPLE_X12_B);

        ComparePreview preview = service.prepareComparison(CompareRequest.builder()
                .sourceInputPath(sourceInput.toString())
                .sourceOutputPath(sourceOutput.toString())
                .targetInputPath(targetInput.toString())
                .targetOutputPath(targetOutput.toString())
                .build());

        CompareReport report = service.executeComparison(preview.getSessionId());
        FileCompareResult result = report.getResults().get(0);

        assertFalse(result.isOutputsIdentical());
        assertTrue(result.getFieldDifferences() > 0);
        // NM1 and PO1 segments differ
        assertTrue(result.getFieldDiffs().stream()
                .anyMatch(d -> d.getSegmentId() != null && d.getSegmentId().equals("NM1")));
    }

    // ========================================================================
    // EXECUTE — text fallback comparison
    // ========================================================================

    @Test
    void execute_plainTextDiff_fallsBackToLineDiff() throws IOException {
        Path sourceOutput = createDir("text-src");
        Path targetOutput = createDir("text-tgt");
        Path sourceInput = createDir("text-src-in");
        Path targetInput = createDir("text-tgt-in");

        writeFile(sourceOutput, "readme.txt", "Line 1\nLine 2\nLine 3\n");
        writeFile(targetOutput, "readme.txt", "Line 1\nModified Line 2\nLine 3\nLine 4\n");

        ComparePreview preview = service.prepareComparison(CompareRequest.builder()
                .sourceInputPath(sourceInput.toString())
                .sourceOutputPath(sourceOutput.toString())
                .targetInputPath(targetInput.toString())
                .targetOutputPath(targetOutput.toString())
                .build());

        CompareReport report = service.executeComparison(preview.getSessionId());
        FileCompareResult result = report.getResults().get(0);

        assertFalse(result.isOutputsIdentical());
        assertTrue(result.getFieldDifferences() > 0);
    }

    // ========================================================================
    // MAP AGGREGATION
    // ========================================================================

    @Test
    void execute_multipleFiles_aggregatesByMap() throws IOException {
        Path sourceOutput = createDir("agg-src");
        Path targetOutput = createDir("agg-tgt");
        Path sourceInput = createDir("agg-src-in");
        Path targetInput = createDir("agg-tgt-in");

        // 2 files with same format, 1 identical + 1 different
        writeFile(sourceOutput, "850_order1.json", SAMPLE_JSON_A);
        writeFile(targetOutput, "850_order1.json", SAMPLE_JSON_A); // identical
        writeFile(sourceOutput, "850_order2.json", SAMPLE_JSON_A);
        writeFile(targetOutput, "850_order2.json", SAMPLE_JSON_B); // different

        ComparePreview preview = service.prepareComparison(CompareRequest.builder()
                .sourceInputPath(sourceInput.toString())
                .sourceOutputPath(sourceOutput.toString())
                .targetInputPath(targetInput.toString())
                .targetOutputPath(targetOutput.toString())
                .build());

        CompareReport report = service.executeComparison(preview.getSessionId());

        assertEquals(2, report.getTotalPairsCompared());
        assertEquals(1, report.getIdenticalPairs());
        assertEquals(1, report.getDifferentPairs());

        // Should have map summaries
        assertFalse(report.getMapSummaries().isEmpty());
    }

    // ========================================================================
    // RECOMMENDATIONS
    // ========================================================================

    @Test
    void execute_generatesMeaningfulRecommendations() throws IOException {
        Path sourceOutput = createDir("rec-src");
        Path targetOutput = createDir("rec-tgt");
        Path sourceInput = createDir("rec-src-in");
        Path targetInput = createDir("rec-tgt-in");

        writeFile(sourceOutput, "data.json", SAMPLE_JSON_A);
        writeFile(targetOutput, "data.json", SAMPLE_JSON_B);

        ComparePreview preview = service.prepareComparison(CompareRequest.builder()
                .sourceInputPath(sourceInput.toString())
                .sourceOutputPath(sourceOutput.toString())
                .targetInputPath(targetInput.toString())
                .targetOutputPath(targetOutput.toString())
                .build());

        CompareReport report = service.executeComparison(preview.getSessionId());

        assertFalse(report.getRecommendations().isEmpty());
        for (Recommendation rec : report.getRecommendations()) {
            assertNotNull(rec.getCategory());
            assertNotNull(rec.getSeverity());
            assertNotNull(rec.getField());
            assertNotNull(rec.getIssue());
            assertNotNull(rec.getFix());
            assertTrue(rec.getAffectedFiles() > 0);
        }
    }

    @Test
    void execute_dateFormatDiff_categorizedAsTransformDate() throws IOException {
        String jsonA = "{\"date\": \"20260406\"}";
        String jsonB = "{\"date\": \"2026-04-06\"}";

        Path sourceOutput = createDir("date-src");
        Path targetOutput = createDir("date-tgt");
        Path sourceInput = createDir("date-src-in");
        Path targetInput = createDir("date-tgt-in");

        writeFile(sourceOutput, "dates.json", jsonA);
        writeFile(targetOutput, "dates.json", jsonB);

        ComparePreview preview = service.prepareComparison(CompareRequest.builder()
                .sourceInputPath(sourceInput.toString())
                .sourceOutputPath(sourceOutput.toString())
                .targetInputPath(targetInput.toString())
                .targetOutputPath(targetOutput.toString())
                .build());

        CompareReport report = service.executeComparison(preview.getSessionId());

        boolean hasDateRec = report.getRecommendations().stream()
                .anyMatch(r -> "TRANSFORM_DATE".equals(r.getCategory()));
        assertTrue(hasDateRec, "Should recommend DATE_REFORMAT transform");
    }

    @Test
    void execute_caseDiff_categorizedAsTransformCase() throws IOException {
        String jsonA = "{\"name\": \"ACME CORP\"}";
        String jsonB = "{\"name\": \"acme corp\"}";

        Path sourceOutput = createDir("case-src");
        Path targetOutput = createDir("case-tgt");
        Path sourceInput = createDir("case-src-in");
        Path targetInput = createDir("case-tgt-in");

        writeFile(sourceOutput, "names.json", jsonA);
        writeFile(targetOutput, "names.json", jsonB);

        ComparePreview preview = service.prepareComparison(CompareRequest.builder()
                .sourceInputPath(sourceInput.toString())
                .sourceOutputPath(sourceOutput.toString())
                .targetInputPath(targetInput.toString())
                .targetOutputPath(targetOutput.toString())
                .build());

        CompareReport report = service.executeComparison(preview.getSessionId());

        boolean hasCaseRec = report.getRecommendations().stream()
                .anyMatch(r -> "TRANSFORM_CASE".equals(r.getCategory()));
        assertTrue(hasCaseRec, "Should recommend case transform");
    }

    // ========================================================================
    // REPORT GENERATION
    // ========================================================================

    @Test
    void execute_withReportPath_writesReportToFile() throws IOException {
        Path sourceOutput = createDir("rpt-src");
        Path targetOutput = createDir("rpt-tgt");
        Path sourceInput = createDir("rpt-src-in");
        Path targetInput = createDir("rpt-tgt-in");
        Path reportDir = createDir("reports");

        writeFile(sourceOutput, "file.json", SAMPLE_JSON_A);
        writeFile(targetOutput, "file.json", SAMPLE_JSON_B);

        ComparePreview preview = service.prepareComparison(CompareRequest.builder()
                .sourceInputPath(sourceInput.toString())
                .sourceOutputPath(sourceOutput.toString())
                .targetInputPath(targetInput.toString())
                .targetOutputPath(targetOutput.toString())
                .reportOutputPath(reportDir.toString())
                .build());

        CompareReport report = service.executeComparison(preview.getSessionId());

        assertNotNull(report.getReportFilePath());
        assertTrue(Files.exists(Path.of(report.getReportFilePath())));

        // Check that summary file was also generated
        String summaryPath = report.getReportFilePath().replace(".json", "-summary.txt");
        assertTrue(Files.exists(Path.of(summaryPath)));

        String summaryContent = Files.readString(Path.of(summaryPath));
        assertTrue(summaryContent.contains("EDI CONVERSION COMPARE REPORT"));
        assertTrue(summaryContent.contains("RECOMMENDATIONS"));
    }

    @Test
    void buildHumanReadableSummary_formatsCorrectly() throws IOException {
        Path sourceOutput = createDir("summary-src");
        Path targetOutput = createDir("summary-tgt");
        Path sourceInput = createDir("summary-src-in");
        Path targetInput = createDir("summary-tgt-in");

        writeFile(sourceOutput, "a.json", SAMPLE_JSON_A);
        writeFile(targetOutput, "a.json", SAMPLE_JSON_B);

        ComparePreview preview = service.prepareComparison(CompareRequest.builder()
                .sourceInputPath(sourceInput.toString())
                .sourceOutputPath(sourceOutput.toString())
                .targetInputPath(targetInput.toString())
                .targetOutputPath(targetOutput.toString())
                .build());

        CompareReport report = service.executeComparison(preview.getSessionId());
        String summary = service.buildHumanReadableSummary(report);

        assertTrue(summary.contains("VERDICT:"));
        assertTrue(summary.contains("Total pairs compared:"));
        assertTrue(summary.contains("BY MAP / FORMAT"));
        assertTrue(summary.contains("END OF REPORT"));
    }

    // ========================================================================
    // SESSION MANAGEMENT
    // ========================================================================

    @Test
    void getReport_beforeExecute_returnsNull() throws IOException {
        Path sourceOutput = createDir("sess-src");
        Path targetOutput = createDir("sess-tgt");
        Path sourceInput = createDir("sess-src-in");
        Path targetInput = createDir("sess-tgt-in");

        writeFile(sourceOutput, "f.json", SAMPLE_JSON_A);
        writeFile(targetOutput, "f.json", SAMPLE_JSON_A);

        ComparePreview preview = service.prepareComparison(CompareRequest.builder()
                .sourceInputPath(sourceInput.toString())
                .sourceOutputPath(sourceOutput.toString())
                .targetInputPath(targetInput.toString())
                .targetOutputPath(targetOutput.toString())
                .build());

        assertNull(service.getReport(preview.getSessionId()));
    }

    @Test
    void execute_unknownSession_throws() {
        assertThrows(IllegalArgumentException.class, () ->
                service.executeComparison(UUID.randomUUID()));
    }

    @Test
    void execute_sameSessionTwice_returnsCachedReport() throws IOException {
        Path sourceOutput = createDir("cache-src");
        Path targetOutput = createDir("cache-tgt");
        Path sourceInput = createDir("cache-src-in");
        Path targetInput = createDir("cache-tgt-in");

        writeFile(sourceOutput, "f.json", SAMPLE_JSON_A);
        writeFile(targetOutput, "f.json", SAMPLE_JSON_A);

        ComparePreview preview = service.prepareComparison(CompareRequest.builder()
                .sourceInputPath(sourceInput.toString())
                .sourceOutputPath(sourceOutput.toString())
                .targetInputPath(targetInput.toString())
                .targetOutputPath(targetOutput.toString())
                .build());

        CompareReport report1 = service.executeComparison(preview.getSessionId());
        CompareReport report2 = service.executeComparison(preview.getSessionId());
        assertSame(report1, report2);
    }

    @Test
    void getPreview_returnsSessionState() throws IOException {
        Path sourceOutput = createDir("prev-src");
        Path targetOutput = createDir("prev-tgt");
        Path sourceInput = createDir("prev-src-in");
        Path targetInput = createDir("prev-tgt-in");

        writeFile(sourceOutput, "f.json", SAMPLE_JSON_A);
        writeFile(targetOutput, "f.json", SAMPLE_JSON_B);

        ComparePreview preview = service.prepareComparison(CompareRequest.builder()
                .sourceInputPath(sourceInput.toString())
                .sourceOutputPath(sourceOutput.toString())
                .targetInputPath(targetInput.toString())
                .targetOutputPath(targetOutput.toString())
                .build());

        ComparePreview retrieved = service.getPreview(preview.getSessionId());
        assertNotNull(retrieved);
        assertEquals("READY", retrieved.getStatus());
        assertEquals(1, retrieved.getMatchedPairs());
    }

    // ========================================================================
    // COMPARE PAIR — direct unit tests
    // ========================================================================

    @Test
    void comparePair_identicalContent_reportsIdentical() throws IOException {
        Path srcDir = createDir("cp-src");
        Path tgtDir = createDir("cp-tgt");
        Path srcFile = writeFile(srcDir, "same.json", SAMPLE_JSON_A);
        Path tgtFile = writeFile(tgtDir, "same.json", SAMPLE_JSON_A);

        FilePair pair = FilePair.builder()
                .sourceOutputFile(srcFile.toString())
                .targetOutputFile(tgtFile.toString())
                .detectedFormat("JSON")
                .mapLabel("test")
                .build();

        FileCompareResult result = service.comparePair(pair);
        assertEquals("IDENTICAL", result.getVerdict());
        assertTrue(result.isOutputsIdentical());
        assertEquals(0, result.getFieldDifferences());
    }

    @Test
    void comparePair_missingFile_reportsError() {
        FilePair pair = FilePair.builder()
                .sourceOutputFile("/nonexistent/file.json")
                .targetOutputFile("/nonexistent/other.json")
                .detectedFormat("JSON")
                .mapLabel("test")
                .build();

        FileCompareResult result = service.comparePair(pair);
        assertEquals("ERROR", result.getVerdict());
        assertNotNull(result.getError());
    }

    // ========================================================================
    // VERDICT CLASSIFICATION
    // ========================================================================

    @Test
    void execute_perfectMatch_verdictStartsWithPerfect() throws IOException {
        Path sourceOutput = createDir("v-src");
        Path targetOutput = createDir("v-tgt");
        Path sourceInput = createDir("v-src-in");
        Path targetInput = createDir("v-tgt-in");

        writeFile(sourceOutput, "a.json", SAMPLE_JSON_A);
        writeFile(targetOutput, "a.json", SAMPLE_JSON_A);
        writeFile(sourceOutput, "b.json", SAMPLE_JSON_B);
        writeFile(targetOutput, "b.json", SAMPLE_JSON_B);

        ComparePreview preview = service.prepareComparison(CompareRequest.builder()
                .sourceInputPath(sourceInput.toString())
                .sourceOutputPath(sourceOutput.toString())
                .targetInputPath(targetInput.toString())
                .targetOutputPath(targetOutput.toString())
                .build());

        CompareReport report = service.executeComparison(preview.getSessionId());
        assertTrue(report.getOverallVerdict().startsWith("PERFECT"));
        assertEquals(2, report.getIdenticalPairs());
    }

    // ========================================================================
    // HELPERS
    // ========================================================================

    private Path createDir(String name) throws IOException {
        Path dir = tempDir.resolve(name);
        Files.createDirectories(dir);
        return dir;
    }

    private Path writeFile(Path dir, String name, String content) throws IOException {
        Path file = dir.resolve(name);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }
}
