package com.filetransfer.ai.service.edi;

import com.filetransfer.ai.entity.edi.TrainingSample;
import com.filetransfer.ai.service.edi.EdiMapTrainingEngine.FieldMapping;
import com.filetransfer.ai.service.edi.EdiMapTrainingEngine.TrainingResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests EdiMapTrainingEngine: training pipeline, field mapping, transform detection.
 * No Spring context needed -- uses real FieldEmbeddingEngine (plug-and-play).
 */
class EdiMapTrainingEngineTest {

    private EdiMapTrainingEngine engine;

    @BeforeEach
    void setUp() {
        FieldEmbeddingEngine embeddingEngine = new FieldEmbeddingEngine();
        engine = new EdiMapTrainingEngine(embeddingEngine);
    }

    // ---- Helper: build realistic X12 850 training samples ----

    private TrainingSample buildSample1() {
        return TrainingSample.builder()
                .sourceFormat("X12").sourceType("850").targetFormat("JSON")
                .inputContent(
                        "ISA*00*          *00*          *ZZ*ACME           *ZZ*GLOBALSUP      *240101*1200*U*00501*000000001*0*P*>~" +
                        "GS*PO*ACME*GLOBALSUP*20240101*1200*1*X*005010~" +
                        "ST*850*0001~" +
                        "BEG*00*NE*PO-123**20240101~" +
                        "N1*BY*Acme Corp*92*ACME01~" +
                        "PO1*001*500*EA*12.50*PE*VP*WIDGET-100~" +
                        "CTT*1*500~" +
                        "SE*7*0001~" +
                        "GE*1*1~" +
                        "IEA*1*000000001~")
                .outputContent(
                        "{\"documentType\":\"Purchase Order\",\"poNumber\":\"PO-123\",\"date\":\"2024-01-01\"," +
                        "\"buyer\":{\"name\":\"Acme Corp\",\"id\":\"ACME01\"}," +
                        "\"lineItems\":[{\"quantity\":500,\"unitPrice\":12.50,\"productCode\":\"WIDGET-100\"}]}")
                .build();
    }

    private TrainingSample buildSample2() {
        return TrainingSample.builder()
                .sourceFormat("X12").sourceType("850").targetFormat("JSON")
                .inputContent(
                        "ISA*00*          *00*          *ZZ*TECHPARTS      *ZZ*MEGADIST       *240520*0830*U*00501*000000202*0*P*>~" +
                        "GS*PO*TECHPARTS*MEGADIST*20240520*0830*202*X*005010~" +
                        "ST*850*0001~" +
                        "BEG*00*NE*PO-456**20240520~" +
                        "N1*BY*TechParts LLC*92*TECH01~" +
                        "PO1*001*1000*EA*3.25*PE*VP*RESISTOR-10K~" +
                        "CTT*1*1000~" +
                        "SE*7*0001~" +
                        "GE*1*202~" +
                        "IEA*1*000000202~")
                .outputContent(
                        "{\"documentType\":\"Purchase Order\",\"poNumber\":\"PO-456\",\"date\":\"2024-05-20\"," +
                        "\"buyer\":{\"name\":\"TechParts LLC\",\"id\":\"TECH01\"}," +
                        "\"lineItems\":[{\"quantity\":1000,\"unitPrice\":3.25,\"productCode\":\"RESISTOR-10K\"}]}")
                .build();
    }

    private TrainingSample buildSample3() {
        return TrainingSample.builder()
                .sourceFormat("X12").sourceType("850").targetFormat("JSON")
                .inputContent(
                        "ISA*00*          *00*          *ZZ*FRESHFOODS     *ZZ*ORGANICFARMS   *241101*1400*U*00401*000000303*0*P*>~" +
                        "GS*PO*FRESHFOODS*ORGANICFARMS*20241101*1400*303*X*004010~" +
                        "ST*850*0001~" +
                        "BEG*00*NE*PO-789**20241101~" +
                        "N1*BY*FreshFoods Market*92*FRESH01~" +
                        "PO1*001*2000*LB*2.15*PE*VP*APPLE-FUJI~" +
                        "CTT*1*2000~" +
                        "SE*7*0001~" +
                        "GE*1*303~" +
                        "IEA*1*000000303~")
                .outputContent(
                        "{\"documentType\":\"Purchase Order\",\"poNumber\":\"PO-789\",\"date\":\"2024-11-01\"," +
                        "\"buyer\":{\"name\":\"FreshFoods Market\",\"id\":\"FRESH01\"}," +
                        "\"lineItems\":[{\"quantity\":2000,\"unitPrice\":2.15,\"productCode\":\"APPLE-FUJI\"}]}")
                .build();
    }

