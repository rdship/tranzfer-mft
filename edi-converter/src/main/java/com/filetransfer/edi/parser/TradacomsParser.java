package com.filetransfer.edi.parser;

import com.filetransfer.edi.model.EdiDocument;
import com.filetransfer.edi.model.EdiDocument.DelimiterInfo;
import com.filetransfer.edi.model.EdiDocument.Segment;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Dedicated TRADACOMS parser.
 *
 * TRADACOMS uses STX (start) and END (end) envelope segments.
 * Segment separator: ' (apostrophe)
 * Element separator: = (equals) for segment ID, + (plus) between data elements
 * Sub-element separator: : (colon) within data elements
 *
 * Example:
 * STX=ANA:1+5000000000000:14+5555555555555:14+060901:100000+100001'
 * MHD=1+ORDERS:9'
 * TYP=0430+NEW ORDERS'
 * SDT=5555555555:PARTNER+PARTNER NAME'
 * CDT=5000000000:14+BUYER NAME'
 * MTR=14'
 * END=1'
 */
@Component
@Slf4j
public class TradacomsParser {

    private static final char SEGMENT_TERMINATOR = '\'';
    private static final char ELEMENT_SEPARATOR = '+';
    private static final char SUB_ELEMENT_SEPARATOR = ':';

    private static final Map<String, String> TRADACOMS_MSG_TYPES = Map.ofEntries(
            Map.entry("ORDERS:9", "Purchase Order"),
            Map.entry("INVOIC:9", "Invoice"),
            Map.entry("DELIVR:9", "Delivery Notification"),
            Map.entry("CREDIT:9", "Credit Note"),
            Map.entry("ACKHDR:9", "Acknowledgement Header"),
            Map.entry("SNPSTS:9", "Stock Snapshot"),
            Map.entry("UTLHDR:9", "Utility Header"),
            Map.entry("PRICAT:9", "Price/Sales Catalogue"),
            Map.entry("AVLHDR:9", "Availability Report"),
            Map.entry("GENRAL:9", "General Communication")
    );

