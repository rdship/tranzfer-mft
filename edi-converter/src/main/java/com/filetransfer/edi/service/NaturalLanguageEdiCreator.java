package com.filetransfer.edi.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.filetransfer.edi.format.TemplateLibrary;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Natural Language EDI Creator — describe what you need in English, get valid EDI.
 *
 * "Send a purchase order for 500 widgets at $12.50 each to Acme Corp"
 *   → generates a valid X12 850 Purchase Order
 *
 * "Create an invoice for $15,000 from GlobalSupplier to RetailBuyer"
 *   → generates a valid X12 810 Invoice
 *
 * "Generate a healthcare claim for patient John Doe, $1500, diagnosis J06.9"
 *   → generates a valid X12 837 Healthcare Claim
 *
 * No LLM required — uses pattern matching and entity extraction.
 * With LLM (optional): handles more complex/ambiguous requests.
 */
@Service @RequiredArgsConstructor @Slf4j
public class NaturalLanguageEdiCreator {

    private final TemplateLibrary templateLibrary;
    private final ClaudeApiClient claudeApiClient;

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private static final String NL_SYSTEM_PROMPT = """
            You are an EDI generation expert. Given a natural language description, determine:
            1. The EDI document type (one of: X12_850, X12_810, X12_837, X12_856, X12_820, SWIFT_MT103, HL7_ADT, EDIFACT_ORDERS)
            2. All relevant fields/entities from the description

            Return ONLY valid JSON in this exact format:
            {
              "type": "X12_850",
              "label": "Purchase Order (X12 850)",
              "fields": { "key": "value" }
            }

            Supported field keys per type:
            - X12_850: buyerName, buyerId, sellerName, sellerId, poNumber, poDate, quantity, unitPrice, itemNumber, unitOfMeasure
            - X12_810: sellerName, sellerId, buyerName, buyerId, invoiceNumber, invoiceDate, totalAmount
            - X12_837: senderName, senderId, receiverName, receiverId, patientFirstName, patientLastName, claimId, claimAmount, serviceDate, diagnosisCode
            - X12_856: shipperName, shipperId, receiverName, receiverId, shipmentId, carrierName, trackingNumber
            - X12_820: payerName, payerId, payeeName, payeeId, paymentAmount, paymentDate
            - SWIFT_MT103: senderBic, receiverBic, reference, amount, currency, orderingName, orderingAccount, beneficiaryName, beneficiaryAccount
            - HL7_ADT: sendingApp, sendingFacility, patientId, patientFirstName, patientLastName, dateOfBirth, gender, ward
            - EDIFACT_ORDERS: buyerName, sellerName, orderNumber, quantity, itemDescription

            If you cannot determine the type, set type to null.""";

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class NlEdiResult {
        private String intent;           // What we understood
        private String documentType;     // X12 850, EDIFACT ORDERS, etc.
        private String generatedEdi;     // The actual EDI content
        private Map<String, String> extractedFields;
        private int confidence;          // 0-100
        private List<String> warnings;
        private String explanation;      // Human-readable explanation of what was generated
    }

