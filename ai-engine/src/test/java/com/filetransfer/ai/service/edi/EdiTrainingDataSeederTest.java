package com.filetransfer.ai.service.edi;

import com.filetransfer.ai.entity.edi.TrainingSample;
import com.filetransfer.ai.repository.edi.TrainingSampleRepository;
import com.filetransfer.ai.service.edi.EdiTrainingDataService.AddSampleRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Tests EdiTrainingDataSeeder: verifies seed data count, format correctness, and content validity.
 * Mocks the repository (not the service) to avoid JDK 25 Mockito byte-buddy limitations.
 */
@ExtendWith(MockitoExtension.class)
class EdiTrainingDataSeederTest {

    @Mock
    private TrainingSampleRepository sampleRepo;

    private EdiTrainingDataService dataService;
    private EdiTrainingDataSeeder seeder;

    @BeforeEach
    void setUp() {
        dataService = new EdiTrainingDataService(sampleRepo);
        seeder = new EdiTrainingDataSeeder(dataService);
    }

    private void stubRepoSave() {
        when(sampleRepo.save(any(TrainingSample.class))).thenAnswer(inv -> {
            TrainingSample s = inv.getArgument(0);
            s.setId(UUID.randomUUID());
            return s;
        });
    }

    // ---- seedAll should call save the correct number of times ----
    // Counts: X12_850(3) + X12_810(3) + X12_837(3) + X12_835(3) + X12_856(3)
    //       + EDIFACT_ORDERS(2) + EDIFACT_INVOIC(2) + HL7_ADT(2) + SWIFT_MT103(2) = 23

    @Test
    void seedAll_shouldSaveAllBuiltInSamples() {
        stubRepoSave();

        Map<String, Object> result = seeder.seedAll();

        verify(sampleRepo, times(23)).save(any(TrainingSample.class));
        assertEquals(23, result.get("totalSamples"));
    }

    // ---- Every sample has non-null required fields ----

    @Test
    void seedAll_eachSample_shouldHaveRequiredFields() {
        ArgumentCaptor<TrainingSample> captor = ArgumentCaptor.forClass(TrainingSample.class);
        stubRepoSave();

        seeder.seedAll();

        verify(sampleRepo, atLeastOnce()).save(captor.capture());
        List<TrainingSample> allSamples = captor.getAllValues();

        for (int i = 0; i < allSamples.size(); i++) {
            TrainingSample s = allSamples.get(i);
            assertNotNull(s.getSourceFormat(), "Sample " + i + " sourceFormat should not be null");
            assertNotNull(s.getTargetFormat(), "Sample " + i + " targetFormat should not be null");
            assertNotNull(s.getInputContent(), "Sample " + i + " inputContent should not be null");
            assertNotNull(s.getOutputContent(), "Sample " + i + " outputContent should not be null");
            assertFalse(s.getInputContent().isBlank(), "Sample " + i + " inputContent should not be blank");
            assertFalse(s.getOutputContent().isBlank(), "Sample " + i + " outputContent should not be blank");
        }
    }

    // ---- X12 samples contain ISA* ----

    @Test
    void seedAll_x12Samples_shouldContainIsaDelimiter() {
        ArgumentCaptor<TrainingSample> captor = ArgumentCaptor.forClass(TrainingSample.class);
        stubRepoSave();

        seeder.seedAll();

        verify(sampleRepo, atLeastOnce()).save(captor.capture());

        long x12Count = captor.getAllValues().stream()
                .filter(s -> "X12".equals(s.getSourceFormat()))
                .peek(s -> assertTrue(s.getInputContent().contains("ISA*"),
                        "X12 sample should contain 'ISA*' in inputContent"))
                .count();

        assertTrue(x12Count > 0, "Should have at least one X12 sample");
    }

    // ---- EDIFACT samples contain UNB+ ----

    @Test
    void seedAll_edifactSamples_shouldContainUnbDelimiter() {
        ArgumentCaptor<TrainingSample> captor = ArgumentCaptor.forClass(TrainingSample.class);
        stubRepoSave();

        seeder.seedAll();

        verify(sampleRepo, atLeastOnce()).save(captor.capture());

        long edifactCount = captor.getAllValues().stream()
                .filter(s -> "EDIFACT".equals(s.getSourceFormat()))
                .peek(s -> assertTrue(s.getInputContent().contains("UNB+"),
                        "EDIFACT sample should contain 'UNB+' in inputContent"))
                .count();

        assertTrue(edifactCount > 0, "Should have at least one EDIFACT sample");
    }

    // ---- HL7 samples contain MSH| ----

    @Test
    void seedAll_hl7Samples_shouldContainMshPipe() {
        ArgumentCaptor<TrainingSample> captor = ArgumentCaptor.forClass(TrainingSample.class);
        stubRepoSave();

        seeder.seedAll();

        verify(sampleRepo, atLeastOnce()).save(captor.capture());

        long hl7Count = captor.getAllValues().stream()
                .filter(s -> "HL7".equals(s.getSourceFormat()))
                .peek(s -> assertTrue(s.getInputContent().contains("MSH|"),
                        "HL7 sample should contain 'MSH|' in inputContent"))
                .count();

        assertTrue(hl7Count > 0, "Should have at least one HL7 sample");
    }

    // ---- SWIFT samples contain {1: ----

    @Test
    void seedAll_swiftSamples_shouldContainBlock1Marker() {
        ArgumentCaptor<TrainingSample> captor = ArgumentCaptor.forClass(TrainingSample.class);
        stubRepoSave();

        seeder.seedAll();

        verify(sampleRepo, atLeastOnce()).save(captor.capture());

        long swiftCount = captor.getAllValues().stream()
                .filter(s -> "SWIFT_MT".equals(s.getSourceFormat()))
                .peek(s -> assertTrue(s.getInputContent().contains("{1:"),
                        "SWIFT sample should contain '{1:' in inputContent"))
                .count();

        assertTrue(swiftCount > 0, "Should have at least one SWIFT sample");
    }

    // ---- JSON outputs start with { and are structurally valid ----

    @Test
    void seedAll_jsonOutputs_shouldStartWithBrace() {
        ArgumentCaptor<TrainingSample> captor = ArgumentCaptor.forClass(TrainingSample.class);
        stubRepoSave();

        seeder.seedAll();

        verify(sampleRepo, atLeastOnce()).save(captor.capture());

        for (TrainingSample s : captor.getAllValues()) {
            if ("JSON".equals(s.getTargetFormat())) {
                String output = s.getOutputContent().trim();
                assertTrue(output.startsWith("{"),
                        "JSON output should start with '{' but was: " + output.substring(0, Math.min(20, output.length())));
                assertTrue(output.endsWith("}"),
                        "JSON output should end with '}' but was: " + output.substring(Math.max(0, output.length() - 20)));
            }
        }
    }

    // ---- seedAll returns summary with correct transaction type breakdown ----

    @Test
    void seedAll_shouldReturnTransactionTypeSummary() {
        stubRepoSave();

        Map<String, Object> result = seeder.seedAll();

        assertNotNull(result.get("transactionTypes"));
        @SuppressWarnings("unchecked")
        Map<String, Integer> counts = (Map<String, Integer>) result.get("transactionTypes");
        assertEquals(9, counts.size(), "Should have 9 transaction type groups");
        assertTrue(counts.containsKey("X12_850"));
        assertTrue(counts.containsKey("EDIFACT_ORDERS"));
        assertTrue(counts.containsKey("HL7_ADT"));
        assertTrue(counts.containsKey("SWIFT_MT103"));
    }
}