    // ---- Train with 3 samples produces field mappings with confidence > 50% ----

    @Test
    void train_with3Samples_shouldProduceMappingsWithConfidenceAbove50() {
        List<TrainingSample> samples = List.of(buildSample1(), buildSample2(), buildSample3());
        TrainingResult result = engine.train(samples, "X12:850->JSON");

        assertTrue(result.isSuccess(), "Training should succeed");
        assertNotNull(result.getFieldMappings(), "Field mappings should not be null");
        assertFalse(result.getFieldMappings().isEmpty(), "Should produce at least one mapping");

        // At least some mappings should have confidence > 50%
        long highConfidence = result.getFieldMappings().stream()
                .filter(m -> m.getConfidence() > 50)
                .count();
        assertTrue(highConfidence > 0, "Should have at least one mapping with confidence > 50%");
    }

    // ---- Exact value alignment: identical values across samples -> high confidence ----

    @Test
    void train_exactValueAlignment_shouldProduceHighConfidenceMapping() {
        List<TrainingSample> samples = List.of(buildSample1(), buildSample2(), buildSample3());
        TrainingResult result = engine.train(samples, "X12:850->JSON");

        assertTrue(result.isSuccess());

        // "documentType" always equals "Purchase Order" in every sample -- look for it
        // The value "Purchase Order" appears in both source (if parsed from BEG or a constant)
        // and target "documentType" field. But more reliably, buyer.name should map from N1*02.
        // Check that at least some exact-value mappings exist
        boolean hasExactValueMapping = result.getFieldMappings().stream()
                .anyMatch(m -> "EXACT_VALUE".equals(m.getStrategy()));
        assertTrue(hasExactValueMapping, "Should have at least one EXACT_VALUE strategy mapping");
    }

    // ---- Transform detection: date reformatting ----

    @Test
    void train_dateReformat_shouldDetectTransformWithSimpleSamples() {
        // Use identical field names so the engine maps them (via exact name/position),
        // then detects the value transform (yyyyMMdd → yyyy-MM-dd)
        TrainingSample s1 = TrainingSample.builder()
                .sourceFormat("X12").sourceType("850").targetFormat("JSON")
                .inputContent("{\"date\":\"20240101\",\"buyer\":\"Acme\"}")
                .outputContent("{\"date\":\"2024-01-01\",\"buyer\":\"Acme\"}")
                .build();
        TrainingSample s2 = TrainingSample.builder()
                .sourceFormat("X12").sourceType("850").targetFormat("JSON")
                .inputContent("{\"date\":\"20240615\",\"buyer\":\"TechCo\"}")
                .outputContent("{\"date\":\"2024-06-15\",\"buyer\":\"TechCo\"}")
                .build();
        TrainingSample s3 = TrainingSample.builder()
                .sourceFormat("X12").sourceType("850").targetFormat("JSON")
                .inputContent("{\"date\":\"20241101\",\"buyer\":\"FoodCo\"}")
                .outputContent("{\"date\":\"2024-11-01\",\"buyer\":\"FoodCo\"}")
                .build();

        TrainingResult result = engine.train(List.of(s1, s2, s3), "test:date");

        assertTrue(result.isSuccess());

        boolean hasDateReformat = result.getFieldMappings().stream()
                .anyMatch(m -> m.getTransform() != null && m.getTransform().contains("DATE_REFORMAT"));
        assertTrue(hasDateReformat, "Should detect DATE_REFORMAT transform for date fields");
    }

    // ---- Transform detection: TRIM ----

