package com.filetransfer.shared.matching;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Lightweight EDI standard and type detection from file headers.
 * Reads at most 128 bytes. Pure CPU, no network. Target: < 0.1ms.
 */
public final class EdiDetector {

    private EdiDetector() {}

    public record EdiInfo(String standard, String typeCode) {}

    /**
     * Detect EDI info from a file. Returns null if not a recognized EDI format.
     */
    public static EdiInfo detect(Path file) throws IOException {
        byte[] header = new byte[512];
        int read;
        try (InputStream in = Files.newInputStream(file)) {
            read = in.read(header);
        }
        if (read < 10) return null;
        return detect(new String(header, 0, read));
    }

    /**
     * Detect EDI info from a string (first ~128 chars of file content).
     */
    public static EdiInfo detect(String header) {
        if (header == null || header.length() < 3) return null;
        String trimmed = header.stripLeading();

        // X12: starts with "ISA"
        if (trimmed.startsWith("ISA") && trimmed.length() > 3) {
            char elementSep = trimmed.charAt(3);
            // Find ST segment for transaction type: ISA*...*~GS*...*~ST*850*...
            String stPrefix = "ST" + elementSep;
            int stPos = trimmed.indexOf(stPrefix);
            if (stPos >= 0) {
                int typeStart = stPos + 3;
                int typeEnd = trimmed.indexOf(elementSep, typeStart);
                if (typeEnd < 0) typeEnd = trimmed.indexOf('~', typeStart);
                if (typeEnd > typeStart) {
                    return new EdiInfo("X12", trimmed.substring(typeStart, typeEnd).trim());
                }
            }
            return new EdiInfo("X12", null);
        }

        // EDIFACT: starts with "UNA" or "UNB"
        if (trimmed.startsWith("UNA") || trimmed.startsWith("UNB")) {
            // Determine separator: UNA defines it at position 3, default is '+'
            char sep = trimmed.startsWith("UNA") && trimmed.length() > 4
                    ? trimmed.charAt(4) : '+';
            // Find UNH segment for message type: UNH+1+INVOIC:D:96A:UN'
            int unhPos = trimmed.indexOf("UNH");
            if (unhPos >= 0) {
                // Skip UNH + reference number, get to message identifier
                int firstSep = trimmed.indexOf(sep, unhPos + 3);
                if (firstSep >= 0) {
                    int secondSep = trimmed.indexOf(sep, firstSep + 1);
                    if (secondSep >= 0) {
                        int typeEnd = trimmed.indexOf(':', secondSep + 1);
                        if (typeEnd < 0) typeEnd = trimmed.indexOf('\'', secondSep + 1);
                        if (typeEnd > secondSep + 1) {
                            return new EdiInfo("EDIFACT",
                                    trimmed.substring(secondSep + 1, typeEnd).trim());
                        }
                    }
                }
            }
            return new EdiInfo("EDIFACT", null);
        }

        return null;
    }
}
