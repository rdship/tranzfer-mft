package com.filetransfer.edi.parser;

import com.filetransfer.edi.model.EdiDocument;
import com.filetransfer.edi.model.EdiDocument.DelimiterInfo;
import com.filetransfer.edi.model.EdiDocument.LoopStructure;
import com.filetransfer.edi.model.EdiDocument.Segment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Universal EDI parser — parses ANY supported format into the EdiDocument model.
 * Each format has its own parsing logic, but all produce the same output.
 *
 * Production-grade: ISA-driven delimiter detection, UNA-aware EDIFACT parsing,
 * composite/repeating element support, release character handling, error recovery,
 * and loop structure detection.
 */
@Service @RequiredArgsConstructor @Slf4j
public class UniversalEdiParser {

    private final FormatDetector detector;
    private final X12LoopDefinitions loopDefinitions;

    /**
     * Constructor for backward compatibility — creates parser without loop definitions.
     */
    public UniversalEdiParser(FormatDetector detector) {
        this.detector = detector;
        this.loopDefinitions = new X12LoopDefinitions();
    }

    private static final Map<String, String> X12_TYPES = Map.ofEntries(
            Map.entry("837", "Health Care Claim"), Map.entry("835", "Health Care Payment/Remittance"),
            Map.entry("850", "Purchase Order"), Map.entry("856", "Ship Notice/Manifest"),
            Map.entry("810", "Invoice"), Map.entry("820", "Payment Order/Remittance"),
            Map.entry("270", "Eligibility Inquiry"), Map.entry("271", "Eligibility Response"),
            Map.entry("997", "Functional Acknowledgment"), Map.entry("834", "Benefit Enrollment"),
            Map.entry("277", "Claim Status Response"), Map.entry("278", "Health Care Review"),
            Map.entry("214", "Transportation Carrier Shipment Status"), Map.entry("204", "Motor Carrier Load Tender")
    );

    private static final Map<String, String> EDIFACT_TYPES = Map.of(
            "ORDERS", "Purchase Order", "INVOIC", "Invoice", "DESADV", "Despatch Advice",
            "APERAK", "Application Error/Ack", "CONTRL", "Syntax Control", "IFTMIN", "Instruction",
            "CUSCAR", "Customs Cargo Report", "BAPLIE", "Bayplan", "IFTMBF", "Booking"
    );

    public EdiDocument parse(String content) {
        String format = detector.detect(content);
        log.info("Parsing EDI format: {}", format);

        return switch (format) {
            case "X12" -> parseX12(content);
            case "EDIFACT" -> parseEdifact(content);
            case "TRADACOMS" -> parseTradacoms(content);
            case "SWIFT_MT" -> parseSwiftMt(content);
            case "HL7" -> parseHl7(content);
            case "NACHA" -> parseNacha(content);
            case "BAI2" -> parseBai2(content);
            case "ISO20022" -> parseIso20022(content);
            case "FIX" -> parseFix(content);
            case "PEPPOL" -> parsePeppol(content);
            default -> EdiDocument.builder().sourceFormat("UNKNOWN").rawContent(content).segments(List.of())
                    .parseErrors(List.of()).parseWarnings(List.of()).build();
        };
    }

    // ========================================================================
    // X12 Parser — ISA-driven delimiter detection
    // ========================================================================

