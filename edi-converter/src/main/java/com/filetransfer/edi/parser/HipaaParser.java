package com.filetransfer.edi.parser;

import com.filetransfer.edi.model.EdiDocument;
import com.filetransfer.edi.model.EdiDocument.Segment;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Pattern;

/**
 * HIPAA document type specializer for X12 healthcare transactions.
 *
 * HIPAA uses standard X12 format but requires specific document type detection
 * to distinguish between:
 *   837P  — Professional Health Care Claim (005010X222A1)
 *   837I  — Institutional Health Care Claim (005010X223A2)
 *   837D  — Dental Health Care Claim (005010X224A2)
 *   835   — Health Care Claim Payment/Remittance Advice
 *   270   — Eligibility, Coverage, or Benefit Inquiry
 *   271   — Eligibility, Coverage, or Benefit Response
 *   276   — Health Care Claim Status Request
 *   277   — Health Care Claim Status Response
 *   278   — Health Care Services Review
 *   834   — Benefit Enrollment and Maintenance
 *   820   — Payroll Deducted/Other Group Premium Payment
 *
 * This parser takes an already-parsed X12 EdiDocument and enriches it
 * with HIPAA-specific document type and business data.
 */
@Component
@Slf4j
public class HipaaParser {

    private static final Map<String, String> HIPAA_TX_TYPES = Map.ofEntries(
            Map.entry("837", "Health Care Claim"),
            Map.entry("835", "Health Care Claim Payment/Remittance Advice"),
            Map.entry("270", "Eligibility, Coverage, or Benefit Inquiry"),
            Map.entry("271", "Eligibility, Coverage, or Benefit Response"),
            Map.entry("276", "Health Care Claim Status Request"),
            Map.entry("277", "Health Care Claim Status Response"),
            Map.entry("278", "Health Care Services Review"),
            Map.entry("834", "Benefit Enrollment and Maintenance"),
            Map.entry("820", "Payroll Deducted/Other Group Premium Payment")
    );

    private static final Map<String, String> VERSION_TO_SUBTYPE = Map.of(
            "005010X222A1", "837P",
            "005010X222", "837P",
            "005010X223A2", "837I",
            "005010X223A3", "837I",
            "005010X223", "837I",
            "005010X224A2", "837D",
            "005010X224", "837D"
    );

    /**
     * Detect the HIPAA document type from raw X12 content.
     * Returns a string like "X12_837P", "X12_835", "X12_270", etc.
     * Returns null if the content is not a recognized HIPAA transaction.
     */
    public String detectDocumentType(String content) {
        if (content == null || content.isEmpty()) return null;

        // Find ST segment to get transaction set code
        String txnSetCode = extractStTransactionSet(content);
        if (txnSetCode == null) return null;

        // Check if it's a HIPAA transaction type
        if (!HIPAA_TX_TYPES.containsKey(txnSetCode)) return null;

        // For 837, differentiate by GS08 version identifier
        if ("837".equals(txnSetCode)) {
            String gsVersion = extractGsVersion(content);
            if (gsVersion != null) {
                String subtype = VERSION_TO_SUBTYPE.get(gsVersion);
                if (subtype != null) {
                    return "X12_" + subtype;
                }
            }
            // Check CLM segment for claim type hints
            String claimSubtype = detectClaimSubtypeFromContent(content);
            if (claimSubtype != null) {
                return "X12_837" + claimSubtype;
            }
            // Default to generic 837 if we can't determine subtype
            return "X12_837";
        }

        return "X12_" + txnSetCode;
    }

