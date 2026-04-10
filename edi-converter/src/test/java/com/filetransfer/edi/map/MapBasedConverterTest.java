package com.filetransfer.edi.map;

import com.filetransfer.edi.model.EdiDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for MapBasedConverter: field-level transforms (COPY, TRIM, DATE_FORMAT,
 * LOOKUP), loop mapping, qualified segment lookup, default values, and nested
 * target path creation.
 *
 * Constructs MapBasedConverter directly — no Spring context needed.
 */
class MapBasedConverterTest {

    private MapBasedConverter converter;

    @BeforeEach
    void setUp() {
        converter = new MapBasedConverter();
    }

    // === Helper: build a minimal EdiDocument ===

    private EdiDocument buildDoc(EdiDocument.Segment... segments) {
        return EdiDocument.builder()
                .sourceFormat("X12")
                .documentType("850")
                .segments(List.of(segments))
                .build();
    }

    private EdiDocument.Segment seg(String id, String... elements) {
        return EdiDocument.Segment.builder()
                .id(id)
                .elements(List.of(elements))
                .build();
    }

    // === COPY transform ===

    @Test
    void convert_simpleCopy() {
        EdiDocument doc = buildDoc(seg("BEG", "00", "NE", "PO-12345"));

        ConversionMapDefinition map = ConversionMapDefinition.builder()
                .mapId("test-copy")
                .fieldMappings(List.of(
                        ConversionMapDefinition.FieldMapping.builder()
                                .sourcePath("BEG.03")
                                .targetPath("poNumber")
                                .transform("COPY")
                                .confidence(1.0)
                                .build()))
                .build();

        Map<String, Object> result = converter.convert(doc, map);

        assertEquals("PO-12345", result.get("poNumber"));
    }

    // === DATE_FORMAT transform ===

    @Test
    void convert_dateFormat() {
        EdiDocument doc = buildDoc(seg("BEG", "00", "NE", "PO-123", "", "20240115"));

        ConversionMapDefinition map = ConversionMapDefinition.builder()
                .mapId("test-date")
                .fieldMappings(List.of(
                        ConversionMapDefinition.FieldMapping.builder()
                                .sourcePath("BEG.05")
                                .targetPath("orderDate")
                                .transform("DATE_FORMAT")
                                .transformConfig(Map.of(
                                        "sourceFormat", "yyyyMMdd",
                                        "targetFormat", "yyyy-MM-dd"))
                                .confidence(1.0)
                                .build()))
                .build();

        Map<String, Object> result = converter.convert(doc, map);

        assertEquals("2024-01-15", result.get("orderDate"));
    }

    // === LOOKUP transform ===

    @Test
    void convert_lookup() {
        EdiDocument doc = buildDoc(seg("BEG", "00", "NE", "PO-123"));

        Map<String, List<ConversionMapDefinition.CodeTableEntry>> codeTables = Map.of(
                "purposeCodes", List.of(
                        ConversionMapDefinition.CodeTableEntry.builder()
                                .sourceCode("00").targetCode("ORIGINAL").build(),
                        ConversionMapDefinition.CodeTableEntry.builder()
                                .sourceCode("01").targetCode("CANCEL").build()));

        ConversionMapDefinition map = ConversionMapDefinition.builder()
                .mapId("test-lookup")
                .fieldMappings(List.of(
                        ConversionMapDefinition.FieldMapping.builder()
                                .sourcePath("BEG.01")
                                .targetPath("purpose")
                                .transform("LOOKUP")
                                .transformConfig(Map.of("table", "purposeCodes"))
                                .confidence(1.0)
                                .build()))
                .codeTables(codeTables)
                .build();

        Map<String, Object> result = converter.convert(doc, map);

        assertEquals("ORIGINAL", result.get("purpose"));
    }

    // === Loop mapping ===