    private EdiDocument parseX12(String content) {
        DelimiterInfo delimiters = detectX12Delimiters(content);
        char elemSep = delimiters.getElementSeparator();
        char segTerm = delimiters.getSegmentTerminator();
        char compSep = delimiters.getComponentSeparator();
        char repSep = delimiters.getRepetitionSeparator();

        List<Segment> segments = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        String txnType = null, senderId = null, receiverId = null, controlNum = null, date = null;

        String elemSepQ = Pattern.quote(String.valueOf(elemSep));
        String[] rawSegments = content.split(Pattern.quote(String.valueOf(segTerm)));
        int segCount = 0;

        for (String raw : rawSegments) {
            String seg = raw.trim();
            if (seg.isEmpty()) continue;
            segCount++;

            try {
                String[] elems = seg.split(elemSepQ, -1);
                String segId = elems[0];

                List<String> elements = new ArrayList<>();
                for (int i = 1; i < elems.length; i++) {
                    elements.add(elems[i]);
                }

                // Composite element parsing
                List<List<String>> composites = new ArrayList<>();
                for (String elem : elements) {
                    if (compSep != '\0' && elem.indexOf(compSep) >= 0) {
                        composites.add(Arrays.asList(elem.split(Pattern.quote(String.valueOf(compSep)), -1)));
                    } else {
                        composites.add(List.of(elem));
                    }
                }

                // Repeating element parsing
                List<List<String>> repeating = new ArrayList<>();
                for (String elem : elements) {
                    if (repSep != '\0' && elem.indexOf(repSep) >= 0) {
                        repeating.add(Arrays.asList(elem.split(Pattern.quote(String.valueOf(repSep)), -1)));
                    } else {
                        repeating.add(List.of(elem));
                    }
                }

                Segment segment = Segment.builder()
                        .id(segId)
                        .elements(elements)
                        .compositeElements(composites)
                        .repeatingElements(repeating)
                        .build();
                segments.add(segment);

                // Extract envelope information
                if ("ISA".equals(segId) && elems.length > 8) {
                    senderId = elems[6].trim();
                    receiverId = elems[8].trim();
                    if (elems.length > 9) date = elems[9].trim();
                    if (elems.length > 13) controlNum = elems[13].trim();
                }
                if ("ST".equals(segId) && elems.length > 1) {
                    txnType = elems[1].trim();
                }
            } catch (Exception e) {
                errors.add("Segment " + segCount + ": " + e.getMessage());
                // Add partial segment with what we could parse
                try {
                    String partialId = seg.contains(String.valueOf(elemSep))
                            ? seg.substring(0, seg.indexOf(elemSep))
                            : seg;
                    segments.add(Segment.builder().id(partialId).elements(List.of(seg)).build());
                } catch (Exception inner) {
                    segments.add(Segment.builder().id("ERROR").elements(List.of(seg)).build());
                }
            }
        }

        // Loop detection
        if (txnType != null) {
            assignLoopIds(txnType, segments);
        }
        List<LoopStructure> loops = txnType != null ? buildLoopStructures(txnType, segments) : List.of();

        return EdiDocument.builder().sourceFormat("X12").documentType(txnType)
                .documentName(X12_TYPES.getOrDefault(txnType, "X12 Transaction " + txnType))
                .senderId(senderId).receiverId(receiverId).documentDate(date).controlNumber(controlNum)
                .version("005010").segments(segments).rawContent(content)
                .businessData(extractX12BusinessData(txnType, segments))
                .delimiterInfo(delimiters)
                .parseErrors(errors)
                .parseWarnings(warnings)
                .loops(loops)
                .build();
    }

    /**
     * Detect X12 delimiters from the ISA segment.
     * ISA is ALWAYS exactly 106 characters:
     *   Position 3 (char after "ISA") = element separator
     *   Position 104 = component separator
     *   Position 105 = segment terminator
     *   ISA11 = repetition separator
     */
    private DelimiterInfo detectX12Delimiters(String content) {
        if (content == null || content.length() < 106) {
            return defaultX12Delimiters();
        }
        // Find ISA position
        int isaPos = content.indexOf("ISA");
        if (isaPos < 0 || (isaPos + 106) > content.length()) {
            return defaultX12Delimiters();
        }

        String isaBlock = content.substring(isaPos, isaPos + 106);
        char elemSep = isaBlock.charAt(3);
        char segTerm = isaBlock.charAt(105);
        char compSep = isaBlock.charAt(104);

        // ISA11 is the repetition separator — extract from ISA elements
        String[] isaElems = isaBlock.split(Pattern.quote(String.valueOf(elemSep)), -1);
        char repSep = '^'; // default
        if (isaElems.length > 11 && !isaElems[11].isEmpty()) {
            repSep = isaElems[11].charAt(0);
        }

        return DelimiterInfo.builder()
                .elementSeparator(elemSep)
                .componentSeparator(compSep)
                .segmentTerminator(segTerm)
                .repetitionSeparator(repSep)
                .build();
    }

    private DelimiterInfo defaultX12Delimiters() {
        return DelimiterInfo.builder()
                .elementSeparator('*')
                .componentSeparator('>')
                .segmentTerminator('~')
                .repetitionSeparator('^')
                .build();
    }

