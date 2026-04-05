package com.filetransfer.edi.model;

import lombok.*;
import java.util.*;

/**
 * Universal EDI document model — the internal representation that all
 * formats parse INTO and all output formats render FROM.
 *
 * Any EDI format → EdiDocument → Any output format
 * X12, EDIFACT, TRADACOMS, SWIFT, HL7, NACHA, BAI2, ISO20022, FIX
 * → EdiDocument →
 * JSON, XML, CSV, YAML, Flat-width, TIF (TranzFer Internal Format)
 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class EdiDocument {
    /** Source format: X12, EDIFACT, TRADACOMS, SWIFT_MT, SWIFT_MX, HL7, NACHA, BAI2, ISO20022, FIX */
    private String sourceFormat;
    /** Specific type within format (e.g. "837", "ORDERS", "MT103", "ADT") */
    private String documentType;
    /** Human-readable type name */
    private String documentName;
    /** Format version */
    private String version;
    /** Sender identifier */
    private String senderId;
    /** Receiver identifier */
    private String receiverId;
    /** Document date */
    private String documentDate;
    /** Control/reference number */
    private String controlNumber;
    /** The segments/records that make up this document */
    private List<Segment> segments;
    /** Parsed business fields (key-value from the specific transaction type) */
    private Map<String, Object> businessData;
    /** Raw content */
    private String rawContent;

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class Segment {
        private String id;
        private List<String> elements;
        private Map<String, String> namedFields;
        private List<Segment> children;
    }
}
