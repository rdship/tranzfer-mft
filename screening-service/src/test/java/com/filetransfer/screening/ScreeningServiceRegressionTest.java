package com.filetransfer.screening;

import com.filetransfer.screening.client.ClamAvClient;
import com.filetransfer.screening.service.AntivirusEngine;
import com.filetransfer.screening.service.DlpEngine;
import com.filetransfer.screening.service.ScreeningEngine;
import com.filetransfer.shared.entity.DlpPolicy;
import com.filetransfer.shared.entity.QuarantineRecord;
import com.filetransfer.shared.repository.DlpPolicyRepository;
import com.filetransfer.shared.repository.QuarantineRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Regression, usability, and performance tests for screening-service.
 * Pure JUnit 5 + Mockito — no Spring context.
 */
@ExtendWith(MockitoExtension.class)
class ScreeningServiceRegressionTest {

    @Mock private ClamAvClient clamAvClient;
    @Mock private QuarantineRecordRepository quarantineRepository;
    @Mock private DlpPolicyRepository dlpPolicyRepository;

    private AntivirusEngine antivirusEngine;
    private DlpEngine dlpEngine;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws Exception {
        antivirusEngine = new AntivirusEngine(clamAvClient, quarantineRepository);
        // Set quarantine path via reflection
        setField(antivirusEngine, "quarantinePath", tempDir.resolve("quarantine").toString());

        dlpEngine = new DlpEngine(dlpPolicyRepository);
    }

    // ── AV: Clean file ─────────────────────────────────────────────────

    @Test
    void screeningEngine_cleanFile_shouldPassWithClearVerdict() throws Exception {
        Path testFile = tempDir.resolve("clean.txt");
        Files.writeString(testFile, "This is safe content.");

        ClamAvClient.ScanResult cleanResult = ClamAvClient.ScanResult.builder()
                .clean(true).scanTimeMs(50).build();
        when(clamAvClient.scanFile(testFile)).thenReturn(cleanResult);

        AntivirusEngine.AvScanResult result = antivirusEngine.scanFile(
                testFile, "TRK-CLEAN-001", "test-user");

        assertTrue(result.isClean(), "Clean file must produce clean verdict");
        assertNull(result.getVirusName());
    }

    // ── AV: Infected file ──────────────────────────────────────────────

    @Test
    void screeningEngine_infectedFile_shouldReturnBlockedVerdict() throws Exception {
        Path testFile = tempDir.resolve("infected.txt");
        Files.writeString(testFile, "X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*");

        ClamAvClient.ScanResult infectedResult = ClamAvClient.ScanResult.builder()
                .clean(false).virusName("Eicar-Test-Signature").scanTimeMs(100).build();
        when(clamAvClient.scanFile(testFile)).thenReturn(infectedResult);

        QuarantineRecord mockRecord = QuarantineRecord.builder()
                .id(UUID.randomUUID()).filename("infected.txt").status("QUARANTINED").build();
        when(quarantineRepository.save(any(QuarantineRecord.class))).thenReturn(mockRecord);

        AntivirusEngine.AvScanResult result = antivirusEngine.scanFile(
                testFile, "TRK-INF-001", "test-user");

        assertFalse(result.isClean(), "Infected file must produce blocked verdict");
        assertEquals("Eicar-Test-Signature", result.getVirusName());
    }

    // ── Usability: null filename should not crash ──────────────────────

    @Test
    void screeningPipeline_nullFilename_shouldNotCrash() {
        // Jaro-Winkler similarity with null should return 0 and not crash
        double score = ScreeningEngine.jaroWinklerSimilarity(null, "test");
        assertEquals(0.0, score, "Null input to screening should return 0, not crash");

        score = ScreeningEngine.jaroWinklerSimilarity("test", null);
        assertEquals(0.0, score);

        score = ScreeningEngine.jaroWinklerSimilarity(null, null);
        assertEquals(0.0, score);
    }

    // ── Performance: sanctions check throughput ────────────────────────

    @Test
    void screeningEngine_performance_1000Evaluations_shouldBeUnder1s() {
        long start = System.nanoTime();
        for (int i = 0; i < 1000; i++) {
            ScreeningEngine.jaroWinklerSimilarity("muhammad ali", "mohammad ali");
            ScreeningEngine.jaroWinklerSimilarity("john smith", "jon smith");
            ScreeningEngine.jaroWinklerSimilarity("acme corporation", "acme corp");
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        System.out.println("[BENCHMARK] 3000 Jaro-Winkler evaluations: " + elapsedMs + "ms");
        assertTrue(elapsedMs < 1000,
                "1000 screening evaluations took " + elapsedMs + "ms — must complete under 1s");
    }

    // ── DLP: detect SSN/credit card patterns ───────────────────────────

    @Test
    void dlpEngine_sensitivePattern_shouldDetect() throws Exception {
        // Create a text file with sensitive data
        Path testFile = tempDir.resolve("sensitive.csv");
        Files.writeString(testFile, "name,ssn,card\nJohn Doe,123-45-6789,4111111111111111\n");

        // Create DLP policy with SSN and credit card patterns
        DlpPolicy policy = DlpPolicy.builder()
                .id(UUID.randomUUID())
                .name("PII-Detection")
                .active(true)
                .action("BLOCK")
                .patterns(List.of(
                        DlpPolicy.PatternDefinition.builder()
                                .type("PII_SSN")
                                .label("Social Security Number")
                                .regex("\\d{3}-\\d{2}-\\d{4}")
                                .build(),
                        DlpPolicy.PatternDefinition.builder()
                                .type("PCI_CREDIT_CARD")
                                .label("Credit Card Number")
                                .regex("\\b(?:4[0-9]{12}(?:[0-9]{3})?|5[1-5][0-9]{14})\\b")
                                .build()
                ))
                .build();

        when(dlpPolicyRepository.findByActiveTrueOrderByCreatedAtDesc()).thenReturn(List.of(policy));

        DlpEngine.DlpScanResult result = dlpEngine.scanFile(testFile, "TRK-DLP-001");

        assertTrue(result.isHasSensitiveData(), "DLP should detect sensitive patterns");
        assertFalse(result.getFindings().isEmpty(), "Should have at least one finding");

        // Verify SSN was detected
        boolean ssnFound = result.getFindings().stream()
                .anyMatch(f -> "PII_SSN".equals(f.getType()));
        assertTrue(ssnFound, "SSN pattern should be detected");
    }

    // ── Helper ──────────────────────────────────────────────────────────

    private void setField(Object target, String fieldName, Object value) throws Exception {
        var field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
