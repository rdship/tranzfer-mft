package com.filetransfer.edi.service;

import com.filetransfer.edi.model.CanonicalDocument;
import com.filetransfer.edi.model.CanonicalDocument.*;
import com.filetransfer.edi.model.EdiDocument;
import com.filetransfer.edi.model.EdiDocument.Segment;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Maps EdiDocument (raw parsed segments) -> CanonicalDocument (business meaning).
 * Also maps CanonicalDocument -> EdiDocument for reverse conversion.
 *
 * This is the "Rosetta Stone" that eliminates per-partner mapping.
 */
@Service
public class CanonicalMapper {

    private static final AtomicLong CONTROL_COUNTER = new AtomicLong(1);

    private String nextControlNumber() {
        return String.format("%09d", CONTROL_COUNTER.getAndIncrement() % 1_000_000_000);
    }

    // === Forward: EdiDocument -> CanonicalDocument ===

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

    // === X12 -> Canonical ===
    private CanonicalDocument mapX12(EdiDocument doc) {
        DocumentType type = resolveX12Type(doc.getDocumentType());
        List<Party> parties = new ArrayList<>();
        List<LineItem> items = new ArrayList<>();
        List<Reference> refs = new ArrayList<>();
        List<DateInfo> dates = new ArrayList<>();
        List<Contact> contacts = new ArrayList<>();
        List<Note> notes = new ArrayList<>();
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
                case "N1" -> { // Simpler party identification (not NM1)
                    if (e.size() > 1) {
                        Party.PartyRole role = switch (e.get(0)) {
                            case "ST" -> Party.PartyRole.SHIP_TO;
                            case "BT" -> Party.PartyRole.BILL_TO;
                            case "BY" -> Party.PartyRole.BUYER;
                            case "SE" -> Party.PartyRole.SELLER;
                            case "PE" -> Party.PartyRole.PAYEE;
                            case "PR" -> Party.PartyRole.PAYER;
                            default -> Party.PartyRole.SENDER;
                        };
                        Party.PartyBuilder p = Party.builder().role(role).name(e.get(1));
                        if (e.size() > 3) p.qualifier(e.get(2)).id(e.get(3));
                        parties.add(p.build());
                    }
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
                case "REF" -> {
                    if (e.size() > 1) {
                        refs.add(Reference.builder()
                                .qualifier(e.get(0))
                                .value(e.get(1))
                                .description(e.size() > 2 ? e.get(2) : null).build());
                    }
                }
                case "DTP" -> {
                    if (e.size() > 2) {
                        dates.add(DateInfo.builder()
                                .qualifier(e.get(0)).format(e.get(1)).value(e.get(2)).build());
                    }
                }
                case "DTM" -> {
                    if (e.size() > 1) {
                        dates.add(DateInfo.builder()
                                .qualifier(e.get(0)).value(e.get(1)).build());
                    }
                }
                case "PER" -> {
                    if (e.size() > 1) {
                        Contact.ContactBuilder c = Contact.builder().type(e.get(0)).name(e.get(1));
                        if (e.size() > 3 && "TE".equals(e.get(2))) c.phone(e.get(3));
                        if (e.size() > 5 && "EM".equals(e.get(4))) c.email(e.get(5));
                        if (e.size() > 5 && "FX".equals(e.get(4))) c.fax(e.get(5));
                        contacts.add(c.build());
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
                case "HL" -> {
                    if (e.size() > 2) ext.put("hl_" + e.get(0), Map.of(
                            "parentId", e.size() > 1 ? e.get(1) : "",
                            "levelCode", e.get(2)));
                }
                case "SBR" -> {
                    if (e.size() > 0) ext.put("subscriberPayerResponsibility", e.get(0));
                    if (e.size() > 8) ext.put("claimFilingIndicator", e.get(8));
                }
                case "DMG" -> {
                    if (e.size() > 1) ext.put("dateOfBirth", e.get(1));
                    if (e.size() > 2) ext.put("genderCode", e.get(2));
                }
                case "PAT" -> {
                    if (e.size() > 0) ext.put("patientRelationship", e.get(0));
                }
                case "AMT" -> {
                    if (e.size() > 1) ext.put("amount_" + e.get(0), e.get(1));
                }
                case "BPR" -> {
                    if (e.size() > 1) totals.totalAmount(parseDouble(e.get(1)));
                    if (e.size() > 2) ext.put("creditDebitFlag", e.get(2));
                }
                case "TRN" -> {
                    if (e.size() > 1) hdr.referenceNumber(e.get(1));
                }
                case "CTT" -> {
                    if (e.size() > 0) ext.put("lineItemCount", e.get(0));
                }
                case "BSN" -> {
                    if (e.size() > 1) hdr.documentNumber(e.get(1));
                    if (e.size() > 2) hdr.documentDate(formatDate(e.get(2)));
                }
                case "SN1" -> {
                    lineNum++;
                    LineItem.LineItemBuilder item = LineItem.builder().lineNumber(lineNum);
                    if (e.size() > 1) item.quantity(parseDouble(e.get(1)));
                    if (e.size() > 2) item.unitOfMeasure(e.get(2));
                    items.add(item.build());
                }
                case "AK1" -> ext.put("ackFuncIdCode", e.size() > 0 ? e.get(0) : "");
                case "AK9" -> {
                    if (e.size() > 0) ext.put("funcGroupAckCode", e.get(0));
                    hdr.status(e.size() > 0 ? e.get(0) : "");
                }
                case "NTE" -> {
                    if (e.size() > 1) {
                        notes.add(Note.builder().type(e.get(0)).text(e.get(1)).build());
                    } else if (e.size() > 0) {
                        notes.add(Note.builder().text(e.get(0)).build());
                    }
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
                .references(refs.isEmpty() ? null : refs)
                .dates(dates.isEmpty() ? null : dates)
                .contacts(contacts.isEmpty() ? null : contacts)
                .notes(notes.isEmpty() ? null : notes)
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

    // === EDIFACT -> Canonical ===
    private CanonicalDocument mapEdifact(EdiDocument doc) {
        DocumentType type = resolveEdifactType(doc.getDocumentType());
        List<Party> parties = new ArrayList<>();
        List<LineItem> items = new ArrayList<>();
        List<Reference> refs = new ArrayList<>();
        List<DateInfo> dates = new ArrayList<>();
        List<Note> notes = new ArrayList<>();
        Header.HeaderBuilder hdr = Header.builder();
        MonetaryTotal.MonetaryTotalBuilder totals = MonetaryTotal.builder();
        Map<String, Object> ext = new LinkedHashMap<>();
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
                        if (dtmParts.length > 0) {
                            dates.add(DateInfo.builder()
                                    .qualifier(dtmParts[0])
                                    .value(dtmParts.length > 1 ? dtmParts[1] : "")
                                    .format(dtmParts.length > 2 ? dtmParts[2] : null).build());
                        }
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
                case "RFF" -> {
                    if (e.size() > 0) {
                        String[] rffParts = e.get(0).split(":");
                        if (rffParts.length > 1) {
                            refs.add(Reference.builder()
                                    .qualifier(rffParts[0])
                                    .value(rffParts[1]).build());
                        }
                    }
                }
                case "FTX" -> {
                    if (e.size() > 0) {
                        String noteType = e.get(0);
                        String text = e.size() > 3 ? e.get(3) : (e.size() > 1 ? e.get(1) : "");
                        notes.add(Note.builder().type(noteType).text(text).build());
                    }
                }
                case "PIA" -> {
                    if (!items.isEmpty() && e.size() > 1) {
                        String[] piaParts = e.get(1).split(":");
                        if (piaParts.length > 0) {
                            items.get(items.size() - 1).setProductCode(piaParts[0]);
                        }
                    }
                }
                case "TAX" -> {
                    if (e.size() > 0) {
                        ext.put("taxType", e.get(0));
                        if (e.size() > 4) {
                            String[] taxParts = e.get(4).split(":");
                            if (taxParts.length > 0) ext.put("taxRate", taxParts[taxParts.length - 1]);
                        }
                    }
                }
                case "ALC" -> {
                    if (e.size() > 0) {
                        ext.put("allowanceChargeIndicator", e.get(0));
                    }
                }
                case "TDT" -> {
                    if (e.size() > 0) ext.put("transportStageQualifier", e.get(0));
                    if (e.size() > 3) ext.put("carrierIdentification", e.get(3));
                }
                case "LOC" -> {
                    if (e.size() > 1) {
                        ext.put("location_" + e.get(0), e.get(1));
                    }
                }
                case "PAC" -> {
                    if (e.size() > 0) ext.put("numberOfPackages", e.get(0));
                }
                case "CNT" -> {
                    if (e.size() > 0) {
                        String[] cntParts = e.get(0).split(":");
                        if (cntParts.length > 1) ext.put("controlCount_" + cntParts[0], cntParts[1]);
                    }
                }
                case "UNS" -> {
                    // Section control separator - just marks boundary between detail and summary
                    ext.put("sectionSeparator", e.size() > 0 ? e.get(0) : "S");
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
                .references(refs.isEmpty() ? null : refs)
                .dates(dates.isEmpty() ? null : dates)
                .notes(notes.isEmpty() ? null : notes)
                .totals(totals.build()).extensions(ext)
                .createdAt(Instant.now().toString()).build();
    }

    // === HL7 -> Canonical ===
    private CanonicalDocument mapHl7(EdiDocument doc) {
        Header.HeaderBuilder hdr = Header.builder();
        List<Party> parties = new ArrayList<>();
        List<LineItem> items = new ArrayList<>();
        List<Note> notes = new ArrayList<>();
        Map<String, Object> ext = new LinkedHashMap<>();
        int lineNum = 0;

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
                case "EVN" -> {
                    if (e.size() > 0) ext.put("eventTypeCode", e.get(0));
                    if (e.size() > 1) ext.put("recordedDateTime", e.get(1));
                }
                case "OBR" -> {
                    lineNum++;
                    LineItem.LineItemBuilder item = LineItem.builder().lineNumber(lineNum);
                    if (e.size() > 3) {
                        String obsId = e.get(3);
                        item.productCode(obsId);
                        // Parse composite: code^description
                        if (obsId.contains("^")) {
                            String[] parts = obsId.split("\\^", 2);
                            item.productCode(parts[0]);
                            item.description(parts[1]);
                        }
                    }
                    items.add(item.build());
                }
                case "OBX" -> {
                    if (e.size() > 4) {
                        ext.put("observation_" + (e.size() > 2 ? e.get(2) : lineNum),
                                Map.of("valueType", e.size() > 1 ? e.get(1) : "",
                                        "observationId", e.size() > 2 ? e.get(2) : "",
                                        "value", e.get(4)));
                    }
                }
                case "ORC" -> {
                    if (e.size() > 0) ext.put("orderControl", e.get(0));
                    if (e.size() > 1) hdr.referenceNumber(e.get(1));
                }
                case "AL1" -> {
                    if (e.size() > 2) {
                        ext.put("allergy_" + e.get(0), Map.of(
                                "allergyType", e.size() > 1 ? e.get(1) : "",
                                "allergenCode", e.get(2)));
                    }
                }
                case "DG1" -> {
                    if (e.size() > 2) {
                        ext.put("diagnosis_" + e.get(0), Map.of(
                                "codingMethod", e.size() > 1 ? e.get(1) : "",
                                "diagnosisCode", e.get(2)));
                    }
                }
                case "IN1" -> {
                    if (e.size() > 2) {
                        Party.PartyBuilder insurer = Party.builder()
                                .role(Party.PartyRole.PAYER)
                                .id(e.get(1));
                        if (e.size() > 3) insurer.name(e.get(3));
                        parties.add(insurer.build());
                    }
                }
                default -> {}
            }
        }

        return CanonicalDocument.builder()
                .documentId(UUID.randomUUID().toString())
                .type(DocumentType.PATIENT_ADMISSION).sourceFormat("HL7")
                .sourceDocumentType(doc.getDocumentType())
                .header(hdr.build()).parties(parties).lineItems(items.isEmpty() ? List.of() : items)
                .notes(notes.isEmpty() ? null : notes)
                .extensions(ext).createdAt(Instant.now().toString()).build();
    }

    // === SWIFT MT -> Canonical ===
    private CanonicalDocument mapSwift(EdiDocument doc) {
        Header.HeaderBuilder hdr = Header.builder();
        List<Party> parties = new ArrayList<>();
        MonetaryTotal.MonetaryTotalBuilder totals = MonetaryTotal.builder();
        Map<String, Object> ext = new LinkedHashMap<>();

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
                case "20" -> hdr.referenceNumber(value);
                case "23B" -> ext.put("bankOperationCode", value);
                case "32A" -> {
                    // valueDate(6) + currency(3) + amount
                    if (value.length() >= 9) {
                        hdr.documentDate(value.substring(0, 6));
                        totals.currency(value.substring(6, 9));
                        if (value.length() > 9) totals.totalAmount(parseDouble(value.substring(9).replace(",", ".")));
                    }
                }
                case "50K", "50A" -> parties.add(Party.builder().role(Party.PartyRole.PAYER).name(value).build());
                case "52A" -> parties.add(Party.builder().role(Party.PartyRole.BANK_SENDER).id(value).build());
                case "53A" -> ext.put("sendersCorrespondent", value);
                case "57A" -> parties.add(Party.builder().role(Party.PartyRole.BANK_RECEIVER).id(value).build());
                case "59", "59A" -> parties.add(Party.builder().role(Party.PartyRole.PAYEE).name(value).build());
                case "70" -> ext.put("remittanceInfo", value);
                case "71A" -> ext.put("detailsOfCharges", value);
                case "79" -> ext.put("narrativeText", value);
            }
        }

        return CanonicalDocument.builder()
                .documentId(UUID.randomUUID().toString())
                .type(DocumentType.WIRE_TRANSFER).sourceFormat("SWIFT_MT")
                .sourceDocumentType(doc.getDocumentType())
                .header(hdr.build()).parties(parties).lineItems(List.of())
                .totals(totals.build()).extensions(ext)
                .createdAt(Instant.now().toString()).build();
    }

    // === NACHA -> Canonical ===
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

    // === FIX -> Canonical ===
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

    // ===================================================================
    // REVERSE: CanonicalDocument -> EDI string
    // ===================================================================

    /**
     * Convert a CanonicalDocument to a raw EDI string in the target format.
     * Supported targets: X12, EDIFACT, HL7, SWIFT_MT
     */
    public String fromCanonical(CanonicalDocument doc, String targetFormat) {
        return switch (targetFormat.toUpperCase()) {
            case "X12" -> generateX12(doc);
            case "EDIFACT" -> generateEdifact(doc);
            case "HL7" -> generateHl7(doc);
            case "SWIFT_MT", "SWIFT" -> generateSwift(doc);
            default -> throw new IllegalArgumentException(
                    "Unsupported EDI output format: " + targetFormat + ". Supported: X12, EDIFACT, HL7, SWIFT_MT");
        };
    }

    // === Canonical -> X12 ===
    private String generateX12(CanonicalDocument doc) {
        char elemSep = '*';
        char compSep = ':';
        char segTerm = '~';

        String txnType = resolveX12TxnType(doc.getType());
        String date = compactDate(doc.getHeader() != null ? doc.getHeader().getDocumentDate() : null);
        String time = currentTime4();
        String controlNum = nextControlNumber();
        String docNumber = doc.getHeader() != null && doc.getHeader().getDocumentNumber() != null
                ? doc.getHeader().getDocumentNumber() : "DOC001";
        String senderId = findPartyId(doc, Party.PartyRole.SENDER, Party.PartyRole.BUYER);
        String receiverId = findPartyId(doc, Party.PartyRole.RECEIVER, Party.PartyRole.SELLER);
        String purpose = doc.getHeader() != null && doc.getHeader().getPurpose() != null
                ? encodePurpose(doc.getHeader().getPurpose()) : "00";

        List<String> segments = new ArrayList<>();

        // ISA — build to EXACTLY 106 characters per spec
        StringBuilder isa = new StringBuilder();
        isa.append("ISA");
        isa.append(elemSep);
        isa.append(padRight("00", 2, ' '));        // ISA01: Auth qualifier (2)
        isa.append(elemSep);
        isa.append(padRight("", 10, ' '));          // ISA02: Auth info (10)
        isa.append(elemSep);
        isa.append(padRight("00", 2, ' '));        // ISA03: Security qualifier (2)
        isa.append(elemSep);
        isa.append(padRight("", 10, ' '));          // ISA04: Security info (10)
        isa.append(elemSep);
        isa.append(padRight("ZZ", 2, ' '));        // ISA05: Sender qualifier (2)
        isa.append(elemSep);
        isa.append(padRight(senderId, 15, ' '));   // ISA06: Sender ID (15)
        isa.append(elemSep);
        isa.append(padRight("ZZ", 2, ' '));        // ISA07: Receiver qualifier (2)
        isa.append(elemSep);
        isa.append(padRight(receiverId, 15, ' ')); // ISA08: Receiver ID (15)
        isa.append(elemSep);
        isa.append(date6());                        // ISA09: Date YYMMDD (6)
        isa.append(elemSep);
        isa.append(time);                           // ISA10: Time HHMM (4)
        isa.append(elemSep);
        isa.append("U");                            // ISA11: Repetition sep (1)
        isa.append(elemSep);
        isa.append("00501");                        // ISA12: Version (5)
        isa.append(elemSep);
        isa.append(padRight(controlNum, 9, '0'));  // ISA13: Control number (9)
        isa.append(elemSep);
        isa.append("0");                            // ISA14: Ack requested (1)
        isa.append(elemSep);
        isa.append("P");                            // ISA15: Usage (1)
        isa.append(elemSep);
        isa.append(compSep);                        // ISA16: Component separator (1)
        isa.append(segTerm);
        segments.add(isa.toString());

        // GS
        String gsCode = resolveGsFuncCode(doc.getType());
        segments.add("GS" + elemSep + gsCode + elemSep + senderId + elemSep + receiverId
                + elemSep + date + elemSep + time + elemSep + "1" + elemSep + "X" + elemSep + "005010" + segTerm);

        // ST
        segments.add("ST" + elemSep + txnType + elemSep + "0001" + segTerm);

        // Transaction-specific header
        switch (doc.getType()) {
            case PURCHASE_ORDER -> segments.add("BEG" + elemSep + purpose + elemSep + "NE" + elemSep + docNumber + elemSep + elemSep + date + segTerm);
            case INVOICE -> segments.add("BIG" + elemSep + date + elemSep + docNumber + segTerm);
            case HEALTHCARE_CLAIM -> segments.add("BHT" + elemSep + "0019" + elemSep + "00" + elemSep + docNumber + elemSep + date + segTerm);
            case PAYMENT, REMITTANCE -> segments.add("BPR" + elemSep + "I" + elemSep + formatAmount(doc.getTotals()) + elemSep + "C" + elemSep + "ACH" + segTerm);
            default -> segments.add("BEG" + elemSep + "00" + elemSep + "NE" + elemSep + docNumber + elemSep + elemSep + date + segTerm);
        }

        // REF segments
        if (doc.getReferences() != null) {
            for (Reference ref : doc.getReferences()) {
                StringBuilder refSeg = new StringBuilder("REF" + elemSep + safe(ref.getQualifier()) + elemSep + safe(ref.getValue()));
                if (ref.getDescription() != null) refSeg.append(elemSep).append(ref.getDescription());
                refSeg.append(segTerm);
                segments.add(refSeg.toString());
            }
        }

        // DTP segments
        if (doc.getDates() != null) {
            for (DateInfo di : doc.getDates()) {
                if (di.getFormat() != null) {
                    segments.add("DTP" + elemSep + safe(di.getQualifier()) + elemSep + di.getFormat() + elemSep + safe(di.getValue()) + segTerm);
                } else {
                    segments.add("DTM" + elemSep + safe(di.getQualifier()) + elemSep + safe(di.getValue()) + segTerm);
                }
            }
        }

        // PER segments
        if (doc.getContacts() != null) {
            for (Contact ct : doc.getContacts()) {
                StringBuilder perSeg = new StringBuilder("PER" + elemSep + safe(ct.getType()) + elemSep + safe(ct.getName()));
                if (ct.getPhone() != null) perSeg.append(elemSep + "TE" + elemSep).append(ct.getPhone());
                if (ct.getEmail() != null) perSeg.append(elemSep + "EM" + elemSep).append(ct.getEmail());
                if (ct.getFax() != null) perSeg.append(elemSep + "FX" + elemSep).append(ct.getFax());
                perSeg.append(segTerm);
                segments.add(perSeg.toString());
            }
        }

        // NM1 segments for parties
        if (doc.getParties() != null) {
            for (Party party : doc.getParties()) {
                if (party.getRole() == Party.PartyRole.SENDER || party.getRole() == Party.PartyRole.RECEIVER)
                    continue; // Already in ISA/GS envelope
                String entityCode = partyRoleToX12(party.getRole());
                String[] nameParts = splitName(party.getName());
                String nm1 = "NM1" + elemSep + entityCode + elemSep + "1" + elemSep + nameParts[0] + elemSep + nameParts[1];
                if (party.getQualifier() != null || party.getId() != null) {
                    nm1 += elemSep + elemSep + elemSep + (party.getQualifier() != null ? elemSep + party.getQualifier() : "" + elemSep)
                            + elemSep + (party.getId() != null ? party.getId() : "");
                }
                segments.add(nm1 + segTerm);

                // N3/N4 address
                if (party.getAddress() != null) {
                    Address addr = party.getAddress();
                    if (addr.getLine1() != null) segments.add("N3" + elemSep + addr.getLine1() + segTerm);
                    if (addr.getCity() != null)
                        segments.add("N4" + elemSep + addr.getCity() + elemSep + safe(addr.getState())
                                + elemSep + safe(addr.getPostalCode()) + segTerm);
                }
            }
        }

        // Line items
        if (doc.getLineItems() != null) {
            for (LineItem item : doc.getLineItems()) {
                switch (doc.getType()) {
                    case PURCHASE_ORDER -> segments.add("PO1" + elemSep + item.getLineNumber()
                            + elemSep + (int) item.getQuantity()
                            + elemSep + safe(item.getUnitOfMeasure(), "EA")
                            + elemSep + formatPrice(item.getUnitPrice())
                            + elemSep + elemSep + "VP" + elemSep + safe(item.getProductCode()) + segTerm);
                    case INVOICE -> segments.add("IT1" + elemSep + item.getLineNumber()
                            + elemSep + (int) item.getQuantity()
                            + elemSep + safe(item.getUnitOfMeasure(), "EA")
                            + elemSep + formatPrice(item.getUnitPrice()) + segTerm);
                    case HEALTHCARE_CLAIM -> segments.add("SV1" + elemSep + safe(item.getProductCode())
                            + elemSep + formatPrice(item.getUnitPrice())
                            + elemSep + "UN" + elemSep + (int) item.getQuantity() + segTerm);
                    default -> segments.add("PO1" + elemSep + item.getLineNumber()
                            + elemSep + (int) item.getQuantity()
                            + elemSep + "EA" + elemSep + formatPrice(item.getUnitPrice()) + segTerm);
                }
            }
        }

        // Totals
        if (doc.getTotals() != null && doc.getTotals().getTotalAmount() > 0) {
            segments.add("TDS" + elemSep + (int) (doc.getTotals().getTotalAmount() * 100) + segTerm);
        }

        // Currency
        if (doc.getTotals() != null && doc.getTotals().getCurrency() != null) {
            segments.add("CUR" + elemSep + "BY" + elemSep + doc.getTotals().getCurrency() + segTerm);
        }

        // SE/GE/IEA trailers
        // SE count = number of segments from ST to SE inclusive (ST + body + SE)
        // ST is at index 2 (after ISA, GS), so body segments from index 2 onwards
        int stIndex = -1;
        for (int i = 0; i < segments.size(); i++) {
            if (segments.get(i).startsWith("ST" + elemSep)) {
                stIndex = i;
                break;
            }
        }
        int seCount = segments.size() - stIndex + 1; // +1 for SE itself
        segments.add("SE" + elemSep + seCount + elemSep + "0001" + segTerm);
        segments.add("GE" + elemSep + "1" + elemSep + "1" + segTerm);
        segments.add("IEA" + elemSep + "1" + elemSep + controlNum + segTerm);

        return String.join("\n", segments);
    }

    // === Canonical -> EDIFACT ===
    private String generateEdifact(CanonicalDocument doc) {
        String msgType = resolveEdifactMsgType(doc.getType());
        String date = compactDate(doc.getHeader() != null ? doc.getHeader().getDocumentDate() : null);
        String time = currentTime4();
        String docNumber = doc.getHeader() != null && doc.getHeader().getDocumentNumber() != null
                ? doc.getHeader().getDocumentNumber() : "DOC001";
        String senderId = findPartyId(doc, Party.PartyRole.SENDER, Party.PartyRole.BUYER);
        String receiverId = findPartyId(doc, Party.PartyRole.RECEIVER, Party.PartyRole.SELLER);
        String controlRef = nextControlNumber();

        List<String> segments = new ArrayList<>();

        // UNA (service string advice)
        segments.add("UNA:+.? '");

        // UNB (interchange header) — use YYMMDD for date
        segments.add("UNB+UNOA:4+" + senderId + ":ZZ+" + receiverId + ":ZZ+" + date6Edifact() + ":" + time + "+" + controlRef + "'");

        // UNH (message header)
        segments.add("UNH+1+" + msgType + ":D:01B:UN'");

        // BGM (beginning of message)
        String bgmCode = switch (doc.getType()) {
            case PURCHASE_ORDER -> "220";
            case INVOICE -> "380";
            case SHIPMENT_NOTICE -> "351";
            default -> "220";
        };
        segments.add("BGM+" + bgmCode + "+" + docNumber + "+9'");

        // DTM (date/time)
        if (date != null && !date.isEmpty()) {
            segments.add("DTM+137:" + date + ":102'");
        }

        // RFF (references)
        if (doc.getReferences() != null) {
            for (Reference ref : doc.getReferences()) {
                segments.add("RFF+" + safe(ref.getQualifier()) + ":" + safe(ref.getValue()) + "'");
            }
        }

        // NAD (name and address) for parties
        if (doc.getParties() != null) {
            for (Party party : doc.getParties()) {
                if (party.getRole() == Party.PartyRole.SENDER || party.getRole() == Party.PartyRole.RECEIVER)
                    continue;
                String nadCode = partyRoleToEdifact(party.getRole());
                String partyId = safe(party.getId());
                String partyName = safe(party.getName());
                segments.add("NAD+" + nadCode + "+" + partyId + "::91++" + partyName + "'");

                if (party.getAddress() != null) {
                    Address addr = party.getAddress();
                    if (addr.getLine1() != null) {
                        segments.add("NAD+" + nadCode + "+" + partyId + "::91++" + partyName
                                + "+" + safe(addr.getLine1())
                                + "+" + safe(addr.getCity())
                                + "++" + safe(addr.getPostalCode())
                                + "+" + safe(addr.getCountry(), "US") + "'");
                        // Remove the simple NAD we just added
                        segments.remove(segments.size() - 2);
                    }
                }
            }
        }

        // FTX (free text notes)
        if (doc.getNotes() != null) {
            for (Note note : doc.getNotes()) {
                segments.add("FTX+" + safe(note.getType(), "AAA") + "+++" + safe(note.getText()) + "'");
            }
        }

        // CUX (currency)
        if (doc.getTotals() != null && doc.getTotals().getCurrency() != null) {
            segments.add("CUX+2:" + doc.getTotals().getCurrency() + ":4'");
        }

        // LIN/QTY/PRI for line items
        if (doc.getLineItems() != null) {
            for (LineItem item : doc.getLineItems()) {
                segments.add("LIN+" + item.getLineNumber() + "++" + safe(item.getProductCode()) + ":SA'");
                if (item.getQuantity() > 0) {
                    segments.add("QTY+21:" + (int) item.getQuantity() + "'");
                }
                if (item.getUnitPrice() > 0) {
                    segments.add("PRI+AAA:" + formatPrice(item.getUnitPrice()) + "'");
                }
                if (item.getDescription() != null) {
                    segments.add("IMD+F++:::" + item.getDescription() + "'");
                }
            }
        }

        // UNS (section control)
        segments.add("UNS+S'");

        // MOA (total amount)
        if (doc.getTotals() != null && doc.getTotals().getTotalAmount() > 0) {
            segments.add("MOA+86:" + formatPrice(doc.getTotals().getTotalAmount()) + "'");
        }

        // CNT (control total -- line item count)
        int lineCount = doc.getLineItems() != null ? doc.getLineItems().size() : 0;
        segments.add("CNT+2:" + lineCount + "'");

        // UNT (message trailer) -- count segments from UNH to UNT inclusive
        // Find UNH index
        int unhIndex = -1;
        for (int i = 0; i < segments.size(); i++) {
            if (segments.get(i).startsWith("UNH+")) {
                unhIndex = i;
                break;
            }
        }
        int untCount = segments.size() - unhIndex + 1; // +1 for UNT itself
        segments.add("UNT+" + untCount + "+1'");

        // UNZ (interchange trailer)
        segments.add("UNZ+1+" + controlRef + "'");

        return String.join("\n", segments);
    }

    // === Canonical -> HL7 ===
    private String generateHl7(CanonicalDocument doc) {
        String date = compactDate(doc.getHeader() != null ? doc.getHeader().getDocumentDate() : null);
        String docNumber = doc.getHeader() != null && doc.getHeader().getDocumentNumber() != null
                ? doc.getHeader().getDocumentNumber() : "MSG001";
        String senderId = findPartyId(doc, Party.PartyRole.SENDER, Party.PartyRole.PROVIDER);
        String receiverId = findPartyId(doc, Party.PartyRole.RECEIVER, Party.PartyRole.PROVIDER);

        List<String> segments = new ArrayList<>();

        // MSH
        segments.add("MSH|^~\\&|" + senderId + "||" + receiverId + "||" + date + "||ADT^A01|" + docNumber + "|P|2.5");

        // EVN (event type)
        segments.add("EVN|A01|" + date);

        // PID (patient info)
        Party patient = findParty(doc, Party.PartyRole.PATIENT);
        if (patient != null) {
            String[] name = splitName(patient.getName());
            segments.add("PID|1||" + safe(patient.getId()) + "||" + name[0] + "^" + name[1]);
        } else {
            segments.add("PID|1||UNKNOWN||UNKNOWN^PATIENT");
        }

        // PV1 (patient visit)
        Party provider = findParty(doc, Party.PartyRole.PROVIDER);
        String providerName = provider != null && provider.getName() != null
                ? provider.getName().replace(" ", "^") : "";
        segments.add("PV1|1|I|||||||" + providerName);

        return String.join("\r", segments); // HL7 uses \r as segment delimiter
    }

    // === Canonical -> SWIFT MT103 ===
    private String generateSwift(CanonicalDocument doc) {
        String docNumber = doc.getHeader() != null && doc.getHeader().getDocumentNumber() != null
                ? doc.getHeader().getDocumentNumber() : "REF001";
        double amount = doc.getTotals() != null ? doc.getTotals().getTotalAmount() : 0;
        String currency = doc.getTotals() != null && doc.getTotals().getCurrency() != null
                ? doc.getTotals().getCurrency() : "USD";
        String date = compactDate(doc.getHeader() != null ? doc.getHeader().getDocumentDate() : null);

        Party payer = findParty(doc, Party.PartyRole.PAYER, Party.PartyRole.SENDER);
        Party payee = findParty(doc, Party.PartyRole.PAYEE, Party.PartyRole.RECEIVER);
        Party senderBank = findParty(doc, Party.PartyRole.BANK_SENDER);
        Party receiverBank = findParty(doc, Party.PartyRole.BANK_RECEIVER);

        StringBuilder sb = new StringBuilder();
        sb.append("{1:F01").append(safe(senderBank != null ? senderBank.getId() : "BANKUS33")).append("0000000000}")
          .append("{2:O1030000").append(date).append(safe(receiverBank != null ? receiverBank.getId() : "BANKGB22")).append("00000000000000N}");
        sb.append("{4:\n");
        sb.append(":20:").append(docNumber).append("\n");
        sb.append(":23B:CRED\n");
        sb.append(":32A:").append(date).append(currency).append(String.format("%.2f", amount).replace(",", ".")).append("\n");
        if (senderBank != null && senderBank.getId() != null)
            sb.append(":52A:").append(senderBank.getId()).append("\n");
        if (payer != null) sb.append(":50K:/\n").append(safe(payer.getName())).append("\n");
        if (receiverBank != null && receiverBank.getId() != null)
            sb.append(":57A:").append(receiverBank.getId()).append("\n");
        if (payee != null) sb.append(":59:/\n").append(safe(payee.getName())).append("\n");
        sb.append(":71A:OUR\n");
        sb.append("-}");

        return sb.toString();
    }

    // ===================================================================
    // Reverse conversion helpers
    // ===================================================================

    private String resolveX12TxnType(DocumentType type) {
        return switch (type) {
            case PURCHASE_ORDER -> "850";
            case INVOICE -> "810";
            case SHIPMENT_NOTICE -> "856";
            case PAYMENT, REMITTANCE -> "820";
            case HEALTHCARE_CLAIM -> "837";
            case ELIGIBILITY_INQUIRY -> "270";
            case ELIGIBILITY_RESPONSE -> "271";
            case FUNCTIONAL_ACK -> "997";
            default -> "850";
        };
    }

    private String resolveEdifactMsgType(DocumentType type) {
        return switch (type) {
            case PURCHASE_ORDER -> "ORDERS";
            case INVOICE -> "INVOIC";
            case SHIPMENT_NOTICE -> "DESADV";
            case PAYMENT, REMITTANCE -> "PAYORD";
            case CUSTOMS_DECLARATION -> "CUSCAR";
            default -> "ORDERS";
        };
    }

    private String resolveGsFuncCode(DocumentType type) {
        return switch (type) {
            case PURCHASE_ORDER -> "PO";
            case INVOICE -> "IN";
            case HEALTHCARE_CLAIM -> "HC";
            case PAYMENT, REMITTANCE -> "RA";
            default -> "PO";
        };
    }

    private String partyRoleToX12(Party.PartyRole role) {
        return switch (role) {
            case BUYER -> "BY";
            case SELLER -> "SE";
            case SHIP_TO -> "ST";
            case SHIP_FROM -> "SF";
            case BILL_TO -> "BT";
            case PAYER -> "PR";
            case PAYEE -> "PE";
            case PATIENT -> "IL";
            case PROVIDER -> "82";
            default -> "BY";
        };
    }

    private String partyRoleToEdifact(Party.PartyRole role) {
        return switch (role) {
            case BUYER -> "BY";
            case SELLER -> "SE";
            case SHIP_TO -> "ST";
            case BILL_TO -> "IV";
            case SHIP_FROM -> "SF";
            case PAYER -> "PR";
            case PAYEE -> "PE";
            default -> "BY";
        };
    }

    private String encodePurpose(String purpose) {
        if (purpose == null) return "00";
        return switch (purpose.toLowerCase()) {
            case "original" -> "00";
            case "cancellation" -> "01";
            case "replace" -> "05";
            case "confirmation" -> "06";
            case "duplicate" -> "07";
            default -> "00";
        };
    }

    private String findPartyId(CanonicalDocument doc, Party.PartyRole... roles) {
        if (doc.getParties() == null) return "UNKNOWN";
        for (Party.PartyRole role : roles) {
            for (Party p : doc.getParties()) {
                if (p.getRole() == role) {
                    return p.getId() != null ? p.getId() : (p.getName() != null ? p.getName() : "UNKNOWN");
                }
            }
        }
        return "UNKNOWN";
    }

    private Party findParty(CanonicalDocument doc, Party.PartyRole... roles) {
        if (doc.getParties() == null) return null;
        for (Party.PartyRole role : roles) {
            for (Party p : doc.getParties()) {
                if (p.getRole() == role) return p;
            }
        }
        return null;
    }

    private String[] splitName(String fullName) {
        if (fullName == null || fullName.isEmpty()) return new String[]{"", ""};
        String[] parts = fullName.trim().split("\\s+", 2);
        if (parts.length == 1) return new String[]{parts[0], ""};
        // EDI convention: Last*First
        return new String[]{parts[parts.length - 1], parts[0]};
    }

    private String compactDate(String date) {
        if (date == null || date.isEmpty()) {
            // Use today's date
            java.time.LocalDate now = java.time.LocalDate.now();
            return String.format("%04d%02d%02d", now.getYear(), now.getMonthValue(), now.getDayOfMonth());
        }
        return date.replaceAll("[^0-9]", "");
    }

    private String date6() {
        java.time.LocalDate now = java.time.LocalDate.now();
        return String.format("%02d%02d%02d", now.getYear() % 100, now.getMonthValue(), now.getDayOfMonth());
    }

    private String date6Edifact() {
        java.time.LocalDate now = java.time.LocalDate.now();
        return String.format("%02d%02d%02d", now.getYear() % 100, now.getMonthValue(), now.getDayOfMonth());
    }

    private String currentTime4() {
        java.time.LocalTime now = java.time.LocalTime.now();
        return String.format("%02d%02d", now.getHour(), now.getMinute());
    }

    private String padRight(String s, int len, char c) {
        if (s == null) s = "";
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() < len) sb.append(c);
        return sb.substring(0, len);
    }

    private String safe(String s) {
        return s != null ? s : "";
    }

    private String safe(String s, String defaultVal) {
        return s != null && !s.isEmpty() ? s : defaultVal;
    }

    private String formatPrice(double price) {
        if (price == (int) price) return String.valueOf((int) price);
        return String.format("%.2f", price);
    }

    private String formatAmount(MonetaryTotal totals) {
        if (totals == null || totals.getTotalAmount() == 0) return "0.00";
        return String.format("%.2f", totals.getTotalAmount());
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
