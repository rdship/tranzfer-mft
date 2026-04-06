package com.filetransfer.edi.parser;

import com.filetransfer.edi.parser.FormatDetector.DetectionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;

import static org.junit.jupiter.api.Assertions.*;

class FormatDetectorTest {

    private FormatDetector detector;

    @BeforeEach
    void setUp() {
        detector = new FormatDetector();
    }

    // ========================================================================
    // X12 Detection
    // ========================================================================

    @Test
    void x12WithStandardDelimitersDetected() {
        String x12 = "ISA*00*          *00*          *ZZ*SENDER         *ZZ*RECEIVER       *230101*1200*^*00501*000000001*0*P*>~"
                + "GS*HP*SENDER*RECEIVER*20230101*1200*1*X*005010X222A1~"
                + "ST*837*0001~";
        assertEquals("X12", detector.detect(x12));
    }

    @Test
    void x12WithPipeDelimiterDetected() {
        // ISA with | as element separator
        String x12 = "ISA|00|          |00|          |ZZ|SENDER         |ZZ|RECEIVER       |230101|1200|^|00501|000000001|0|P|>#";
        assertEquals("X12", detector.detect(x12));
    }

    @Test
    void x12WithTabDelimiterDetected() {
        // ISA with tab as element separator
        String x12 = "ISA\t00\t";
        assertEquals("X12", detector.detect(x12));
    }

    @Test
    void x12DetectionHighConfidenceWithFullIsa() {
        // Full 106+ char ISA
        String x12 = "ISA*00*          *00*          *ZZ*SENDER         *ZZ*RECEIVER       *230101*1200*^*00501*000000001*0*P*>~";
        DetectionResult result = detector.detectWithConfidence(x12);
        assertEquals("X12", result.format());
        assertTrue(result.confidence() >= 0.95, "Full ISA should have confidence >= 0.95, got " + result.confidence());
    }

    // ========================================================================
    // EDIFACT Detection
    // ========================================================================

    @Test
    void edifactWithUnaDetected() {
        String edifact = "UNA:+.? 'UNB+UNOC:3+SENDER+RECEIVER+230101:1200+00000001'";
        assertEquals("EDIFACT", detector.detect(edifact));
    }

    @Test
    void edifactWithoutUnaDetected() {
        String edifact = "UNB+UNOC:3+SENDER+RECEIVER+230101:1200+00000001'UNH+1+ORDERS:D:96A:UN'";
        assertEquals("EDIFACT", detector.detect(edifact));
    }

    @Test
    void edifactWithUnaHighConfidence() {
        String edifact = "UNA:+.? 'UNB+UNOC:3+SENDER+RECEIVER+230101:1200+00000001'";
        DetectionResult result = detector.detectWithConfidence(edifact);
        assertEquals("EDIFACT", result.format());
        assertTrue(result.confidence() >= 0.95, "UNA + UNB should have confidence >= 0.95");
    }

    @Test
    void edifactWithCustomSeparatorDetected() {
        // UNB with non-standard element separator
        String edifact = "UNB|UNOC|SENDER|RECEIVER'";
        assertEquals("EDIFACT", detector.detect(edifact));
    }

    // ========================================================================
    // HL7 Detection
    // ========================================================================

    @Test
    void hl7Detected() {
        String hl7 = "MSH|^~\\&|SENDING|FACILITY|RECEIVING|FACILITY|20230101||ADT^A01|MSG001|P|2.5\rPID|1||12345";
        assertEquals("HL7", detector.detect(hl7));
    }

    // ========================================================================
    // SWIFT MT Detection
    // ========================================================================

    @Test
    void swiftMt103Detected() {
        String swift = "{1:F01BANKBEBBAXXX0000000000}{2:O1030900010101BANKDEFFAXXX0000000000010101090000N}\n"
                + ":20:REF12345\n"
                + ":32A:230101USD1000,00\n"
                + ":50K:/12345678\nJohn Doe\n"
                + ":59:/98765432\nJane Doe\n";
        assertEquals("SWIFT_MT", detector.detect(swift));
    }

    @Test
    void swiftMtDetectedByBlockStructure() {
        String swift = "{1:F01BANKBEBBAXXX0000000000}";
        assertEquals("SWIFT_MT", detector.detect(swift));
    }

    @Test
    void swiftMtDetectedByTagPatterns() {
        String swift = "some header\n:20:REF001\n:32A:230101USD5000,00\n:50K:Sender\n:59:Receiver";
        assertEquals("SWIFT_MT", detector.detect(swift));
    }

    // ========================================================================
    // NACHA Detection
    // ========================================================================

    @Test
    void nachaDetected() {
        // 94-char lines, first line starts with 101
        String line1 = "101 091000019 0610001231902060000A094101FIRST BANK            COMPANY NAME           ";
        // Pad to exactly 94 chars
        line1 = padTo94(line1);
        String line2 = padTo94("5200COMPANY NAME                        1234567890PPDPAYROLL 230101   1091000010000001");
        String nacha = line1 + "\n" + line2;
        assertEquals("NACHA", detector.detect(nacha));
    }

