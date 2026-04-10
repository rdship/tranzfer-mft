package com.filetransfer.edi.parser;

import com.filetransfer.edi.model.EdiDocument;
import com.filetransfer.edi.model.EdiDocument.Segment;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Dedicated BAI2 parser with continuation line support and full field extraction.
 *
 * BAI2 is a comma-separated format with record types:
 *   01 — File Header
 *   02 — Group Header
 *   03 — Account Identifier
 *   16 — Transaction Detail
 *   49 — Account Trailer
 *   88 — Continuation Record (appended to previous record)
 *   98 — Group Trailer
 *   99 — File Trailer
 *
 * Each record ends with '/' (slash). Continuation records (88) are merged
 * with the preceding record before field extraction.
 */
@Component
@Slf4j
public class Bai2Parser {

    private static final Map<String, String> BAI_TYPE_CODES = Map.ofEntries(
            Map.entry("010", "Opening Ledger Balance"),
            Map.entry("015", "Closing Ledger Balance"),
            Map.entry("040", "Opening Available Balance"),
            Map.entry("045", "Closing Available Balance"),
            Map.entry("100", "Total Credits"),
            Map.entry("400", "Total Debits"),
            Map.entry("108", "Credit – Preauthorized ACH"),
            Map.entry("175", "Check Paid"),
            Map.entry("195", "Incoming Wire Transfer"),
            Map.entry("275", "Check Deposited"),
            Map.entry("295", "Outgoing Wire Transfer"),
            Map.entry("301", "Individual ACH Return"),
            Map.entry("355", "Deposit Correction"),
            Map.entry("451", "Individual ACH Debit"),
            Map.entry("452", "ACH Debit Settlement"),
            Map.entry("455", "ACH Preauthorized Debit"),
            Map.entry("495", "Outgoing Wire Transfer"),
            Map.entry("698", "Miscellaneous Fee"),
            Map.entry("699", "Miscellaneous Credit")
    );

