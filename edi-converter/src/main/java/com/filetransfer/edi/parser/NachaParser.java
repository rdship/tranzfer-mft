package com.filetransfer.edi.parser;

import com.filetransfer.edi.model.EdiDocument;
import com.filetransfer.edi.model.EdiDocument.Segment;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Dedicated NACHA/ACH parser with full fixed-width field extraction.
 *
 * NACHA records are exactly 94 characters wide. Record types:
 *   1 — File Header
 *   5 — Batch Header
 *   6 — Entry Detail
 *   7 — Addenda
 *   8 — Batch Control
 *   9 — File Control
 *
 * Each record is parsed by position into named fields.
 */
@Component
@Slf4j
public class NachaParser {

    private static final int RECORD_LENGTH = 94;

    private static final Map<String, String> TRANSACTION_CODES = Map.ofEntries(
            Map.entry("22", "Checking Credit (Deposit)"),
            Map.entry("23", "Checking Credit Prenote"),
            Map.entry("27", "Checking Debit (Payment)"),
            Map.entry("28", "Checking Debit Prenote"),
            Map.entry("32", "Savings Credit (Deposit)"),
            Map.entry("33", "Savings Credit Prenote"),
            Map.entry("37", "Savings Debit (Payment)"),
            Map.entry("38", "Savings Debit Prenote")
    );

    private static final Map<String, String> SEC_CODES = Map.of(
            "PPD", "Prearranged Payment/Deposit",
            "CCD", "Corporate Credit/Debit",
            "CTX", "Corporate Trade Exchange",
            "WEB", "Internet-Initiated Entry",
            "TEL", "Telephone-Initiated Entry",
            "RCK", "Re-presented Check",
            "ARC", "Accounts Receivable Conversion",
            "IAT", "International ACH Transaction"
    );