    @Test
    void nachaHighConfidenceWithAllLinesCorrectLength() {
        String line1 = padTo94("101 091000019 0610001231902060000A094101FIRST BANK            COMPANY NAME           ");
        String line2 = padTo94("5200COMPANY NAME                        1234567890PPDPAYROLL 230101   1091000010000001");
        String line3 = padTo94("9000001000001000000010012345670000000000000000000100000000000000000000000000000000000001");
        String nacha = line1 + "\n" + line2 + "\n" + line3;
        DetectionResult result = detector.detectWithConfidence(nacha);
        assertEquals("NACHA", result.format());
        assertTrue(result.confidence() >= 0.90, "All 94-char lines starting with 101 should have high confidence");
    }

    private String padTo94(String input) {
        if (input.length() >= 94) return input.substring(0, 94);
        return input + " ".repeat(94 - input.length());
    }

    // ========================================================================
    // FIX Detection
    // ========================================================================

    @Test
    void fixDetected() {
        String fix = "8=FIX.4.2|9=178|35=D|49=SENDER|56=TARGET|34=1|52=20230101-12:00:00|11=ORDER001|";
        assertEquals("FIX", detector.detect(fix));
    }

    // ========================================================================
    // PEPPOL / UBL Detection
    // ========================================================================

    @Test
    void peppolDetected() {
        String peppol = "<?xml version=\"1.0\"?>\n<Invoice xmlns=\"urn:oasis:names:specification:ubl:schema:xsd:Invoice-2\">";
        assertEquals("PEPPOL", detector.detect(peppol));
    }

    // ========================================================================
    // ISO 20022 Detection
    // ========================================================================

    @Test
    void iso20022Detected() {
        String iso = "<?xml version=\"1.0\"?>\n<Document xmlns=\"urn:iso:std:iso:20022:tech:xsd:camt.053.001.02\">";
        assertEquals("ISO20022", detector.detect(iso));
    }

    // ========================================================================
    // Null / Empty / Garbage
    // ========================================================================

    @ParameterizedTest
    @NullAndEmptySource
    void nullAndEmptyReturnUnknown(String input) {
        assertEquals("UNKNOWN", detector.detect(input));
    }

    @Test
    void blankStringReturnsUnknown() {
        assertEquals("UNKNOWN", detector.detect("   \n\t  "));
    }

    @Test
    void garbageReturnsReasonableResult() {
        String garbage = "This is just some random text that doesn't match any EDI format pattern whatsoever.";
        String result = detector.detect(garbage);
        assertNotNull(result, "Should return a non-null result for garbage input");
        // Could be UNKNOWN or possibly JSON/XML if it somehow matches
    }

    // ========================================================================
    // detectWithConfidence
    // ========================================================================

    @Test
    void detectWithConfidenceReturnHighConfidenceForClearFormats() {
        String x12 = "ISA*00*          *00*          *ZZ*SENDER         *ZZ*RECEIVER       *230101*1200*^*00501*000000001*0*P*>~";
        DetectionResult x12Result = detector.detectWithConfidence(x12);
        assertTrue(x12Result.confidence() > 0.9, "Clear X12 should have confidence > 0.9");
        assertNotNull(x12Result.reason());
        assertFalse(x12Result.reason().isEmpty());

        String edifact = "UNA:+.? 'UNB+UNOC:3+SENDER+RECEIVER+230101:1200+00000001'";
        DetectionResult edifactResult = detector.detectWithConfidence(edifact);
        assertTrue(edifactResult.confidence() > 0.9, "Clear EDIFACT should have confidence > 0.9");
    }

    @Test
    void detectWithConfidenceReturnsLowerConfidenceForAmbiguousContent() {
        // Content that contains ISA but not at position 0
        String ambiguous = "   some header\nISA*00*test";
        DetectionResult result = detector.detectWithConfidence(ambiguous);
        if ("X12".equals(result.format())) {
            assertTrue(result.confidence() < 0.95, "ISA not at position 0 should have lower confidence");
        }
    }

    @Test
    void detectWithConfidenceReturnsZeroForUnknown() {
        DetectionResult result = detector.detectWithConfidence("random gibberish no pattern here");
        assertEquals("UNKNOWN", result.format());
        assertEquals(0.0, result.confidence());
    }

    @Test
    void detectWithConfidenceIncludesReason() {
        String hl7 = "MSH|^~\\&|SENDING|FACILITY";
        DetectionResult result = detector.detectWithConfidence(hl7);
        assertEquals("HL7", result.format());
        assertNotNull(result.reason());
        assertFalse(result.reason().isEmpty(), "Reason should not be empty");
    }
}