    public EdiDocument parse(String content) {
        List<Segment> segments = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        String senderId = null, receiverId = null, txnType = null, docName = null;
        String controlNum = null, docDate = null;
        Map<String, Object> biz = new LinkedHashMap<>();

        // Split by segment terminator (apostrophe), handling newlines
        String normalized = content.replace("\r\n", "").replace("\r", "").replace("\n", "");
        String[] rawSegments = normalized.split(String.valueOf(SEGMENT_TERMINATOR));
        int segCount = 0;

        for (String raw : rawSegments) {
            String seg = raw.trim();
            if (seg.isEmpty()) continue;
            segCount++;

            try {
                // Split segment ID from data: first '=' separates segment ID
                int eqPos = seg.indexOf('=');
                if (eqPos < 0) {
                    warnings.add("Segment " + segCount + ": no '=' found, treating as raw");
                    segments.add(Segment.builder().id(seg).elements(List.of()).build());
                    continue;
                }

                String segId = seg.substring(0, eqPos);
                String data = seg.substring(eqPos + 1);

                // Split data by '+' into elements
                String[] elemParts = data.split("\\+", -1);
                List<String> elements = new ArrayList<>(Arrays.asList(elemParts));

                // Parse composite sub-elements (colon-separated within each element)
                List<List<String>> composites = new ArrayList<>();
                for (String elem : elements) {
                    if (elem.indexOf(SUB_ELEMENT_SEPARATOR) >= 0) {
                        composites.add(Arrays.asList(elem.split(":", -1)));
                    } else {
                        composites.add(List.of(elem));
                    }
                }

                Map<String, String> namedFields = new LinkedHashMap<>();
                namedFields.put("segmentId", segId);
                namedFields.put("rawData", data);

                Segment segment = Segment.builder()
                        .id(segId)
                        .elements(elements)
                        .compositeElements(composites)
                        .namedFields(namedFields)
                        .build();
                segments.add(segment);

                // Extract envelope information
                switch (segId) {
                    case "STX" -> {
                        // STX=ANA:1+senderEAN:qualifier+receiverEAN:qualifier+date:time+controlRef
                        if (composites.size() > 1) {
                            senderId = composites.get(1).get(0); // sender EAN
                            biz.put("senderQualifier", composites.get(1).size() > 1 ? composites.get(1).get(1) : "");
                        }
                        if (composites.size() > 2) {
                            receiverId = composites.get(2).get(0); // receiver EAN
                            biz.put("receiverQualifier", composites.get(2).size() > 1 ? composites.get(2).get(1) : "");
                        }
                        if (composites.size() > 3) {
                            docDate = composites.get(3).get(0); // date YYMMDD
                            biz.put("transmissionTime", composites.get(3).size() > 1 ? composites.get(3).get(1) : "");
                        }
                        if (elements.size() > 4) {
                            controlNum = elements.get(4);
                        }
                        if (composites.size() > 0) {
                            biz.put("syntaxId", composites.get(0).get(0));
                            biz.put("syntaxVersion", composites.get(0).size() > 1 ? composites.get(0).get(1) : "");
                        }
                    }
                    case "MHD" -> {
                        // MHD=messageRef+messageType:version
                        if (elements.size() > 1) {
                            String msgTypeField = elements.get(1);
                            txnType = "TRADACOMS-" + msgTypeField;
                            docName = TRADACOMS_MSG_TYPES.getOrDefault(msgTypeField,
                                    "TRADACOMS " + msgTypeField);
                            biz.put("messageReference", elements.get(0));
                            biz.put("messageType", msgTypeField);
                        }
                    }
                    case "TYP" -> {
                        // TYP=typeCode+description
                        if (elements.size() > 0) biz.put("typeCode", elements.get(0));
                        if (elements.size() > 1) biz.put("typeDescription", elements.get(1));
                    }
                    case "SDT" -> {
                        // SDT=supplierCode:qualifier+supplierName
                        if (composites.size() > 0) biz.put("supplierCode", composites.get(0).get(0));
                        if (elements.size() > 1) biz.put("supplierName", elements.get(1));
                    }
                    case "CDT" -> {
                        // CDT=customerCode:qualifier+customerName
                        if (composites.size() > 0) biz.put("customerCode", composites.get(0).get(0));
                        if (elements.size() > 1) biz.put("customerName", elements.get(1));
                    }
                    case "FIL" -> {
                        // FIL=fileGenNumber+fileVersion+fileCreationDate
                        if (elements.size() > 0) biz.put("fileGenNumber", elements.get(0));
                        if (elements.size() > 1) biz.put("fileVersion", elements.get(1));
                    }
                    case "MTR" -> {
                        // MTR=segmentCount — message trailer
                        if (elements.size() > 0) biz.put("messageSegmentCount", elements.get(0));
                    }
                    case "END" -> {
                        // END=messageCount — transmission trailer
                        if (elements.size() > 0) biz.put("transmissionMessageCount", elements.get(0));
                    }
                    case "OLD" -> {
                        // OLD=orderLineRef+productCode+...
                        if (elements.size() > 0) biz.put("lastOrderLine", elements.get(0));
                    }
                }
            } catch (Exception e) {
                errors.add("Segment " + segCount + ": " + e.getMessage());
                segments.add(Segment.builder().id("ERROR").elements(List.of(seg)).build());
            }
        }

        biz.put("segmentCount", segments.size());
        biz.put("transactionType", txnType != null ? txnType : "TRADACOMS");

        DelimiterInfo delimiters = DelimiterInfo.builder()
                .segmentTerminator(SEGMENT_TERMINATOR)
                .elementSeparator(ELEMENT_SEPARATOR)
                .componentSeparator(SUB_ELEMENT_SEPARATOR)
                .build();

        return EdiDocument.builder()
                .sourceFormat("TRADACOMS")
                .documentType(txnType != null ? txnType : "TRADACOMS")
                .documentName(docName != null ? docName : "TRADACOMS Message")
                .senderId(senderId)
                .receiverId(receiverId)
                .documentDate(docDate)
                .controlNumber(controlNum)
                .segments(segments)
                .rawContent(content)
                .businessData(biz)
                .delimiterInfo(delimiters)
                .parseErrors(errors)
                .parseWarnings(warnings)
                .build();
    }
}