    // Intent patterns
    private static final List<IntentPattern> PATTERNS = List.of(
            // Purchase Orders
            new IntentPattern("X12_850", Pattern.compile(
                    "(?i)(send|create|generate|make|write|build)\\s+(a\\s+)?(purchase\\s*order|PO|order)"),
                    "Purchase Order (X12 850)"),
            // Invoices
            new IntentPattern("X12_810", Pattern.compile(
                    "(?i)(send|create|generate|make|write|build)\\s+(a\\s+|an\\s+)?(invoice|bill)"),
                    "Invoice (X12 810)"),
            // Healthcare Claims
            new IntentPattern("X12_837", Pattern.compile(
                    "(?i)(send|create|generate|make|write|build)\\s+(a\\s+)?(health\\s*care\\s*claim|claim|medical\\s*claim)"),
                    "Healthcare Claim (X12 837)"),
            // Ship Notice
            new IntentPattern("X12_856", Pattern.compile(
                    "(?i)(send|create|generate|make|write|build)\\s+(a\\s+)?(ship\\s*notice|shipment\\s*notice|ASN|advance\\s*ship)"),
                    "Ship Notice (X12 856)"),
            // SWIFT Transfer — must be checked BEFORE generic "payment"
            new IntentPattern("SWIFT_MT103", Pattern.compile(
                    "(?i)(send|create|generate|make|write|build|transfer)\\s+(a\\s+)?(wire\\s*transfer|swift|MT103|bank\\s*transfer|money\\s*transfer|wire)"),
                    "Wire Transfer (SWIFT MT103)"),
            // Payment (820)
            new IntentPattern("X12_820", Pattern.compile(
                    "(?i)(send|create|generate|make|write|build)\\s+(a\\s+)?(payment|remittance)"),
                    "Payment (X12 820)"),
            // HL7 Patient Admission
            new IntentPattern("HL7_ADT", Pattern.compile(
                    "(?i)(admit|create|generate)\\s+(a\\s+)?(patient|admission|ADT)"),
                    "Patient Admission (HL7 ADT)"),
            // EDIFACT Order
            new IntentPattern("EDIFACT_ORDERS", Pattern.compile(
                    "(?i)(send|create|generate)\\s+(a\\s+|an\\s+)?(edifact\\s*order|international\\s*order|UN\\s*order)"),
                    "International Order (EDIFACT ORDERS)")
    );

    private record IntentPattern(String type, Pattern pattern, String label) {}

