package com.filetransfer.ai.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests DataClassificationService pattern detection and risk scoring.
 */
class DataClassificationServiceTest {

    @TempDir
    Path tempDir;

    private DataClassificationService service;

    @BeforeEach
    void setUp() throws Exception {
        service = new DataClassificationService();
        setField(service, "maxScanSizeMb", 100);
        setField(service, "blockUnencryptedPci", true);
    }

    @Test
    void classify_creditCardVisa_shouldDetectPCI() throws Exception {
        Path file = tempDir.resolve("visa.csv");
        Files.writeString(file, "name,card\nJohn Doe,4111111111111111\n");

        DataClassificationService.ClassificationResult result = service.classify(file);

        assertTrue(result.isScanned());
        assertEquals("CRITICAL", result.getRiskLevel());
        assertTrue(result.isBlocked());
        assertTrue(result.getCategoryCounts().containsKey("PCI"));
    }

    @Test
    void classify_creditCardMastercard_shouldDetectPCI() throws Exception {
        Path file = tempDir.resolve("mc.csv");
        Files.writeString(file, "card: 5105105105105100\n");

        DataClassificationService.ClassificationResult result = service.classify(file);

        assertTrue(result.isScanned());
        assertEquals("CRITICAL", result.getRiskLevel());
        assertTrue(result.getCategoryCounts().containsKey("PCI"));
    }

    @Test
    void classify_ssn_shouldDetectPII() throws Exception {
        Path file = tempDir.resolve("pii.txt");
        Files.writeString(file, "Employee SSN: 123-45-6789\n");

        DataClassificationService.ClassificationResult result = service.classify(file);

        assertTrue(result.isScanned());
        assertTrue(result.getCategoryCounts().containsKey("PII"));
        assertTrue(result.getDetections().stream()
                .anyMatch(d -> d.getType().equals("SSN")));
    }

    @Test
    void classify_email_shouldDetectPII() throws Exception {
        Path file = tempDir.resolve("emails.txt");
        Files.writeString(file, "Contact: john.doe@example.com\nCC: jane@company.org\n");

        DataClassificationService.ClassificationResult result = service.classify(file);

        assertTrue(result.isScanned());
        assertTrue(result.getCategoryCounts().containsKey("PII"));
        assertTrue(result.getDetections().stream()
                .anyMatch(d -> d.getType().equals("EMAIL")));
    }

    @Test
    void classify_icd10_shouldDetectPHI() throws Exception {
        Path file = tempDir.resolve("medical.csv");
        Files.writeString(file, "patient,diagnosis\nSmith,E11.9\nJones,J44.1\n");

        DataClassificationService.ClassificationResult result = service.classify(file);

        assertTrue(result.isScanned());
        assertTrue(result.getCategoryCounts().containsKey("PHI"));
    }

    @Test
    void classify_mrn_shouldDetectPHI() throws Exception {
        Path file = tempDir.resolve("records.txt");
        Files.writeString(file, "MRN: 12345678\nDiagnosis: Routine\n");

        DataClassificationService.ClassificationResult result = service.classify(file);

        assertTrue(result.isScanned());
        assertTrue(result.getCategoryCounts().containsKey("PHI"));
    }

    @Test
    void classify_cleanFile_shouldReturnNoDetections() throws Exception {
        Path file = tempDir.resolve("clean.txt");
        Files.writeString(file, "This is a plain text file with no sensitive data.\nJust business information.\n");

        DataClassificationService.ClassificationResult result = service.classify(file);

        assertTrue(result.isScanned());
        assertEquals("NONE", result.getRiskLevel());
        assertEquals(0, result.getRiskScore());
        assertFalse(result.isBlocked());
        assertTrue(result.getDetections().isEmpty());
    }

    @Test
    void classify_multiplePCI_shouldIncreaseRiskScore() throws Exception {
        Path file = tempDir.resolve("many-cards.csv");
        StringBuilder sb = new StringBuilder("card_number\n");
        for (int i = 0; i < 5; i++) {
            sb.append("4111111111111111\n");
        }
        Files.writeString(file, sb.toString());

        DataClassificationService.ClassificationResult result = service.classify(file);

        assertTrue(result.getRiskScore() > 70);
        assertEquals("CRITICAL", result.getRiskLevel());
        assertTrue(result.isRequiresEncryption());
    }

    @Test
    void classify_pciBlockedWhenConfigured_shouldBlock() throws Exception {
        Path file = tempDir.resolve("pci.csv");
        Files.writeString(file, "4111111111111111\n");

        DataClassificationService.ClassificationResult result = service.classify(file);

        assertTrue(result.isBlocked());
        assertEquals("PCI data detected — encryption required before transfer", result.getBlockReason());
    }

    @Test
    void classify_pciNotBlockedWhenDisabled() throws Exception {
        setField(service, "blockUnencryptedPci", false);

        Path file = tempDir.resolve("pci.csv");
        Files.writeString(file, "4111111111111111\n");

        DataClassificationService.ClassificationResult result = service.classify(file);

        assertFalse(result.isBlocked());
    }

    @Test
    void classify_riskScoreCapsAt100() throws Exception {
        Path file = tempDir.resolve("risky.csv");
        StringBuilder sb = new StringBuilder();
        // Tons of credit cards to push score past 100
        for (int i = 0; i < 50; i++) {
            sb.append("4111111111111111\n");
        }
        Files.writeString(file, sb.toString());

        DataClassificationService.ClassificationResult result = service.classify(file);

        assertTrue(result.getRiskScore() <= 100);
    }

    @Test
    void classify_maskedSamplesShouldNotLeakFullValues() throws Exception {
        Path file = tempDir.resolve("mask-test.csv");
        Files.writeString(file, "123-45-6789\n");

        DataClassificationService.ClassificationResult result = service.classify(file);

        for (DataClassificationService.Detection d : result.getDetections()) {
            if (d.getSample() != null) {
                // Sample should be masked (not contain the full SSN)
                assertFalse(d.getSample().equals("123-45-6789"),
                        "Sample should be masked, not plain text");
                assertTrue(d.getSample().contains("*"),
                        "Sample should contain masking characters");
            }
        }
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        var field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
