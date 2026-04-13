package com.filetransfer.shared.compliance;

import com.filetransfer.shared.compliance.ComplianceEnforcementService.ComplianceContext;
import com.filetransfer.shared.compliance.ComplianceEnforcementService.ComplianceResult;
import com.filetransfer.shared.entity.security.ComplianceProfile;
import com.filetransfer.shared.entity.security.ComplianceViolation;
import com.filetransfer.shared.repository.ComplianceProfileRepository;
import com.filetransfer.shared.repository.ComplianceViolationRepository;
import com.filetransfer.shared.routing.AiClassificationClient;
import com.filetransfer.shared.routing.AiClassificationClient.ClassificationDecision;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Comprehensive tests for ComplianceEnforcementService.
 * Covers: PCI/PHI/PII data rules, AI risk thresholds, file rules,
 * connection rules, violation actions, multiple violations, and edge cases.
 */
class ComplianceEnforcementTest {

    @Mock
    private ComplianceProfileRepository profileRepo;

    @Mock
    private ComplianceViolationRepository violationRepo;

    @Mock
    private AiClassificationClient aiClient;

    private ComplianceEnforcementService service;

    private static final UUID PROFILE_ID = UUID.randomUUID();
    private static final UUID SERVER_ID = UUID.randomUUID();
    private static final String TRACK_ID = "TRK-000001";
    private static final String SERVER_NAME = "test-server";
    private static final String USERNAME = "testuser";

