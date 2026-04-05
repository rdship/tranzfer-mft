package com.filetransfer.edi.model;

import lombok.*;
import java.util.*;

/**
 * Canonical Data Model — the universal business representation of ANY EDI document.
 *
 * Instead of dealing with format-specific segments (ISA, UNB, MSH),
 * this model represents the BUSINESS MEANING: who sent what to whom, with what items.
 *
 * Inspired by Orderful's Mosaic approach: one schema per document type,
 * partner adapters on top. Any EDI format → Canonical → Any EDI format.
 *
 * Supported document types:
 *   PURCHASE_ORDER, INVOICE, SHIPMENT_NOTICE, PAYMENT, HEALTHCARE_CLAIM,
 *   ELIGIBILITY, PATIENT_ADMISSION, BANK_STATEMENT, WIRE_TRANSFER,
 *   TRADE_ORDER, CUSTOMS_DECLARATION
 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class CanonicalDocument {
    private String documentId;
    private DocumentType type;
    private String sourceFormat;       // X12, EDIFACT, HL7, etc.
    private String sourceDocumentType; // 850, ORDERS, ADT_A01, etc.
    private Header header;
    private List<LineItem> lineItems;
    private List<Party> parties;
    private MonetaryTotal totals;
    private Map<String, Object> extensions; // Format-specific extras
    private String createdAt;

    public enum DocumentType {
        PURCHASE_ORDER, INVOICE, SHIPMENT_NOTICE, PAYMENT, REMITTANCE,
        HEALTHCARE_CLAIM, ELIGIBILITY_INQUIRY, ELIGIBILITY_RESPONSE,
        PATIENT_ADMISSION, BANK_STATEMENT, WIRE_TRANSFER,
        TRADE_ORDER, CUSTOMS_DECLARATION, FUNCTIONAL_ACK, UNKNOWN
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class Header {
        private String documentNumber;   // PO number, invoice number, claim ID, etc.
        private String documentDate;
        private String dueDate;
        private String currency;
        private String referenceNumber;
        private String purpose;          // Original, Duplicate, Cancellation, etc.
        private String status;
        private Map<String, String> metadata;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class Party {
        private PartyRole role;
        private String id;
        private String name;
        private String qualifier;  // DUNS, EIN, NPI, GLN, BIC, etc.
        private Address address;
        private String phone;
        private String email;

        public enum PartyRole {
            SENDER, RECEIVER, BUYER, SELLER, SHIP_TO, SHIP_FROM,
            BILL_TO, PAYER, PAYEE, PATIENT, PROVIDER, SUBSCRIBER,
            EMPLOYER, BROKER, CARRIER, BANK_SENDER, BANK_RECEIVER
        }
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class Address {
        private String line1;
        private String line2;
        private String city;
        private String state;
        private String postalCode;
        private String country;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class LineItem {
        private int lineNumber;
        private String itemId;
        private String description;
        private double quantity;
        private String unitOfMeasure;
        private double unitPrice;
        private double lineTotal;
        private String productCode;     // UPC, EAN, SKU, NDC, HCPCS, CPT
        private String codeQualifier;
        private Map<String, String> attributes;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class MonetaryTotal {
        private double totalAmount;
        private double taxAmount;
        private double shippingAmount;
        private double discountAmount;
        private double netAmount;
        private String currency;
    }
}
