package com.filetransfer.edi.service;

import com.filetransfer.edi.model.CanonicalDocument;
import com.filetransfer.edi.model.CanonicalDocument.*;
import com.filetransfer.edi.model.EdiDocument;
import com.filetransfer.edi.model.EdiDocument.Segment;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

/**
 * Maps EdiDocument (raw parsed segments) → CanonicalDocument (business meaning).
 * Also maps CanonicalDocument → EdiDocument for reverse conversion.
 *
 * This is the "Rosetta Stone" that eliminates per-partner mapping.
 */
@Service
public class CanonicalMapper {

    // === Forward: EdiDocument → CanonicalDocument ===

    public CanonicalDocument toCanonical(EdiDocument doc) {
        return switch (doc.getSourceFormat()) {
            case "X12" -> mapX12(doc);
            case "EDIFACT" -> mapEdifact(doc);
            case "HL7" -> mapHl7(doc);
            case "SWIFT_MT" -> mapSwift(doc);
            case "NACHA" -> mapNacha(doc);
            case "FIX" -> mapFix(doc);
            default -> mapGeneric(doc);
        };
    }

    // === X12 → Canonical ===
    private CanonicalDocument mapX12(EdiDocument doc) {
        DocumentType type = resolveX12Type(doc.getDocumentType());
        List<Party> parties = new ArrayList<>();
        List<LineItem> items = new ArrayList<>();
        Header.HeaderBuilder hdr = Header.builder();
        MonetaryTotal.MonetaryTotalBuilder totals = MonetaryTotal.builder();
        Map<String, Object> ext = new LinkedHashMap<>();
        int lineNum = 0;

        for (Segment seg : doc.getSegments()) {
            List<String> e = seg.getElements() != null ? seg.getElements() : List.of();
            switch (seg.getId()) {
                case "BEG" -> { // Purchase Order header
                    if (e.size() > 2) hdr.documentNumber(e.get(2));
                    if (e.size() > 4) hdr.documentDate(formatDate(e.get(4)));
                    if (e.size() > 0) hdr.purpose(decodePurpose(e.get(0)));
                }
                case "BIG" -> { // Invoice header
                    if (e.size() > 0) hdr.documentDate(formatDate(e.get(0)));
                    if (e.size() > 1) hdr.documentNumber(e.get(1));
                }
                case "BHT" -> { // Begin Hierarchical Transaction
                    if (e.size() > 2) hdr.documentNumber(e.get(2));
                    if (e.size() > 3) hdr.documentDate(formatDate(e.get(3)));
                }
                case "CLM" -> { // Claim
                    if (e.size() > 0) hdr.documentNumber(e.get(0));
                    if (e.size() > 1) totals.totalAmount(parseDouble(e.get(1)));
                }
                case "NM1" -> { // Entity name
                    Party party = parseX12Party(e);
                    if (party != null) parties.add(party);
                }
                case "N3" -> { // Address line
                    if (!parties.isEmpty() && e.size() > 0) {
                        Party last = parties.get(parties.size() - 1);
                        if (last.getAddress() == null) last.setAddress(new Address());
                        last.getAddress().setLine1(e.get(0));
                    }
                }
                case "N4" -> { // City/State/Zip
                    if (!parties.isEmpty() && e.size() > 2) {
                        Party last = parties.get(parties.size() - 1);
                        if (last.getAddress() == null) last.setAddress(new Address());
                        last.getAddress().setCity(e.get(0));
                        last.getAddress().setState(e.get(1));
                        last.getAddress().setPostalCode(e.get(2));
                    }
                }
                case "PO1" -> { // Purchase Order line item
                    lineNum++;
                    LineItem.LineItemBuilder item = LineItem.builder().lineNumber(lineNum);
                    if (e.size() > 0) item.itemId(e.get(0));
                    if (e.size() > 1) item.quantity(parseDouble(e.get(1)));
                    if (e.size() > 2) item.unitOfMeasure(e.get(2));
                    if (e.size() > 3) item.unitPrice(parseDouble(e.get(3)));
                    if (e.size() > 6) item.productCode(e.get(6));
                    items.add(item.build());
                }
                case "IT1" -> { // Invoice line item
                    lineNum++;
                    LineItem.LineItemBuilder item = LineItem.builder().lineNumber(lineNum);
                    if (e.size() > 0) item.itemId(e.get(0));
                    if (e.size() > 1) item.quantity(parseDouble(e.get(1)));
                    if (e.size() > 2) item.unitOfMeasure(e.get(2));
                    if (e.size() > 3) item.unitPrice(parseDouble(e.get(3)));
                    items.add(item.build());
                }
                case "SV1" -> { // Healthcare service line
                    lineNum++;
                    LineItem.LineItemBuilder item = LineItem.builder().lineNumber(lineNum);
                    if (e.size() > 0) item.productCode(e.get(0));
                    if (e.size() > 1) item.unitPrice(parseDouble(e.get(1)));
                    if (e.size() > 3) item.quantity(parseDouble(e.get(3)));
                    items.add(item.build());
                }
                case "TDS" -> { // Total monetary value summary
                    if (e.size() > 0) totals.totalAmount(parseDouble(e.get(0)) / 100.0);
                }
                case "CUR" -> { // Currency
                    if (e.size() > 1) totals.currency(e.get(1));
                }
                case "ISA" -> { // Interchange control header
                    if (e.size() > 5) ext.put("senderQualifier", e.get(4));
                    if (e.size() > 7) ext.put("receiverQualifier", e.get(6));
                }
                default -> {} // Other segments stored as raw in extensions
            }
        }

        // Add sender/receiver from ISA as parties if no NM1 found for them
        if (parties.stream().noneMatch(p -> p.getRole() == Party.PartyRole.SENDER) && doc.getSenderId() != null) {
            parties.add(Party.builder().role(Party.PartyRole.SENDER).id(doc.getSenderId()).build());
        }
        if (parties.stream().noneMatch(p -> p.getRole() == Party.PartyRole.RECEIVER) && doc.getReceiverId() != null) {
            parties.add(Party.builder().role(Party.PartyRole.RECEIVER).id(doc.getReceiverId()).build());
        }

        return CanonicalDocument.builder()
                .documentId(UUID.randomUUID().toString())
                .type(type).sourceFormat("X12").sourceDocumentType(doc.getDocumentType())
                .header(hdr.build()).lineItems(items).parties(parties)
                .totals(totals.build()).extensions(ext)
                .createdAt(Instant.now().toString()).build();
    }

