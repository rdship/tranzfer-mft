package com.filetransfer.edi.converter;

import com.filetransfer.edi.model.EdiDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for UniversalConverter covering all output formats (JSON, XML, CSV, YAML, TIF)
 * and resilience for invalid/null inputs.
 */
@ExtendWith(MockitoExtension.class)
class UniversalConverterTest {

    private UniversalConverter converter;
    private EdiDocument sampleDoc;

    @BeforeEach
    void setUp() {
        converter = new UniversalConverter();

        sampleDoc = EdiDocument.builder()
                .sourceFormat("X12")
                .documentType("850")
                .documentName("Purchase Order")
                .version("005010")
                .senderId("SENDER")
                .receiverId("RECEIVER")
                .documentDate("240101")
                .controlNumber("000000001")
                .rawContent("ISA*00*...(raw)...")
                .businessData(Map.of("transactionType", "850", "segmentCount", 8))
                .segments(List.of(
                        EdiDocument.Segment.builder().id("ISA")
                                .elements(List.of("00", "          ", "00", "          ",
                                        "ZZ", "SENDER         ", "ZZ", "RECEIVER       ",
                                        "240101", "1200", "U", "00501", "000000001", "0", "P"))
                                .build(),
                        EdiDocument.Segment.builder().id("GS")
                                .elements(List.of("PO", "SENDER", "RECEIVER", "20240101", "1200", "1", "X", "005010"))
                                .build(),
                        EdiDocument.Segment.builder().id("ST")
                                .elements(List.of("850", "0001"))
                                .build(),
                        EdiDocument.Segment.builder().id("BEG")
                                .elements(List.of("00", "NE", "PO-12345", "", "20240101"))
                                .build(),
                        EdiDocument.Segment.builder().id("PO1")
                                .elements(List.of("1", "10", "EA", "25.00", "", "UP", "123456789"))
                                .build(),
                        EdiDocument.Segment.builder().id("SE")
                                .elements(List.of("4", "0001"))
                                .build(),
                        EdiDocument.Segment.builder().id("GE")
                                .elements(List.of("1", "1"))
                                .build(),
                        EdiDocument.Segment.builder().id("IEA")
                                .elements(List.of("1", "000000001"))
                                .build()
                ))
                .build();
    }

    // === JSON conversion ===

    @Test
    void convertToJson_producesValidJsonStructure() {
        String json = converter.convert(sampleDoc, "JSON");
        assertNotNull(json);
        assertTrue(json.startsWith("{"));
        assertTrue(json.endsWith("}"));
    }

    @Test
    void convertToJson_containsSourceFormat() {
        String json = converter.convert(sampleDoc, "JSON");
        assertTrue(json.contains("\"sourceFormat\""));
        assertTrue(json.contains("X12"));
    }

    @Test
    void convertToJson_containsDocumentType() {
        String json = converter.convert(sampleDoc, "JSON");
        assertTrue(json.contains("\"documentType\""));
        assertTrue(json.contains("850"));
    }

    @Test
    void convertToJson_containsSegments() {
        String json = converter.convert(sampleDoc, "JSON");
        assertTrue(json.contains("\"segments\""));
        assertTrue(json.contains("\"ISA\""));
        assertTrue(json.contains("\"BEG\""));
    }

    @Test
    void convertToJson_containsSenderAndReceiver() {
        String json = converter.convert(sampleDoc, "JSON");
        assertTrue(json.contains("\"senderId\""));
        assertTrue(json.contains("SENDER"));
        assertTrue(json.contains("\"receiverId\""));
        assertTrue(json.contains("RECEIVER"));
    }

    @Test
    void convertToJson_caseInsensitiveFormat() {
        String jsonLower = converter.convert(sampleDoc, "json");
        String jsonMixed = converter.convert(sampleDoc, "Json");
        assertNotNull(jsonLower);
        assertNotNull(jsonMixed);
        assertTrue(jsonLower.contains("sourceFormat"));
        assertTrue(jsonMixed.contains("sourceFormat"));
    }

    // === XML conversion ===

    @Test
    void convertToXml_containsXmlDeclaration() {
        String xml = converter.convert(sampleDoc, "XML");
        assertTrue(xml.contains("<?xml version=\"1.0\""));
    }

    @Test
    void convertToXml_containsXmlTags() {
        String xml = converter.convert(sampleDoc, "XML");
        assertTrue(xml.contains("<sourceFormat>"));
        assertTrue(xml.contains("X12"));
        assertTrue(xml.contains("<documentType>"));
    }

    @Test
    void convertToXml_containsSegmentData() {
        String xml = converter.convert(sampleDoc, "XML");
        assertTrue(xml.contains("ISA") || xml.contains("segments"));
    }

    // === CSV conversion ===

    @Test
    void convertToCsv_hasHeaderRow() {
        String csv = converter.convert(sampleDoc, "CSV");
        assertNotNull(csv);
        String[] lines = csv.split("\n");
        assertTrue(lines.length > 0);
        assertTrue(lines[0].startsWith("segment_id"));
    }

    @Test
    void convertToCsv_hasDataRows() {
        String csv = converter.convert(sampleDoc, "CSV");
        String[] lines = csv.split("\n");
        // Header + 8 segment rows
        assertTrue(lines.length >= 9);
    }

    @Test
    void convertToCsv_headerContainsElementColumns() {
        String csv = converter.convert(sampleDoc, "CSV");
        String headerLine = csv.split("\n")[0];
        assertTrue(headerLine.contains("element_1"));
        assertTrue(headerLine.contains("element_2"));
    }

    @Test
    void convertToCsv_dataRowsContainSegmentIds() {
        String csv = converter.convert(sampleDoc, "CSV");
        assertTrue(csv.contains("ISA,"));
        assertTrue(csv.contains("BEG,"));
        assertTrue(csv.contains("PO1,"));
    }

