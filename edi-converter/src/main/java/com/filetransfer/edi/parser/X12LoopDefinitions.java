package com.filetransfer.edi.parser;

import lombok.*;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Static loop definitions for common X12 transaction sets.
 * Maps trigger segments to loop identifiers for structural analysis.
 */
@Component
public class X12LoopDefinitions {

    @Data @AllArgsConstructor
    public static class LoopDef {
        private String loopId;
        private String triggerSegment;
        private int level;
        private String parentLoopId;
    }

    private static final Map<String, List<LoopDef>> LOOP_DEFS = new HashMap<>();

    static {
        // 837 - Health Care Claim
        LOOP_DEFS.put("837", List.of(
                new LoopDef("1000A", "NM1", 1, null),     // Submitter
                new LoopDef("1000B", "NM1", 1, null),     // Receiver
                new LoopDef("2000A", "HL", 1, null),      // Billing Provider HL
                new LoopDef("2010AA", "NM1", 2, "2000A"), // Billing Provider Name
                new LoopDef("2010AB", "N3", 2, "2000A"),  // Billing Provider Address
                new LoopDef("2000B", "HL", 1, null),      // Subscriber HL
                new LoopDef("2010BA", "NM1", 2, "2000B"), // Subscriber Name
                new LoopDef("2010BB", "NM1", 2, "2000B"), // Payer Name
                new LoopDef("2300", "CLM", 2, "2000B"),   // Claim Info
                new LoopDef("2400", "SV1", 3, "2300"),    // Service Line
                new LoopDef("2420A", "NM1", 4, "2400")    // Rendering Provider
        ));

        // 835 - Health Care Payment
        LOOP_DEFS.put("835", List.of(
                new LoopDef("1000A", "N1", 1, null),      // Payer Identification
                new LoopDef("1000B", "N1", 1, null),      // Payee Identification
                new LoopDef("2000", "LX", 1, null),       // Header Number
                new LoopDef("2100", "CLP", 2, "2000"),    // Claim Payment Info
                new LoopDef("2110", "SVC", 3, "2100")     // Service Payment Info
        ));

        // 850 - Purchase Order
        LOOP_DEFS.put("850", List.of(
                new LoopDef("HEADER", "BEG", 0, null),
                new LoopDef("N1", "N1", 1, null),         // Party Identification
                new LoopDef("PO1", "PO1", 1, null),       // Line Item
                new LoopDef("PID", "PID", 2, "PO1"),      // Product Description
                new LoopDef("CTT", "CTT", 0, null)        // Transaction Totals
        ));

        // 856 - Ship Notice
        LOOP_DEFS.put("856", List.of(
                new LoopDef("HL_SHIPMENT", "HL", 1, null),
                new LoopDef("HL_ORDER", "HL", 2, "HL_SHIPMENT"),
                new LoopDef("HL_ITEM", "HL", 3, "HL_ORDER"),
                new LoopDef("SN1", "SN1", 4, "HL_ITEM")   // Item Detail
        ));

        // 810 - Invoice
        LOOP_DEFS.put("810", List.of(
                new LoopDef("N1", "N1", 1, null),
                new LoopDef("IT1", "IT1", 1, null),       // Line Item
                new LoopDef("PID", "PID", 2, "IT1"),
                new LoopDef("TDS", "TDS", 0, null)        // Total Monetary
        ));

        // 820 - Payment Order
        LOOP_DEFS.put("820", List.of(
                new LoopDef("N1", "N1", 1, null),
                new LoopDef("ENT", "ENT", 1, null),       // Entity
                new LoopDef("RMR", "RMR", 2, "ENT")       // Remittance Detail
        ));

        // 270 - Eligibility Inquiry
        LOOP_DEFS.put("270", List.of(
                new LoopDef("2000A", "HL", 1, null),      // Info Source
                new LoopDef("2100A", "NM1", 2, "2000A"),
                new LoopDef("2000B", "HL", 1, null),      // Info Receiver
                new LoopDef("2100B", "NM1", 2, "2000B"),
                new LoopDef("2000C", "HL", 1, null),      // Subscriber
                new LoopDef("2100C", "NM1", 2, "2000C"),
                new LoopDef("2110C", "EQ", 3, "2000C")    // Eligibility Inquiry
        ));

        // 271 - Eligibility Response
        LOOP_DEFS.put("271", List.of(
                new LoopDef("2000A", "HL", 1, null),
                new LoopDef("2100A", "NM1", 2, "2000A"),
                new LoopDef("2000B", "HL", 1, null),
                new LoopDef("2100B", "NM1", 2, "2000B"),
                new LoopDef("2000C", "HL", 1, null),
                new LoopDef("2100C", "NM1", 2, "2000C"),
                new LoopDef("2110C", "EB", 3, "2000C")
        ));

        // 997 - Functional Acknowledgment
        LOOP_DEFS.put("997", List.of(
                new LoopDef("AK2", "AK2", 1, null),       // Transaction Set Response
                new LoopDef("AK3", "AK3", 2, "AK2"),      // Data Segment Note
                new LoopDef("AK4", "AK4", 3, "AK3")       // Data Element Note
        ));

        // 834 - Benefit Enrollment
        LOOP_DEFS.put("834", List.of(
                new LoopDef("1000A", "N1", 1, null),      // Sponsor Name
                new LoopDef("1000B", "N1", 1, null),      // Payer
                new LoopDef("2000", "INS", 1, null),      // Member Level Detail
                new LoopDef("2100A", "NM1", 2, "2000"),   // Member Name
                new LoopDef("2300", "HD", 2, "2000")       // Health Coverage
        ));
    }

    public List<LoopDef> getLoopDefs(String transactionType) {
        return LOOP_DEFS.getOrDefault(transactionType, List.of());
    }

    public boolean hasDefinitions(String transactionType) {
        return LOOP_DEFS.containsKey(transactionType);
    }
}