    private Party parseX12Party(List<String> e) {
        if (e.size() < 3) return null;
        String entityCode = e.get(0);
        Party.PartyRole role = switch (entityCode) {
            case "IL" -> Party.PartyRole.PATIENT;
            case "82", "85" -> Party.PartyRole.PROVIDER;
            case "40" -> Party.PartyRole.RECEIVER;
            case "41", "QC" -> Party.PartyRole.PATIENT;
            case "BY" -> Party.PartyRole.BUYER;
            case "SE" -> Party.PartyRole.SELLER;
            case "ST" -> Party.PartyRole.SHIP_TO;
            case "SF" -> Party.PartyRole.SHIP_FROM;
            case "BT" -> Party.PartyRole.BILL_TO;
            case "PE" -> Party.PartyRole.PAYEE;
            case "PR" -> Party.PartyRole.PAYER;
            default -> Party.PartyRole.SENDER;
        };
        String name = e.size() > 2 ? e.get(2) : "";
        if (e.size() > 3 && !e.get(3).isEmpty()) name = e.get(3) + " " + name;
        String id = e.size() > 8 ? e.get(8) : null;
        String qualifier = e.size() > 7 ? e.get(7) : null;
        return Party.builder().role(role).name(name.trim()).id(id).qualifier(qualifier).build();
    }

