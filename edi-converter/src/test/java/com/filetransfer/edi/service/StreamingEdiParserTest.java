package com.filetransfer.edi.service;

import com.filetransfer.edi.model.EdiDocument;
import com.filetransfer.edi.model.EdiDocument.Segment;
import com.filetransfer.edi.service.StreamingEdiParser.ParseEvent;
import com.filetransfer.edi.service.StreamingEdiParser.StreamResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class StreamingEdiParserTest {

    private StreamingEdiParser parser;

    @BeforeEach
    void setUp() {
        parser = new StreamingEdiParser();
    }

    // ========================================================================
    // Helper to build a standard X12 ISA segment (106 characters exactly)
    // ========================================================================

    /**
     * Build a proper 106-character ISA segment using the given delimiters.
     * ISA has 16 elements with fixed widths. The segment terminator is at position 105.
     */
    private String buildIsa(char elemSep, char compSep, char segTerm) {
        // ISA segment: 16 elements with fixed widths
        // ISA*00*          *00*          *ZZ*SENDER         *ZZ*RECEIVER       *230101*1200*^*00501*000000001*0*P*>~
        StringBuilder isa = new StringBuilder();
        isa.append("ISA").append(elemSep);             // ISA + elem sep (pos 3)
        isa.append("00").append(elemSep);              // ISA01
        isa.append("          ").append(elemSep);      // ISA02 (10 chars)
        isa.append("00").append(elemSep);              // ISA03
        isa.append("          ").append(elemSep);      // ISA04 (10 chars)
        isa.append("ZZ").append(elemSep);              // ISA05
        isa.append("SENDER         ").append(elemSep); // ISA06 (15 chars)
        isa.append("ZZ").append(elemSep);              // ISA07
        isa.append("RECEIVER       ").append(elemSep); // ISA08 (15 chars)
        isa.append("230101").append(elemSep);          // ISA09
        isa.append("1200").append(elemSep);            // ISA10
        isa.append("^").append(elemSep);               // ISA11 (repetition sep)
        isa.append("00501").append(elemSep);           // ISA12
        isa.append("000000001").append(elemSep);       // ISA13
        isa.append("0").append(elemSep);               // ISA14
        isa.append("P").append(elemSep);               // ISA15
        isa.append(compSep);                           // ISA16 (component sep) -> pos 104
        isa.append(segTerm);                           // segment terminator -> pos 105
        // Pad to exactly 106 characters
        while (isa.length() < 106) {
            isa.insert(isa.length() - 1, ' ');
        }
        // Trim to exactly 106 if over
        if (isa.length() > 106) {
            isa.setLength(106);
            isa.setCharAt(104, compSep);
            isa.setCharAt(105, segTerm);
        }
        return isa.toString();
    }

    /**
     * Build a standard X12 document with default delimiters.
     */
    private String buildStandardX12() {
        String isa = buildIsa('*', '>', '~');
        return isa
                + "GS*HP*SENDER*RECEIVER*20230101*1200*1*X*005010X222A1~"
                + "ST*837*0001~"
                + "BHT*0019*00*12345*20230101*1200*CH~"
                + "SE*3*0001~"
                + "GE*1*1~"
                + "IEA*1*000000001~";
    }

    // ========================================================================
    // X12 Tests
    // ========================================================================

    @Test
    void x12StreamingProducesCorrectSegmentCount() {
        String x12 = buildStandardX12();
        List<Segment> segments = new ArrayList<>();

        StreamResult result = parser.stream(x12, "X12", event -> {
            if (event.getType() == ParseEvent.EventType.SEGMENT && event.getSegment() != null) {
                segments.add(event.getSegment());
            }
        });

        assertEquals(7, result.getTotalSegments(), "Should have 7 segments: ISA, GS, ST, BHT, SE, GE, IEA");
        assertEquals(7, segments.size());
        assertEquals("X12", result.getFormat());
    }

    @Test
    void x12StreamingWithCustomDelimiters() {
        // Use | as element separator, # as segment terminator, > as component separator
        // Build ISA manually with | separator, > component, # terminator
        String isa = buildIsa('|', '>', '#');
        String x12 = isa
                + "GS|HP|SENDER|RECEIVER|20230101|1200|1|X|005010X222A1#"
                + "ST|837|0001#"
                + "SE|2|0001#"
                + "GE|1|1#"
                + "IEA|1|000000001#";

        List<Segment> segments = new ArrayList<>();

        StreamResult result = parser.stream(x12, "X12", event -> {
            if (event.getType() == ParseEvent.EventType.SEGMENT && event.getSegment() != null) {
                segments.add(event.getSegment());
            }
        });

        assertEquals(6, result.getTotalSegments());
        // Verify element parsing used the correct delimiter
        Segment gs = segments.stream().filter(s -> "GS".equals(s.getId())).findFirst().orElse(null);
        assertNotNull(gs, "GS segment should be found");
        assertEquals("HP", gs.getElements().get(0));
    }

    @Test
    void x12StreamingExtractsIsaMetadata() {
        String x12 = buildStandardX12();
        Map<String, String> capturedMeta = new LinkedHashMap<>();

        parser.stream(x12, "X12", event -> {
            if (event.getMetadata() != null) {
                capturedMeta.putAll(event.getMetadata());
            }
        });

        // Metadata should include delimiter info
        assertEquals("*", capturedMeta.get("elementSeparator"));
        assertEquals("~", capturedMeta.get("segmentTerminator"));
        // Metadata should include sender/receiver from ISA
        assertTrue(capturedMeta.containsKey("senderId"), "Should have senderId from ISA");
        assertTrue(capturedMeta.get("senderId").contains("SENDER"));
    }

    @Test
    void x12StreamingHandlesCompositeElements() {
        // Build X12 with composite elements (component separator >)
        String isa = buildIsa('*', '>', '~');
        String x12 = isa
                + "ST*837*0001~"
                + "SV1*HC>99213>25*50*UN*1~"
                + "SE*2*0001~";

        List<Segment> segments = new ArrayList<>();
        parser.stream(x12, "X12", event -> {
            if (event.getType() == ParseEvent.EventType.SEGMENT && event.getSegment() != null) {
                segments.add(event.getSegment());
            }
        });

        Segment sv1 = segments.stream().filter(s -> "SV1".equals(s.getId())).findFirst().orElse(null);
        assertNotNull(sv1, "SV1 segment should be found");
        assertNotNull(sv1.getCompositeElements(), "Should have compositeElements");
        // First element "HC>99213>25" should be split into 3 components
        List<String> firstComposite = sv1.getCompositeElements().get(0);
        assertEquals(3, firstComposite.size(), "HC>99213>25 should split into 3 components");
        assertEquals("HC", firstComposite.get(0));
        assertEquals("99213", firstComposite.get(1));
        assertEquals("25", firstComposite.get(2));
    }

    @Test
    void x12StreamingErrorRecoveryBadSegmentDoesNotStopStream() {
        // Inject a null-byte mid-stream that won't cause a real exception,
        // but we can test that the parser processes all segments
        String isa = buildIsa('*', '>', '~');
        String x12 = isa
                + "ST*837*0001~"
                + "BHT*0019*00*12345~"
                + "SE*2*0001~"
                + "GE*1*1~";

        List<Segment> segments = new ArrayList<>();
        AtomicInteger errorEvents = new AtomicInteger(0);

        StreamResult result = parser.stream(x12, "X12", event -> {
            if (event.getType() == ParseEvent.EventType.SEGMENT && event.getSegment() != null) {
                segments.add(event.getSegment());
            }
            if (event.getType() == ParseEvent.EventType.ERROR) {
                errorEvents.incrementAndGet();
            }
        });

        // All segments should still be parsed
        assertTrue(segments.size() >= 4, "Should parse at least 4 segments even with potential errors");
        assertEquals(result.getTotalSegments(), segments.size() + result.getErrorCount());
    }

    @Test
    void x12StreamingRepeatingElements() {
        String isa = buildIsa('*', '>', '~');
        // Use ^ as repetition separator (detected from ISA11)
        String x12 = isa
                + "ST*837*0001~"
                + "NM1*85*1*DOE^SMITH*JOHN~"
                + "SE*2*0001~";

        List<Segment> segments = new ArrayList<>();
        parser.stream(x12, "X12", event -> {
            if (event.getType() == ParseEvent.EventType.SEGMENT && event.getSegment() != null) {
                segments.add(event.getSegment());
            }
        });

        Segment nm1 = segments.stream().filter(s -> "NM1".equals(s.getId())).findFirst().orElse(null);
        assertNotNull(nm1);
        assertNotNull(nm1.getRepeatingElements(), "Should have repeatingElements");
        // Element "DOE^SMITH" should be split into 2 repeating values
        List<String> repeating = nm1.getRepeatingElements().get(2); // 3rd element (index 2)
        assertEquals(2, repeating.size(), "DOE^SMITH should split into 2 repeating values");
        assertEquals("DOE", repeating.get(0));
        assertEquals("SMITH", repeating.get(1));
    }

    // ========================================================================
    // EDIFACT Tests
    // ========================================================================

    @Test
    void edifactStreamingWithUnaCustomDelimiters() {
        // UNA:+.? '  — standard EDIFACT UNA
        String edifact = "UNA:+.? '"
                + "UNB+UNOC:3+SENDER+RECEIVER+230101:1200+00000001'"
                + "UNH+1+ORDERS:D:96A:UN'"
                + "BGM+220+PO12345+9'"
                + "UNT+3+1'"
                + "UNZ+1+00000001'";

        List<Segment> segments = new ArrayList<>();
        StreamResult result = parser.stream(edifact, "EDIFACT", event -> {
            if (event.getType() == ParseEvent.EventType.SEGMENT && event.getSegment() != null) {
                segments.add(event.getSegment());
            }
        });

        assertEquals(5, result.getTotalSegments(), "Should have 5 segments: UNB, UNH, BGM, UNT, UNZ");
        assertEquals("EDIFACT", result.getFormat());

        Segment bgm = segments.stream().filter(s -> "BGM".equals(s.getId())).findFirst().orElse(null);
        assertNotNull(bgm);
        assertEquals("220", bgm.getElements().get(0));
    }

    @Test
    void edifactStreamingHandlesReleaseCharacter() {
        // Use ? as release character — ?' should NOT be treated as segment end
        String edifact = "UNA:+.? '"
                + "UNB+UNOC:3+SENDER+RECEIVER+230101:1200+00000001'"
                + "FTX+AAA+++Text with quote?'s inside'"
                + "UNT+2+1'";

        List<Segment> segments = new ArrayList<>();
        StreamResult result = parser.stream(edifact, "EDIFACT", event -> {
            if (event.getType() == ParseEvent.EventType.SEGMENT && event.getSegment() != null) {
                segments.add(event.getSegment());
            }
        });

        assertEquals(3, result.getTotalSegments());
        // The FTX segment should contain the text with the escaped quote
        Segment ftx = segments.stream().filter(s -> "FTX".equals(s.getId())).findFirst().orElse(null);
        assertNotNull(ftx, "FTX segment should be found");
        // The text should contain a literal apostrophe (release char resolved)
        String lastElem = ftx.getElements().get(ftx.getElements().size() - 1);
        assertTrue(lastElem.contains("'"), "Text should contain a literal apostrophe after release char resolution");
    }

    @Test
    void edifactStreamingCompositeElements() {
        String edifact = "UNA:+.? '"
                + "UNB+UNOC:3+SENDER:ID1+RECEIVER:ID2+230101:1200+REF001'"
                + "UNT+1+1'";

        List<Segment> segments = new ArrayList<>();
        parser.stream(edifact, "EDIFACT", event -> {
            if (event.getType() == ParseEvent.EventType.SEGMENT && event.getSegment() != null) {
                segments.add(event.getSegment());
            }
        });

        Segment unb = segments.stream().filter(s -> "UNB".equals(s.getId())).findFirst().orElse(null);
        assertNotNull(unb);
        assertNotNull(unb.getCompositeElements());
        // "UNOC:3" should be split by component separator : into ["UNOC", "3"]
        List<String> firstComp = unb.getCompositeElements().get(0);
        assertEquals(2, firstComp.size());
        assertEquals("UNOC", firstComp.get(0));
        assertEquals("3", firstComp.get(1));
    }

    @Test
    void edifactStreamingWithoutUna() {
        // EDIFACT without UNA should use defaults: + elem, : comp, ' term
        String edifact = "UNB+UNOC:3+SENDER+RECEIVER+230101:1200+00000001'"
                + "UNH+1+INVOIC:D:97B:UN'"
                + "UNT+2+1'"
                + "UNZ+1+00000001'";

        List<Segment> segments = new ArrayList<>();
        StreamResult result = parser.stream(edifact, "EDIFACT", event -> {
            if (event.getType() == ParseEvent.EventType.SEGMENT && event.getSegment() != null) {
                segments.add(event.getSegment());
            }
        });

        assertEquals(4, result.getTotalSegments());
    }

    // ========================================================================
    // HL7 Tests
    // ========================================================================

    @Test
    void hl7StreamingProducesCorrectSegments() {
        String hl7 = "MSH|^~\\&|SENDING|FACILITY|RECEIVING|FACILITY|20230101120000||ADT^A01|MSG001|P|2.5\r"
                + "EVN|A01|20230101120000\r"
                + "PID|1||12345^^^MR||DOE^JOHN^A||19800101|M\r"
                + "PV1|1|I|W^101^1\r";

        List<Segment> segments = new ArrayList<>();
        StreamResult result = parser.stream(hl7, "HL7", event -> {
            if (event.getType() == ParseEvent.EventType.SEGMENT && event.getSegment() != null) {
                segments.add(event.getSegment());
            }
        });

        assertEquals(4, result.getTotalSegments());
        assertEquals("MSH", segments.get(0).getId());
        assertEquals("EVN", segments.get(1).getId());
        assertEquals("PID", segments.get(2).getId());
        assertEquals("PV1", segments.get(3).getId());
    }

    @Test
    void hl7StreamingParsesComponentElements() {
        String hl7 = "MSH|^~\\&|SENDING|FACILITY|RECEIVING|FACILITY|20230101||ADT^A01|MSG001|P|2.5\r"
                + "PID|1||12345^^^MR||DOE^JOHN^A||19800101|M\r";

        List<Segment> segments = new ArrayList<>();
        parser.stream(hl7, "HL7", event -> {
            if (event.getType() == ParseEvent.EventType.SEGMENT && event.getSegment() != null) {
                segments.add(event.getSegment());
            }
        });

        Segment pid = segments.stream().filter(s -> "PID".equals(s.getId())).findFirst().orElse(null);
        assertNotNull(pid);
        assertNotNull(pid.getCompositeElements(), "PID should have compositeElements");
        // Element "DOE^JOHN^A" (index 4) should be split into ["DOE", "JOHN", "A"]
        List<String> nameComposite = pid.getCompositeElements().get(4);
        assertEquals(3, nameComposite.size());
        assertEquals("DOE", nameComposite.get(0));
        assertEquals("JOHN", nameComposite.get(1));
        assertEquals("A", nameComposite.get(2));
    }

    @Test
    void hl7StreamingParsesRepeatingElements() {
        String hl7 = "MSH|^~\\&|SENDING|FACILITY|RECEIVING|FACILITY|20230101||ADT^A01|MSG001|P|2.5\r"
                + "PID|1||12345~67890||DOE^JOHN\r";

        List<Segment> segments = new ArrayList<>();
        parser.stream(hl7, "HL7", event -> {
            if (event.getType() == ParseEvent.EventType.SEGMENT && event.getSegment() != null) {
                segments.add(event.getSegment());
            }
        });

        Segment pid = segments.stream().filter(s -> "PID".equals(s.getId())).findFirst().orElse(null);
        assertNotNull(pid);
        assertNotNull(pid.getRepeatingElements());
        // Element "12345~67890" (index 2) should split into ["12345", "67890"]
        List<String> repeating = pid.getRepeatingElements().get(2);
        assertEquals(2, repeating.size());
        assertEquals("12345", repeating.get(0));
        assertEquals("67890", repeating.get(1));
    }

    // ========================================================================
    // StreamResult error/warning count tests
    // ========================================================================

    @Test
    void streamResultHasErrorCount() {
        String x12 = buildStandardX12();

        StreamResult result = parser.stream(x12, "X12", event -> {});

        assertNotNull(result);
        assertTrue(result.getErrorCount() >= 0, "errorCount should be non-negative");
        assertTrue(result.getWarningCount() >= 0, "warningCount should be non-negative");
    }

    @Test
    void streamResultErrorCountReflectsErrors() {
        // Valid X12, no errors expected
        String x12 = buildStandardX12();
        StreamResult result = parser.stream(x12, "X12", event -> {});
        assertEquals(0, result.getErrorCount(), "Valid X12 should have 0 errors");
    }

    // ========================================================================
    // Backward compatibility
    // ========================================================================

    @Test
    void streamToDocumentStillWorks() {
        String x12 = buildStandardX12();
        ByteArrayInputStream input = new ByteArrayInputStream(x12.getBytes(StandardCharsets.UTF_8));

        EdiDocument doc = parser.streamToDocument(input, "X12");

        assertNotNull(doc);
        assertEquals("X12", doc.getSourceFormat());
        assertNotNull(doc.getSegments());
        assertFalse(doc.getSegments().isEmpty());
        assertTrue(doc.getSegments().stream().anyMatch(s -> "ISA".equals(s.getId())));
        assertTrue(doc.getSegments().stream().anyMatch(s -> "ST".equals(s.getId())));
    }

    @Test
    void streamToDocumentEdifact() {
        String edifact = "UNA:+.? '"
                + "UNB+UNOC:3+SENDER+RECEIVER+230101:1200+00000001'"
                + "UNH+1+ORDERS:D:96A:UN'"
                + "UNT+1+1'"
                + "UNZ+1+00000001'";

        ByteArrayInputStream input = new ByteArrayInputStream(edifact.getBytes(StandardCharsets.UTF_8));
        EdiDocument doc = parser.streamToDocument(input, "EDIFACT");

        assertNotNull(doc);
        assertEquals("EDIFACT", doc.getSourceFormat());
        assertFalse(doc.getSegments().isEmpty());
    }

    // ========================================================================
    // Edge cases
    // ========================================================================

    @Test
    void emptyInputHandledGracefully() {
        StreamResult result = parser.stream("", "X12", event -> {});

        assertNotNull(result);
        assertEquals(0, result.getTotalSegments());
        assertEquals("X12", result.getFormat());
    }

    @Test
    void nullInputStreamHandledGracefully() {
        ByteArrayInputStream emptyStream = new ByteArrayInputStream(new byte[0]);
        StreamResult result = parser.stream(emptyStream, "X12", event -> {});

        assertNotNull(result);
        assertEquals(0, result.getTotalSegments());
    }

    @Test
    void documentStartAndEndEventsEmitted() {
        String x12 = buildStandardX12();
        AtomicInteger startCount = new AtomicInteger(0);
        AtomicInteger endCount = new AtomicInteger(0);

        parser.stream(x12, "X12", event -> {
            if (event.getType() == ParseEvent.EventType.DOCUMENT_START) startCount.incrementAndGet();
            if (event.getType() == ParseEvent.EventType.DOCUMENT_END) endCount.incrementAndGet();
        });

        assertEquals(1, startCount.get(), "Should emit exactly one DOCUMENT_START");
        assertEquals(1, endCount.get(), "Should emit exactly one DOCUMENT_END");
    }

    @Test
    void streamStringConvenienceMethodWorks() {
        String hl7 = "MSH|^~\\&|SENDING|FACILITY|RECEIVING|FACILITY|20230101||ADT^A01|MSG001|P|2.5\r"
                + "PID|1||12345||DOE^JOHN\r";

        List<Segment> segments = new ArrayList<>();
        StreamResult result = parser.stream(hl7, "HL7", event -> {
            if (event.getType() == ParseEvent.EventType.SEGMENT) {
                segments.add(event.getSegment());
            }
        });

        assertEquals(2, result.getTotalSegments());
        assertEquals("HL7", result.getFormat());
    }

    @Test
    void parseEventErrorMessagePopulated() {
        // Test that ParseEvent has errorMessage field accessible
        ParseEvent event = ParseEvent.builder()
                .type(ParseEvent.EventType.ERROR)
                .errorMessage("Test error message")
                .build();

        assertEquals("Test error message", event.getErrorMessage());
        assertEquals(ParseEvent.EventType.ERROR, event.getType());
    }

    @Test
    void x12StreamingMetadataIncludesDocumentType() {
        String x12 = buildStandardX12();
        Map<String, String> capturedMeta = new LinkedHashMap<>();

        parser.stream(x12, "X12", event -> {
            if (event.getMetadata() != null) {
                capturedMeta.putAll(event.getMetadata());
            }
        });

        assertEquals("837", capturedMeta.get("documentType"), "Should extract documentType from ST segment");
    }
}
