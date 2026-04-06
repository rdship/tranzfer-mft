package com.filetransfer.edi.parser;

import lombok.*;
import org.springframework.stereotype.Component;
import java.util.*;

/**
 * Registry of EDIFACT segment definitions — elements, data types, min/max lengths,
 * required/optional flags, and valid code sets.
 * Covers envelope segments (UNB/UNH/UNT/UNZ) and common message segments
 * for ORDERS, INVOIC, DESADV, PAYMUL, and general-purpose segments.
 */
@Component
public class EdifactSegmentDefinitions {

    @Data @AllArgsConstructor
    public static class SegmentDef {
        private String segmentId;
        private String name;
        private List<ElementDef> elements;
        private boolean repeatable;
    }

    @Data @AllArgsConstructor
    public static class ElementDef {
        private int position;       // 1-based
        private String name;
        private DataType dataType;
        private int minLength;
        private int maxLength;
        private boolean required;
        private Set<String> validValues;  // null means any value OK

        public enum DataType {
            AN,   // Alphanumeric
            A,    // Alphabetic
            N,    // Numeric
            ID,   // Identifier (from code set)
            DT,   // Date (CCYYMMDD or YYMMDD)
            TM,   // Time (HHMM or HHMMSS or HHMMSSsss)
            R,    // Decimal number
            COMP  // Composite element (colon-separated sub-elements)
        }
    }

    private static final Map<String, SegmentDef> DEFINITIONS = new HashMap<>();