    /**
     * Enrich an already-parsed X12 EdiDocument with HIPAA-specific fields.
     * This adds documentType refinement and HIPAA business data extraction.
     */
    public EdiDocument enrich(EdiDocument doc) {
        if (doc == null || !"X12".equals(doc.getSourceFormat())) return doc;

        String txnSetCode = doc.getDocumentType();
        if (txnSetCode == null || !HIPAA_TX_TYPES.containsKey(txnSetCode)) return doc;

        Map<String, Object> biz = doc.getBusinessData() != null
                ? new LinkedHashMap<>(doc.getBusinessData())
                : new LinkedHashMap<>();

        String hipaaType = txnSetCode;
        String docName = HIPAA_TX_TYPES.get(txnSetCode);

        // For 837, refine to 837P/837I/837D
        if ("837".equals(txnSetCode)) {
            String gsVersion = findGsVersionFromSegments(doc.getSegments());
            if (gsVersion != null) {
                String subtype = VERSION_TO_SUBTYPE.get(gsVersion);
                if (subtype != null) {
                    hipaaType = subtype;
                    docName = switch (subtype) {
                        case "837P" -> "HIPAA Professional Claim";
                        case "837I" -> "HIPAA Institutional Claim";
                        case "837D" -> "HIPAA Dental Claim";
                        default -> docName;
                    };
                }
            }
            biz.put("hipaaType", hipaaType);
        }

        // Extract HIPAA-specific business data from segments
        enrichBusinessData(txnSetCode, doc.getSegments(), biz);

        return EdiDocument.builder()
                .sourceFormat(doc.getSourceFormat())
                .documentType(hipaaType)
                .documentName("HIPAA " + docName)
                .version(doc.getVersion())
                .senderId(doc.getSenderId())
                .receiverId(doc.getReceiverId())
                .documentDate(doc.getDocumentDate())
                .controlNumber(doc.getControlNumber())
                .segments(doc.getSegments())
                .rawContent(doc.getRawContent())
                .businessData(biz)
                .delimiterInfo(doc.getDelimiterInfo())
                .parseErrors(doc.getParseErrors())
                .parseWarnings(doc.getParseWarnings())
                .loops(doc.getLoops())
                .build();
    }

    /**
     * Check if the given X12 content is a HIPAA transaction.
     */
    public boolean isHipaa(String content) {
        String txnSetCode = extractStTransactionSet(content);
        return txnSetCode != null && HIPAA_TX_TYPES.containsKey(txnSetCode);
    }

    /**
     * Check if the given X12 EdiDocument is a HIPAA transaction.
     */
    public boolean isHipaa(EdiDocument doc) {
        return doc != null && "X12".equals(doc.getSourceFormat())
                && doc.getDocumentType() != null
                && HIPAA_TX_TYPES.containsKey(doc.getDocumentType());
    }

    // --- Internal helpers ---

    private String extractStTransactionSet(String content) {
        // Find ST segment — works with any element separator
        int stPos = content.indexOf("ST");
        while (stPos >= 0) {
            if (stPos + 3 < content.length() && !Character.isLetterOrDigit(content.charAt(stPos + 2))) {
                char sep = content.charAt(stPos + 2);
                int endPos = content.indexOf(String.valueOf(sep), stPos + 3);
                if (endPos < 0) endPos = content.length();
                String txnCode = content.substring(stPos + 3, endPos).trim();
                if (txnCode.matches("\\d{3}")) {
                    return txnCode;
                }
            }
            stPos = content.indexOf("ST", stPos + 1);
        }
        return null;
    }

    private String extractGsVersion(String content) {
        // Find GS segment and extract element 8 (version/release/industry identifier)
        int gsPos = content.indexOf("GS");
        if (gsPos < 0) return null;

        // Detect element separator from ISA or from the character after GS
        char sep = '*'; // default
        if (gsPos + 2 < content.length() && !Character.isLetterOrDigit(content.charAt(gsPos + 2))) {
            sep = content.charAt(gsPos + 2);
        }

        String fromGs = content.substring(gsPos);
        // Find segment terminator
        String sepQ = Pattern.quote(String.valueOf(sep));
        String[] elements = fromGs.split(sepQ, -1);
        // GS08 is the 9th field (index 8)
        if (elements.length > 8) {
            // Clean up — might include segment terminator at the end
            String version = elements[8].replaceAll("[^a-zA-Z0-9]", "");
            return version.isEmpty() ? null : version;
        }
        return null;
    }

