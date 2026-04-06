package com.filetransfer.edi.parser;

import lombok.*;
import org.springframework.stereotype.Component;
import java.util.*;

/**
 * Registry of X12 segment definitions — elements, data types, min/max lengths,
 * required/optional flags, and valid code sets.
 * Covers envelope segments (ISA/GS/ST/SE/GE/IEA) and common transaction segments
 * for 850, 810, 856, 837, 835, 834, 997, and general-purpose segments.
 */
@Component
public class X12SegmentDefinitions {

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
        private DataType dataType;  // AN, N, N0, N2, ID, DT, TM, R, B
        private int minLength;
        private int maxLength;
        private boolean required;   // M=mandatory, O=optional
        private Set<String> validValues;  // for ID type — null means any value OK

        public enum DataType {
            AN,   // Alphanumeric
            N,    // Numeric (may have implied decimal)
            N0,   // Numeric integer
            N2,   // Numeric 2 decimal places
            ID,   // Identifier (from code set)
            DT,   // Date CCYYMMDD or YYMMDD
            TM,   // Time HHMM or HHMMSS
            R,    // Decimal number
            B     // Binary
        }
    }

    private static final Map<String, SegmentDef> DEFINITIONS = new HashMap<>();

    static {
        // === ISA - Interchange Control Header (16 elements, all mandatory) ===
        DEFINITIONS.put("ISA", new SegmentDef("ISA", "Interchange Control Header", List.of(
            elem(1, "Authorization Info Qualifier", ElementDef.DataType.ID, 2, 2, true, Set.of("00", "01", "02", "03", "04", "05", "06")),
            elem(2, "Authorization Information", ElementDef.DataType.AN, 10, 10, true, null),
            elem(3, "Security Info Qualifier", ElementDef.DataType.ID, 2, 2, true, Set.of("00", "01")),
            elem(4, "Security Information", ElementDef.DataType.AN, 10, 10, true, null),
            elem(5, "Interchange ID Qualifier", ElementDef.DataType.ID, 2, 2, true, Set.of("01","02","03","04","07","08","09","10","11","12","13","14","15","16","17","18","19","20","27","28","29","30","33","ZZ")),
            elem(6, "Interchange Sender ID", ElementDef.DataType.AN, 15, 15, true, null),
            elem(7, "Interchange ID Qualifier", ElementDef.DataType.ID, 2, 2, true, Set.of("01","02","03","04","07","08","09","10","11","12","13","14","15","16","17","18","19","20","27","28","29","30","33","ZZ")),
            elem(8, "Interchange Receiver ID", ElementDef.DataType.AN, 15, 15, true, null),
            elem(9, "Interchange Date", ElementDef.DataType.DT, 6, 6, true, null),
            elem(10, "Interchange Time", ElementDef.DataType.TM, 4, 4, true, null),
            elem(11, "Repetition Separator", ElementDef.DataType.AN, 1, 1, true, null),
            elem(12, "Interchange Control Version", ElementDef.DataType.ID, 5, 5, true, Set.of("00200","00300","00301","00302","00303","00304","00305","00400","00401","00402","00501","00601","00801")),
            elem(13, "Interchange Control Number", ElementDef.DataType.N0, 9, 9, true, null),
            elem(14, "Acknowledgment Requested", ElementDef.DataType.ID, 1, 1, true, Set.of("0","1")),
            elem(15, "Usage Indicator", ElementDef.DataType.ID, 1, 1, true, Set.of("I","P","T")),
            elem(16, "Component Element Separator", ElementDef.DataType.AN, 1, 1, true, null)
        ), false));

        // === GS - Functional Group Header ===
        DEFINITIONS.put("GS", new SegmentDef("GS", "Functional Group Header", List.of(
            elem(1, "Functional Identifier Code", ElementDef.DataType.ID, 2, 2, true, Set.of("AA","AG","AH","CA","CE","CF","CO","CR","CS","D3","D4","D5","DX","EC","ED","EI","EN","EP","ER","ES","EV","EX","FA","FB","FC","FG","FR","GC","GE","GF","GL","GP","GR","GT","HB","HC","HI","HN","HP","HR","HS","IA","IB","IC","IG","IM","IN","IO","IR","IS","ME","MP","MR","MS","NC","NL","OG","OW","PA","PB","PC","PD","PE","PH","PI","PK","PO","PP","PR","PS","PT","PU","PY","QG","QM","QO","RA","RB","RC","RD","RE","RF","RG","RH","RI","RJ","RK","RL","RM","RN","RO","RP","RQ","RR","RS","RT","RU","RV","RW","RX","RY","RZ","SA","SB","SC","SD","SE","SH","SI","SJ","SL","SM","SN","SO","SP","SQ","SR","SS","ST","SU","SV","SW","TA","TC","TI","TM","TN","TO","TP","TR","TS","TT","TU","TX","UA","UB","UC","UD","UE","UF","UI","UM","UP","UQ","UR","US","UX","VB","VC","VD","VE","VH","VI","VS","WA","WB","WG","WI","WL","WR","WS")),
            elem(2, "Application Sender's Code", ElementDef.DataType.AN, 2, 15, true, null),
            elem(3, "Application Receiver's Code", ElementDef.DataType.AN, 2, 15, true, null),
            elem(4, "Date", ElementDef.DataType.DT, 8, 8, true, null),
            elem(5, "Time", ElementDef.DataType.TM, 4, 8, true, null),
            elem(6, "Group Control Number", ElementDef.DataType.N0, 1, 9, true, null),
            elem(7, "Responsible Agency Code", ElementDef.DataType.ID, 1, 2, true, Set.of("T","X")),
            elem(8, "Version/Release/Industry Code", ElementDef.DataType.AN, 1, 12, true, null)
        ), false));

        // === ST - Transaction Set Header ===
        DEFINITIONS.put("ST", new SegmentDef("ST", "Transaction Set Header", List.of(
            elem(1, "Transaction Set Identifier Code", ElementDef.DataType.ID, 3, 3, true, null),
            elem(2, "Transaction Set Control Number", ElementDef.DataType.AN, 4, 9, true, null),
            elem(3, "Implementation Convention Reference", ElementDef.DataType.AN, 1, 35, false, null)
        ), false));

        // === SE - Transaction Set Trailer ===
        DEFINITIONS.put("SE", new SegmentDef("SE", "Transaction Set Trailer", List.of(
            elem(1, "Number of Included Segments", ElementDef.DataType.N0, 1, 10, true, null),
            elem(2, "Transaction Set Control Number", ElementDef.DataType.AN, 4, 9, true, null)
        ), false));

        // === GE - Functional Group Trailer ===
        DEFINITIONS.put("GE", new SegmentDef("GE", "Functional Group Trailer", List.of(
            elem(1, "Number of Transaction Sets", ElementDef.DataType.N0, 1, 6, true, null),
            elem(2, "Group Control Number", ElementDef.DataType.N0, 1, 9, true, null)
        ), false));

        // === IEA - Interchange Control Trailer ===
        DEFINITIONS.put("IEA", new SegmentDef("IEA", "Interchange Control Trailer", List.of(
            elem(1, "Number of Included Groups", ElementDef.DataType.N0, 1, 5, true, null),
            elem(2, "Interchange Control Number", ElementDef.DataType.N0, 9, 9, true, null)
        ), false));

        // === BEG - Beginning Segment for Purchase Order ===
        DEFINITIONS.put("BEG", new SegmentDef("BEG", "Beginning Segment for Purchase Order", List.of(
            elem(1, "Transaction Set Purpose Code", ElementDef.DataType.ID, 2, 2, true, Set.of("00","01","02","03","04","05","06","07","08","10","11","12","13","14","15","16","17","18","19","20","22","24","25","26","27","28","30","31","32","33","34","35","36","37","38","39","40","41","42","43","44","45","46","47","48","49","50","51","52","53","54","55","56","77","CN","CO","EX","GR","PR","PY","RH","RV","ZZ")),
            elem(2, "Purchase Order Type Code", ElementDef.DataType.ID, 2, 2, true, Set.of("AB","AC","AO","BE","BK","BL","BN","BY","CA","CF","CN","CO","CP","CR","DS","EO","IN","KA","KB","KC","KD","KE","KG","KI","KN","KO","KP","KQ","KR","KS","KT","NE","NP","NS","OS","PR","RA","RC","RE","RL","RN","RO","RP","RQ","RS","SA","SD","SP","SS","ST","SW","TR","UD","UE","US","UT","WO","ZZ")),
            elem(3, "Purchase Order Number", ElementDef.DataType.AN, 1, 22, true, null),
            elem(4, "Release Number", ElementDef.DataType.AN, 1, 30, false, null),
            elem(5, "Date", ElementDef.DataType.DT, 8, 8, false, null)
        ), false));

        // === PO1 - Purchase Order Line Item ===
        DEFINITIONS.put("PO1", new SegmentDef("PO1", "Baseline Item Data", List.of(
            elem(1, "Assigned Identification", ElementDef.DataType.AN, 1, 20, false, null),
            elem(2, "Quantity Ordered", ElementDef.DataType.R, 1, 15, true, null),
            elem(3, "Unit of Measure Code", ElementDef.DataType.ID, 2, 2, true, Set.of("BX","CA","CS","CY","DZ","EA","FT","GA","GR","IN","KG","LB","LT","ME","MO","OZ","PA","PC","PF","PK","PL","PR","PT","QT","RL","SE","SF","SH","SL","SP","TN","TO","TU","UN","YD")),
            elem(4, "Unit Price", ElementDef.DataType.R, 1, 17, false, null),
            elem(5, "Basis of Unit Price Code", ElementDef.DataType.ID, 2, 2, false, null),
            elem(6, "Product/Service ID Qualifier", ElementDef.DataType.ID, 2, 2, false, Set.of("BP","CB","EN","EP","HI","IN","IP","IT","MG","MN","ON","PL","SK","UK","UP","VC","VN","ZZ")),
            elem(7, "Product/Service ID", ElementDef.DataType.AN, 1, 48, false, null)
        ), true));

        // === NM1 - Individual or Organizational Name ===
        DEFINITIONS.put("NM1", new SegmentDef("NM1", "Individual or Organizational Name", List.of(
            elem(1, "Entity Identifier Code", ElementDef.DataType.ID, 2, 3, true, Set.of("03","0B","1P","2B","36","40","41","45","71","72","73","74","77","80","82","85","87","98","DD","DK","DN","DQ","FA","GP","IL","LR","NF","P3","P5","PB","PC","PE","PR","PW","QC","QD","SEL","SG","TL","TT","TTP","Y2")),
            elem(2, "Entity Type Qualifier", ElementDef.DataType.ID, 1, 1, true, Set.of("1","2","3","4","5","6","7","8","D","X")),
            elem(3, "Name Last/Organization Name", ElementDef.DataType.AN, 1, 60, false, null),
            elem(4, "Name First", ElementDef.DataType.AN, 1, 35, false, null),
            elem(5, "Name Middle", ElementDef.DataType.AN, 1, 25, false, null),
            elem(6, "Name Prefix", ElementDef.DataType.AN, 1, 10, false, null),
            elem(7, "Name Suffix", ElementDef.DataType.AN, 1, 10, false, null),
            elem(8, "Identification Code Qualifier", ElementDef.DataType.ID, 1, 2, false, Set.of("24","34","46","FI","MI","PI","SV","XV","XX","ZZ")),
            elem(9, "Identification Code", ElementDef.DataType.AN, 2, 80, false, null)
        ), true));

        // === CLM - Health Claim ===
        DEFINITIONS.put("CLM", new SegmentDef("CLM", "Claim Information", List.of(
            elem(1, "Patient Control Number", ElementDef.DataType.AN, 1, 38, true, null),
            elem(2, "Monetary Amount", ElementDef.DataType.R, 1, 18, true, null),
            elem(3, "Claim Filing Indicator", ElementDef.DataType.ID, 1, 2, false, null),
            elem(4, "Non-institutional Claim Type", ElementDef.DataType.ID, 1, 2, false, null),
            elem(5, "Health Care Service Location", ElementDef.DataType.AN, 1, 15, true, null)
        ), false));

        // === SV1 - Professional Service ===
        DEFINITIONS.put("SV1", new SegmentDef("SV1", "Professional Service", List.of(
            elem(1, "Composite Medical Procedure", ElementDef.DataType.AN, 1, 50, true, null),
            elem(2, "Monetary Amount", ElementDef.DataType.R, 1, 18, true, null),
            elem(3, "Unit of Measure Code", ElementDef.DataType.ID, 2, 2, false, Set.of("DA","MJ","UN")),
            elem(4, "Service Unit Count", ElementDef.DataType.R, 1, 15, false, null),
            elem(5, "Place of Service Code", ElementDef.DataType.ID, 1, 2, false, null)
        ), true));

        // === N1 - Party Identification ===
        DEFINITIONS.put("N1", new SegmentDef("N1", "Party Identification", List.of(
            elem(1, "Entity Identifier Code", ElementDef.DataType.ID, 2, 3, true, Set.of("BT","BY","EN","II","PE","PR","RE","RI","SE","SF","SH","SN","ST","SU","VN")),
            elem(2, "Name", ElementDef.DataType.AN, 1, 60, false, null),
            elem(3, "Identification Code Qualifier", ElementDef.DataType.ID, 1, 2, false, Set.of("1","2","9","24","34","91","92","ZZ")),
            elem(4, "Identification Code", ElementDef.DataType.AN, 2, 80, false, null)
        ), true));

        // === N3 - Address ===
        DEFINITIONS.put("N3", new SegmentDef("N3", "Party Location", List.of(
            elem(1, "Address Information", ElementDef.DataType.AN, 1, 55, true, null),
            elem(2, "Address Information", ElementDef.DataType.AN, 1, 55, false, null)
        ), false));

        // === N4 - Geographic Location ===
        DEFINITIONS.put("N4", new SegmentDef("N4", "Geographic Location", List.of(
            elem(1, "City Name", ElementDef.DataType.AN, 2, 30, false, null),
            elem(2, "State/Province Code", ElementDef.DataType.ID, 2, 2, false, null),
            elem(3, "Postal Code", ElementDef.DataType.ID, 3, 15, false, null),
            elem(4, "Country Code", ElementDef.DataType.ID, 2, 3, false, null)
        ), false));

        // === REF - Reference Identification ===
        DEFINITIONS.put("REF", new SegmentDef("REF", "Reference Identification", List.of(
            elem(1, "Reference ID Qualifier", ElementDef.DataType.ID, 2, 3, true, null),
            elem(2, "Reference Identification", ElementDef.DataType.AN, 1, 50, false, null),
            elem(3, "Description", ElementDef.DataType.AN, 1, 80, false, null)
        ), true));

        // === DTM - Date/Time Reference ===
        DEFINITIONS.put("DTM", new SegmentDef("DTM", "Date/Time Reference", List.of(
            elem(1, "Date/Time Qualifier", ElementDef.DataType.ID, 3, 3, true, null),
            elem(2, "Date", ElementDef.DataType.DT, 8, 8, false, null),
            elem(3, "Time", ElementDef.DataType.TM, 4, 8, false, null)
        ), true));

        // === DTP - Date/Time Period ===
        DEFINITIONS.put("DTP", new SegmentDef("DTP", "Date or Time Period", List.of(
            elem(1, "Date/Time Qualifier", ElementDef.DataType.ID, 3, 3, true, null),
            elem(2, "Date Time Period Format Qualifier", ElementDef.DataType.ID, 2, 3, true, Set.of("D8","RD8","DT","RDT")),
            elem(3, "Date Time Period", ElementDef.DataType.AN, 1, 35, true, null)
        ), true));

        // === BHT - Beginning of Hierarchical Transaction ===
        DEFINITIONS.put("BHT", new SegmentDef("BHT", "Beginning of Hierarchical Transaction", List.of(
            elem(1, "Hierarchical Structure Code", ElementDef.DataType.ID, 4, 4, true, Set.of("0019","0022","0085")),
            elem(2, "Transaction Set Purpose Code", ElementDef.DataType.ID, 2, 2, true, Set.of("00","01","13","18")),
            elem(3, "Reference Identification", ElementDef.DataType.AN, 1, 50, true, null),
            elem(4, "Date", ElementDef.DataType.DT, 8, 8, true, null),
            elem(5, "Time", ElementDef.DataType.TM, 4, 8, false, null),
            elem(6, "Transaction Type Code", ElementDef.DataType.ID, 2, 2, false, Set.of("CH","RP","SU"))
        ), false));

        // === HL - Hierarchical Level ===
        DEFINITIONS.put("HL", new SegmentDef("HL", "Hierarchical Level", List.of(
            elem(1, "Hierarchical ID Number", ElementDef.DataType.AN, 1, 12, true, null),
            elem(2, "Hierarchical Parent ID", ElementDef.DataType.AN, 1, 12, false, null),
            elem(3, "Hierarchical Level Code", ElementDef.DataType.ID, 1, 2, true, Set.of("19","20","21","22","23","24","25","26","27","28","29","30","31","32","33","34","35","36","37","38","39","40","41","42","43","44","45","PT","SS")),
            elem(4, "Hierarchical Child Code", ElementDef.DataType.ID, 1, 1, false, Set.of("0","1"))
        ), true));

        // === CTT - Transaction Totals ===
        DEFINITIONS.put("CTT", new SegmentDef("CTT", "Transaction Totals", List.of(
            elem(1, "Number of Line Items", ElementDef.DataType.N0, 1, 6, true, null),
            elem(2, "Hash Total", ElementDef.DataType.R, 1, 10, false, null)
        ), false));

        // === BIG - Beginning Segment for Invoice ===
        DEFINITIONS.put("BIG", new SegmentDef("BIG", "Beginning Segment for Invoice", List.of(
            elem(1, "Date", ElementDef.DataType.DT, 8, 8, true, null),
            elem(2, "Invoice Number", ElementDef.DataType.AN, 1, 22, true, null),
            elem(3, "Date", ElementDef.DataType.DT, 8, 8, false, null),
            elem(4, "Purchase Order Number", ElementDef.DataType.AN, 1, 22, false, null)
        ), false));

        // === IT1 - Baseline Item Data (Invoice) ===
        DEFINITIONS.put("IT1", new SegmentDef("IT1", "Baseline Item Data (Invoice)", List.of(
            elem(1, "Assigned Identification", ElementDef.DataType.AN, 1, 20, false, null),
            elem(2, "Quantity Invoiced", ElementDef.DataType.R, 1, 10, true, null),
            elem(3, "Unit of Measure Code", ElementDef.DataType.ID, 2, 2, true, null),
            elem(4, "Unit Price", ElementDef.DataType.R, 1, 17, false, null)
        ), true));

        // === TDS - Total Monetary Value Summary ===
        DEFINITIONS.put("TDS", new SegmentDef("TDS", "Total Monetary Value Summary", List.of(
            elem(1, "Amount", ElementDef.DataType.N2, 1, 15, true, null),
            elem(2, "Amount", ElementDef.DataType.N2, 1, 15, false, null)
        ), false));

        // === BSN - Beginning Segment for Ship Notice ===
        DEFINITIONS.put("BSN", new SegmentDef("BSN", "Beginning Segment for Ship Notice", List.of(
            elem(1, "Transaction Set Purpose Code", ElementDef.DataType.ID, 2, 2, true, Set.of("00","01","02","04","05")),
            elem(2, "Shipment Identification", ElementDef.DataType.AN, 2, 30, true, null),
            elem(3, "Date", ElementDef.DataType.DT, 8, 8, true, null),
            elem(4, "Time", ElementDef.DataType.TM, 4, 8, true, null)
        ), false));

        // === BPR - Beginning Segment for Payment Order ===
        DEFINITIONS.put("BPR", new SegmentDef("BPR", "Financial Information", List.of(
            elem(1, "Transaction Handling Code", ElementDef.DataType.ID, 1, 2, true, Set.of("C","D","H","I","P","U","X","Z")),
            elem(2, "Monetary Amount", ElementDef.DataType.R, 1, 18, true, null),
            elem(3, "Credit/Debit Flag Code", ElementDef.DataType.ID, 1, 1, true, Set.of("C","D")),
            elem(4, "Payment Method Code", ElementDef.DataType.ID, 3, 3, true, Set.of("ACH","BOP","CHK","DRA","FED","FWT","NON","SWT","ZZZ"))
        ), false));

        // === CLP - Claim Level Payment (835) ===
        DEFINITIONS.put("CLP", new SegmentDef("CLP", "Claim Payment Information", List.of(
            elem(1, "Patient Control Number", ElementDef.DataType.AN, 1, 38, true, null),
            elem(2, "Claim Status Code", ElementDef.DataType.ID, 1, 2, true, Set.of("1","2","3","4","19","20","21","22","23","25")),
            elem(3, "Monetary Amount (Charge)", ElementDef.DataType.R, 1, 18, true, null),
            elem(4, "Monetary Amount (Payment)", ElementDef.DataType.R, 1, 18, true, null),
            elem(5, "Monetary Amount (Patient Resp)", ElementDef.DataType.R, 1, 18, false, null),
            elem(6, "Claim Filing Indicator", ElementDef.DataType.ID, 1, 2, true, null)
        ), true));

        // === SVC - Service Payment (835) ===
        DEFINITIONS.put("SVC", new SegmentDef("SVC", "Service Payment Information", List.of(
            elem(1, "Composite Medical Procedure", ElementDef.DataType.AN, 1, 50, true, null),
            elem(2, "Monetary Amount (Charge)", ElementDef.DataType.R, 1, 18, true, null),
            elem(3, "Monetary Amount (Payment)", ElementDef.DataType.R, 1, 18, true, null),
            elem(4, "Product/Service ID", ElementDef.DataType.AN, 1, 48, false, null),
            elem(5, "Quantity", ElementDef.DataType.R, 1, 15, false, null)
        ), true));

        // === AK1 - Functional Group Response Header (997) ===
        DEFINITIONS.put("AK1", new SegmentDef("AK1", "Functional Group Response Header", List.of(
            elem(1, "Functional Identifier Code", ElementDef.DataType.ID, 2, 2, true, null),
            elem(2, "Group Control Number", ElementDef.DataType.N0, 1, 9, true, null)
        ), false));

        // === AK9 - Functional Group Response Trailer (997) ===
        DEFINITIONS.put("AK9", new SegmentDef("AK9", "Functional Group Response Trailer", List.of(
            elem(1, "Functional Group Acknowledge Code", ElementDef.DataType.ID, 1, 1, true, Set.of("A","E","M","P","R","W","X")),
            elem(2, "Number of Txn Sets Included", ElementDef.DataType.N0, 1, 6, true, null),
            elem(3, "Number of Txn Sets Received", ElementDef.DataType.N0, 1, 6, true, null),
            elem(4, "Number of Txn Sets Accepted", ElementDef.DataType.N0, 1, 6, true, null)
        ), false));

        // === PER - Administrative Communications Contact ===
        DEFINITIONS.put("PER", new SegmentDef("PER", "Administrative Communications Contact", List.of(
            elem(1, "Contact Function Code", ElementDef.DataType.ID, 2, 2, true, Set.of("IC","IP")),
            elem(2, "Name", ElementDef.DataType.AN, 1, 60, false, null),
            elem(3, "Communication Number Qualifier", ElementDef.DataType.ID, 2, 2, false, Set.of("EM","FX","TE")),
            elem(4, "Communication Number", ElementDef.DataType.AN, 1, 256, false, null)
        ), true));

        // === PID - Product/Item Description ===
        DEFINITIONS.put("PID", new SegmentDef("PID", "Product/Item Description", List.of(
            elem(1, "Item Description Type", ElementDef.DataType.ID, 1, 1, true, Set.of("F","S","X")),
            elem(2, "Product/Process Char Code", ElementDef.DataType.ID, 2, 3, false, null),
            elem(3, "Agency Qualifier Code", ElementDef.DataType.ID, 2, 2, false, null),
            elem(4, "Product Description Code", ElementDef.DataType.AN, 1, 12, false, null),
            elem(5, "Description", ElementDef.DataType.AN, 1, 80, false, null)
        ), true));

        // === INS - Insured Benefit (834) ===
        DEFINITIONS.put("INS", new SegmentDef("INS", "Insured Benefit", List.of(
            elem(1, "Yes/No Condition", ElementDef.DataType.ID, 1, 1, true, Set.of("N","Y")),
            elem(2, "Individual Relationship Code", ElementDef.DataType.ID, 2, 2, true, Set.of("01","18","19","20","21","23","24","29","32","33","34","36","39","40","41","43","53","60","G8")),
            elem(3, "Maintenance Type Code", ElementDef.DataType.ID, 3, 3, true, Set.of("001","002","003","021","024","025","026","030")),
            elem(4, "Maintenance Reason Code", ElementDef.DataType.ID, 2, 5, false, null)
        ), true));

        // === HD - Health Coverage (834) ===
        DEFINITIONS.put("HD", new SegmentDef("HD", "Health Coverage", List.of(
            elem(1, "Maintenance Type Code", ElementDef.DataType.ID, 3, 3, true, Set.of("001","002","003","021","024","025","026","030")),
            elem(2, "Maintenance Reason Code", ElementDef.DataType.ID, 2, 5, false, null),
            elem(3, "Insurance Line Code", ElementDef.DataType.ID, 2, 3, true, Set.of("AG","AH","AJ","AK","DEN","FAC","HE","HLT","LV","MM","PDG","PPO","PRA","PSY","SP","UR","VIS"))
        ), true));

        // === LX - Transaction Set Line Number (835) ===
        DEFINITIONS.put("LX", new SegmentDef("LX", "Transaction Set Line Number", List.of(
            elem(1, "Assigned Number", ElementDef.DataType.N0, 1, 6, true, null)
        ), true));

        // === AMT - Monetary Amount ===
        DEFINITIONS.put("AMT", new SegmentDef("AMT", "Monetary Amount Information", List.of(
            elem(1, "Amount Qualifier Code", ElementDef.DataType.ID, 1, 3, true, null),
            elem(2, "Monetary Amount", ElementDef.DataType.R, 1, 18, true, null)
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