    // === EDIFACT → Canonical ===
    private CanonicalDocument mapEdifact(EdiDocument doc) {
        DocumentType type = resolveEdifactType(doc.getDocumentType());
        List<Party> parties = new ArrayList<>();
        List<LineItem> items = new ArrayList<>();
        Header.HeaderBuilder hdr = Header.builder();
        MonetaryTotal.MonetaryTotalBuilder totals = MonetaryTotal.builder();
        int lineNum = 0;

        for (Segment seg : doc.getSegments()) {
            List<String> e = seg.getElements() != null ? seg.getElements() : List.of();
            switch (seg.getId()) {
                case "BGM" -> {
                    if (e.size() > 1) hdr.documentNumber(e.get(1));
                }
                case "DTM" -> {
                    if (e.size() > 0) {
                        String[] dtmParts = e.get(0).split(":");
                        if (dtmParts.length > 1) hdr.documentDate(formatDate(dtmParts[1]));
                    }
                }
                case "NAD" -> {
                    if (e.size() > 1) {
                        Party.PartyRole role = switch (e.get(0)) {
                            case "BY" -> Party.PartyRole.BUYER;
                            case "SE", "SU" -> Party.PartyRole.SELLER;
                            case "ST" -> Party.PartyRole.SHIP_TO;
                            case "IV" -> Party.PartyRole.BILL_TO;
                            case "DP" -> Party.PartyRole.SHIP_TO;
                            default -> Party.PartyRole.SENDER;
                        };
                        parties.add(Party.builder().role(role).id(e.get(1))
                                .name(e.size() > 3 ? e.get(3) : "").build());
                    }
                }
                case "LIN" -> {
                    lineNum++;
                    LineItem.LineItemBuilder item = LineItem.builder().lineNumber(lineNum);
                    if (e.size() > 2) item.productCode(e.get(2));
                    items.add(item.build());
                }
                case "QTY" -> {
                    if (!items.isEmpty() && e.size() > 0) {
                        String[] qty = e.get(0).split(":");
                        if (qty.length > 1) items.get(items.size() - 1).setQuantity(parseDouble(qty[1]));
                    }
                }
                case "PRI" -> {
                    if (!items.isEmpty() && e.size() > 0) {
                        String[] pri = e.get(0).split(":");
                        if (pri.length > 1) items.get(items.size() - 1).setUnitPrice(parseDouble(pri[1]));
                    }
                }
                case "MOA" -> {
                    if (e.size() > 0) {
                        String[] moa = e.get(0).split(":");
                        if (moa.length > 1 && "86".equals(moa[0])) totals.totalAmount(parseDouble(moa[1]));
                    }
                }
                case "CUX" -> {
                    if (e.size() > 0) {
                        String[] cux = e.get(0).split(":");
                        if (cux.length > 1) totals.currency(cux[1]);
                    }
                }
                default -> {}
            }
        }

        if (doc.getSenderId() != null)
            parties.add(Party.builder().role(Party.PartyRole.SENDER).id(doc.getSenderId()).build());
        if (doc.getReceiverId() != null)
            parties.add(Party.builder().role(Party.PartyRole.RECEIVER).id(doc.getReceiverId()).build());

        return CanonicalDocument.builder()
                .documentId(UUID.randomUUID().toString())
                .type(type).sourceFormat("EDIFACT").sourceDocumentType(doc.getDocumentType())
                .header(hdr.build()).lineItems(items).parties(parties)
                .totals(totals.build()).createdAt(Instant.now().toString()).build();
    }

    // === HL7 → Canonical ===
    private CanonicalDocument mapHl7(EdiDocument doc) {
        Header.HeaderBuilder hdr = Header.builder();
        List<Party> parties = new ArrayList<>();
        Map<String, Object> ext = new LinkedHashMap<>();

        for (Segment seg : doc.getSegments()) {
            List<String> e = seg.getElements() != null ? seg.getElements() : List.of();
            switch (seg.getId()) {
                case "MSH" -> {
                    if (e.size() > 2) ext.put("sendingApplication", e.get(2));
                    if (e.size() > 4) ext.put("receivingApplication", e.get(4));
                    if (e.size() > 6) hdr.documentDate(e.get(6));
                    if (e.size() > 8) hdr.purpose(e.get(8));
                    if (e.size() > 9) hdr.documentNumber(e.get(9));
                }
                case "PID" -> {
                    String name = e.size() > 4 ? e.get(4).replace("^", " ") : "";
                    parties.add(Party.builder().role(Party.PartyRole.PATIENT).name(name)
                            .id(e.size() > 2 ? e.get(2) : null).build());
                    if (e.size() > 6) ext.put("dateOfBirth", e.get(6));
                    if (e.size() > 7) ext.put("gender", e.get(7));
                }
                case "PV1" -> {
                    if (e.size() > 2) ext.put("ward", e.get(2));
                    if (e.size() > 7) {
                        parties.add(Party.builder().role(Party.PartyRole.PROVIDER)
                                .name(e.get(7).replace("^", " ")).build());
                    }
                }
                default -> {}
            }
        }

        return CanonicalDocument.builder()
                .documentId(UUID.randomUUID().toString())
                .type(DocumentType.PATIENT_ADMISSION).sourceFormat("HL7")
                .sourceDocumentType(doc.getDocumentType())
                .header(hdr.build()).parties(parties).lineItems(List.of())
                .extensions(ext).createdAt(Instant.now().toString()).build();
    }

