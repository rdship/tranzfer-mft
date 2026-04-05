package com.filetransfer.edi.format;

import lombok.*;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Pre-built EDI templates for common transactions.
 * Users pick a template, fill in their data, and get valid EDI.
 * No need to know the spec.
 */
@Service
public class TemplateLibrary {

    public List<Template> listTemplates() {
        return List.of(
            Template.builder().id("x12-837-claim").name("Healthcare Claim (837)")
                    .format("X12").description("Submit a medical claim to insurance")
                    .fields(List.of(
                            field("senderName", "Your company name", true),
                            field("senderId", "Your EDI ID", true),
                            field("receiverName", "Insurance company name", true),
                            field("receiverId", "Insurance EDI ID", true),
                            field("patientLastName", "Patient last name", true),
                            field("patientFirstName", "Patient first name", true),
                            field("claimId", "Claim reference number", true),
                            field("claimAmount", "Total claim amount (e.g. 1500.00)", true),
                            field("serviceDate", "Date of service (YYYYMMDD)", true),
                            field("diagnosisCode", "ICD-10 diagnosis code", false)
                    )).build(),

            Template.builder().id("x12-850-po").name("Purchase Order (850)")
                    .format("X12").description("Send a purchase order to a supplier")
                    .fields(List.of(
                            field("buyerName", "Buyer company name", true),
                            field("buyerId", "Buyer EDI ID", true),
                            field("sellerName", "Seller company name", true),
                            field("sellerId", "Seller EDI ID", true),
                            field("poNumber", "Purchase order number", true),
                            field("poDate", "Order date (YYYYMMDD)", true),
                            field("itemNumber", "Product/item number", true),
                            field("quantity", "Order quantity", true),
                            field("unitPrice", "Price per unit", true),
                            field("shipToAddress", "Shipping address", false)
                    )).build(),

            Template.builder().id("x12-810-invoice").name("Invoice (810)")
                    .format("X12").description("Send an invoice for goods/services")
                    .fields(List.of(
                            field("sellerName", "Your company name", true),
                            field("sellerId", "Your EDI ID", true),
                            field("buyerName", "Customer name", true),
                            field("buyerId", "Customer EDI ID", true),
                            field("invoiceNumber", "Invoice number", true),
                            field("invoiceDate", "Invoice date (YYYYMMDD)", true),
                            field("totalAmount", "Total amount", true)
                    )).build(),

            Template.builder().id("edifact-orders").name("EDIFACT Purchase Order (ORDERS)")
                    .format("EDIFACT").description("International purchase order")
                    .fields(List.of(
                            field("buyerId", "Buyer ID", true),
                            field("sellerId", "Seller ID", true),
                            field("orderNumber", "Order number", true),
                            field("orderDate", "Order date (YYYYMMDD)", true),
                            field("itemCode", "Item code", true),
                            field("quantity", "Quantity", true)
                    )).build(),

            Template.builder().id("hl7-adt-a01").name("HL7 Patient Admission (ADT^A01)")
                    .format("HL7").description("Notify systems that a patient has been admitted")
                    .fields(List.of(
                            field("sendingApp", "Sending application name", true),
                            field("sendingFacility", "Hospital/facility name", true),
                            field("patientId", "Patient ID (MRN)", true),
                            field("patientLastName", "Patient last name", true),
                            field("patientFirstName", "Patient first name", true),
                            field("dateOfBirth", "Date of birth (YYYYMMDD)", true),
                            field("gender", "M or F", true),
                            field("ward", "Ward/unit (e.g. ICU)", false)
                    )).build(),

            Template.builder().id("swift-mt103").name("SWIFT MT103 Payment")
                    .format("SWIFT_MT").description("International wire transfer between banks")
                    .fields(List.of(
                            field("senderBic", "Sender bank BIC code", true),
                            field("receiverBic", "Receiver bank BIC code", true),
                            field("reference", "Transaction reference", true),
                            field("amount", "Amount (e.g. USD50000,00)", true),
                            field("orderingName", "Sender name", true),
                            field("orderingAccount", "Sender account number", true),
                            field("beneficiaryName", "Receiver name", true),
                            field("beneficiaryAccount", "Receiver account number", true)
                    )).build()
        );
    }

    public String generate(String templateId, Map<String, String> values) {
        return switch (templateId) {
            case "x12-837-claim" -> generateX12_837(values);
            case "x12-850-po" -> generateX12_850(values);
            case "x12-810-invoice" -> generateX12_810(values);
            case "hl7-adt-a01" -> generateHl7Adt(values);
            default -> "Template not found: " + templateId;
        };
    }