    /**
     * Assign loop IDs to segments based on X12 loop definitions.
     */
    private void assignLoopIds(String txnType, List<Segment> segments) {
        List<X12LoopDefinitions.LoopDef> defs = loopDefinitions.getLoopDefs(txnType);
        if (defs.isEmpty()) return;

        // Build a map of trigger segment -> possible loop defs
        Map<String, List<X12LoopDefinitions.LoopDef>> triggerMap = new LinkedHashMap<>();
        for (X12LoopDefinitions.LoopDef def : defs) {
            triggerMap.computeIfAbsent(def.getTriggerSegment(), k -> new ArrayList<>()).add(def);
        }

        // Track which loop def index to use next for each trigger segment (round-robin for repeated triggers)
        Map<String, Integer> triggerIndexes = new HashMap<>();

        String currentLoopId = null;
        int currentLevel = 0;

        for (Segment seg : segments) {
            List<X12LoopDefinitions.LoopDef> possibleLoops = triggerMap.get(seg.getId());
            if (possibleLoops != null && !possibleLoops.isEmpty()) {
                int idx = triggerIndexes.getOrDefault(seg.getId(), 0);
                if (idx >= possibleLoops.size()) {
                    idx = 0; // wrap around
                }
                X12LoopDefinitions.LoopDef matchedDef = possibleLoops.get(idx);
                triggerIndexes.put(seg.getId(), idx + 1);

                seg.setLoopId(matchedDef.getLoopId());
                seg.setLoopLevel(matchedDef.getLevel());
                currentLoopId = matchedDef.getLoopId();
                currentLevel = matchedDef.getLevel();
            } else if (currentLoopId != null) {
                // Segments between loop triggers inherit the current loop
                seg.setLoopId(currentLoopId);
                seg.setLoopLevel(currentLevel);
            }
        }
    }

    /**
     * Build a LoopStructure tree from the assigned loop IDs.
     */
    private List<LoopStructure> buildLoopStructures(String txnType, List<Segment> segments) {
        List<LoopStructure> topLevel = new ArrayList<>();
        Map<String, LoopStructure> activeLoops = new LinkedHashMap<>();

        for (int i = 0; i < segments.size(); i++) {
            Segment seg = segments.get(i);
            if (seg.getLoopId() == null) continue;

            // Check if this segment is a loop trigger (first segment in its loop)
            String loopId = seg.getLoopId();
            if (!activeLoops.containsKey(loopId)) {
                // Check if this is the trigger for a known loop def
                List<X12LoopDefinitions.LoopDef> defs = loopDefinitions.getLoopDefs(txnType);
                X12LoopDefinitions.LoopDef matchedDef = null;
                for (X12LoopDefinitions.LoopDef def : defs) {
                    if (def.getLoopId().equals(loopId) && def.getTriggerSegment().equals(seg.getId())) {
                        matchedDef = def;
                        break;
                    }
                }
                if (matchedDef != null) {
                    LoopStructure loop = LoopStructure.builder()
                            .loopId(loopId)
                            .triggerSegmentId(seg.getId())
                            .level(matchedDef.getLevel())
                            .startIndex(i)
                            .endIndex(i)
                            .children(new ArrayList<>())
                            .build();

                    // Find parent loop
                    if (matchedDef.getParentLoopId() != null && activeLoops.containsKey(matchedDef.getParentLoopId())) {
                        activeLoops.get(matchedDef.getParentLoopId()).getChildren().add(loop);
                    } else {
                        topLevel.add(loop);
                    }
                    activeLoops.put(loopId, loop);
                }
            } else {
                // Update end index of existing loop
                activeLoops.get(loopId).setEndIndex(i);
            }
        }

        return topLevel;
    }