    // Entity extraction patterns
    private static final Pattern QUANTITY_PATTERN = Pattern.compile("(\\d+[,\\d]*)\\s*(units?|items?|pieces?|widgets?|boxes?|each|qty)?", Pattern.CASE_INSENSITIVE);
    private static final Pattern PRICE_PATTERN = Pattern.compile("\\$([\\d,]+\\.?\\d*)(?:\\s*(?:each|per|per\\s*unit))?", Pattern.CASE_INSENSITIVE);
    private static final Pattern AMOUNT_PATTERN = Pattern.compile("(?:for|amount|total|\\$)\\s*\\$?([\\d,]+\\.?\\d*)", Pattern.CASE_INSENSITIVE);
    private static final Pattern COMPANY_PATTERN = Pattern.compile("(?:to|from|for|buyer|seller|partner|company|vendor)\\s+([A-Z][a-zA-Z]+(?:\\s+[A-Z][a-zA-Z]*)*)", Pattern.CASE_INSENSITIVE);
    private static final Pattern NAME_PATTERN = Pattern.compile("(?:patient|person|name)\\s+([A-Z][a-z]+)\\s+([A-Z][a-z]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern DIAGNOSIS_PATTERN = Pattern.compile("(?:diagnosis|diag|dx|ICD)\\s*:?\\s*([A-Z]\\d{2}\\.?\\d*)", Pattern.CASE_INSENSITIVE);
    private static final Pattern DATE_PATTERN = Pattern.compile("(?:by|on|date|due|ship)\\s+(\\d{1,2}[/-]\\d{1,2}[/-]\\d{2,4}|(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)\\w*\\s+\\d{1,2}(?:,?\\s+\\d{4})?)", Pattern.CASE_INSENSITIVE);
    private static final Pattern PO_NUM_PATTERN = Pattern.compile("(?:PO|order|invoice)\\s*#?\\s*(\\w+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern ITEM_PATTERN = Pattern.compile("(?:item|sku|product|part)\\s*#?\\s*(\\w+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern BIC_PATTERN = Pattern.compile("(?:BIC|SWIFT)\\s*:?\\s*([A-Z]{8,11})", Pattern.CASE_INSENSITIVE);
    private static final Pattern ACCOUNT_PATTERN = Pattern.compile("(?:account|acct)\\s*#?\\s*(\\d+)", Pattern.CASE_INSENSITIVE);

    public NlEdiResult create(String naturalLanguage) {
        if (naturalLanguage == null || naturalLanguage.isBlank()) {
            return NlEdiResult.builder().confidence(0)
                    .warnings(List.of("Empty input — please describe what you need"))
                    .explanation("No input provided").build();
        }

        // Try Claude AI first if available
        if (claudeApiClient.isAvailable()) {
            try {
                return createWithClaude(naturalLanguage);
            } catch (Exception e) {
                log.warn("Claude NL EDI creation failed, falling back to pattern matching: {}", e.getMessage());
            }
        }

        return createFallback(naturalLanguage);
    }

    /**
     * AI-powered EDI creation using Claude to understand intent and extract entities.
     */
    @SuppressWarnings("unchecked")
    private NlEdiResult createWithClaude(String naturalLanguage) throws Exception {
        String response = claudeApiClient.call(NL_SYSTEM_PROMPT, naturalLanguage);
        String json = ClaudeApiClient.extractJson(response);
        Map<String, Object> parsed = objectMapper.readValue(json, Map.class);

        String type = (String) parsed.get("type");
        String label = (String) parsed.get("label");
        Map<String, String> fields = new LinkedHashMap<>();

        Object rawFields = parsed.get("fields");
        if (rawFields instanceof Map<?, ?> fieldMap) {
            fieldMap.forEach((k, v) -> {
                if (v != null) fields.put(String.valueOf(k), String.valueOf(v));
            });
        }

        if (type == null || type.isBlank() || "null".equals(type)) {
            // Claude couldn't determine intent — fall back to regex
            log.debug("Claude couldn't determine EDI type, falling back to regex");
            return createFallback(naturalLanguage);
        }

        // Use Claude's extracted type and fields, but drive through existing generators
        return generateFromTypeAndFields(type, label, fields, naturalLanguage);
    }

    /**
     * Core generation logic shared between AI and fallback paths.
     * Takes a resolved type and fields map, generates EDI through existing generators.
     */
    private NlEdiResult generateFromTypeAndFields(String detectedType, String detectedLabel,
                                                   Map<String, String> fields, String naturalLanguage) {
        List<String> warnings = new ArrayList<>();
        String edi;
        String explanation;
        int confidence;

        switch (detectedType) {
            case "X12_850" -> {
                fillDefaults850(fields);
                edi = generate850(fields);
                confidence = calculateConfidence(fields, List.of("buyerName", "sellerName", "quantity", "unitPrice"));
                explanation = String.format("Generated a Purchase Order from %s to %s for %s items at $%s each (PO# %s)",
                        fields.get("buyerName"), fields.get("sellerName"),
                        fields.get("quantity"), fields.get("unitPrice"), fields.get("poNumber"));
            }
            case "X12_810" -> {
                fillDefaults810(fields);
                edi = generate810(fields);
                confidence = calculateConfidence(fields, List.of("sellerName", "buyerName", "totalAmount"));
                explanation = String.format("Generated an Invoice from %s to %s for $%s (Invoice# %s)",
                        fields.get("sellerName"), fields.get("buyerName"),
                        fields.get("totalAmount"), fields.get("invoiceNumber"));
            }
            case "X12_837" -> {
                fillDefaults837(fields);
                edi = generate837(fields);
                confidence = calculateConfidence(fields, List.of("patientLastName", "claimAmount"));
                explanation = String.format("Generated a Healthcare Claim for patient %s %s, amount $%s, diagnosis %s",
                        fields.get("patientFirstName"), fields.get("patientLastName"),
                        fields.get("claimAmount"), fields.get("diagnosisCode"));
            }
            case "HL7_ADT" -> {
                fillDefaultsHl7(fields);
                edi = generateHl7Adt(fields);
                confidence = calculateConfidence(fields, List.of("patientLastName", "patientFirstName"));
                explanation = String.format("Generated HL7 Patient Admission for %s %s (ID: %s)",
                        fields.get("patientFirstName"), fields.get("patientLastName"), fields.get("patientId"));
            }
            case "SWIFT_MT103" -> {
                fillDefaultsSwift(fields);
                edi = generateSwiftMt103(fields);
                confidence = calculateConfidence(fields, List.of("amount"));
                explanation = String.format("Generated SWIFT MT103 wire transfer for %s %s from %s to %s",
                        fields.getOrDefault("currency", "USD"), fields.get("amount"),
                        fields.get("orderingName"), fields.get("beneficiaryName"));
            }
            default -> {
                edi = ""; confidence = 0;
                explanation = "Document type recognized but generator not yet implemented for: " + detectedType;
                warnings.add("Type " + detectedType + " generation coming soon");
            }
        }

        // Add warnings for missing fields
        if (fields.values().stream().anyMatch(v -> v.startsWith("DEFAULT_"))) {
            warnings.add("Some fields used default values — review and adjust as needed");
        }

        return NlEdiResult.builder()
                .intent(detectedLabel)
                .documentType(detectedType)
                .generatedEdi(edi)
                .extractedFields(fields)
                .confidence(confidence)
                .warnings(warnings)
                .explanation(explanation)
                .build();
    }

    /**
     * Fallback: create EDI using regex pattern matching and entity extraction.
     * Used when no API key is configured or when Claude call fails.
     */
    NlEdiResult createFallback(String naturalLanguage) {
        // Step 1: Detect intent via regex
        String detectedType = null;
        String detectedLabel = null;
        for (IntentPattern ip : PATTERNS) {
            if (ip.pattern.matcher(naturalLanguage).find()) {
                detectedType = ip.type;
                detectedLabel = ip.label;
                break;
            }
        }

        if (detectedType == null) {
            return NlEdiResult.builder().confidence(0)
                    .warnings(List.of("Could not determine document type",
                            "Try: 'create a purchase order for...' or 'generate an invoice for...'"))
                    .explanation("I couldn't figure out what type of document you need. " +
                            "Try starting with 'create a purchase order', 'generate an invoice', " +
                            "'send a healthcare claim', or 'make a wire transfer'.").build();
        }

        // Step 2: Extract entities via regex
        Map<String, String> fields = extractEntities(naturalLanguage, detectedType);

        // Step 3: Generate EDI through shared generator logic
        return generateFromTypeAndFields(detectedType, detectedLabel, fields, naturalLanguage);
    }

    // === Entity Extraction ===
    private Map<String, String> extractEntities(String text, String type) {
        Map<String, String> fields = new LinkedHashMap<>();

        // Extract companies/names
        List<String> companies = new ArrayList<>();
        Matcher cm = COMPANY_PATTERN.matcher(text);
        while (cm.find()) {
            String company = cm.group(1).trim();
            if (!company.isEmpty() && company.length() > 1) companies.add(company);
        }

        // Extract quantities
        Matcher qm = QUANTITY_PATTERN.matcher(text);
        while (qm.find()) {
            String qty = qm.group(1).replace(",", "");
            if (!qty.isEmpty() && Integer.parseInt(qty) > 0) {
                fields.put("quantity", qty);
                break;
            }
        }

        // Extract prices
        Matcher pm = PRICE_PATTERN.matcher(text);
        if (pm.find()) fields.put("unitPrice", pm.group(1).replace(",", ""));

        // Extract amounts
        Matcher am = AMOUNT_PATTERN.matcher(text);
        if (am.find()) fields.put("totalAmount", am.group(1).replace(",", ""));

        // Extract person names
        Matcher nm = NAME_PATTERN.matcher(text);
        if (nm.find()) {
            fields.put("patientFirstName", nm.group(1));
            fields.put("patientLastName", nm.group(2));
        }

        // Extract diagnosis codes
        Matcher dm = DIAGNOSIS_PATTERN.matcher(text);
        if (dm.find()) fields.put("diagnosisCode", dm.group(1));

        // Extract dates
        Matcher dtm = DATE_PATTERN.matcher(text);
        if (dtm.find()) fields.put("date", dtm.group(1));

        // Extract PO/invoice numbers
        Matcher pom = PO_NUM_PATTERN.matcher(text);
        if (pom.find()) fields.put("documentNumber", pom.group(1));

        // Extract BIC codes
        Matcher bm = BIC_PATTERN.matcher(text);
        List<String> bics = new ArrayList<>();
        while (bm.find()) bics.add(bm.group(1));

        // Assign companies based on context
        if (type.startsWith("X12_850")) {
            if (companies.size() >= 2) {
                fields.put("buyerName", companies.get(0));
                fields.put("sellerName", companies.get(1));
            } else if (companies.size() == 1) {
                // "to X" means seller, "from X" means buyer
                if (text.toLowerCase().contains("to " + companies.get(0).toLowerCase())) {
                    fields.put("sellerName", companies.get(0));
                } else {
                    fields.put("buyerName", companies.get(0));
                }
            }
        } else if (type.startsWith("X12_810")) {
            if (companies.size() >= 2) {
                fields.put("sellerName", companies.get(0));
                fields.put("buyerName", companies.get(1));
            } else if (companies.size() == 1) {
                if (text.toLowerCase().contains("from " + companies.get(0).toLowerCase())) {
                    fields.put("sellerName", companies.get(0));
                } else {
                    fields.put("buyerName", companies.get(0));
                }
            }
        } else if (type.equals("SWIFT_MT103")) {
            if (companies.size() >= 2) {
                fields.put("orderingName", companies.get(0));
                fields.put("beneficiaryName", companies.get(1));
            } else if (companies.size() == 1) {
                fields.put("beneficiaryName", companies.get(0));
            }
            if (bics.size() >= 2) {
                fields.put("senderBic", bics.get(0));
                fields.put("receiverBic", bics.get(1));
            } else if (bics.size() == 1) {
                fields.put("senderBic", bics.get(0));
            }
        }

        // Extract amount for claims
        if (type.equals("X12_837")) {
            if (fields.containsKey("totalAmount")) fields.put("claimAmount", fields.get("totalAmount"));
        }

        return fields;
    }

    // === Default Fillers ===
    private void fillDefaults850(Map<String, String> f) {
        String today = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        f.putIfAbsent("buyerName", "DEFAULT_BUYER");
        f.putIfAbsent("buyerId", "BUYER001");
        f.putIfAbsent("sellerName", "DEFAULT_SELLER");
        f.putIfAbsent("sellerId", "SELLER001");
        f.putIfAbsent("poNumber", "PO" + today.substring(2));
        f.putIfAbsent("poDate", today);
        f.putIfAbsent("quantity", "1");
        f.putIfAbsent("unitPrice", "0.00");
        f.putIfAbsent("itemNumber", "ITEM001");
        f.putIfAbsent("unitOfMeasure", "EA");
    }

    private void fillDefaults810(Map<String, String> f) {
        String today = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        f.putIfAbsent("sellerName", "DEFAULT_SELLER");
        f.putIfAbsent("sellerId", "SELLER001");
        f.putIfAbsent("buyerName", "DEFAULT_BUYER");
        f.putIfAbsent("buyerId", "BUYER001");
        f.putIfAbsent("invoiceNumber", "INV" + today.substring(2));
        f.putIfAbsent("invoiceDate", today);
        f.putIfAbsent("totalAmount", "0.00");
    }

    private void fillDefaults837(Map<String, String> f) {
        String today = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        f.putIfAbsent("senderName", "CLINIC");
        f.putIfAbsent("senderId", "1234567890");
        f.putIfAbsent("receiverName", "PAYER");
        f.putIfAbsent("receiverId", "9876543210");
        f.putIfAbsent("patientFirstName", "JOHN");
        f.putIfAbsent("patientLastName", "DOE");
        f.putIfAbsent("claimId", "CLM" + today.substring(2));
        f.putIfAbsent("claimAmount", "0.00");
        f.putIfAbsent("serviceDate", today);
        f.putIfAbsent("diagnosisCode", "J06.9");
    }

    private void fillDefaultsHl7(Map<String, String> f) {
        f.putIfAbsent("sendingApp", "HOSPITAL_APP");
        f.putIfAbsent("sendingFacility", "HOSPITAL");
        f.putIfAbsent("patientId", "PID" + System.currentTimeMillis() % 100000);
        f.putIfAbsent("patientFirstName", "JOHN");
        f.putIfAbsent("patientLastName", "DOE");
        f.putIfAbsent("dateOfBirth", "19800101");
        f.putIfAbsent("gender", "M");
        f.putIfAbsent("ward", "ICU");
    }

    private void fillDefaultsSwift(Map<String, String> f) {
        f.putIfAbsent("senderBic", "BANKUS33XXX");
        f.putIfAbsent("receiverBic", "BANKGB2LXXX");
        f.putIfAbsent("reference", "REF" + System.currentTimeMillis() % 100000);
        f.putIfAbsent("amount", "0.00");
        f.putIfAbsent("currency", "USD");
        f.putIfAbsent("orderingName", "SENDER CORP");
        f.putIfAbsent("orderingAccount", "1234567890");
        f.putIfAbsent("beneficiaryName", "RECEIVER CORP");
        f.putIfAbsent("beneficiaryAccount", "9876543210");
    }

    // === EDI Generators ===
    private String generate850(Map<String, String> f) {
        String today = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        double qty = parseDouble(f.get("quantity"));
        double price = parseDouble(f.get("unitPrice"));
        double total = qty * price;
        String totalCents = String.valueOf((int) (total * 100));

        return "ISA*00*          *00*          *ZZ*" + pad(f.get("buyerId"), 15) + "*ZZ*" + pad(f.get("sellerId"), 15) +
                "*" + today.substring(2, 8) + "*1200*U*00501*000000001*0*P*>~" +
                "GS*PO*" + f.get("buyerId") + "*" + f.get("sellerId") + "*" + today + "*1200*1*X*005010~" +
                "ST*850*0001~" +
                "BEG*00*NE*" + f.get("poNumber") + "**" + today + "~" +
                "NM1*BY*2*" + f.get("buyerName") + "*****ZZ*" + f.get("buyerId") + "~" +
                "NM1*SE*2*" + f.get("sellerName") + "*****ZZ*" + f.get("sellerId") + "~" +
                "PO1*1*" + f.get("quantity") + "*" + f.get("unitOfMeasure") + "*" + f.get("unitPrice") +
                "**VP*" + f.get("itemNumber") + "~" +
                "CTT*1~" +
                "SE*8*0001~" +
                "GE*1*1~" +
                "IEA*1*000000001~";
    }

    private String generate810(Map<String, String> f) {
        String today = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        double total = parseDouble(f.get("totalAmount"));
        String totalCents = String.valueOf((int) (total * 100));

        return "ISA*00*          *00*          *ZZ*" + pad(f.get("sellerId"), 15) + "*ZZ*" + pad(f.get("buyerId"), 15) +
                "*" + today.substring(2, 8) + "*1200*U*00501*000000001*0*P*>~" +
                "GS*IN*" + f.get("sellerId") + "*" + f.get("buyerId") + "*" + today + "*1200*1*X*005010~" +
                "ST*810*0001~" +
                "BIG*" + today + "*" + f.get("invoiceNumber") + "~" +
                "NM1*SE*2*" + f.get("sellerName") + "*****ZZ*" + f.get("sellerId") + "~" +
                "NM1*BY*2*" + f.get("buyerName") + "*****ZZ*" + f.get("buyerId") + "~" +
                "IT1*1*1*EA*" + f.get("totalAmount") + "~" +
                "TDS*" + totalCents + "~" +
                "SE*7*0001~" +
                "GE*1*1~" +
                "IEA*1*000000001~";
    }

    private String generate837(Map<String, String> f) {
        String today = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        return "ISA*00*          *00*          *ZZ*" + pad(f.get("senderId"), 15) + "*ZZ*" + pad(f.get("receiverId"), 15) +
                "*" + today.substring(2, 8) + "*1200*U*00501*000000001*0*P*>~" +
                "GS*HC*" + f.get("senderId") + "*" + f.get("receiverId") + "*" + today + "*1200*1*X*005010X222A1~" +
                "ST*837*0001*005010X222A1~" +
                "BHT*0019*00*" + f.get("claimId") + "*" + today + "*1200*CH~" +
                "NM1*85*2*" + f.get("senderName") + "*****XX*" + f.get("senderId") + "~" +
                "NM1*40*2*" + f.get("receiverName") + "*****46*" + f.get("receiverId") + "~" +
                "NM1*IL*1*" + f.get("patientLastName") + "*" + f.get("patientFirstName") + "~" +
                "CLM*" + f.get("claimId") + "*" + f.get("claimAmount") + "***11:B:1~" +
                "HI*ABK:" + f.get("diagnosisCode") + "~" +
                "SV1*HC:" + f.get("diagnosisCode") + "*" + f.get("claimAmount") + "*UN*1~" +
                "DTP*472*D8*" + f.get("serviceDate") + "~" +
                "SE*11*0001~" +
                "GE*1*1~" +
                "IEA*1*000000001~";
    }

    private String generateHl7Adt(Map<String, String> f) {
        String now = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + "120000";
        return "MSH|^~\\&|" + f.get("sendingApp") + "|" + f.get("sendingFacility") + "|RECV_APP|RECV_FAC|" +
                now + "||ADT^A01|MSG" + System.currentTimeMillis() % 100000 + "|P|2.5\r" +
                "EVN|A01|" + now + "\r" +
                "PID|1||" + f.get("patientId") + "||" + f.get("patientLastName") + "^" + f.get("patientFirstName") +
                "||" + f.get("dateOfBirth") + "|" + f.get("gender") + "\r" +
                "PV1|1|I|" + f.get("ward") + "||||ATTENDING^DOCTOR\r";
    }

    private String generateSwiftMt103(Map<String, String> f) {
        String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyMMdd"));
        return "{1:F01" + f.get("senderBic") + "0000000000}\r\n" +
                "{2:O103" + today + "1200" + f.get("receiverBic") + "00000000001200N}\r\n" +
                "{4:\r\n" +
                ":20:" + f.get("reference") + "\r\n" +
                ":23B:CRED\r\n" +
                ":32A:" + today + f.get("currency") + f.get("amount") + "\r\n" +
                ":50K:/" + f.get("orderingAccount") + "\r\n" + f.get("orderingName") + "\r\n" +
                ":59:/" + f.get("beneficiaryAccount") + "\r\n" + f.get("beneficiaryName") + "\r\n" +
                ":71A:SHA\r\n" +
                "-}";
    }

    // === Helpers ===
    private int calculateConfidence(Map<String, String> fields, List<String> keyFields) {
        int total = keyFields.size();
        int provided = (int) keyFields.stream()
                .filter(f -> fields.containsKey(f) && !fields.get(f).startsWith("DEFAULT_"))
                .count();
        return total > 0 ? (provided * 100) / total : 50;
    }

    private String pad(String s, int len) {
        if (s == null) s = "";
        return String.format("%-" + len + "s", s).substring(0, len);
    }

    private double parseDouble(String s) {
        try { return Double.parseDouble(s.replace(",", "")); }
        catch (Exception e) { return 0; }
    }
}