    static {
        // === UNB - Interchange Header ===
        DEFINITIONS.put("UNB", new SegmentDef("UNB", "Interchange Header", List.of(
            elem(1, "Syntax Identifier", ElementDef.DataType.COMP, 4, 9, true, null),
            elem(2, "Interchange Sender", ElementDef.DataType.COMP, 1, 35, true, null),
            elem(3, "Interchange Recipient", ElementDef.DataType.COMP, 1, 35, true, null),
            elem(4, "Date/Time of Preparation", ElementDef.DataType.COMP, 8, 12, true, null),
            elem(5, "Interchange Control Reference", ElementDef.DataType.AN, 1, 14, true, null),
            elem(6, "Recipient Reference/Password", ElementDef.DataType.AN, 1, 14, false, null),
            elem(7, "Application Reference", ElementDef.DataType.AN, 1, 14, false, null),
            elem(8, "Processing Priority Code", ElementDef.DataType.ID, 1, 1, false, Set.of("A")),
            elem(9, "Acknowledgement Request", ElementDef.DataType.N, 1, 1, false, null),
            elem(10, "Interchange Agreement ID", ElementDef.DataType.AN, 1, 35, false, null),
            elem(11, "Test Indicator", ElementDef.DataType.N, 1, 1, false, null)
        ), false));

        // === UNH - Message Header ===
        DEFINITIONS.put("UNH", new SegmentDef("UNH", "Message Header", List.of(
            elem(1, "Message Reference Number", ElementDef.DataType.AN, 1, 14, true, null),
            elem(2, "Message Identifier", ElementDef.DataType.COMP, 1, 35, true, null),
            elem(3, "Common Access Reference", ElementDef.DataType.AN, 1, 35, false, null),
            elem(4, "Status of Transfer", ElementDef.DataType.COMP, 1, 4, false, null)
        ), false));

        // === UNT - Message Trailer ===
        DEFINITIONS.put("UNT", new SegmentDef("UNT", "Message Trailer", List.of(
            elem(1, "Number of Segments", ElementDef.DataType.N, 1, 10, true, null),
            elem(2, "Message Reference Number", ElementDef.DataType.AN, 1, 14, true, null)
        ), false));

        // === UNZ - Interchange Trailer ===
        DEFINITIONS.put("UNZ", new SegmentDef("UNZ", "Interchange Trailer", List.of(
            elem(1, "Interchange Control Count", ElementDef.DataType.N, 1, 6, true, null),
            elem(2, "Interchange Control Reference", ElementDef.DataType.AN, 1, 14, true, null)
        ), false));

        // === BGM - Beginning of Message ===
        DEFINITIONS.put("BGM", new SegmentDef("BGM", "Beginning of Message", List.of(
            elem(1, "Document/Message Name", ElementDef.DataType.COMP, 1, 35, false, null),
            elem(2, "Document/Message Number", ElementDef.DataType.AN, 1, 35, false, null),
            elem(3, "Message Function Code", ElementDef.DataType.ID, 1, 3, false, Set.of("1","2","3","4","5","6","7","9","11","13","22","24","27","29","31","33","35","43","44","45","46","47")),
            elem(4, "Response Type Code", ElementDef.DataType.ID, 2, 3, false, null)
        ), false));

        // === DTM - Date/Time/Period ===
        DEFINITIONS.put("DTM", new SegmentDef("DTM", "Date/Time/Period", List.of(
            elem(1, "Date/Time/Period", ElementDef.DataType.COMP, 3, 35, true, null)
        ), true));

        // === NAD - Name and Address ===
        DEFINITIONS.put("NAD", new SegmentDef("NAD", "Name and Address", List.of(
            elem(1, "Party Function Qualifier", ElementDef.DataType.ID, 2, 3, true, Set.of("AB","BY","CA","CN","CO","DP","FR","II","IV","MF","OB","PE","PW","SE","SF","SN","ST","SU","TO","UC")),
            elem(2, "Party Identification", ElementDef.DataType.COMP, 1, 35, false, null),
            elem(3, "Name and Address", ElementDef.DataType.COMP, 1, 35, false, null),
            elem(4, "Party Name", ElementDef.DataType.COMP, 1, 35, false, null),
            elem(5, "Street", ElementDef.DataType.COMP, 1, 35, false, null),
            elem(6, "City Name", ElementDef.DataType.AN, 1, 35, false, null),
            elem(7, "Country Sub-entity ID", ElementDef.DataType.AN, 1, 9, false, null),
            elem(8, "Postal Identification Code", ElementDef.DataType.AN, 1, 17, false, null),
            elem(9, "Country Code", ElementDef.DataType.ID, 2, 3, false, null)
        ), true));

        // === LIN - Line Item ===
        DEFINITIONS.put("LIN", new SegmentDef("LIN", "Line Item", List.of(
            elem(1, "Line Item Identifier", ElementDef.DataType.AN, 1, 6, false, null),
            elem(2, "Action Request/Notification Code", ElementDef.DataType.ID, 1, 3, false, null),
            elem(3, "Item Number Identification", ElementDef.DataType.COMP, 1, 35, false, null),
            elem(4, "Sub-line Information", ElementDef.DataType.COMP, 1, 4, false, null)
        ), true));

        // === QTY - Quantity ===
        DEFINITIONS.put("QTY", new SegmentDef("QTY", "Quantity", List.of(
            elem(1, "Quantity Details", ElementDef.DataType.COMP, 1, 35, true, null)
        ), true));

        // === PRI - Price Details ===
        DEFINITIONS.put("PRI", new SegmentDef("PRI", "Price Details", List.of(
            elem(1, "Price Information", ElementDef.DataType.COMP, 1, 35, true, null)
        ), true));

        // === MOA - Monetary Amount ===
        DEFINITIONS.put("MOA", new SegmentDef("MOA", "Monetary Amount", List.of(
            elem(1, "Monetary Amount", ElementDef.DataType.COMP, 1, 35, true, null)
        ), true));

        // === UNS - Section Control ===
        DEFINITIONS.put("UNS", new SegmentDef("UNS", "Section Control", List.of(
            elem(1, "Section Identification", ElementDef.DataType.ID, 1, 1, true, Set.of("D","S"))
        ), false));

        // === CNT - Control Total ===
        DEFINITIONS.put("CNT", new SegmentDef("CNT", "Control Total", List.of(
            elem(1, "Control", ElementDef.DataType.COMP, 1, 35, true, null)
        ), true));

        // === FTX - Free Text ===
        DEFINITIONS.put("FTX", new SegmentDef("FTX", "Free Text", List.of(
            elem(1, "Text Subject Code Qualifier", ElementDef.DataType.ID, 1, 3, true, Set.of("AAA","AAB","AAC","AAD","AAE","AAI","AAJ","AAK","ABN","ACB","ACD","ACE","ACI","ACS","ACT","ADR","ALL","AQD","ARR","BLR","CHG","CIP","CUR","DEL","DIN","DOC","GEN","GIS","ICN","INS","INV","IVN","LIN","MIS","MKS","ORI","OSI","PAC","PAI","PAY","PKG","PMD","PMT","PRD","PRI","PRR","QIN","QQD","QVR","RAH","REG","RET","RQR","SAF","SIN","SLR","SUR","TCA","TDT","TRA","TRR","TXD","WHI","ZZZ")),
            elem(2, "Free Text Function Code", ElementDef.DataType.ID, 1, 3, false, null),
            elem(3, "Text Reference", ElementDef.DataType.COMP, 1, 35, false, null),
            elem(4, "Text Literal", ElementDef.DataType.COMP, 1, 512, false, null)
        ), true));

        // === RFF - Reference ===
        DEFINITIONS.put("RFF", new SegmentDef("RFF", "Reference", List.of(
            elem(1, "Reference", ElementDef.DataType.COMP, 1, 70, true, null)
        ), true));

        // === PIA - Additional Product ID ===
        DEFINITIONS.put("PIA", new SegmentDef("PIA", "Additional Product ID", List.of(
            elem(1, "Product ID Function Qualifier", ElementDef.DataType.ID, 1, 3, true, Set.of("1","5")),
            elem(2, "Item Number Identification", ElementDef.DataType.COMP, 1, 35, true, null),
            elem(3, "Item Number Identification", ElementDef.DataType.COMP, 1, 35, false, null),
            elem(4, "Item Number Identification", ElementDef.DataType.COMP, 1, 35, false, null)
        ), true));

        // === TAX - Duty/Tax/Fee Details ===
        DEFINITIONS.put("TAX", new SegmentDef("TAX", "Duty/Tax/Fee Details", List.of(
            elem(1, "Duty/Tax/Fee Function Qualifier", ElementDef.DataType.ID, 1, 3, true, Set.of("5","6","7")),
            elem(2, "Duty/Tax/Fee Type", ElementDef.DataType.COMP, 1, 6, false, null),
            elem(3, "Duty/Tax/Fee Account Detail", ElementDef.DataType.COMP, 1, 6, false, null),
            elem(4, "Duty/Tax/Fee Assessment Basis", ElementDef.DataType.AN, 1, 15, false, null),
            elem(5, "Duty/Tax/Fee Detail", ElementDef.DataType.COMP, 1, 17, false, null),
            elem(6, "Duty/Tax/Fee Category Code", ElementDef.DataType.ID, 1, 3, false, Set.of("A","AA","AB","AC","AD","AE","B","C","E","G","H","O","S","Z")),
            elem(7, "Party Tax ID", ElementDef.DataType.AN, 1, 20, false, null)
        ), true));

        // === ALC - Allowance or Charge ===
        DEFINITIONS.put("ALC", new SegmentDef("ALC", "Allowance or Charge", List.of(
            elem(1, "Allowance/Charge Code Qualifier", ElementDef.DataType.ID, 1, 3, true, Set.of("A","C","N")),
            elem(2, "Allowance/Charge Identification", ElementDef.DataType.COMP, 1, 35, false, null),
            elem(3, "Settlement Means Code", ElementDef.DataType.ID, 1, 3, false, null),
            elem(4, "Calculation Sequence Code", ElementDef.DataType.ID, 1, 3, false, null),
            elem(5, "Special Services Identification", ElementDef.DataType.COMP, 1, 35, false, null)
        ), true));

        // === TDT - Transport Information ===
        DEFINITIONS.put("TDT", new SegmentDef("TDT", "Transport Information", List.of(
            elem(1, "Transport Stage Code Qualifier", ElementDef.DataType.ID, 1, 3, true, Set.of("1","10","11","12","13","15","16","17","18","20","21","22","23","24","25","26","27","28","30","31","32","33","34","40","41","42","43","44","50")),
            elem(2, "Means of Transport Journey ID", ElementDef.DataType.AN, 1, 17, false, null),
            elem(3, "Mode of Transport", ElementDef.DataType.COMP, 1, 3, false, null),
            elem(4, "Transport Means", ElementDef.DataType.COMP, 1, 17, false, null),
            elem(5, "Carrier", ElementDef.DataType.COMP, 1, 17, false, null),
            elem(6, "Transit Direction Indicator Code", ElementDef.DataType.ID, 1, 3, false, null),
            elem(7, "Excess Transportation Information", ElementDef.DataType.COMP, 1, 17, false, null),
            elem(8, "Transport Identification", ElementDef.DataType.COMP, 1, 35, false, null)
        ), true));

        // === LOC - Place/Location Identification ===
        DEFINITIONS.put("LOC", new SegmentDef("LOC", "Place/Location Identification", List.of(
            elem(1, "Location Function Code Qualifier", ElementDef.DataType.ID, 1, 3, true, Set.of("1","5","7","8","9","11","13","14","18","20","24","27","28","35","88","CW","FW","ZZZ")),
            elem(2, "Location Identification", ElementDef.DataType.COMP, 1, 35, false, null),
            elem(3, "Related Location One ID", ElementDef.DataType.COMP, 1, 25, false, null),
            elem(4, "Related Location Two ID", ElementDef.DataType.COMP, 1, 25, false, null)
        ), true));

        // === PAC - Package ===
        DEFINITIONS.put("PAC", new SegmentDef("PAC", "Package", List.of(
            elem(1, "Number of Packages", ElementDef.DataType.N, 1, 8, false, null),
            elem(2, "Packaging Details", ElementDef.DataType.COMP, 1, 3, false, null),
            elem(3, "Package Type", ElementDef.DataType.COMP, 1, 17, false, null)
        ), true));
    }

    private static ElementDef elem(int pos, String name, ElementDef.DataType type, int min, int max, boolean req, Set<String> vals) {
        return new ElementDef(pos, name, type, min, max, req, vals);
    }

    public SegmentDef getDefinition(String segmentId) {
        return DEFINITIONS.get(segmentId);
    }

    public boolean hasDefinition(String segmentId) {
        return DEFINITIONS.containsKey(segmentId);
    }

    public Set<String> getDefinedSegments() {
        return Collections.unmodifiableSet(DEFINITIONS.keySet());
    }
}