    @Test
    void convert_loopMapping() {
        EdiDocument doc = buildDoc(
                seg("BEG", "00", "NE", "PO-123"),
                seg("PO1", "001", "10", "EA", "25.00"),
                seg("PO1", "002", "5", "CS", "50.00"));

        ConversionMapDefinition map = ConversionMapDefinition.builder()
                .mapId("test-loop")
                .loopMappings(List.of(
                        ConversionMapDefinition.LoopMapping.builder()
                                .sourceLoop("PO1")
                                .targetArray("lineItems")
                                .fieldMappings(List.of(
                                        ConversionMapDefinition.FieldMapping.builder()
                                                .sourcePath("PO1.01").targetPath("lineNumber")
                                                .transform("COPY").confidence(1.0).build(),
                                        ConversionMapDefinition.FieldMapping.builder()
                                                .sourcePath("PO1.02").targetPath("quantity")
                                                .transform("COPY").confidence(1.0).build(),
                                        ConversionMapDefinition.FieldMapping.builder()
                                                .sourcePath("PO1.04").targetPath("unitPrice")
                                                .transform("COPY").confidence(1.0).build()))
                                .build()))
                .build();

        Map<String, Object> result = converter.convert(doc, map);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) result.get("lineItems");
        assertNotNull(items, "lineItems should be present");
        assertEquals(2, items.size(), "Should produce 2 line items from 2 PO1 segments");

        assertEquals("001", items.get(0).get("lineNumber"));
        assertEquals("10", items.get(0).get("quantity"));
        assertEquals("25.00", items.get(0).get("unitPrice"));