    @BeforeEach
    void setUp() throws Exception {
        MockitoAnnotations.openMocks(this);
        service = new ComplianceEnforcementService(profileRepo, violationRepo);
        // Inject aiClient via reflection since it's @Autowired(required=false)
        setField(service, "aiClient", aiClient);
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private ComplianceProfile.ComplianceProfileBuilder baseProfile() {
        return ComplianceProfile.builder()
                .id(PROFILE_ID)
                .name("Test Profile")
                .active(true)
                .violationAction("BLOCK")
                .maxAllowedRiskLevel("HIGH")
                .maxAllowedRiskScore(70);
    }

    private ComplianceContext ctx(String filename) {
        return new ComplianceContext(
                TRACK_ID, PROFILE_ID, SERVER_ID, SERVER_NAME, USERNAME,
                filename, 1024L, false, true, true, Path.of("/tmp/" + filename),
                null, null
        );
    }

    private ComplianceContext ctx(String filename, long fileSize, boolean encrypted,
                                  boolean tls, boolean checksum) {
        return new ComplianceContext(
                TRACK_ID, PROFILE_ID, SERVER_ID, SERVER_NAME, USERNAME,
                filename, fileSize, encrypted, tls, checksum, Path.of("/tmp/" + filename),
                null, null
        );
    }

    private ComplianceContext ctxNoProfile(String filename) {
        return new ComplianceContext(
                TRACK_ID, null, SERVER_ID, SERVER_NAME, USERNAME,
                filename, 1024L, false, true, true, Path.of("/tmp/" + filename),
                null, null
        );
    }

    private void mockProfile(ComplianceProfile profile) {
        when(profileRepo.findById(PROFILE_ID)).thenReturn(Optional.of(profile));
    }

    private void mockAi(String riskLevel, int riskScore, String blockReason) {
        when(aiClient.classify(any(), any(), anyBoolean()))
                .thenReturn(new ClassificationDecision(false, riskLevel, riskScore, blockReason));
    }

    private void mockAiClean() {
        when(aiClient.classify(any(), any(), anyBoolean()))
                .thenReturn(new ClassificationDecision(true, "NONE", 0, null));
    }

    private boolean hasViolationType(ComplianceResult result, String type) {
        return result.violations().stream().anyMatch(v -> type.equals(v.getViolationType()));
    }

    // ════════════════════════════════════════════════════════════════════════
    // Section A: PCI Data Tests (THE CRITICAL ONES)
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Section A: PCI Data Tests")
    class PciDataTests {

        @Test
        @DisplayName("CRITICAL: Server allows PCI data + AI detects PCI = should PASS (no violation)")
        void evaluate_serverAllowsPciData_aiDetectsPci_shouldPass() {
            ComplianceProfile profile = baseProfile()
                    .allowPciData(true)
                    .requireEncryption(false)
                    .requireTls(false)
                    .requireChecksum(false)
                    .build();
            mockProfile(profile);
            mockAi("LOW", 10, "PCI data detected");

            ComplianceResult result = service.evaluate(ctx("invoice.edi"));

            assertTrue(result.passed(), "Transfer must PASS when server allows PCI data");
            assertFalse(hasViolationType(result, "PCI_DATA_DETECTED"),
                    "No PCI violation should exist when PCI data is allowed");
        }

        @Test
        @DisplayName("Server blocks PCI data + AI detects PCI = should VIOLATE with BLOCKED")
        void evaluate_serverBlocksPciData_aiDetectsPci_shouldViolate() {
            ComplianceProfile profile = baseProfile()
                    .allowPciData(false)
                    .requireEncryption(false)
                    .requireTls(false)
                    .requireChecksum(false)
                    .build();
            mockProfile(profile);
            mockAi("LOW", 10, "PCI data detected");

            ComplianceResult result = service.evaluate(ctx("invoice.edi"));

            assertTrue(result.blocked(), "Transfer must be BLOCKED when PCI is not allowed");
            assertTrue(hasViolationType(result, "PCI_DATA_DETECTED"),
                    "Must have PCI_DATA_DETECTED violation");
            assertEquals("CRITICAL", result.violations().stream()
                    .filter(v -> "PCI_DATA_DETECTED".equals(v.getViolationType()))
                    .findFirst().orElseThrow().getSeverity());
        }

        @Test
        @DisplayName("Server allows PCI but blocks PHI + AI detects both = only PHI violation")
        void evaluate_serverAllowsPciData_blocksPhi_aiDetectsBoth_shouldOnlyViolatePhi() {
            ComplianceProfile profile = baseProfile()
                    .allowPciData(true)
                    .allowPhiData(false)
                    .requireEncryption(false)
                    .requireTls(false)
                    .requireChecksum(false)
                    .build();
            mockProfile(profile);
            mockAi("LOW", 10, "PCI and PHI data detected");

            ComplianceResult result = service.evaluate(ctx("mixed-data.csv"));

            assertFalse(hasViolationType(result, "PCI_DATA_DETECTED"),
                    "PCI violation must NOT exist when PCI is allowed");
            assertTrue(hasViolationType(result, "PHI_DATA_DETECTED"),
                    "PHI violation MUST exist when PHI is not allowed");
        }

        @Test
        @DisplayName("No compliance profile assigned = PCI data flows freely")
        void evaluate_noComplianceProfile_pciDataFlowsFreely() {
            ComplianceResult result = service.evaluate(ctxNoProfile("pci-data.csv"));

            assertTrue(result.passed(), "No profile = no checks = pass");
            assertTrue(result.violations().isEmpty());
            verifyNoInteractions(profileRepo);
        }

        @Test
        @DisplayName("Inactive profile = PCI data flows freely")
        void evaluate_inactiveProfile_pciDataFlowsFreely() {
            ComplianceProfile profile = baseProfile().active(false).build();
            mockProfile(profile);

            ComplianceResult result = service.evaluate(ctx("pci-data.csv"));

            assertTrue(result.passed(), "Inactive profile = pass");
            assertTrue(result.violations().isEmpty());
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Section B: PHI/PII Data Tests
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Section B: PHI/PII Data Tests")
    class PhiPiiDataTests {

        @Test
        @DisplayName("Server blocks PHI + AI detects health data = PHI_DATA_DETECTED")
        void evaluate_serverBlocksPhi_aiDetectsHealth_shouldViolate() {
            ComplianceProfile profile = baseProfile()
                    .allowPhiData(false)
                    .requireEncryption(false)
                    .requireTls(false)
                    .requireChecksum(false)
                    .build();
            mockProfile(profile);
            mockAi("LOW", 10, "HIPAA violation detected");

            ComplianceResult result = service.evaluate(ctx("patient-records.csv"));

            assertTrue(hasViolationType(result, "PHI_DATA_DETECTED"));
            assertTrue(result.blocked());
        }

        @Test
        @DisplayName("Server allows PHI + AI detects health data = should PASS")
        void evaluate_serverAllowsPhi_aiDetectsHealth_shouldPass() {
            ComplianceProfile profile = baseProfile()
                    .allowPhiData(true)
                    .requireEncryption(false)
                    .requireTls(false)
                    .requireChecksum(false)
                    .build();
            mockProfile(profile);
            mockAi("LOW", 10, "HIPAA violation detected");

            ComplianceResult result = service.evaluate(ctx("patient-records.csv"));

            assertFalse(hasViolationType(result, "PHI_DATA_DETECTED"),
                    "PHI violation must not exist when PHI data is allowed");
            assertTrue(result.passed());
        }

        @Test
        @DisplayName("Server blocks PII + AI detects PII = PII_DATA_DETECTED")
        void evaluate_serverBlocksPii_aiDetectsPii_shouldViolate() {
            ComplianceProfile profile = baseProfile()
                    .allowPiiData(false)
                    .requireEncryption(false)
                    .requireTls(false)
                    .requireChecksum(false)
                    .build();
            mockProfile(profile);
            mockAi("LOW", 10, "PII detected — names, SSNs, email addresses");

            ComplianceResult result = service.evaluate(ctx("contacts.csv"));

            assertTrue(hasViolationType(result, "PII_DATA_DETECTED"));
            assertTrue(result.blocked());
        }

        @Test
        @DisplayName("Server allows ALL data types + AI detects everything = PASS")
        void evaluate_serverAllowsAllDataTypes_aiDetectsEverything_shouldPass() {
            ComplianceProfile profile = baseProfile()
                    .allowPciData(true)
                    .allowPhiData(true)
                    .allowPiiData(true)
                    .requireEncryption(false)
                    .requireTls(false)
                    .requireChecksum(false)
                    .build();
            mockProfile(profile);
            mockAi("LOW", 10, "PCI, PHI, HIPAA, PII data detected");

            ComplianceResult result = service.evaluate(ctx("everything.csv"));

            assertTrue(result.passed(), "All data types allowed = no data violations");
            assertFalse(hasViolationType(result, "PCI_DATA_DETECTED"));
            assertFalse(hasViolationType(result, "PHI_DATA_DETECTED"));
            assertFalse(hasViolationType(result, "PII_DATA_DETECTED"));
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Section C: AI Risk Threshold Tests
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Section C: AI Risk Threshold Tests")
    class AiRiskThresholdTests {

        @Test
        @DisplayName("Risk level below threshold = PASS")
        void evaluate_riskLevelBelowThreshold_shouldPass() {
            ComplianceProfile profile = baseProfile()
                    .maxAllowedRiskLevel("HIGH")
                    .requireEncryption(false)
                    .requireTls(false)
                    .requireChecksum(false)
                    .build();
            mockProfile(profile);
            mockAi("MEDIUM", 30, null);

            ComplianceResult result = service.evaluate(ctx("safe-file.csv"));

            assertTrue(result.passed());
            assertFalse(hasViolationType(result, "RISK_THRESHOLD_EXCEEDED"));
        }

        @Test
        @DisplayName("Risk level above threshold = RISK_THRESHOLD_EXCEEDED")
        void evaluate_riskLevelAboveThreshold_shouldViolate() {
            ComplianceProfile profile = baseProfile()
                    .maxAllowedRiskLevel("MEDIUM")
                    .maxAllowedRiskScore(90)
                    .requireEncryption(false)
                    .requireTls(false)
                    .requireChecksum(false)
                    .build();
            mockProfile(profile);
            mockAi("HIGH", 50, "Suspicious patterns");

            ComplianceResult result = service.evaluate(ctx("risky-file.csv"));

            assertTrue(hasViolationType(result, "RISK_THRESHOLD_EXCEEDED"),
                    "HIGH exceeds MEDIUM threshold");
            assertTrue(result.blocked());
        }

        @Test
        @DisplayName("Risk score below threshold = PASS")
        void evaluate_riskScoreBelowThreshold_shouldPass() {
            ComplianceProfile profile = baseProfile()
                    .maxAllowedRiskLevel("CRITICAL")
                    .maxAllowedRiskScore(70)
                    .requireEncryption(false)
                    .requireTls(false)
                    .requireChecksum(false)
                    .build();
            mockProfile(profile);
            mockAi("LOW", 50, null);

            ComplianceResult result = service.evaluate(ctx("safe-file.csv"));

            assertTrue(result.passed());
        }

        @Test
        @DisplayName("Risk score above threshold = RISK_THRESHOLD_EXCEEDED")
        void evaluate_riskScoreAboveThreshold_shouldViolate() {
            ComplianceProfile profile = baseProfile()
                    .maxAllowedRiskLevel("CRITICAL")
                    .maxAllowedRiskScore(70)
                    .requireEncryption(false)
                    .requireTls(false)
                    .requireChecksum(false)
                    .build();
            mockProfile(profile);
            mockAi("MEDIUM", 85, "High confidence malware");

            ComplianceResult result = service.evaluate(ctx("malware.csv"));

            assertTrue(hasViolationType(result, "RISK_THRESHOLD_EXCEEDED"),
                    "Score 85 exceeds threshold of 70");
            assertTrue(result.blocked());
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Section D: File Rule Tests
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Section D: File Rule Tests")
    class FileRuleTests {

        @Test
        @DisplayName("Blocked extension = BLOCKED_EXTENSION violation")
        void evaluate_blockedExtension_shouldViolate() {
            ComplianceProfile profile = baseProfile()
                    .blockedFileExtensions("exe,bat,ps1")
                    .requireEncryption(false)
                    .requireTls(false)
                    .requireChecksum(false)
                    .build();
            mockProfile(profile);
            mockAiClean();

            ComplianceResult result = service.evaluate(ctx("payload.exe"));

            assertTrue(hasViolationType(result, "BLOCKED_EXTENSION"));
            assertTrue(result.blocked());
        }

        @Test
        @DisplayName("Allowed extension in whitelist = PASS")
        void evaluate_allowedExtension_shouldPass() {
            ComplianceProfile profile = baseProfile()
                    .allowedFileExtensions("edi,xml,json")
                    .requireEncryption(false)
                    .requireTls(false)
                    .requireChecksum(false)
                    .build();
            mockProfile(profile);
            mockAiClean();

            ComplianceResult result = service.evaluate(ctx("invoice.edi"));

            assertTrue(result.passed(), "EDI is in the allowed list");
            assertFalse(hasViolationType(result, "BLOCKED_EXTENSION"));
        }

        @Test
        @DisplayName("File exceeds max size = FILE_TOO_LARGE violation")
        void evaluate_fileExceedsMaxSize_shouldViolate() {
            ComplianceProfile profile = baseProfile()
                    .maxFileSizeBytes(10 * 1024 * 1024L) // 10 MB
                    .requireEncryption(false)
                    .requireTls(false)
                    .requireChecksum(false)
                    .build();
            mockProfile(profile);
            mockAiClean();

            // 50 MB file
            ComplianceResult result = service.evaluate(ctx("bigfile.dat", 50 * 1024 * 1024L, false, true, true));

            assertTrue(hasViolationType(result, "FILE_TOO_LARGE"));
            assertTrue(result.blocked());
        }

        @Test
        @DisplayName("Encryption required + file NOT encrypted = ENCRYPTION_REQUIRED")
        void evaluate_encryptionRequired_fileNotEncrypted_shouldViolate() {
            ComplianceProfile profile = baseProfile()
                    .requireEncryption(true)
                    .requireTls(false)
                    .requireChecksum(false)
                    .build();
            mockProfile(profile);
            mockAiClean();

            // not encrypted (false)
            ComplianceResult result = service.evaluate(ctx("data.csv", 1024L, false, true, true));

            assertTrue(hasViolationType(result, "ENCRYPTION_REQUIRED"));
            assertTrue(result.blocked());
        }

        @Test
        @DisplayName("Encryption required + file IS encrypted = PASS")
        void evaluate_encryptionRequired_fileEncrypted_shouldPass() {
            ComplianceProfile profile = baseProfile()
                    .requireEncryption(true)
                    .requireTls(false)
                    .requireChecksum(false)
                    .build();
            mockProfile(profile);
            mockAiClean();

            // encrypted (true)
            ComplianceResult result = service.evaluate(ctx("data.csv.pgp", 1024L, true, true, true));

            assertTrue(result.passed());
            assertFalse(hasViolationType(result, "ENCRYPTION_REQUIRED"));
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Section E: Connection Rule Tests
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Section E: Connection Rule Tests")
    class ConnectionRuleTests {

        @Test
        @DisplayName("TLS required + plain FTP = TLS_REQUIRED violation")
        void evaluate_tlsRequired_plainFtp_shouldViolate() {
            ComplianceProfile profile = baseProfile()
                    .requireTls(true)
                    .requireEncryption(false)
                    .requireChecksum(false)
                    .build();
            mockProfile(profile);
            mockAiClean();

            // tls=false
            ComplianceResult result = service.evaluate(ctx("file.csv", 1024L, false, false, true));

            assertTrue(hasViolationType(result, "TLS_REQUIRED"));
            assertTrue(result.blocked());
        }

        @Test
        @DisplayName("TLS required + SFTP connection = PASS")
        void evaluate_tlsRequired_sftpConnection_shouldPass() {
            ComplianceProfile profile = baseProfile()
                    .requireTls(true)
                    .requireEncryption(false)
                    .requireChecksum(false)
                    .build();
            mockProfile(profile);
            mockAiClean();

            // tls=true
            ComplianceResult result = service.evaluate(ctx("file.csv", 1024L, false, true, true));

            assertTrue(result.passed());
            assertFalse(hasViolationType(result, "TLS_REQUIRED"));
        }

        @Test
        @DisplayName("Checksum required + no checksum = CHECKSUM_REQUIRED violation")
        void evaluate_checksumRequired_shouldViolate() {
            ComplianceProfile profile = baseProfile()
                    .requireChecksum(true)
                    .requireEncryption(false)
                    .requireTls(false)
                    .build();
            mockProfile(profile);
            mockAiClean();

            // checksum=false
            ComplianceResult result = service.evaluate(ctx("file.csv", 1024L, false, true, false));

            assertTrue(hasViolationType(result, "CHECKSUM_REQUIRED"));
            assertTrue(result.blocked());
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Section F: Violation Action Tests
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Section F: Violation Action Tests")
    class ViolationActionTests {

        @Test
        @DisplayName("Violation action=BLOCK = result.blocked=true")
        void evaluate_actionBlock_shouldReturnBlocked() {
            ComplianceProfile profile = baseProfile()
                    .violationAction("BLOCK")
                    .blockedFileExtensions("exe")
                    .requireEncryption(false)
                    .requireTls(false)
                    .requireChecksum(false)
                    .build();
            mockProfile(profile);
            mockAiClean();

            ComplianceResult result = service.evaluate(ctx("virus.exe"));

            assertTrue(result.blocked(), "BLOCK action = blocked=true");
            assertFalse(result.violations().isEmpty());
        }

        @Test
        @DisplayName("Violation action=WARN = result.blocked=false but violations NOT empty")
        void evaluate_actionWarn_shouldReturnNotBlocked() {
            ComplianceProfile profile = baseProfile()
                    .violationAction("WARN")
                    .blockedFileExtensions("exe")
                    .requireEncryption(false)
                    .requireTls(false)
                    .requireChecksum(false)
                    .build();
            mockProfile(profile);
            mockAiClean();

            ComplianceResult result = service.evaluate(ctx("virus.exe"));

            assertFalse(result.blocked(), "WARN action = not blocked");
            assertFalse(result.violations().isEmpty(), "But violations must still be recorded");
        }

        @Test
        @DisplayName("Violation action=LOG = result.blocked=false, violations saved silently")
        void evaluate_actionLog_shouldReturnNotBlocked() {
            ComplianceProfile profile = baseProfile()
                    .violationAction("LOG")
                    .blockedFileExtensions("exe")
                    .requireEncryption(false)
                    .requireTls(false)
                    .requireChecksum(false)
                    .build();
            mockProfile(profile);
            mockAiClean();

            ComplianceResult result = service.evaluate(ctx("virus.exe"));

            assertFalse(result.blocked(), "LOG action = not blocked");
            assertFalse(result.violations().isEmpty(), "Violations still recorded");
            verify(violationRepo).saveAll(anyList());
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Section G: Multiple Violations
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Section G: Multiple Violations")
    class MultipleViolationTests {

        @Test
        @DisplayName("Multiple violations = ALL returned, not just the first")
        void evaluate_multipleViolations_shouldReturnAll() {
            ComplianceProfile profile = baseProfile()
                    .blockedFileExtensions("exe")
                    .maxFileSizeBytes(1024L)       // 1 KB limit
                    .requireEncryption(true)
                    .requireTls(false)
                    .requireChecksum(false)
                    .build();
            mockProfile(profile);
            mockAiClean();

            // exe + 50MB + not encrypted
            ComplianceResult result = service.evaluate(
                    ctx("payload.exe", 50 * 1024 * 1024L, false, true, true));

            assertTrue(hasViolationType(result, "BLOCKED_EXTENSION"), "Must have blocked extension");
            assertTrue(hasViolationType(result, "FILE_TOO_LARGE"), "Must have file too large");
            assertTrue(hasViolationType(result, "ENCRYPTION_REQUIRED"), "Must have encryption required");
            assertTrue(result.violations().size() >= 3, "At least 3 violations: got " + result.violations().size());
        }

        @Test
        @DisplayName("All checks fail = max violations returned")
        void evaluate_allChecksFail_shouldReturnMaxViolations() {
            ComplianceProfile profile = baseProfile()
                    .blockedFileExtensions("exe")
                    .maxFileSizeBytes(100L)
                    .requireEncryption(true)
                    .requireTls(true)
                    .requireChecksum(true)
                    .allowPciData(false)
                    .allowPhiData(false)
                    .allowPiiData(false)
                    .maxAllowedRiskLevel("LOW")
                    .maxAllowedRiskScore(10)
                    .build();
            mockProfile(profile);
            mockAi("CRITICAL", 99, "PCI, PHI, HIPAA, PII data detected");

            // Everything wrong: exe, huge, not encrypted, no TLS, no checksum
            ComplianceResult result = service.evaluate(
                    ctx("payload.exe", 50 * 1024 * 1024L, false, false, false));

            assertTrue(result.blocked());
            // Expect: BLOCKED_EXTENSION + FILE_TOO_LARGE + ENCRYPTION_REQUIRED +
            // TLS_REQUIRED + CHECKSUM_REQUIRED + RISK_THRESHOLD_EXCEEDED (level) +
            // RISK_THRESHOLD_EXCEEDED (score) + PCI_DATA_DETECTED + PHI_DATA_DETECTED + PII_DATA_DETECTED
            assertTrue(hasViolationType(result, "BLOCKED_EXTENSION"));
            assertTrue(hasViolationType(result, "FILE_TOO_LARGE"));
            assertTrue(hasViolationType(result, "ENCRYPTION_REQUIRED"));
            assertTrue(hasViolationType(result, "TLS_REQUIRED"));
            assertTrue(hasViolationType(result, "CHECKSUM_REQUIRED"));
            assertTrue(hasViolationType(result, "RISK_THRESHOLD_EXCEEDED"));
            assertTrue(hasViolationType(result, "PCI_DATA_DETECTED"));
            assertTrue(hasViolationType(result, "PHI_DATA_DETECTED"));
            assertTrue(hasViolationType(result, "PII_DATA_DETECTED"));
            assertTrue(result.violations().size() >= 9,
                    "Should have at least 9 violations, got " + result.violations().size());
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Section H: Edge Cases
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Section H: Edge Cases")
    class EdgeCaseTests {

        @Test
        @DisplayName("Null filename = should not crash (no NPE)")
        void evaluate_nullFilename_shouldNotCrash() {
            ComplianceProfile profile = baseProfile()
                    .blockedFileExtensions("exe")
                    .requireEncryption(false)
                    .requireTls(false)
                    .requireChecksum(false)
                    .build();
            mockProfile(profile);
            mockAiClean();

            ComplianceContext nullFilenameCtx = new ComplianceContext(
                    TRACK_ID, PROFILE_ID, SERVER_ID, SERVER_NAME, USERNAME,
                    null, 1024L, false, true, true, null, null, null);

            assertDoesNotThrow(() -> service.evaluate(nullFilenameCtx),
                    "Null filename must not throw NPE");
        }

        @Test
        @DisplayName("Null aiClient = skip AI checks, file/extension checks still work")
        void evaluate_nullAiClient_shouldSkipAiChecks() throws Exception {
            // Set aiClient to null
            setField(service, "aiClient", null);

            ComplianceProfile profile = baseProfile()
                    .blockedFileExtensions("exe")
                    .requireEncryption(false)
                    .requireTls(false)
                    .requireChecksum(false)
                    .build();
            mockProfile(profile);

            ComplianceResult result = service.evaluate(ctx("virus.exe"));

            assertTrue(hasViolationType(result, "BLOCKED_EXTENSION"),
                    "File extension check must still work without AI");
            assertFalse(hasViolationType(result, "PCI_DATA_DETECTED"),
                    "No AI = no PCI check");
            assertFalse(hasViolationType(result, "RISK_THRESHOLD_EXCEEDED"),
                    "No AI = no risk check");
        }

        @Test
        @DisplayName("Empty profile with all defaults = enforce defaults")
        void evaluate_emptyProfile_allDefaults_shouldEnforceDefaults() {
            // Profile with only defaults set
            ComplianceProfile profile = ComplianceProfile.builder()
                    .id(PROFILE_ID)
                    .name("Default Profile")
                    .active(true)
                    .build();
            mockProfile(profile);
            mockAiClean();

            // Default requires TLS (requireTls defaults to true), plain FTP should fail
            ComplianceResult resultNoTls = service.evaluate(
                    ctx("safe.csv", 1024L, false, false, true));

            assertTrue(hasViolationType(resultNoTls, "TLS_REQUIRED"),
                    "Default profile requires TLS — plain FTP must violate");

            // With TLS, should pass (defaults: no encryption req, no checksum req)
            ComplianceResult resultWithTls = service.evaluate(
                    ctx("safe.csv", 1024L, false, true, true));

            // Default allowPiiData=true, so PII data is fine. Default maxRiskLevel=MEDIUM.
            // With clean AI, should pass.
            assertFalse(hasViolationType(resultWithTls, "TLS_REQUIRED"));
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Section I: Performance
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Section I: Performance")
    class PerformanceTests {

        @Test
        @DisplayName("1000 evaluations should complete under 500ms")
        void evaluate_performance_1000Evaluations_shouldBeUnder500ms() {
            ComplianceProfile profile = baseProfile()
                    .blockedFileExtensions("exe,bat")
                    .maxFileSizeBytes(10_000_000L)
                    .requireEncryption(false)
                    .requireTls(false)
                    .requireChecksum(false)
                    .build();
            mockProfile(profile);
            mockAiClean();

            // Warm up
            for (int i = 0; i < 10; i++) {
                service.evaluate(ctx("file" + i + ".csv"));
            }

            long start = System.nanoTime();
            for (int i = 0; i < 1000; i++) {
                service.evaluate(ctx("file" + i + ".csv"));
            }
            long elapsed = (System.nanoTime() - start) / 1_000_000; // ms

            assertTrue(elapsed < 500, "1000 evaluations took " + elapsed + "ms, must be under 500ms");
        }
    }
}
