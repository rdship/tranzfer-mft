package com.filetransfer.edi.parser;

import com.filetransfer.edi.model.EdiDocument;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component @Slf4j
public class FormatDetector {

    public String detect(String content) {
        if (content == null || content.isBlank()) return "UNKNOWN";
        String trimmed = content.trim();

        // X12: starts with ISA* or has ST* segments
        if (trimmed.startsWith("ISA*") || trimmed.contains("ISA*")) return "X12";

        // EDIFACT: starts with UNB+ or UNA or UNH+
        if (trimmed.startsWith("UNB+") || trimmed.startsWith("UNA") || trimmed.contains("UNH+")) return "EDIFACT";

        // TRADACOMS: starts with STX= 
        if (trimmed.startsWith("STX=") || trimmed.contains("STX=")) return "TRADACOMS";

        // SWIFT MT: starts with {1: or contains :20: and :32A:
        if (trimmed.startsWith("{1:") || (trimmed.contains(":20:") && trimmed.contains(":32A:"))) return "SWIFT_MT";

        // SWIFT MX / ISO 20022: XML with urn:iso:std or <Document>
        if (trimmed.contains("urn:iso:std:iso:20022") || trimmed.contains("<BkToCstmrStmt>") || trimmed.contains("pacs.008")) return "ISO20022";

        // HL7 v2: starts with MSH| 
        if (trimmed.startsWith("MSH|") || trimmed.contains("MSH|")) return "HL7";

        // NACHA/ACH: starts with 1 (file header) and has fixed-width 94-char records
        if (trimmed.length() >= 94 && (trimmed.charAt(0) == '1') && trimmed.substring(0, 1).matches("\\d")) {
            String firstLine = trimmed.split("\\n")[0];
            if (firstLine.length() == 94 && firstLine.startsWith("1")) return "NACHA";
        }

        // BAI2: starts with 01,
        if (trimmed.startsWith("01,") || trimmed.startsWith("02,")) return "BAI2";

        // FIX: starts with 8=FIX
        if (trimmed.startsWith("8=FIX") || trimmed.contains("8=FIX")) return "FIX";

        // XML (generic)
        if (trimmed.startsWith("<?xml") || trimmed.startsWith("<")) return "XML";

        // JSON
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) return "JSON";

        return "UNKNOWN";
    }
}