    public EdiDocument parse(String content) {
        List<Segment> segments = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Map<String, Object> biz = new LinkedHashMap<>();

        String immediateOrigin = null, immediateDestination = null;
        String fileCreationDate = null, controlNum = null;
        int batchCount = 0, entryCount = 0;
        long totalDebitAmount = 0, totalCreditAmount = 0;
        List<Map<String, String>> entries = new ArrayList<>();
        int segCount = 0;

        for (String line : content.split("\\r?\\n")) {
            if (line.isEmpty()) continue;
            // Pad line to 94 chars if short (some systems trim trailing spaces)
            String record = line.length() < RECORD_LENGTH
                    ? String.format("%-" + RECORD_LENGTH + "s", line)
                    : line.substring(0, Math.min(line.length(), RECORD_LENGTH));
            segCount++;

            try {
                String recType = record.substring(0, 1);
                switch (recType) {
                    case "1" -> {
                        // File Header Record
                        Map<String, String> named = new LinkedHashMap<>();
                        named.put("recordType", "1");
                        named.put("priorityCode", safe(record, 1, 3));
                        immediateDestination = safe(record, 3, 13).trim();
                        immediateOrigin = safe(record, 13, 23).trim();
                        fileCreationDate = safe(record, 23, 29).trim();
                        String fileCreationTime = safe(record, 29, 33).trim();
                        String fileIdModifier = safe(record, 33, 34);
                        String recordSize = safe(record, 34, 37);
                        String blockingFactor = safe(record, 37, 39);
                        String formatCode = safe(record, 39, 40);
                        String destName = safe(record, 40, 63).trim();
                        String originName = safe(record, 63, 86).trim();
                        controlNum = safe(record, 86, 94).trim();

                        named.put("immediateDestination", immediateDestination);
                        named.put("immediateOrigin", immediateOrigin);
                        named.put("fileCreationDate", fileCreationDate);
                        named.put("fileCreationTime", fileCreationTime);
                        named.put("fileIdModifier", fileIdModifier);
                        named.put("recordSize", recordSize);
                        named.put("blockingFactor", blockingFactor);
                        named.put("formatCode", formatCode);
                        named.put("destinationName", destName);
                        named.put("originName", originName);
                        named.put("referenceCode", controlNum);

                        segments.add(Segment.builder().id("FILE_HEADER")
                                .elements(List.of(record)).namedFields(named).build());

                        biz.put("immediateDestination", immediateDestination);
                        biz.put("immediateOrigin", immediateOrigin);
                        biz.put("destinationName", destName);
                        biz.put("originName", originName);
                        biz.put("fileCreationDate", fileCreationDate);
                    }
                    case "5" -> {
                        // Batch Header Record
                        batchCount++;
                        Map<String, String> named = new LinkedHashMap<>();
                        named.put("recordType", "5");
                        named.put("serviceClassCode", safe(record, 1, 4));
                        named.put("companyName", safe(record, 4, 20).trim());
                        named.put("companyDiscretionary", safe(record, 20, 40).trim());
                        named.put("companyIdentification", safe(record, 40, 50).trim());
                        String secCode = safe(record, 50, 53).trim();
                        named.put("standardEntryClass", secCode);
                        named.put("standardEntryClassDesc",
                                SEC_CODES.getOrDefault(secCode, secCode));
                        named.put("companyEntryDescription", safe(record, 53, 63).trim());
                        named.put("companyDescriptiveDate", safe(record, 63, 69).trim());
                        named.put("effectiveEntryDate", safe(record, 69, 75).trim());
                        named.put("settlementDate", safe(record, 75, 78).trim());
                        named.put("originatorStatusCode", safe(record, 78, 79));
                        named.put("originatingDFI", safe(record, 79, 87).trim());
                        named.put("batchNumber", safe(record, 87, 94).trim());

                        segments.add(Segment.builder().id("BATCH_HEADER")
                                .elements(List.of(record)).namedFields(named).build());

                        biz.put("standardEntryClass", secCode);
                        biz.put("companyName", named.get("companyName"));
                    }
                    case "6" -> {
                        // Entry Detail Record
                        entryCount++;
                        Map<String, String> named = new LinkedHashMap<>();
                        named.put("recordType", "6");
                        String txnCode = safe(record, 1, 3);
                        named.put("transactionCode", txnCode);
                        named.put("transactionDescription",
                                TRANSACTION_CODES.getOrDefault(txnCode, "Unknown (" + txnCode + ")"));
                        named.put("receivingDFI", safe(record, 3, 11).trim());
                        named.put("checkDigit", safe(record, 11, 12));
                        named.put("dfiAccountNumber", safe(record, 12, 29).trim());
                        String amountStr = safe(record, 29, 39).trim();
                        named.put("amount", amountStr);
                        named.put("individualId", safe(record, 39, 54).trim());
                        named.put("individualName", safe(record, 54, 76).trim());
                        named.put("discretionaryData", safe(record, 76, 78).trim());
                        named.put("addendaIndicator", safe(record, 78, 79));
                        named.put("traceNumber", safe(record, 79, 94).trim());

                        // Parse amount
                        try {
                            long amountCents = Long.parseLong(amountStr);
                            named.put("amountFormatted",
                                    String.format("%.2f", amountCents / 100.0));
                            if (txnCode.startsWith("2") && txnCode.charAt(1) >= '2' && txnCode.charAt(1) <= '3') {
                                totalCreditAmount += amountCents;
                            } else if (txnCode.startsWith("2") && txnCode.charAt(1) >= '7' && txnCode.charAt(1) <= '8') {
                                totalDebitAmount += amountCents;
                            } else if (txnCode.startsWith("3") && txnCode.charAt(1) >= '2' && txnCode.charAt(1) <= '3') {
                                totalCreditAmount += amountCents;
                            } else if (txnCode.startsWith("3") && txnCode.charAt(1) >= '7' && txnCode.charAt(1) <= '8') {
                                totalDebitAmount += amountCents;
                            }
                        } catch (NumberFormatException e) {
                            warnings.add("Entry " + entryCount + ": non-numeric amount '" + amountStr + "'");
                        }

                        segments.add(Segment.builder().id("ENTRY_DETAIL")
                                .elements(List.of(record)).namedFields(named).build());

                        // Collect entry for business data
                        entries.add(named);
                    }
                    case "7" -> {
                        // Addenda Record
                        Map<String, String> named = new LinkedHashMap<>();
                        named.put("recordType", "7");
                        named.put("addendaTypeCode", safe(record, 1, 3));
                        named.put("paymentRelatedInfo", safe(record, 3, 83).trim());
                        named.put("addendaSequenceNumber", safe(record, 83, 87).trim());
                        named.put("entryDetailSequenceNumber", safe(record, 87, 94).trim());

                        segments.add(Segment.builder().id("ADDENDA")
                                .elements(List.of(record)).namedFields(named).build());
                    }
                    case "8" -> {
                        // Batch Control Record
                        Map<String, String> named = new LinkedHashMap<>();
                        named.put("recordType", "8");
                        named.put("serviceClassCode", safe(record, 1, 4));
                        named.put("entryAddendaCount", safe(record, 4, 10).trim());
                        named.put("entryHash", safe(record, 10, 20).trim());
                        named.put("totalDebitAmount", safe(record, 20, 32).trim());
                        named.put("totalCreditAmount", safe(record, 32, 44).trim());
                        named.put("companyIdentification", safe(record, 44, 54).trim());
                        named.put("messageAuthCode", safe(record, 54, 73).trim());
                        named.put("originatingDFI", safe(record, 79, 87).trim());
                        named.put("batchNumber", safe(record, 87, 94).trim());

                        segments.add(Segment.builder().id("BATCH_CONTROL")
                                .elements(List.of(record)).namedFields(named).build());
                    }
                    case "9" -> {
                        // File Control Record
                        Map<String, String> named = new LinkedHashMap<>();
                        named.put("recordType", "9");
                        named.put("batchCount", safe(record, 1, 7).trim());
                        named.put("blockCount", safe(record, 7, 13).trim());
                        named.put("entryAddendaCount", safe(record, 13, 21).trim());
                        named.put("entryHash", safe(record, 21, 31).trim());
                        named.put("totalDebitAmount", safe(record, 31, 43).trim());
                        named.put("totalCreditAmount", safe(record, 43, 55).trim());

                        segments.add(Segment.builder().id("FILE_CONTROL")
                                .elements(List.of(record)).namedFields(named).build());
                    }
                    default -> {
                        // Unknown record type — include as-is
                        warnings.add("Record " + segCount + ": unknown record type '" + recType + "'");
                        segments.add(Segment.builder().id("RECORD_" + recType)
                                .elements(List.of(record))
                                .namedFields(Map.of("recordType", recType, "raw", record))
                                .build());
                    }
                }
            } catch (Exception e) {
                errors.add("Record " + segCount + ": " + e.getMessage());
                segments.add(Segment.builder().id("ERROR").elements(List.of(line)).build());
            }
        }

        biz.put("segmentCount", segments.size());
        biz.put("batchCount", batchCount);
        biz.put("entryCount", entryCount);
        biz.put("totalDebitAmount", String.format("%.2f", totalDebitAmount / 100.0));
        biz.put("totalCreditAmount", String.format("%.2f", totalCreditAmount / 100.0));
        if (!entries.isEmpty()) {
            biz.put("entries", entries);
        }

        return EdiDocument.builder()
                .sourceFormat("NACHA")
                .documentType("ACH")
                .documentName("NACHA ACH File")
                .senderId(immediateOrigin)
                .receiverId(immediateDestination)
                .documentDate(fileCreationDate)
                .controlNumber(controlNum)
                .segments(segments)
                .rawContent(content)
                .businessData(biz)
                .parseErrors(errors)
                .parseWarnings(warnings)
                .build();
    }

    /** Safe substring — returns empty string if positions are out of bounds. */
    private String safe(String s, int start, int end) {
        if (s == null || start >= s.length()) return "";
        return s.substring(start, Math.min(end, s.length()));
    }
}