        assertEquals("002", items.get(1).get("lineNumber"));
        assertEquals("5", items.get(1).get("quantity"));
        assertEquals("50.00", items.get(1).get("unitPrice"));
    }

    // === Qualified segment ===

    @Test
    void convert_qualifiedSegment() {
        // N1[BY].02 means: find N1 segment where element 01 = "BY", then get element 02
        EdiDocument doc = buildDoc(
                seg("N1", "SE", "Seller Corp", "92", "SELL01"),
                seg("N1", "BY", "Buyer Corp", "92", "BUY01"));

        ConversionMapDefinition map = ConversionMapDefinition.builder()
                .mapId("test-qualified")
                .fieldMappings(List.of(
                        ConversionMapDefinition.FieldMapping.builder()
                                .sourcePath("N1[BY].02")
                                .targetPath("buyerName")
                                .transform("COPY")
                                .confidence(1.0)
                                .build()))
                .build();

        Map<String, Object> result = converter.convert(doc, map);

        assertEquals("Buyer Corp", result.get("buyerName"),
                "Should resolve the N1 segment qualified by BY");
    }

    // === Default value ===

    @Test
    void convert_defaultValue() {
        // CUR segment is missing from doc — should use default "USD"
        EdiDocument doc = buildDoc(seg("BEG", "00", "NE", "PO-123"));

        ConversionMapDefinition map = ConversionMapDefinition.builder()
                .mapId("test-default")
                .fieldMappings(List.of(
                        ConversionMapDefinition.FieldMapping.builder()
                                .sourcePath("CUR.02")
                                .targetPath("currency")
                                .transform("COPY")
                                .defaultValue("USD")
                                .confidence(1.0)
                                .build()))
                .build();

        Map<String, Object> result = converter.convert(doc, map);

        assertEquals("USD", result.get("currency"),
                "Missing source field should use default value");
    }

    // === TRIM transform ===

    @Test
    void convert_trim() {
        EdiDocument doc = buildDoc(seg("BEG", "00", "NE", "  PO-123  "));

        ConversionMapDefinition map = ConversionMapDefinition.builder()
                .mapId("test-trim")
                .fieldMappings(List.of(
                        ConversionMapDefinition.FieldMapping.builder()
                                .sourcePath("BEG.03")
                                .targetPath("poNumber")
                                .transform("TRIM")
                                .confidence(1.0)
                                .build()))
                .build();

        Map<String, Object> result = converter.convert(doc, map);

        assertEquals("PO-123", result.get("poNumber"),
                "TRIM should remove leading and trailing whitespace");
    }

    // === Nested target path ===

    @Test
    void convert_nestedTargetPath() {
        EdiDocument doc = buildDoc(
                seg("N4", "New York", "NY", "10001", "US"));

        ConversionMapDefinition map = ConversionMapDefinition.builder()
                .mapId("test-nested")
                .fieldMappings(List.of(
                        ConversionMapDefinition.FieldMapping.builder()
                                .sourcePath("N4.01")
                                .targetPath("buyer.address.city")
                                .transform("COPY")
                                .confidence(1.0)
                                .build(),
                        ConversionMapDefinition.FieldMapping.builder()
                                .sourcePath("N4.02")
                                .targetPath("buyer.address.state")
                                .transform("COPY")
                                .confidence(1.0)
                                .build(),
                        ConversionMapDefinition.FieldMapping.builder()
                                .sourcePath("N4.03")
                                .targetPath("buyer.address.postalCode")
                                .transform("COPY")
                                .confidence(1.0)
                                .build()))
                .build();

        Map<String, Object> result = converter.convert(doc, map);

        @SuppressWarnings("unchecked")
        Map<String, Object> buyer = (Map<String, Object>) result.get("buyer");
        assertNotNull(buyer, "buyer should be created");

        @SuppressWarnings("unchecked")
        Map<String, Object> address = (Map<String, Object>) buyer.get("address");
        assertNotNull(address, "buyer.address should be created");

        assertEquals("New York", address.get("city"));
        assertEquals("NY", address.get("state"));
        assertEquals("10001", address.get("postalCode"));
    }

    // === Multiple transforms in one map ===

    @Test
    void convert_multipleTransforms_combinedResult() {
        EdiDocument doc = buildDoc(
                seg("BEG", "00", "NE", "  PO-999  ", "", "20240315"),
                seg("N1", "BY", "Acme Corp", "92", "ACME01"));

        Map<String, List<ConversionMapDefinition.CodeTableEntry>> codeTables = Map.of(
                "orderTypeCodes", List.of(
                        ConversionMapDefinition.CodeTableEntry.builder()
                                .sourceCode("NE").targetCode("NEW").build()));

        ConversionMapDefinition map = ConversionMapDefinition.builder()
                .mapId("test-combined")
                .fieldMappings(List.of(
                        ConversionMapDefinition.FieldMapping.builder()
                                .sourcePath("BEG.03").targetPath("poNumber")
                                .transform("TRIM").confidence(1.0).build(),
                        ConversionMapDefinition.FieldMapping.builder()
                                .sourcePath("BEG.02").targetPath("orderType")
                                .transform("LOOKUP")
                                .transformConfig(Map.of("table", "orderTypeCodes"))
                                .confidence(1.0).build(),
                        ConversionMapDefinition.FieldMapping.builder()
                                .sourcePath("BEG.05").targetPath("orderDate")
                                .transform("DATE_FORMAT")
                                .transformConfig(Map.of("sourceFormat", "yyyyMMdd",
                                        "targetFormat", "yyyy-MM-dd"))
                                .confidence(1.0).build(),
                        ConversionMapDefinition.FieldMapping.builder()
                                .sourcePath("N1[BY].02").targetPath("buyer.name")
                                .transform("COPY").confidence(1.0).build()))
                .codeTables(codeTables)
                .build();

        Map<String, Object> result = converter.convert(doc, map);

        assertEquals("PO-999", result.get("poNumber"));
        assertEquals("NEW", result.get("orderType"));
        assertEquals("2024-03-15", result.get("orderDate"));

        @SuppressWarnings("unchecked")
        Map<String, Object> buyer = (Map<String, Object>) result.get("buyer");
        assertEquals("Acme Corp", buyer.get("name"));
    }

    // === Empty/null document ===

    @Test
    void convert_emptyDocument_returnsEmptyMap() {
        EdiDocument doc = EdiDocument.builder()
                .sourceFormat("X12")
                .segments(List.of())
                .build();

        ConversionMapDefinition map = ConversionMapDefinition.builder()
                .mapId("test-empty")
                .fieldMappings(List.of(
                        ConversionMapDefinition.FieldMapping.builder()
                                .sourcePath("BEG.03").targetPath("poNumber")
                                .transform("COPY").confidence(1.0).build()))
                .build();

        Map<String, Object> result = converter.convert(doc, map);

        assertNotNull(result);
        assertFalse(result.containsKey("poNumber"),
                "Missing field with no default should not appear in output");
    }
}