    // === SWIFT MT → Canonical ===
    private CanonicalDocument mapSwift(EdiDocument doc) {
        Header.HeaderBuilder hdr = Header.builder();
        List<Party> parties = new ArrayList<>();
        MonetaryTotal.MonetaryTotalBuilder totals = MonetaryTotal.builder();

        Map<String, Object> biz = doc.getBusinessData() != null ? doc.getBusinessData() : Map.of();
        if (biz.containsKey("reference")) hdr.documentNumber((String) biz.get("reference"));
        if (biz.containsKey("valueDate_currency_amount")) {
            String val = (String) biz.get("valueDate_currency_amount");
            if (val.length() >= 9) {
                hdr.documentDate(val.substring(0, 6));
                totals.currency(val.substring(6, 9));
                if (val.length() > 9) totals.totalAmount(parseDouble(val.substring(9).replace(",", ".")));
            }
        }

        for (Segment seg : doc.getSegments()) {
            Map<String, String> fields = seg.getNamedFields() != null ? seg.getNamedFields() : Map.of();
            String tag = fields.getOrDefault("tag", "");
            String value = fields.getOrDefault("value", "");
            switch (tag) {
                case "50K", "50A" -> parties.add(Party.builder().role(Party.PartyRole.PAYER).name(value).build());
                case "59", "59A" -> parties.add(Party.builder().role(Party.PartyRole.PAYEE).name(value).build());
                case "52A" -> parties.add(Party.builder().role(Party.PartyRole.BANK_SENDER).id(value).build());
                case "57A" -> parties.add(Party.builder().role(Party.PartyRole.BANK_RECEIVER).id(value).build());
            }
        }

        return CanonicalDocument.builder()
                .documentId(UUID.randomUUID().toString())
                .type(DocumentType.WIRE_TRANSFER).sourceFormat("SWIFT_MT")
                .sourceDocumentType(doc.getDocumentType())
                .header(hdr.build()).parties(parties).lineItems(List.of())
                .totals(totals.build()).createdAt(Instant.now().toString()).build();
    }

    // === NACHA → Canonical ===
    private CanonicalDocument mapNacha(EdiDocument doc) {
        Header.HeaderBuilder hdr = Header.builder();
        List<Party> parties = new ArrayList<>();
        Map<String, Object> biz = doc.getBusinessData() != null ? doc.getBusinessData() : Map.of();

        hdr.documentDate((String) biz.getOrDefault("fileCreationDate", ""));
        if (biz.containsKey("immediateOrigin"))
            parties.add(Party.builder().role(Party.PartyRole.SENDER).id((String) biz.get("immediateOrigin")).build());
        if (biz.containsKey("immediateDestination"))
            parties.add(Party.builder().role(Party.PartyRole.RECEIVER).id((String) biz.get("immediateDestination")).build());

        return CanonicalDocument.builder()
                .documentId(UUID.randomUUID().toString())
                .type(DocumentType.PAYMENT).sourceFormat("NACHA")
                .sourceDocumentType("ACH")
                .header(hdr.build()).parties(parties).lineItems(List.of())
                .totals(MonetaryTotal.builder().currency("USD").build())
                .createdAt(Instant.now().toString()).build();
    }