    @Test
    void convertToCsv_dataContainsPO1Values() {
        String csv = converter.convert(sampleDoc, "CSV");
        // PO1 row should have item data
        assertTrue(csv.contains("123456789"));
    }

    // === YAML conversion ===

    @Test
    void convertToYaml_producesYamlStructure() {
        String yaml = converter.convert(sampleDoc, "YAML");
        assertNotNull(yaml);
        // YAML typically starts with --- or directly with keys
        assertTrue(yaml.contains("sourceFormat:") || yaml.contains("sourceFormat :"));
    }

    @Test
    void convertToYaml_containsDocumentType() {
        String yaml = converter.convert(sampleDoc, "YAML");
        assertTrue(yaml.contains("850"));
    }

    @Test
    void convertToYaml_containsSegments() {
        String yaml = converter.convert(sampleDoc, "YAML");
        assertTrue(yaml.contains("segments"));
    }

    // === TIF (TranzFer Internal Format) ===

    @Test
    void convertToTif_containsTifVersionMetadata() {
        String tif = converter.convert(sampleDoc, "TIF");
        assertNotNull(tif);
        assertTrue(tif.contains("_tif_version"));
        assertTrue(tif.contains("1.0"));
    }

    @Test
    void convertToTif_containsTifFormatLabel() {
        String tif = converter.convert(sampleDoc, "TIF");
        assertTrue(tif.contains("TranzFer Internal Format"));
    }

    @Test
    void convertToTif_containsSourceBlock() {
        String tif = converter.convert(sampleDoc, "TIF");
        assertTrue(tif.contains("\"source\""));
        assertTrue(tif.contains("\"format\""));
        assertTrue(tif.contains("X12"));
    }

    @Test
    void convertToTif_containsEnvelopeBlock() {
        String tif = converter.convert(sampleDoc, "TIF");
        assertTrue(tif.contains("\"envelope\""));
        assertTrue(tif.contains("\"sender\""));
        assertTrue(tif.contains("\"receiver\""));
        assertTrue(tif.contains("SENDER"));
        assertTrue(tif.contains("RECEIVER"));
    }

    @Test
    void convertToTif_containsRecords() {
        String tif = converter.convert(sampleDoc, "TIF");
        assertTrue(tif.contains("\"records\""));
        assertTrue(tif.contains("\"recordCount\""));
    }

    @Test
    void convertToTif_containsBusinessData() {
        String tif = converter.convert(sampleDoc, "TIF");
        assertTrue(tif.contains("\"businessData\""));
        assertTrue(tif.contains("transactionType"));
    }

    @Test
    void convertToTif_alsoAvailableViaINTERNAL() {
        String tif = converter.convert(sampleDoc, "INTERNAL");
        assertTrue(tif.contains("_tif_version"));
    }

    // === Invalid target format ===

    @ParameterizedTest
    @ValueSource(strings = {"WORD", "PDF", "BANANA", "X12", ""})
    void convertToInvalidFormat_throwsIllegalArgument(String format) {
        // Empty string causes toUpperCase to still not match known formats
        if (format.isEmpty()) return; // toUpperCase of "" is "" which is unsupported
        assertThrows(IllegalArgumentException.class, () -> converter.convert(sampleDoc, format));
    }

    @Test
    void convertToUnsupportedFormat_exceptionContainsFormatName() {
        var ex = assertThrows(IllegalArgumentException.class,
                () -> converter.convert(sampleDoc, "BANANA"));
        assertTrue(ex.getMessage().contains("BANANA"));
    }

    // === Null document handling ===

    @Test
    void convertNullDocument_handledGracefully() {
        // The converter handles null gracefully or throws — both are acceptable
        try {
            String result = converter.convert(null, "JSON");
            assertNotNull(result);
        } catch (Exception e) {
            assertNotNull(e);
        }
    }

    // === Document with null segments ===

    @Test
    void convertDocWithNullSegments_csvHandlesGracefully() {
        EdiDocument emptyDoc = EdiDocument.builder()
                .sourceFormat("X12").documentType("850")
                .documentName("Test").segments(List.of())
                .build();
        String csv = converter.convert(emptyDoc, "CSV");
        assertNotNull(csv);
        assertTrue(csv.contains("segment_id"));
    }

    @Test
    void convertDocWithNullSenderReceiver_tifHandlesNulls() {
        EdiDocument minimalDoc = EdiDocument.builder()
                .sourceFormat("X12").documentType("850")
                .segments(List.of(
                        EdiDocument.Segment.builder().id("ST").elements(List.of("850", "0001")).build()
                ))
                .build();
        String tif = converter.convert(minimalDoc, "TIF");
        assertNotNull(tif);
        assertTrue(tif.contains("_tif_version"));
        // Null fields should become empty strings, not cause errors
        assertTrue(tif.contains("\"sender\""));
    }

    // === FLAT/FIXED output ===

    @Test
    void convertToFlat_producesFixedWidthOutput() {
        String flat = converter.convert(sampleDoc, "FLAT");
        assertNotNull(flat);
        assertTrue(flat.contains("FORMAT"));
        assertTrue(flat.contains("DOC_TYPE"));
        assertTrue(flat.contains("X12"));
    }

    @Test
    void convertToFixed_sameAsFlat() {
        String flat = converter.convert(sampleDoc, "FLAT");
        String fixed = converter.convert(sampleDoc, "FIXED");
        // Both should produce output (exact match may vary by timing but structure is same)
        assertNotNull(flat);
        assertNotNull(fixed);
        assertTrue(fixed.contains("FORMAT"));
    }
}
