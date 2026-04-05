package com.filetransfer.edi.converter;

import com.filetransfer.edi.model.EdiDocument;
import lombok.*;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Makes EDI human-readable. Translates every segment into plain English.
 *
 * Before: ISA*00*          *00*          *ZZ*SENDER*ZZ*RECEIVER*210101*1253*^*00501*1*0*P*:~
 * After:  "This is the start of an EDI message. Sent by SENDER to RECEIVER on Jan 1, 2021 at 12:53."
 *
 * Before: CLM*CLAIM001*1500*11:B:1~
 * After:  "A healthcare claim (ID: CLAIM001) for $1,500.00. Service location: Hospital Inpatient."
 */
@Service
public class EdiExplainer {

    // X12 segment explanations
    private static final Map<String, SegmentExplainer> X12_EXPLAINERS = new LinkedHashMap<>();
    static {
        X12_EXPLAINERS.put("ISA", (elems) -> {
            String sender = elems.size() > 5 ? elems.get(5).trim() : "?";
            String receiver = elems.size() > 7 ? elems.get(7).trim() : "?";
            String date = elems.size() > 8 ? formatDate(elems.get(8)) : "?";
            return "📨 **Message envelope** — Sent by **" + sender + "** to **" + receiver + "** on " + date;
        });
        X12_EXPLAINERS.put("GS", (elems) -> {
            String type = elems.size() > 0 ? elems.get(0) : "?";
            String typeName = switch (type) {
                case "HP" -> "Health Care Claim/Payment"; case "PO" -> "Purchase Order";
                case "IN" -> "Invoice"; case "SH" -> "Ship Notice"; default -> type;
            };
            return "📋 **Group header** — This is a " + typeName + " transaction group";
        });
        X12_EXPLAINERS.put("ST", (elems) -> {
            String type = elems.size() > 0 ? elems.get(0) : "?";
            String name = switch (type) {
                case "837" -> "Healthcare Claim"; case "835" -> "Payment/Remittance";
                case "850" -> "Purchase Order"; case "856" -> "Shipping Notice";
                case "810" -> "Invoice"; case "270" -> "Eligibility Question";
                case "271" -> "Eligibility Answer"; case "997" -> "Receipt Confirmation";
                default -> "Transaction " + type;
            };
            return "📄 **" + name + "** starts here (type " + type + ")";
        });
        X12_EXPLAINERS.put("BHT", (elems) -> {
            String purpose = elems.size() > 1 ? elems.get(1) : "";
            String purposeName = switch (purpose) {
                case "00" -> "Original submission"; case "18" -> "Re-submission";
                case "01" -> "Cancellation"; default -> purpose;
            };
            String refNum = elems.size() > 2 ? elems.get(2) : "?";
            return "🔖 **Transaction info** — " + purposeName + " (reference: " + refNum + ")";
        });
        X12_EXPLAINERS.put("NM1", (elems) -> {
            String qualifier = elems.size() > 0 ? elems.get(0) : "";
            String role = switch (qualifier) {
                case "IL" -> "Patient"; case "40" -> "Provider"; case "41" -> "Submitter";
                case "85" -> "Billing Provider"; case "87" -> "Pay-to Provider";
                case "BY" -> "Buyer"; case "SE" -> "Seller"; case "ST" -> "Ship To";
                default -> "Entity (" + qualifier + ")";
            };
            String lastName = elems.size() > 2 ? elems.get(2) : "";
            String firstName = elems.size() > 3 ? elems.get(3) : "";
            String name = firstName.isBlank() ? lastName : firstName + " " + lastName;
            return "👤 **" + role + "** — " + (name.isBlank() ? "(not specified)" : name);
        });
        X12_EXPLAINERS.put("CLM", (elems) -> {
            String claimId = elems.size() > 0 ? elems.get(0) : "?";
            String amount = elems.size() > 1 ? elems.get(1) : "?";
            return "💰 **Claim** — ID: " + claimId + ", Amount: $" + amount;
        });
        X12_EXPLAINERS.put("SV1", (elems) -> "💊 **Service line** — medical procedure/service detail");
        X12_EXPLAINERS.put("DTP", (elems) -> {
            String qualifier = elems.size() > 0 ? elems.get(0) : "";
            String dateName = switch (qualifier) {
                case "472" -> "Service date"; case "232" -> "Claim statement start";
                case "233" -> "Claim statement end"; case "096" -> "Discharge date";
                default -> "Date (" + qualifier + ")";
            };
            String date = elems.size() > 2 ? formatDate(elems.get(2)) : "?";
            return "📅 **" + dateName + "** — " + date;
        });
        X12_EXPLAINERS.put("REF", (elems) -> {
            String qual = elems.size() > 0 ? elems.get(0) : "";
            String refName = switch (qual) {
                case "EI" -> "Tax ID"; case "SY" -> "SSN"; case "1J" -> "Facility Code";
                case "BT" -> "Batch Number"; case "D9" -> "Claim Number";
                default -> "Reference (" + qual + ")";
            };
            String value = elems.size() > 1 ? elems.get(1) : "?";
            return "🏷️ **" + refName + "** — " + value;
        });
        X12_EXPLAINERS.put("SE", (elems) -> {
            String count = elems.size() > 0 ? elems.get(0) : "?";
            return "✅ **End of transaction** — " + count + " segments total";
        });
        X12_EXPLAINERS.put("GE", (elems) -> "✅ **End of group**");
        X12_EXPLAINERS.put("IEA", (elems) -> "✅ **End of message** — transmission complete");
        X12_EXPLAINERS.put("N3", (elems) -> "🏠 **Address** — " + (elems.size() > 0 ? elems.get(0) : ""));
        X12_EXPLAINERS.put("N4", (elems) -> "🏙️ **City/State/Zip** — " + String.join(", ", elems));
        X12_EXPLAINERS.put("PER", (elems) -> "📞 **Contact info** — " + String.join(" ", elems));
        X12_EXPLAINERS.put("BIG", (elems) -> "🧾 **Invoice** — date: " + (elems.size() > 0 ? formatDate(elems.get(0)) : "?") + ", number: " + (elems.size() > 1 ? elems.get(1) : "?"));
        X12_EXPLAINERS.put("PO1", (elems) -> "📦 **Order line** — quantity: " + (elems.size() > 1 ? elems.get(1) : "?") + ", price: $" + (elems.size() > 3 ? elems.get(3) : "?"));
        X12_EXPLAINERS.put("CTT", (elems) -> "📊 **Summary** — " + (elems.size() > 0 ? elems.get(0) : "?") + " line items");
        X12_EXPLAINERS.put("BSN", (elems) -> "🚚 **Shipment notice** — shipment " + (elems.size() > 1 ? elems.get(1) : "?") + " on " + (elems.size() > 2 ? formatDate(elems.get(2)) : "?"));
    }