    // === FIX → Canonical ===
    private CanonicalDocument mapFix(EdiDocument doc) {
        Header.HeaderBuilder hdr = Header.builder();
        Map<String, Object> biz = doc.getBusinessData() != null ? doc.getBusinessData() : Map.of();
        MonetaryTotal.MonetaryTotalBuilder totals = MonetaryTotal.builder();
        List<LineItem> items = new ArrayList<>();

        hdr.documentNumber((String) biz.getOrDefault("tag_11", ""));
        if (biz.containsKey("tag_44")) totals.totalAmount(parseDouble((String) biz.get("tag_44")));
        if (biz.containsKey("tag_15")) totals.currency((String) biz.get("tag_15"));

        if (biz.containsKey("tag_55")) {
            items.add(LineItem.builder().lineNumber(1)
                    .productCode((String) biz.get("tag_55"))
                    .quantity(parseDouble((String) biz.getOrDefault("tag_38", "0")))
                    .unitPrice(parseDouble((String) biz.getOrDefault("tag_44", "0"))).build());
        }

        return CanonicalDocument.builder()
                .documentId(UUID.randomUUID().toString())
                .type(DocumentType.TRADE_ORDER).sourceFormat("FIX")
                .sourceDocumentType(doc.getDocumentType())
                .header(hdr.build()).lineItems(items).parties(List.of())
                .totals(totals.build()).extensions(biz)
                .createdAt(Instant.now().toString()).build();
    }

    // === Generic fallback ===
    private CanonicalDocument mapGeneric(EdiDocument doc) {
        return CanonicalDocument.builder()
                .documentId(UUID.randomUUID().toString())
                .type(DocumentType.UNKNOWN).sourceFormat(doc.getSourceFormat())
                .sourceDocumentType(doc.getDocumentType())
                .header(Header.builder().documentNumber(doc.getControlNumber()).documentDate(doc.getDocumentDate()).build())
                .parties(List.of()).lineItems(List.of())
                .extensions(doc.getBusinessData())
                .createdAt(Instant.now().toString()).build();
    }

    // === Helpers ===

    private DocumentType resolveX12Type(String txnType) {
        if (txnType == null) return DocumentType.UNKNOWN;
        return switch (txnType) {
            case "850", "860" -> DocumentType.PURCHASE_ORDER;
            case "810" -> DocumentType.INVOICE;
            case "856" -> DocumentType.SHIPMENT_NOTICE;
            case "820" -> DocumentType.PAYMENT;
            case "835" -> DocumentType.REMITTANCE;
            case "837" -> DocumentType.HEALTHCARE_CLAIM;
            case "270" -> DocumentType.ELIGIBILITY_INQUIRY;
            case "271" -> DocumentType.ELIGIBILITY_RESPONSE;
            case "997", "999" -> DocumentType.FUNCTIONAL_ACK;
            default -> DocumentType.UNKNOWN;
        };
    }

    private DocumentType resolveEdifactType(String txnType) {
        if (txnType == null) return DocumentType.UNKNOWN;
        return switch (txnType) {
            case "ORDERS" -> DocumentType.PURCHASE_ORDER;
            case "INVOIC" -> DocumentType.INVOICE;
            case "DESADV" -> DocumentType.SHIPMENT_NOTICE;
            case "PAYORD", "PAYMUL" -> DocumentType.PAYMENT;
            case "CUSCAR" -> DocumentType.CUSTOMS_DECLARATION;
            default -> DocumentType.UNKNOWN;
        };
    }

    private String formatDate(String raw) {
        if (raw == null || raw.isEmpty()) return raw;
        raw = raw.replaceAll("[^0-9]", "");
        if (raw.length() == 8) return raw.substring(0, 4) + "-" + raw.substring(4, 6) + "-" + raw.substring(6, 8);
        if (raw.length() == 6) return "20" + raw.substring(0, 2) + "-" + raw.substring(2, 4) + "-" + raw.substring(4, 6);
        return raw;
    }

    private String decodePurpose(String code) {
        return switch (code != null ? code : "") {
            case "00" -> "Original"; case "01" -> "Cancellation"; case "05" -> "Replace";
            case "06" -> "Confirmation"; case "07" -> "Duplicate"; default -> code;
        };
    }

    private double parseDouble(String s) {
        try { return Double.parseDouble(s.replaceAll("[^0-9.\\-]", "")); }
        catch (Exception e) { return 0; }
    }
}