    private Map<String, Object> extractX12BusinessData(String type, List<Segment> segments) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("transactionType", type);
        data.put("segmentCount", segments.size());
        // Extract key fields based on type
        for (Segment s : segments) {
            switch (s.getId()) {
                case "BHT" -> { if (s.getElements().size() > 3) data.put("transactionId", s.getElements().get(2)); }
                case "NM1" -> { if (s.getElements().size() > 2) data.put("entityName_" + s.getElements().get(0), s.getElements().get(2)); }
                case "CLM" -> { if (s.getElements().size() > 1) data.put("claimAmount", s.getElements().get(1)); }
                case "BIG" -> { if (s.getElements().size() > 1) data.put("invoiceDate", s.getElements().get(0)); }
            }
        }
        return data;
    }

    // ========================================================================
    // EDIFACT Parser — UNA-aware delimiter detection, release character handling
    // ========================================================================

    private EdiDocument parseEdifact(String content) {
        DelimiterInfo delimiters = detectEdifactDelimiters(content);
        char elemSep = delimiters.getElementSeparator();
        char segTerm = delimiters.getSegmentTerminator();
        char compSep = delimiters.getComponentSeparator();
        char releaseChar = delimiters.getReleaseCharacter();

        // Strip UNA segment from content before parsing segments
        String parseContent = content;
        if (content.trim().startsWith("UNA")) {
            int unaEnd = content.indexOf("UNA") + 9; // UNA + 6 chars
            if (unaEnd <= content.length()) {
                parseContent = content.substring(unaEnd);
            }
        }

        List<String> rawSegments = splitEdifactSegments(parseContent, segTerm, releaseChar);
        List<Segment> segments = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        String txnType = null, senderId = null, receiverId = null, controlNum = null;
        int segCount = 0;

        String elemSepQ = Pattern.quote(String.valueOf(elemSep));

        for (String rawSeg : rawSegments) {
            String seg = rawSeg.trim();
            if (seg.isEmpty()) continue;
            segCount++;

            try {
                // Split by element separator, respecting release character
                List<String> partsList = splitRespectingRelease(seg, elemSep, releaseChar);
                String segId = partsList.get(0);

                List<String> elements = new ArrayList<>();
                for (int i = 1; i < partsList.size(); i++) {
                    elements.add(partsList.get(i));
                }

                // Composite element parsing
                List<List<String>> composites = new ArrayList<>();
                for (String elem : elements) {
                    if (compSep != '\0' && elem.indexOf(compSep) >= 0) {
                        composites.add(splitRespectingRelease(elem, compSep, releaseChar));
                    } else {
                        composites.add(List.of(elem));
                    }
                }

                Segment segment = Segment.builder()
                        .id(segId)
                        .elements(elements)
                        .compositeElements(composites)
                        .build();
                segments.add(segment);

                if ("UNB".equals(segId) && partsList.size() > 3) {
                    senderId = partsList.get(2);
                    receiverId = partsList.get(3);
                }
                if ("UNH".equals(segId) && partsList.size() > 2) {
                    String[] msgType = partsList.get(2).split(Pattern.quote(String.valueOf(compSep)));
                    txnType = msgType.length > 0 ? msgType[0] : partsList.get(2);
                }
            } catch (Exception e) {
                errors.add("Segment " + segCount + ": " + e.getMessage());
                try {
                    String partialId = seg.contains(String.valueOf(elemSep))
                            ? seg.substring(0, seg.indexOf(elemSep))
                            : seg;
                    segments.add(Segment.builder().id(partialId).elements(List.of(seg)).build());
                } catch (Exception inner) {
                    segments.add(Segment.builder().id("ERROR").elements(List.of(seg)).build());
                }
            }
        }

        return EdiDocument.builder().sourceFormat("EDIFACT").documentType(txnType)
                .documentName(EDIFACT_TYPES.getOrDefault(txnType, "EDIFACT " + txnType))
                .senderId(senderId).receiverId(receiverId).controlNumber(controlNum)
                .segments(segments).rawContent(content)
                .businessData(Map.of("transactionType", txnType != null ? txnType : ""))
                .delimiterInfo(delimiters)
                .parseErrors(errors)
                .parseWarnings(warnings)
                .build();
    }

    /**
     * Detect EDIFACT delimiters from UNA service string advice.
     * If content starts with "UNA":
     *   Position 3: component separator (default :)
     *   Position 4: element separator (default +)
     *   Position 5: decimal notation (default .)
     *   Position 6: release character (default ?)
     *   Position 7: reserved (space)
     *   Position 8: segment terminator (default ')
     */
    private DelimiterInfo detectEdifactDelimiters(String content) {
        if (content == null) return defaultEdifactDelimiters();

        String trimmed = content.trim();
        if (trimmed.startsWith("UNA") && trimmed.length() >= 9) {
            return DelimiterInfo.builder()
                    .componentSeparator(trimmed.charAt(3))
                    .elementSeparator(trimmed.charAt(4))
                    .decimalNotation(trimmed.charAt(5))
                    .releaseCharacter(trimmed.charAt(6))
                    .segmentTerminator(trimmed.charAt(8))
                    .build();
        }

        return defaultEdifactDelimiters();
    }

    private DelimiterInfo defaultEdifactDelimiters() {
        return DelimiterInfo.builder()
                .componentSeparator(':')
                .elementSeparator('+')
                .decimalNotation('.')
                .releaseCharacter('?')
                .segmentTerminator('\'')
                .build();
    }

    /**
     * Split EDIFACT content into segments, respecting the release character.
     * A release character before the segment terminator means it's a literal,
     * not a segment boundary. Escape sequences are preserved in the output
     * so that downstream splitting (by element/component separator) can also
     * respect them.
     */
    private List<String> splitEdifactSegments(String content, char segTerm, char releaseChar) {
        List<String> segments = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean escaped = false;

        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (escaped) {
                // Keep the release character + the escaped character in output
                current.append(releaseChar);
                current.append(c);
                escaped = false;
                continue;
            }
            if (releaseChar != '\0' && c == releaseChar) {
                escaped = true;
                continue;
            }
            if (c == segTerm) {
                String seg = current.toString().trim();
                if (!seg.isEmpty()) {
                    segments.add(seg);
                }
                current.setLength(0);
                continue;
            }
            if (c != '\r' && c != '\n') {
                current.append(c);
            }
        }

        String remaining = current.toString().trim();
        if (!remaining.isEmpty()) {
            segments.add(remaining);
        }
        return segments;
    }

    /**
     * Split a string by a delimiter, respecting the release (escape) character.
     * The release character and escaped characters are resolved in the output
     * (i.e., ?? becomes ? and ?' becomes ').
     */
    private List<String> splitRespectingRelease(String input, char delimiter, char releaseChar) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean escaped = false;

        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (escaped) {
                current.append(c);
                escaped = false;
                continue;
            }
            if (releaseChar != '\0' && c == releaseChar) {
                escaped = true;
                continue;
            }
            if (c == delimiter) {
                parts.add(current.toString());
                current.setLength(0);
                continue;
            }
            current.append(c);
        }
        parts.add(current.toString());
        return parts;
    }

    // ========================================================================
    // TRADACOMS Parser
    // ========================================================================

    private EdiDocument parseTradacoms(String content) {
        List<Segment> segments = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        String senderId = null, txnType = "TRADACOMS";
        int segCount = 0;

        for (String line : content.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            segCount++;

            try {
                String[] parts = trimmed.split("=", -1);
                String segId = parts[0];
                List<String> elements = parts.length > 1 ? List.of(parts[1].split("\\+")) : List.of();
                segments.add(Segment.builder().id(segId).elements(elements).build());
                if ("STX".equals(segId) && elements.size() > 1) senderId = elements.get(1);
                if ("MHD".equals(segId) && elements.size() > 1) txnType = "TRADACOMS-" + elements.get(1);
            } catch (Exception e) {
                errors.add("Segment " + segCount + ": " + e.getMessage());
                segments.add(Segment.builder().id("ERROR").elements(List.of(trimmed)).build());
            }
        }

        return EdiDocument.builder().sourceFormat("TRADACOMS").documentType(txnType)
                .documentName("TRADACOMS Message").senderId(senderId)
                .segments(segments).rawContent(content)
                .parseErrors(errors).parseWarnings(List.of())
                .build();
    }

    // ========================================================================
    // SWIFT MT Parser
    // ========================================================================

    private EdiDocument parseSwiftMt(String content) {
        List<Segment> segments = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        String mtType = null;
        Map<String, Object> biz = new LinkedHashMap<>();
        int segCount = 0;

        for (String line : content.split("\n")) {
            String trimmed = line.trim();
            segCount++;

            try {
                if (trimmed.startsWith(":")) {
                    String[] parts = trimmed.split(":", 3);
                    if (parts.length >= 3) {
                        String tag = parts[1]; String value = parts[2];
                        segments.add(Segment.builder().id(":" + tag + ":").elements(List.of(value))
                                .namedFields(Map.of("tag", tag, "value", value)).build());
                        biz.put("tag_" + tag, value);
                        if ("20".equals(tag)) biz.put("reference", value);
                        if ("32A".equals(tag)) biz.put("valueDate_currency_amount", value);
                    }
                }
                if (trimmed.startsWith("{2:")) {
                    String digits = trimmed.replaceAll("[^0-9]", "");
                    mtType = "MT" + digits.substring(0, Math.min(3, digits.length()));
                }
            } catch (Exception e) {
                errors.add("Line " + segCount + ": " + e.getMessage());
            }
        }

        return EdiDocument.builder().sourceFormat("SWIFT_MT").documentType(mtType)
                .documentName("SWIFT " + (mtType != null ? mtType : "Message"))
                .segments(segments).rawContent(content).businessData(biz)
                .parseErrors(errors).parseWarnings(List.of())
                .build();
    }

    // ========================================================================
    // HL7 v2 Parser — component/repetition/escape support
    // ========================================================================

    private EdiDocument parseHl7(String content) {
        List<Segment> segments = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        String msgType = null, senderId = null;

        // HL7 encoding characters from MSH-2: ^~\&
        // ^  = component separator
        // ~  = repetition separator
        // \  = escape character
        // &  = sub-component separator
        char hl7CompSep = '^';
        char hl7RepSep = '~';
        char hl7EscChar = '\\';
        int segCount = 0;

        for (String line : content.split("\r\n|\r|\n")) {
            if (line.isEmpty()) continue;
            segCount++;

            try {
                String[] fields = line.split("\\|", -1);
                String segId = fields[0];

                List<String> elements = new ArrayList<>();
                for (int i = 1; i < fields.length; i++) elements.add(fields[i]);

                // Composite element parsing for HL7 (component separator is ^)
                List<List<String>> composites = new ArrayList<>();
                for (String elem : elements) {
                    if (elem.indexOf(hl7CompSep) >= 0) {
                        composites.add(Arrays.asList(elem.split(Pattern.quote(String.valueOf(hl7CompSep)), -1)));
                    } else {
                        composites.add(List.of(elem));
                    }
                }

                // Repeating element parsing for HL7 (repetition separator is ~)
                List<List<String>> repeating = new ArrayList<>();
                for (String elem : elements) {
                    if (elem.indexOf(hl7RepSep) >= 0) {
                        repeating.add(Arrays.asList(elem.split(Pattern.quote(String.valueOf(hl7RepSep)), -1)));
                    } else {
                        repeating.add(List.of(elem));
                    }
                }

                segments.add(Segment.builder().id(segId).elements(elements)
                        .compositeElements(composites).repeatingElements(repeating).build());

                if ("MSH".equals(segId)) {
                    if (fields.length > 3) senderId = fields[3];
                    if (fields.length > 9) msgType = fields[9].replace("^", "_");
                    // Extract encoding characters from MSH-2 if present
                    if (fields.length > 2 && fields[1].length() >= 4) {
                        hl7CompSep = fields[1].charAt(0);
                        hl7RepSep = fields[1].charAt(1);
                        hl7EscChar = fields[1].charAt(2);
                    }
                }
            } catch (Exception e) {
                errors.add("Segment " + segCount + ": " + e.getMessage());
                segments.add(Segment.builder().id("ERROR").elements(List.of(line)).build());
            }
        }

        return EdiDocument.builder().sourceFormat("HL7").documentType(msgType)
                .documentName("HL7 " + (msgType != null ? msgType : "Message"))
                .senderId(senderId).segments(segments).rawContent(content)
                .parseErrors(errors).parseWarnings(List.of())
                .build();
    }

    // ========================================================================
    // NACHA/ACH Parser
    // ========================================================================

    private EdiDocument parseNacha(String content) {
        List<Segment> segments = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        Map<String, Object> biz = new LinkedHashMap<>();
        int segCount = 0;

        for (String line : content.split("\n")) {
            if (line.length() < 2) continue;
            segCount++;

            try {
                String recType = line.substring(0, 1);
                String name = switch (recType) {
                    case "1" -> "FILE_HEADER";
                    case "5" -> "BATCH_HEADER";
                    case "6" -> "ENTRY_DETAIL";
                    case "7" -> "ADDENDA";
                    case "8" -> "BATCH_CONTROL";
                    case "9" -> "FILE_CONTROL";
                    default -> "RECORD_" + recType;
                };
                segments.add(Segment.builder().id(name).elements(List.of(line))
                        .namedFields(Map.of("recordType", recType, "raw", line)).build());

                if ("1".equals(recType) && line.length() >= 40) {
                    biz.put("immediateOrigin", line.substring(13, 23).trim());
                    biz.put("immediateDestination", line.substring(3, 13).trim());
                    biz.put("fileCreationDate", line.substring(23, 29).trim());
                }
            } catch (Exception e) {
                errors.add("Record " + segCount + ": " + e.getMessage());
                segments.add(Segment.builder().id("ERROR").elements(List.of(line)).build());
            }
        }

        return EdiDocument.builder().sourceFormat("NACHA").documentType("ACH")
                .documentName("NACHA ACH File").segments(segments).rawContent(content).businessData(biz)
                .parseErrors(errors).parseWarnings(List.of())
                .build();
    }

    // ========================================================================
    // BAI2 Parser
    // ========================================================================

    private EdiDocument parseBai2(String content) {
        List<Segment> segments = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        int segCount = 0;

        for (String line : content.split("\n")) {
            if (line.isEmpty()) continue;
            segCount++;

            try {
                String[] parts = line.split(",", -1);
                String recType = parts[0];
                String name = switch (recType) {
                    case "01" -> "FILE_HEADER"; case "02" -> "GROUP_HEADER"; case "03" -> "ACCOUNT_IDENTIFIER";
                    case "16" -> "TRANSACTION_DETAIL"; case "49" -> "ACCOUNT_TRAILER";
                    case "98" -> "GROUP_TRAILER"; case "99" -> "FILE_TRAILER";
                    default -> "RECORD_" + recType;
                };
                List<String> elements = new ArrayList<>();
                for (int i = 1; i < parts.length; i++) elements.add(parts[i]);
                segments.add(Segment.builder().id(name).elements(elements).build());
            } catch (Exception e) {
                errors.add("Record " + segCount + ": " + e.getMessage());
                segments.add(Segment.builder().id("ERROR").elements(List.of(line)).build());
            }
        }

        return EdiDocument.builder().sourceFormat("BAI2").documentType("BAI2")
                .documentName("BAI2 Balance Report").segments(segments).rawContent(content)
                .parseErrors(errors).parseWarnings(List.of())
                .build();
    }

    // ========================================================================
    // ISO 20022 / CAMT Parser
    // ========================================================================

    private EdiDocument parseIso20022(String content) {
        List<Segment> segments = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        String msgType = null;
        int segCount = 0;

        for (String line : content.split("\n")) {
            String trimmed = line.trim();
            segCount++;

            try {
                if (trimmed.startsWith("<") && !trimmed.startsWith("<?") && !trimmed.startsWith("</")) {
                    String tag = trimmed.replaceAll("[<>/].*", "").replaceAll("\\s.*", "");
                    String value = trimmed.replaceAll("<[^>]*>", "").trim();
                    if (!tag.isEmpty()) {
                        segments.add(Segment.builder().id(tag).elements(value.isEmpty() ? List.of() : List.of(value)).build());
                    }
                    if ("MsgId".equals(tag) || "BkToCstmrStmt".equals(tag)) msgType = tag;
                }
            } catch (Exception e) {
                errors.add("Line " + segCount + ": " + e.getMessage());
            }
        }

        return EdiDocument.builder().sourceFormat("ISO20022").documentType(msgType)
                .documentName("ISO 20022 " + (msgType != null ? msgType : "Message"))
                .segments(segments).rawContent(content)
                .parseErrors(errors).parseWarnings(List.of())
                .build();
    }

    // ========================================================================
    // PEPPOL / UBL Parser
    // ========================================================================

    private EdiDocument parsePeppol(String content) {
        List<Segment> segments = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        Map<String, Object> biz = new LinkedHashMap<>();
        String docType = null, senderId = null, receiverId = null, docNumber = null, docDate = null;

        if (content.contains("<Invoice")) docType = "Invoice";
        else if (content.contains("<CreditNote")) docType = "CreditNote";
        else if (content.contains("<Order")) docType = "Order";
        else if (content.contains("<DespatchAdvice")) docType = "DespatchAdvice";
        else if (content.contains("<Catalogue")) docType = "Catalogue";
        else docType = "UBL";

        int segCount = 0;
        for (String line : content.split("\n")) {
            String trimmed = line.trim();
            segCount++;

            try {
                if (trimmed.startsWith("<") && !trimmed.startsWith("<?") && !trimmed.startsWith("</")
                        && !trimmed.startsWith("<!--")) {
                    String tag = trimmed.replaceAll("[<>/].*", "").replaceAll("\\s.*", "").replaceAll("^.*:", "");
                    String value = trimmed.replaceAll("<[^>]*>", "").trim();

                    if (!tag.isEmpty()) {
                        Map<String, String> named = new LinkedHashMap<>();
                        named.put("tag", tag);
                        if (!value.isEmpty()) named.put("value", value);
                        segments.add(Segment.builder().id(tag)
                                .elements(value.isEmpty() ? List.of() : List.of(value))
                                .namedFields(named).build());

                        switch (tag) {
                            case "ID" -> { if (docNumber == null) docNumber = value; }
                            case "IssueDate" -> docDate = value;
                            case "EndpointID" -> {
                                if (senderId == null) senderId = value;
                                else if (receiverId == null) receiverId = value;
                            }
                            case "Name" -> {
                                if (biz.containsKey("supplierName")) biz.putIfAbsent("customerName", value);
                                else biz.putIfAbsent("supplierName", value);
                            }
                            case "PayableAmount", "TaxInclusiveAmount" -> biz.put("totalAmount", value);
                            case "TaxAmount" -> biz.put("taxAmount", value);
                            case "InvoicedQuantity", "Quantity" -> biz.put("quantity", value);
                            case "PriceAmount" -> biz.put("unitPrice", value);
                            case "DocumentCurrencyCode" -> biz.put("currency", value);
                            case "Note" -> biz.put("note", value);
                            case "ProfileID" -> biz.put("profileId", value);
                            case "CustomizationID" -> biz.put("customizationId", value);
                        }
                    }
                }
            } catch (Exception e) {
                errors.add("Line " + segCount + ": " + e.getMessage());
            }
        }

        biz.put("ublDocumentType", docType);

        return EdiDocument.builder().sourceFormat("PEPPOL").documentType(docType)
                .documentName("PEPPOL/UBL " + docType)
                .senderId(senderId).receiverId(receiverId)
                .controlNumber(docNumber).documentDate(docDate)
                .segments(segments).rawContent(content).businessData(biz)
                .parseErrors(errors).parseWarnings(List.of())
                .build();
    }

    // ========================================================================
    // FIX Parser
    // ========================================================================

    private EdiDocument parseFix(String content) {
        List<Segment> segments = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        Map<String, Object> biz = new LinkedHashMap<>();
        String version = null, msgType = null;
        int segCount = 0;

        String delim = content.contains("\001") ? "\001" : "\\|";
        for (String field : content.split(delim)) {
            if (field.isEmpty()) continue;
            segCount++;

            try {
                String[] kv = field.split("=", 2);
                if (kv.length == 2) {
                    segments.add(Segment.builder().id("Tag" + kv[0]).elements(List.of(kv[1]))
                            .namedFields(Map.of("tag", kv[0], "value", kv[1])).build());
                    biz.put("tag_" + kv[0], kv[1]);
                    if ("8".equals(kv[0])) version = kv[1];
                    if ("35".equals(kv[0])) msgType = kv[1];
                }
            } catch (Exception e) {
                errors.add("Field " + segCount + ": " + e.getMessage());
            }
        }

        String msgName = switch (msgType != null ? msgType : "") {
            case "D" -> "New Order Single"; case "8" -> "Execution Report";
            case "0" -> "Heartbeat"; case "A" -> "Logon"; case "5" -> "Logout";
            default -> "FIX Message " + msgType;
        };

        return EdiDocument.builder().sourceFormat("FIX").documentType(msgType).documentName(msgName)
                .version(version).segments(segments).rawContent(content).businessData(biz)
                .parseErrors(errors).parseWarnings(List.of())
                .build();
    }
}
