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
    /** Detected delimiter information for the parsed document */
    private DelimiterInfo delimiterInfo;
    /** Errors encountered during parsing (non-fatal) */
    private List<String> parseErrors;
    /** Warnings encountered during parsing */
    private List<String> parseWarnings;
    /** Detected loop structures in the document */
    private List<LoopStructure> loops;

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class Segment {
        private String id;
        /** Element values as simple strings — backward compatible */
        private List<String> elements;
        private Map<String, String> namedFields;
        private List<Segment> children;
        /** Sub-component breakdown of each element. Position i = sub-components of elements[i].
         *  If element is not composite, it's a single-item list. */
        private List<List<String>> compositeElements;
        /** For elements with repetition separator. Position i = repetitions of elements[i].
         *  If element does not repeat, it's a single-item list. */
        private List<List<String>> repeatingElements;
        /** Loop identifier this segment belongs to */
        private String loopId;
        /** Nesting level of the loop this segment belongs to */
        private int loopLevel;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class DelimiterInfo {
        private char elementSeparator;
        private char componentSeparator;
        private char segmentTerminator;
        private char repetitionSeparator;
        private char releaseCharacter;
        private char decimalNotation;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class LoopStructure {
        private String loopId;
        private String triggerSegmentId;
        private int level;
        private int startIndex;
        private int endIndex;
        private List<LoopStructure> children;
    }
}