    @Test
    void train_trimTransform_shouldDetectWhenPaddedValueTrimmed() {
        // Build samples where ISA sender field has trailing spaces and target doesn't
        TrainingSample padded1 = TrainingSample.builder()
                .sourceFormat("X12").sourceType("850").targetFormat("JSON")
                .inputContent("ISA*00*          *00*          *ZZ*ACME           *ZZ*GLOB           *240101*1200*U*00501*000000001*0*P*>~" +
                        "GS*PO*ACME*GLOB*20240101*1200*1*X*005010~ST*850*0001~BEG*00*NE*PO-1**20240101~SE*4*0001~GE*1*1~IEA*1*000000001~")
                .outputContent("{\"sender\":\"ACME\",\"receiver\":\"GLOB\",\"date\":\"2024-01-01\"}")
                .build();
        TrainingSample padded2 = TrainingSample.builder()
                .sourceFormat("X12").sourceType("850").targetFormat("JSON")
                .inputContent("ISA*00*          *00*          *ZZ*TECHCO         *ZZ*MEGACO         *240202*0900*U*00501*000000002*0*P*>~" +
                        "GS*PO*TECHCO*MEGACO*20240202*0900*2*X*005010~ST*850*0001~BEG*00*NE*PO-2**20240202~SE*4*0001~GE*1*2~IEA*1*000000002~")
                .outputContent("{\"sender\":\"TECHCO\",\"receiver\":\"MEGACO\",\"date\":\"2024-02-02\"}")
                .build();
        TrainingSample padded3 = TrainingSample.builder()
                .sourceFormat("X12").sourceType("850").targetFormat("JSON")
                .inputContent("ISA*00*          *00*          *ZZ*FOODS          *ZZ*FARMS          *240303*1400*U*00501*000000003*0*P*>~" +
                        "GS*PO*FOODS*FARMS*20240303*1400*3*X*005010~ST*850*0001~BEG*00*NE*PO-3**20240303~SE*4*0001~GE*1*3~IEA*1*000000003~")
                .outputContent("{\"sender\":\"FOODS\",\"receiver\":\"FARMS\",\"date\":\"2024-03-03\"}")
                .build();

        TrainingResult result = engine.train(List.of(padded1, padded2, padded3), "X12:850->JSON:trim");

        assertTrue(result.isSuccess());
        // The ISA*06 values have trailing spaces ("ACME           ") while target has "ACME"
        boolean hasTrim = result.getFieldMappings().stream()
                .anyMatch(m -> m.getTransform() != null && m.getTransform().contains("TRIM"));
        assertTrue(hasTrim, "Should detect TRIM transform for padded ISA sender fields");
    }

    // ---- Empty samples -> failure ----

    @Test
    void train_emptySamples_shouldReturnFailure() {
        TrainingResult result = engine.train(List.of(), "X12:850->JSON");

        assertFalse(result.isSuccess(), "Training with empty samples should fail");
        assertNotNull(result.getError(), "Should include an error message");
    }

    // ---- Single sample -> still produces mappings ----

    @Test
    void train_singleSample_shouldStillProduceMappings() {
        TrainingResult result = engine.train(List.of(buildSample1()), "X12:850->JSON");

        assertTrue(result.isSuccess(), "Training with 1 sample should succeed");
        assertFalse(result.getFieldMappings().isEmpty(), "Should produce mappings from single sample");
        assertEquals(0, result.getTestSampleCount(), "No test set with only 1 sample");
        assertNull(result.getTestAccuracy(), "No test accuracy with only 1 sample");
    }

    // ---- 5+ samples -> splits train/test, reports testAccuracy ----

    @Test
    void train_5PlusSamples_shouldSplitTrainTestAndReportAccuracy() {
        // Create 6 varied samples by duplicating with minor variations
        List<TrainingSample> samples = new ArrayList<>();
        samples.add(buildSample1());
        samples.add(buildSample2());
        samples.add(buildSample3());

        // Additional samples with different data
        samples.add(TrainingSample.builder()
                .sourceFormat("X12").sourceType("850").targetFormat("JSON")
                .inputContent(
                        "ISA*00*          *00*          *ZZ*STEELCO        *ZZ*METALWORKS     *240601*0800*U*00501*000000404*0*P*>~" +
                        "GS*PO*STEELCO*METALWORKS*20240601*0800*404*X*005010~" +
                        "ST*850*0001~BEG*00*NE*PO-444**20240601~N1*BY*SteelCo Inc*92*STEEL01~" +
                        "PO1*001*300*EA*45.00*PE*VP*BEAM-I200~CTT*1*300~SE*7*0001~GE*1*404~IEA*1*000000404~")
                .outputContent(
                        "{\"documentType\":\"Purchase Order\",\"poNumber\":\"PO-444\",\"date\":\"2024-06-01\"," +
                        "\"buyer\":{\"name\":\"SteelCo Inc\",\"id\":\"STEEL01\"}," +
                        "\"lineItems\":[{\"quantity\":300,\"unitPrice\":45.00,\"productCode\":\"BEAM-I200\"}]}")
                .build());

        samples.add(TrainingSample.builder()
                .sourceFormat("X12").sourceType("850").targetFormat("JSON")
                .inputContent(
                        "ISA*00*          *00*          *ZZ*PHARMA         *ZZ*CHEMICORP      *240701*1000*U*00501*000000505*0*P*>~" +
                        "GS*PO*PHARMA*CHEMICORP*20240701*1000*505*X*005010~" +
                        "ST*850*0001~BEG*00*NE*PO-555**20240701~N1*BY*Pharma Inc*92*PHAR01~" +
                        "PO1*001*5000*EA*0.75*PE*VP*CAPSULE-500MG~CTT*1*5000~SE*7*0001~GE*1*505~IEA*1*000000505~")
                .outputContent(
                        "{\"documentType\":\"Purchase Order\",\"poNumber\":\"PO-555\",\"date\":\"2024-07-01\"," +
                        "\"buyer\":{\"name\":\"Pharma Inc\",\"id\":\"PHAR01\"}," +
                        "\"lineItems\":[{\"quantity\":5000,\"unitPrice\":0.75,\"productCode\":\"CAPSULE-500MG\"}]}")
                .build());

        samples.add(TrainingSample.builder()
                .sourceFormat("X12").sourceType("850").targetFormat("JSON")
                .inputContent(
                        "ISA*00*          *00*          *ZZ*AUTOPARTS      *ZZ*TIREKING       *240801*1200*U*00501*000000606*0*P*>~" +
                        "GS*PO*AUTOPARTS*TIREKING*20240801*1200*606*X*005010~" +
                        "ST*850*0001~BEG*00*NE*PO-666**20240801~N1*BY*AutoParts Ltd*92*AUTO01~" +
                        "PO1*001*400*EA*85.00*PE*VP*TIRE-ALL-SEASON~CTT*1*400~SE*7*0001~GE*1*606~IEA*1*000000606~")
                .outputContent(
                        "{\"documentType\":\"Purchase Order\",\"poNumber\":\"PO-666\",\"date\":\"2024-08-01\"," +
                        "\"buyer\":{\"name\":\"AutoParts Ltd\",\"id\":\"AUTO01\"}," +
                        "\"lineItems\":[{\"quantity\":400,\"unitPrice\":85.00,\"productCode\":\"TIRE-ALL-SEASON\"}]}")
                .build());

        TrainingResult result = engine.train(samples, "X12:850->JSON");

        assertTrue(result.isSuccess());
        assertTrue(result.getTestSampleCount() > 0, "Should have test samples with 6 inputs");
        assertNotNull(result.getTestAccuracy(), "Should report test accuracy");
    }