    public ExplainedDocument explain(EdiDocument doc) {
        List<ExplainedSegment> explained = new ArrayList<>();
        int segNum = 0;

        // Document summary
        String summary = switch (doc.getSourceFormat()) {
            case "X12" -> "This is an **ANSI X12 " + doc.getDocumentType() + "** — " + doc.getDocumentName() + ".\nThink of it like a structured form sent between business computers.";
            case "EDIFACT" -> "This is a **UN/EDIFACT " + doc.getDocumentType() + "** — " + doc.getDocumentName() + ".\nUsed internationally for business-to-business communication.";
            case "HL7" -> "This is an **HL7 " + doc.getDocumentType() + "** message — used in healthcare to share patient information between hospital systems.";
            case "SWIFT_MT" -> "This is a **SWIFT " + doc.getDocumentType() + "** — a banking message used to transfer money between banks worldwide.";
            case "FIX" -> "This is a **FIX " + doc.getDocumentType() + "** — " + doc.getDocumentName() + ". Used in stock trading.";
            case "NACHA" -> "This is a **NACHA ACH file** — used to process electronic payments (direct deposits, bill payments) in the US banking system.";
            case "BAI2" -> "This is a **BAI2 file** — a bank statement that shows account balances and transactions.";
            default -> "This is a " + doc.getSourceFormat() + " " + doc.getDocumentType() + " document.";
        };

        for (EdiDocument.Segment seg : doc.getSegments()) {
            segNum++;
            String explanation;

            if ("X12".equals(doc.getSourceFormat()) && X12_EXPLAINERS.containsKey(seg.getId())) {
                explanation = X12_EXPLAINERS.get(seg.getId()).explain(seg.getElements() != null ? seg.getElements() : List.of());
            } else {
                // Generic explanation
                explanation = "**" + seg.getId() + "** segment" +
                        (seg.getElements() != null && !seg.getElements().isEmpty()
                                ? " with " + seg.getElements().size() + " data fields" : "");
            }

            explained.add(ExplainedSegment.builder()
                    .segmentNumber(segNum).segmentId(seg.getId())
                    .rawContent(seg.getId() + (seg.getElements() != null ? "*" + String.join("*", seg.getElements()) : ""))
                    .explanation(explanation)
                    .elements(seg.getElements())
                    .build());
        }

        return ExplainedDocument.builder()
                .sourceFormat(doc.getSourceFormat())
                .documentType(doc.getDocumentType())
                .documentName(doc.getDocumentName())
                .summary(summary)
                .totalSegments(explained.size())
                .segments(explained)
                .tip(generateTip(doc))
                .build();
    }

    private String generateTip(EdiDocument doc) {
        if ("X12".equals(doc.getSourceFormat())) {
            return "💡 **Tip:** X12 uses * as a field separator and ~ as a segment terminator. " +
                    "Each line starting with a 2-3 letter code (like ISA, ST, CLM) is a different type of data. " +
                    "Think of it like rows in a spreadsheet where the first column tells you what kind of row it is.";
        }
        if ("EDIFACT".equals(doc.getSourceFormat())) {
            return "💡 **Tip:** EDIFACT uses + as field separator and ' as segment terminator. It's the international version of X12.";
        }
        return "💡 **Tip:** EDI files look scary but they're just structured data — like a spreadsheet in a different format.";
    }

    private static String formatDate(String raw) {
        if (raw == null || raw.length() < 6) return raw;
        try {
            String yr = raw.substring(0, 2); String mo = raw.substring(2, 4); String dy = raw.substring(4, 6);
            String month = switch (mo) {
                case "01" -> "Jan"; case "02" -> "Feb"; case "03" -> "Mar"; case "04" -> "Apr";
                case "05" -> "May"; case "06" -> "Jun"; case "07" -> "Jul"; case "08" -> "Aug";
                case "09" -> "Sep"; case "10" -> "Oct"; case "11" -> "Nov"; case "12" -> "Dec";
                default -> mo;
            };
            return month + " " + dy + ", 20" + yr;
        } catch (Exception e) { return raw; }
    }

    @FunctionalInterface
    interface SegmentExplainer { String explain(List<String> elements); }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class ExplainedDocument {
        private String sourceFormat;
        private String documentType;
        private String documentName;
        private String summary;
        private int totalSegments;
        private List<ExplainedSegment> segments;
        private String tip;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class ExplainedSegment {
        private int segmentNumber;
        private String segmentId;
        private String rawContent;
        private String explanation;
        private List<String> elements;
    }
}