    private String findGsVersionFromSegments(List<Segment> segments) {
        if (segments == null) return null;
        for (Segment seg : segments) {
            if ("GS".equals(seg.getId()) && seg.getElements() != null && seg.getElements().size() > 7) {
                return seg.getElements().get(7).trim();
            }
        }
        return null;
    }

    private String detectClaimSubtypeFromContent(String content) {
        // Check for SV1 (professional) or SV2 (institutional) segments
        if (content.contains("SV1")) return "P";
        if (content.contains("SV2")) return "I";
        if (content.contains("SV3")) return "D"; // dental
        return null;
    }

    private void enrichBusinessData(String txnSetCode, List<Segment> segments, Map<String, Object> biz) {
        if (segments == null) return;

        biz.put("hipaaTransactionType", txnSetCode);
        biz.put("hipaaTransactionName", HIPAA_TX_TYPES.getOrDefault(txnSetCode, "Unknown"));

        for (Segment seg : segments) {
            if (seg.getElements() == null) continue;

            switch (seg.getId()) {
                case "BHT" -> {
                    // Begin Hierarchical Transaction
                    if (seg.getElements().size() > 0) biz.put("hierarchicalStructureCode", seg.getElements().get(0));
                    if (seg.getElements().size() > 1) biz.put("transactionSetPurpose", seg.getElements().get(1));
                    if (seg.getElements().size() > 2) biz.put("referenceId", seg.getElements().get(2));
                    if (seg.getElements().size() > 3) biz.put("transactionDate", seg.getElements().get(3));
                    if (seg.getElements().size() > 4) biz.put("transactionTime", seg.getElements().get(4));
                    if (seg.getElements().size() > 5) biz.put("transactionTypeCode", seg.getElements().get(5));
                }
                case "CLM" -> {
                    // Health Care Claim
                    if (seg.getElements().size() > 0) biz.put("patientControlNumber", seg.getElements().get(0));
                    if (seg.getElements().size() > 1) biz.put("totalClaimCharge", seg.getElements().get(1));
                    if (seg.getElements().size() > 4) biz.put("facilityCodeValue", seg.getElements().get(4));
                }
                case "SBR" -> {
                    // Subscriber Information
                    if (seg.getElements().size() > 0) biz.put("payerResponsibilitySequence", seg.getElements().get(0));
                    if (seg.getElements().size() > 2) biz.put("insuredGroupNumber", seg.getElements().get(2));
                    if (seg.getElements().size() > 8) biz.put("claimFilingIndicator", seg.getElements().get(8));
                }
                case "DTP" -> {
                    // Date/Time Period
                    if (seg.getElements().size() > 1) {
                        String qualifier = seg.getElements().get(0);
                        String dateValue = seg.getElements().size() > 2 ? seg.getElements().get(2) : "";
                        biz.put("date_" + qualifier, dateValue);
                    }
                }
                case "CLP" -> {
                    // Claim Payment (835)
                    if (seg.getElements().size() > 0) biz.put("claimId", seg.getElements().get(0));
                    if (seg.getElements().size() > 1) biz.put("claimStatus", seg.getElements().get(1));
                    if (seg.getElements().size() > 2) biz.put("totalChargeAmount", seg.getElements().get(2));
                    if (seg.getElements().size() > 3) biz.put("paymentAmount", seg.getElements().get(3));
                }
                case "EB" -> {
                    // Eligibility/Benefit Information (271)
                    if (seg.getElements().size() > 0) biz.put("eligibilityCode", seg.getElements().get(0));
                    if (seg.getElements().size() > 1) biz.put("coverageLevel", seg.getElements().get(1));
                    if (seg.getElements().size() > 2) biz.put("serviceTypeCode", seg.getElements().get(2));
                }
            }
        }
    }
}
