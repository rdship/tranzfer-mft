package com.filetransfer.edi.parser;

import com.filetransfer.edi.model.EdiDocument;
import com.filetransfer.edi.model.EdiDocument.Segment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Universal EDI parser — parses ANY supported format into the EdiDocument model.
 * Each format has its own parsing logic, but all produce the same output.
 */
@Service @RequiredArgsConstructor @Slf4j
public class UniversalEdiParser {

    private final FormatDetector detector;

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
            default -> EdiDocument.builder().sourceFormat("UNKNOWN").rawContent(content).segments(List.of()).build();
        };
    }

    // === X12 Parser ===
    private EdiDocument parseX12(String content) {
        String segDelim = content.contains("~") ? "~" : "\n";
        String elemDelim = "*";
        List<Segment> segments = new ArrayList<>();
        String txnType = null, senderId = null, receiverId = null, controlNum = null, date = null;

        for (String raw : content.split(segDelim)) {
            String seg = raw.trim();
            if (seg.isEmpty()) continue;
            String[] elems = seg.split("\\*", -1);
            String segId = elems[0];

            List<String> elements = new ArrayList<>();
            for (int i = 1; i < elems.length; i++) elements.add(elems[i]);
            segments.add(Segment.builder().id(segId).elements(elements).build());

            if ("ISA".equals(segId) && elems.length > 8) {
                senderId = elems[6].trim();
                receiverId = elems[8].trim();
                if (elems.length > 9) date = elems[9].trim();
                if (elems.length > 13) controlNum = elems[13].trim();
            }
            if ("ST".equals(segId) && elems.length > 1) txnType = elems[1].trim();
        }

        return EdiDocument.builder().sourceFormat("X12").documentType(txnType)
                .documentName(X12_TYPES.getOrDefault(txnType, "X12 Transaction " + txnType))
                .senderId(senderId).receiverId(receiverId).documentDate(date).controlNumber(controlNum)
                .version("005010").segments(segments).rawContent(content)
                .businessData(extractX12BusinessData(txnType, segments)).build();
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

    // === EDIFACT Parser ===
    private EdiDocument parseEdifact(String content) {
        String segDelim = "'";
        List<Segment> segments = new ArrayList<>();
        String txnType = null, senderId = null, receiverId = null, controlNum = null;

        for (String raw : content.split(segDelim)) {
            String seg = raw.trim();
            if (seg.isEmpty()) continue;
            String[] parts = seg.split("\\+", -1);
            String segId = parts[0];
            List<String> elements = new ArrayList<>();
            for (int i = 1; i < parts.length; i++) elements.add(parts[i]);
            segments.add(Segment.builder().id(segId).elements(elements).build());

            if ("UNB".equals(segId) && parts.length > 3) { senderId = parts[2]; receiverId = parts[3]; }
            if ("UNH".equals(segId) && parts.length > 2) {
                String[] msgType = parts[2].split(":");
                txnType = msgType.length > 0 ? msgType[0] : parts[2];
            }
        }

        return EdiDocument.builder().sourceFormat("EDIFACT").documentType(txnType)
                .documentName(EDIFACT_TYPES.getOrDefault(txnType, "EDIFACT " + txnType))
                .senderId(senderId).receiverId(receiverId).controlNumber(controlNum)
                .segments(segments).rawContent(content)
                .businessData(Map.of("transactionType", txnType != null ? txnType : "")).build();
    }

    // === TRADACOMS Parser ===
    private EdiDocument parseTradacoms(String content) {
        List<Segment> segments = new ArrayList<>();
        String senderId = null, txnType = "TRADACOMS";
        for (String line : content.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            String[] parts = trimmed.split("=", -1);
            String segId = parts[0];
            List<String> elements = parts.length > 1 ? List.of(parts[1].split("\\+")) : List.of();
            segments.add(Segment.builder().id(segId).elements(elements).build());
            if ("STX".equals(segId) && elements.size() > 1) senderId = elements.get(1);
            if ("MHD".equals(segId) && elements.size() > 1) txnType = "TRADACOMS-" + elements.get(1);
        }
        return EdiDocument.builder().sourceFormat("TRADACOMS").documentType(txnType)
                .documentName("TRADACOMS Message").senderId(senderId)
                .segments(segments).rawContent(content).build();
    }

    // === SWIFT MT Parser ===
    private EdiDocument parseSwiftMt(String content) {
        List<Segment> segments = new ArrayList<>();
        String mtType = null;
        Map<String, Object> biz = new LinkedHashMap<>();

        for (String line : content.split("\n")) {
            String trimmed = line.trim();
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
                // Extract MT type from block 2
                mtType = "MT" + trimmed.replaceAll("[^0-9]", "").substring(0, Math.min(3, trimmed.replaceAll("[^0-9]", "").length()));
            }
        }

        return EdiDocument.builder().sourceFormat("SWIFT_MT").documentType(mtType)
                .documentName("SWIFT " + (mtType != null ? mtType : "Message"))
                .segments(segments).rawContent(content).businessData(biz).build();
    }

    // === HL7 v2 Parser ===
    private EdiDocument parseHl7(String content) {
        List<Segment> segments = new ArrayList<>();
        String msgType = null, senderId = null;

        for (String line : content.split("\r\n|\r|\n")) {
            if (line.isEmpty()) continue;
            String[] fields = line.split("\\|", -1);
            String segId = fields[0];
            List<String> elements = new ArrayList<>();
            for (int i = 1; i < fields.length; i++) elements.add(fields[i]);
            segments.add(Segment.builder().id(segId).elements(elements).build());

            if ("MSH".equals(segId)) {
                if (fields.length > 3) senderId = fields[3]; // Sending application
                if (fields.length > 9) msgType = fields[9].replace("^", "_"); // Message type
            }
        }

        return EdiDocument.builder().sourceFormat("HL7").documentType(msgType)
                .documentName("HL7 " + (msgType != null ? msgType : "Message"))
                .senderId(senderId).segments(segments).rawContent(content).build();
    }

    // === NACHA/ACH Parser ===
    private EdiDocument parseNacha(String content) {
        List<Segment> segments = new ArrayList<>();
        Map<String, Object> biz = new LinkedHashMap<>();

        for (String line : content.split("\n")) {
            if (line.length() < 2) continue;
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
            segments.add(Segment.builder().id(name).elements(List.of(line)).namedFields(Map.of("recordType", recType, "raw", line)).build());

            if ("1".equals(recType) && line.length() >= 40) {
                biz.put("immediateOrigin", line.substring(13, 23).trim());
                biz.put("immediateDestination", line.substring(3, 13).trim());
                biz.put("fileCreationDate", line.substring(23, 29).trim());
            }
        }

        return EdiDocument.builder().sourceFormat("NACHA").documentType("ACH")
                .documentName("NACHA ACH File").segments(segments).rawContent(content).businessData(biz).build();
    }

    // === BAI2 Parser ===
    private EdiDocument parseBai2(String content) {
        List<Segment> segments = new ArrayList<>();
        for (String line : content.split("\n")) {
            if (line.isEmpty()) continue;
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
        }
        return EdiDocument.builder().sourceFormat("BAI2").documentType("BAI2")
                .documentName("BAI2 Balance Report").segments(segments).rawContent(content).build();
    }

    // === ISO 20022 / CAMT Parser ===
    private EdiDocument parseIso20022(String content) {
        List<Segment> segments = new ArrayList<>();
        // Simple tag extraction from XML
        String msgType = null;
        for (String line : content.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("<") && !trimmed.startsWith("<?") && !trimmed.startsWith("</")) {
                String tag = trimmed.replaceAll("[<>/].*", "").replaceAll("\\s.*", "");
                String value = trimmed.replaceAll("<[^>]*>", "").trim();
                if (!tag.isEmpty()) {
                    segments.add(Segment.builder().id(tag).elements(value.isEmpty() ? List.of() : List.of(value)).build());
                }
                if ("MsgId".equals(tag) || "BkToCstmrStmt".equals(tag)) msgType = tag;
            }
        }
        return EdiDocument.builder().sourceFormat("ISO20022").documentType(msgType)
                .documentName("ISO 20022 " + (msgType != null ? msgType : "Message"))
                .segments(segments).rawContent(content).build();
    }

    // === FIX Parser ===
    private EdiDocument parseFix(String content) {
        List<Segment> segments = new ArrayList<>();
        Map<String, Object> biz = new LinkedHashMap<>();
        String version = null, msgType = null;

        // FIX uses SOH (0x01) or | as delimiter
        String delim = content.contains("\001") ? "\001" : "\\|";
        for (String field : content.split(delim)) {
            if (field.isEmpty()) continue;
            String[] kv = field.split("=", 2);
            if (kv.length == 2) {
                segments.add(Segment.builder().id("Tag" + kv[0]).elements(List.of(kv[1]))
                        .namedFields(Map.of("tag", kv[0], "value", kv[1])).build());
                biz.put("tag_" + kv[0], kv[1]);
                if ("8".equals(kv[0])) version = kv[1];
                if ("35".equals(kv[0])) msgType = kv[1];
            }
        }

        String msgName = switch (msgType != null ? msgType : "") {
            case "D" -> "New Order Single"; case "8" -> "Execution Report";
            case "0" -> "Heartbeat"; case "A" -> "Logon"; case "5" -> "Logout";
            default -> "FIX Message " + msgType;
        };

        return EdiDocument.builder().sourceFormat("FIX").documentType(msgType).documentName(msgName)
                .version(version).segments(segments).rawContent(content).businessData(biz).build();
    }
}