    private String generateX12_837(Map<String, String> v) {
        String date = v.getOrDefault("serviceDate", "20210101").substring(0, 6);
        return "ISA*00*          *00*          *ZZ*" + pad(v.get("senderId"), 15) + "*ZZ*" + pad(v.get("receiverId"), 15) + "*" + date + "*1200*^*00501*000000001*0*P*:~\n" +
                "GS*HP*" + v.get("senderId") + "*" + v.get("receiverId") + "*" + v.getOrDefault("serviceDate", "20210101") + "*1200*1*X*005010X222A1~\n" +
                "ST*837*0001*005010X222A1~\n" +
                "BHT*0019*00*" + v.get("claimId") + "*" + v.getOrDefault("serviceDate", "20210101") + "*1200*CH~\n" +
                "NM1*85*2*" + v.get("senderName") + "****XX*1234567890~\n" +
                "NM1*40*2*" + v.get("receiverName") + "****46*" + v.get("receiverId") + "~\n" +
                "NM1*IL*1*" + v.get("patientLastName") + "*" + v.get("patientFirstName") + "~\n" +
                "CLM*" + v.get("claimId") + "*" + v.get("claimAmount") + "*11:B:1~\n" +
                "DTP*472*D8*" + v.getOrDefault("serviceDate", "20210101") + "~\n" +
                (v.containsKey("diagnosisCode") ? "HI*ABK:" + v.get("diagnosisCode") + "~\n" : "") +
                "SE*" + (v.containsKey("diagnosisCode") ? "10" : "9") + "*0001~\n" +
                "GE*1*1~\n" +
                "IEA*1*000000001~";
    }

    private String generateX12_850(Map<String, String> v) {
        return "ISA*00*          *00*          *ZZ*" + pad(v.get("buyerId"), 15) + "*ZZ*" + pad(v.get("sellerId"), 15) + "*" + v.getOrDefault("poDate", "20210101").substring(0, 6) + "*1200*^*00501*000000001*0*P*:~\n" +
                "GS*PO*" + v.get("buyerId") + "*" + v.get("sellerId") + "*" + v.getOrDefault("poDate", "20210101") + "*1200*1*X*005010~\n" +
                "ST*850*0001~\n" +
                "BEG*00*NE*" + v.get("poNumber") + "**" + v.getOrDefault("poDate", "20210101") + "~\n" +
                "NM1*BY*2*" + v.get("buyerName") + "~\n" +
                "NM1*SE*2*" + v.get("sellerName") + "~\n" +
                "PO1*1*" + v.get("quantity") + "*EA*" + v.get("unitPrice") + "*PE*VP*" + v.get("itemNumber") + "~\n" +
                "CTT*1~\n" +
                "SE*8*0001~\nGE*1*1~\nIEA*1*000000001~";
    }

    private String generateX12_810(Map<String, String> v) {
        return "ISA*00*          *00*          *ZZ*" + pad(v.get("sellerId"), 15) + "*ZZ*" + pad(v.get("buyerId"), 15) + "*" + v.getOrDefault("invoiceDate", "20210101").substring(0, 6) + "*1200*^*00501*000000001*0*P*:~\n" +
                "GS*IN*" + v.get("sellerId") + "*" + v.get("buyerId") + "*" + v.getOrDefault("invoiceDate", "20210101") + "*1200*1*X*005010~\n" +
                "ST*810*0001~\n" +
                "BIG*" + v.getOrDefault("invoiceDate", "20210101") + "*" + v.get("invoiceNumber") + "~\n" +
                "NM1*SE*2*" + v.get("sellerName") + "~\n" +
                "NM1*BY*2*" + v.get("buyerName") + "~\n" +
                "TDS*" + v.get("totalAmount").replace(".", "") + "~\n" +
                "SE*7*0001~\nGE*1*1~\nIEA*1*000000001~";
    }

    private String generateHl7Adt(Map<String, String> v) {
        return "MSH|^~\\&|" + v.get("sendingApp") + "|" + v.get("sendingFacility") + "|||" +
                v.getOrDefault("dateOfBirth", "20210101") + "||ADT^A01|MSG001|P|2.5\r" +
                "PID|||" + v.get("patientId") + "||" + v.get("patientLastName") + "^" + v.get("patientFirstName") + "||" +
                v.getOrDefault("dateOfBirth", "19800101") + "|" + v.getOrDefault("gender", "M") + "\r" +
                "PV1||I|" + v.getOrDefault("ward", "GENERAL") + "^101^A";
    }

    private String pad(String s, int len) { if (s == null) s = ""; while (s.length() < len) s += " "; return s.substring(0, len); }

    private TemplateField field(String id, String label, boolean required) {
        return TemplateField.builder().id(id).label(label).required(required).build();
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class Template {
        private String id;
        private String name;
        private String format;
        private String description;
        private List<TemplateField> fields;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class TemplateField {
        private String id;
        private String label;
        private boolean required;
    }
}