    // ---- Generated code contains JSONata-like expressions ----

    @Test
    void train_generatedCode_shouldContainJsonataExpressions() {
        List<TrainingSample> samples = List.of(buildSample1(), buildSample2(), buildSample3());
        TrainingResult result = engine.train(samples, "X12:850->JSON");

        assertTrue(result.isSuccess());
        assertNotNull(result.getGeneratedCode(), "Generated code should not be null");
        assertTrue(result.getGeneratedCode().contains("$source."),
                "Generated code should contain JSONata-like $source. expressions");
    }

    // ---- Unmapped fields are reported ----

    @Test
    void train_shouldReportUnmappedFields() {
        List<TrainingSample> samples = List.of(buildSample1(), buildSample2(), buildSample3());
        TrainingResult result = engine.train(samples, "X12:850->JSON");

        assertTrue(result.isSuccess());
        // With X12 850 there are many source fields (ISA, GS, ST segments) that don't map
        // to any JSON target field, so unmapped source fields should exist
        assertNotNull(result.getUnmappedSourceFields(), "Unmapped source fields list should not be null");
        assertNotNull(result.getUnmappedTargetFields(), "Unmapped target fields list should not be null");
    }

    // ---- Strategies used are reported ----

    @Test
    void train_shouldReportStrategiesUsed() {
        List<TrainingSample> samples = List.of(buildSample1(), buildSample2(), buildSample3());
        TrainingResult result = engine.train(samples, "X12:850->JSON");

        assertTrue(result.isSuccess());
        assertNotNull(result.getStrategiesUsed());
        assertTrue(result.getStrategiesUsed().contains("EXACT_VALUE"));
        assertTrue(result.getStrategiesUsed().contains("SEMANTIC_EMBEDDING"));
        assertTrue(result.getStrategiesUsed().contains("TRANSFORM_DETECTION"));
    }

    // ---- Security: no injection through training data ----

    @Test
    void train_maliciousInputContent_shouldNotCrash() {
        TrainingSample malicious = TrainingSample.builder()
                .sourceFormat("X12").sourceType("850").targetFormat("JSON")
                .inputContent("<script>alert('xss')</script>ISA*00*${Runtime.exec('rm -rf /')}*00*" +
                        "          *ZZ*EVIL           *ZZ*TARGET         *240101*1200*U*00501*000000001*0*P*>~" +
                        "ST*850*0001~SE*2*0001~GE*1*1~IEA*1*000000001~")
                .outputContent("{\"field\":\"${7*7}\",\"cmd\":\"$(cat /etc/passwd)\"}")
                .build();

        TrainingResult result = engine.train(List.of(malicious, buildSample1(), buildSample2()), "test");

        // Should not throw; result can be success or not, but must not crash
        assertNotNull(result, "Training engine should handle malicious input gracefully");
    }
}