    public EdiDocument parse(String content) {
        List<Segment> segments = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Map<String, Object> biz = new LinkedHashMap<>();

        String senderId = null, receiverId = null, fileDate = null, controlNum = null;
        int accountCount = 0, transactionCount = 0;
        List<Map<String, String>> transactions = new ArrayList<>();

        // Pre-process: merge continuation records (88)
        List<String> mergedLines = mergeContinuationLines(content);
        int segCount = 0;

        for (String line : mergedLines) {
            if (line.isEmpty()) continue;
            segCount++;

            try {
                // Remove trailing '/' if present
                String cleanLine = line.endsWith("/") ? line.substring(0, line.length() - 1) : line;
                String[] parts = cleanLine.split(",", -1);
                if (parts.length == 0) continue;
                String recType = parts[0].trim();

                switch (recType) {
                    case "01" -> {
                        // File Header: 01,SENDER,RECEIVER,FILEDATE,FILETIME,FILEID,PHYSICALRECLEN
                        Map<String, String> named = new LinkedHashMap<>();
                        named.put("recordType", "01");
                        senderId = parts.length > 1 ? parts[1].trim() : "";
                        receiverId = parts.length > 2 ? parts[2].trim() : "";
                        fileDate = parts.length > 3 ? parts[3].trim() : "";
                        String fileTime = parts.length > 4 ? parts[4].trim() : "";
                        controlNum = parts.length > 5 ? parts[5].trim() : "";

                        named.put("senderId", senderId);
                        named.put("receiverId", receiverId);
                        named.put("fileCreationDate", fileDate);
                        named.put("fileCreationTime", fileTime);
                        named.put("fileId", controlNum);
                        if (parts.length > 6) named.put("physicalRecordLength", parts[6].trim());
                        if (parts.length > 7) named.put("blockSize", parts[7].trim());
                        if (parts.length > 8) named.put("versionNumber", parts[8].trim());

                        List<String> elements = new ArrayList<>();
                        for (int i = 1; i < parts.length; i++) elements.add(parts[i].trim());
                        segments.add(Segment.builder().id("FILE_HEADER")
                                .elements(elements).namedFields(named).build());

                        biz.put("senderId", senderId);
                        biz.put("receiverId", receiverId);
                        biz.put("fileDate", fileDate);
                    }
                    case "02" -> {
                        // Group Header: 02,BANKID,CUSTOMERID,GROUPSTATUS,ASOFDATE,ASOFTIMECURRENCY
                        Map<String, String> named = new LinkedHashMap<>();
                        named.put("recordType", "02");
                        if (parts.length > 1) named.put("ultimateReceiverId", parts[1].trim());
                        if (parts.length > 2) named.put("originatorId", parts[2].trim());
                        if (parts.length > 3) named.put("groupStatus", parts[3].trim());
                        if (parts.length > 4) named.put("asOfDate", parts[4].trim());
                        if (parts.length > 5) named.put("asOfTime", parts[5].trim());
                        if (parts.length > 6) named.put("currencyCode", parts[6].trim());
                        if (parts.length > 7) named.put("asOfDateModifier", parts[7].trim());

                        List<String> elements = new ArrayList<>();
                        for (int i = 1; i < parts.length; i++) elements.add(parts[i].trim());
                        segments.add(Segment.builder().id("GROUP_HEADER")
                                .elements(elements).namedFields(named).build());

                        biz.put("bankId", named.getOrDefault("ultimateReceiverId", ""));
                    }
                    case "03" -> {
                        // Account Identifier: 03,ACCOUNTNUMBER,CURRENCY,TYPECODE,AMOUNT,...
                        accountCount++;
                        Map<String, String> named = new LinkedHashMap<>();
                        named.put("recordType", "03");
                        if (parts.length > 1) named.put("accountNumber", parts[1].trim());
                        if (parts.length > 2) named.put("currencyCode", parts[2].trim());

                        // Parse summary items (groups of 3: typeCode, amount, itemCount)
                        List<Map<String, String>> summaryItems = new ArrayList<>();
                        int idx = 3;
                        while (idx + 2 < parts.length) {
                            String typeCode = parts[idx].trim();
                            String amount = parts[idx + 1].trim();
                            String itemCount = parts[idx + 2].trim();
                            if (!typeCode.isEmpty()) {
                                Map<String, String> item = new LinkedHashMap<>();
                                item.put("typeCode", typeCode);
                                item.put("typeDescription", BAI_TYPE_CODES.getOrDefault(typeCode, "Type " + typeCode));
                                item.put("amount", amount);
                                item.put("itemCount", itemCount);
                                summaryItems.add(item);
                            }
                            idx += 3;
                        }
                        if (!summaryItems.isEmpty()) {
                            named.put("summaryItemCount", String.valueOf(summaryItems.size()));
                        }

                        List<String> elements = new ArrayList<>();
                        for (int i = 1; i < parts.length; i++) elements.add(parts[i].trim());
                        segments.add(Segment.builder().id("ACCOUNT_IDENTIFIER")
                                .elements(elements).namedFields(named).build());

                        biz.put("accountNumber", named.getOrDefault("accountNumber", ""));
                    }
                    case "16" -> {
                        // Transaction Detail: 16,TYPECODE,AMOUNT,FUNDSTYPE,BANKREF,CUSTREF,TEXT
                        transactionCount++;
                        Map<String, String> named = new LinkedHashMap<>();
                        named.put("recordType", "16");
                        String typeCode = parts.length > 1 ? parts[1].trim() : "";
                        named.put("typeCode", typeCode);
                        named.put("typeDescription", BAI_TYPE_CODES.getOrDefault(typeCode, "Type " + typeCode));
                        if (parts.length > 2) named.put("amount", parts[2].trim());
                        if (parts.length > 3) named.put("fundsType", parts[3].trim());
                        if (parts.length > 4) named.put("bankReference", parts[4].trim());
                        if (parts.length > 5) named.put("customerReference", parts[5].trim());
                        if (parts.length > 6) named.put("text", parts[6].trim());

                        List<String> elements = new ArrayList<>();
                        for (int i = 1; i < parts.length; i++) elements.add(parts[i].trim());
                        segments.add(Segment.builder().id("TRANSACTION_DETAIL")
                                .elements(elements).namedFields(named).build());

                        transactions.add(named);
                    }
                    case "49" -> {
                        // Account Trailer: 49,CONTROLBAL,NUMBEROFRECORDS
                        Map<String, String> named = new LinkedHashMap<>();
                        named.put("recordType", "49");
                        if (parts.length > 1) named.put("accountControlTotal", parts[1].trim());
                        if (parts.length > 2) named.put("numberOfRecords", parts[2].trim());

                        List<String> elements = new ArrayList<>();
                        for (int i = 1; i < parts.length; i++) elements.add(parts[i].trim());
                        segments.add(Segment.builder().id("ACCOUNT_TRAILER")
                                .elements(elements).namedFields(named).build());
                    }
                    case "98" -> {
                        // Group Trailer: 98,CONTROLBAL,NUMBEROFACCOUNTS,NUMBEROFRECORDS
                        Map<String, String> named = new LinkedHashMap<>();
                        named.put("recordType", "98");
                        if (parts.length > 1) named.put("groupControlTotal", parts[1].trim());
                        if (parts.length > 2) named.put("numberOfAccounts", parts[2].trim());
                        if (parts.length > 3) named.put("numberOfRecords", parts[3].trim());

                        List<String> elements = new ArrayList<>();
                        for (int i = 1; i < parts.length; i++) elements.add(parts[i].trim());
                        segments.add(Segment.builder().id("GROUP_TRAILER")
                                .elements(elements).namedFields(named).build());
                    }
                    case "99" -> {
                        // File Trailer: 99,CONTROLBAL,NUMBEROFGROUPS,NUMBEROFRECORDS
                        Map<String, String> named = new LinkedHashMap<>();
                        named.put("recordType", "99");
                        if (parts.length > 1) named.put("fileControlTotal", parts[1].trim());
                        if (parts.length > 2) named.put("numberOfGroups", parts[2].trim());
                        if (parts.length > 3) named.put("numberOfRecords", parts[3].trim());

                        List<String> elements = new ArrayList<>();
                        for (int i = 1; i < parts.length; i++) elements.add(parts[i].trim());
                        segments.add(Segment.builder().id("FILE_TRAILER")
                                .elements(elements).namedFields(named).build());
                    }
                    default -> {
                        warnings.add("Record " + segCount + ": unknown record type '" + recType + "'");
                        List<String> elements = new ArrayList<>();
                        for (int i = 1; i < parts.length; i++) elements.add(parts[i].trim());
                        segments.add(Segment.builder().id("RECORD_" + recType)
                                .elements(elements)
                                .namedFields(Map.of("recordType", recType))
                                .build());
                    }
                }
            } catch (Exception e) {
                errors.add("Record " + segCount + ": " + e.getMessage());
                segments.add(Segment.builder().id("ERROR").elements(List.of(line)).build());
            }
        }

        biz.put("segmentCount", segments.size());
        biz.put("accountCount", accountCount);
        biz.put("transactionCount", transactionCount);
        if (!transactions.isEmpty()) {
            biz.put("transactions", transactions);
        }

        return EdiDocument.builder()
                .sourceFormat("BAI2")
                .documentType("BAI2")
                .documentName("BAI2 Balance Report")
                .senderId(senderId)
                .receiverId(receiverId)
                .documentDate(fileDate)
                .controlNumber(controlNum)
                .segments(segments)
                .rawContent(content)
                .businessData(biz)
                .parseErrors(errors)
                .parseWarnings(warnings)
                .build();
    }

    /**
     * Merge continuation records (type 88) with the preceding record.
     * A continuation record's data is appended to the previous record's data.
     */
    private List<String> mergeContinuationLines(String content) {
        List<String> result = new ArrayList<>();
        StringBuilder current = null;

        for (String line : content.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;

            if (trimmed.startsWith("88,")) {
                // Continuation: append data (minus "88,") to previous line
                if (current != null) {
                    // Remove trailing '/' from current before appending
                    String cur = current.toString();
                    if (cur.endsWith("/")) {
                        current.setLength(current.length() - 1);
                    }
                    current.append(",").append(trimmed.substring(3));
                } else {
                    // Orphan continuation — treat as standalone
                    result.add(trimmed);
                }
            } else {
                // Flush previous record
                if (current != null) {
                    result.add(current.toString());
                }
                current = new StringBuilder(trimmed);
            }
        }
        // Flush last record
        if (current != null) {
            result.add(current.toString());
        }
        return result;
    }
}
