package com.filetransfer.shared.matching;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class EdiDetectorTest {

    @TempDir
    Path tempDir;

    @Test
    void detect_x12_isa_header_returns850() throws IOException {
        String content = "ISA*00*          *00*          *ZZ*SENDER         *ZZ*RECEIVER       *230101*1200*U*00401*000000001*0*P*>~GS*PO*SENDER*RECEIVER*20230101*1200*1*X*004010~ST*850*0001~";
        Path file = tempDir.resolve("test.edi");
        Files.writeString(file, content);

        EdiDetector.EdiInfo info = EdiDetector.detect(file);
        assertNotNull(info);
        assertEquals("X12", info.standard());
        assertEquals("850", info.typeCode());
    }

    @Test
    void detect_x12_returns997() {
        String content = "ISA*00*          *00*          *ZZ*SENDER         *ZZ*RECEIVER       *230101*1200*U*00401*000000001*0*P*>~GS*FA*SENDER*RECEIVER*20230101*1200*1*X*004010~ST*997*0001~";
        EdiDetector.EdiInfo info = EdiDetector.detect(content);
        assertNotNull(info);
        assertEquals("X12", info.standard());
        assertEquals("997", info.typeCode());
    }

    @Test
    void detect_x12_noStSegment() {
        String content = "ISA*00*          *00*          *ZZ*SENDER         *ZZ*RECEIVER       *230101*1200*U*00401*000000001*0*P*>~";
        EdiDetector.EdiInfo info = EdiDetector.detect(content);
        assertNotNull(info);
        assertEquals("X12", info.standard());
        assertNull(info.typeCode());
    }

    @Test
    void detect_edifact_una_header_returnsINVOIC() {
        String content = "UNA:+.? 'UNB+UNOC:3+SENDER+RECEIVER+230101:1200+REF001++INVOIC'UNH+1+INVOIC:D:96A:UN'";
        EdiDetector.EdiInfo info = EdiDetector.detect(content);
        assertNotNull(info);
        assertEquals("EDIFACT", info.standard());
        assertEquals("INVOIC", info.typeCode());
    }

    @Test
    void detect_edifact_unb_header() {
        String content = "UNB+UNOC:3+SENDER+RECEIVER+230101:1200+REF001'UNH+1+ORDERS:D:96A:UN'";
        EdiDetector.EdiInfo info = EdiDetector.detect(content);
        assertNotNull(info);
        assertEquals("EDIFACT", info.standard());
        assertEquals("ORDERS", info.typeCode());
    }

    @Test
    void detect_nonEdi_returnsNull() {
        String content = "Name,Amount,Date\nJohn,100.50,2023-01-01\n";
        assertNull(EdiDetector.detect(content));
    }

    @Test
    void detect_emptyString_returnsNull() {
        assertNull(EdiDetector.detect(""));
    }

    @Test
    void detect_nullString_returnsNull() {
        assertNull(EdiDetector.detect((String) null));
    }

    @Test
    void detect_shortString_returnsNull() {
        assertNull(EdiDetector.detect("AB"));
    }
}
