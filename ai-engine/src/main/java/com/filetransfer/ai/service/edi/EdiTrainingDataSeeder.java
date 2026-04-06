package com.filetransfer.ai.service.edi;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Seeds the training engine with standards-compliant EDI samples.
 *
 * Each transaction type has 3+ sample pairs with VARIED data to teach
 * the ML engine which fields map where (not just memorise values).
 *
 * Covers: X12 850/810/837/835/856, EDIFACT ORDERS/INVOIC, HL7 ADT^A01, SWIFT MT103.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EdiTrainingDataSeeder {

    private final EdiTrainingDataService dataService;

    @Value("${edi.training.seed-on-startup:false}")
    private boolean seedOnStartup;

    @PostConstruct
    void init() {
        if (seedOnStartup) {
            log.info("edi.training.seed-on-startup=true — seeding built-in training data");
            seedAll();
        }
    }

    /** Seed all built-in samples. Returns summary. */
    public Map<String, Object> seedAll() {
        int total = 0;
        Map<String, Integer> counts = new LinkedHashMap<>();

        total += seed(x12_850_samples()); counts.put("X12_850", x12_850_samples().size());
        total += seed(x12_810_samples()); counts.put("X12_810", x12_810_samples().size());
        total += seed(x12_837_samples()); counts.put("X12_837", x12_837_samples().size());
        total += seed(x12_835_samples()); counts.put("X12_835", x12_835_samples().size());
        total += seed(x12_856_samples()); counts.put("X12_856", x12_856_samples().size());
        total += seed(edifact_orders_samples()); counts.put("EDIFACT_ORDERS", edifact_orders_samples().size());
        total += seed(edifact_invoic_samples()); counts.put("EDIFACT_INVOIC", edifact_invoic_samples().size());
        total += seed(hl7_adt_samples()); counts.put("HL7_ADT", hl7_adt_samples().size());
        total += seed(swift_mt103_samples()); counts.put("SWIFT_MT103", swift_mt103_samples().size());

        log.info("Seeded {} training samples across {} transaction types", total, counts.size());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalSamples", total);
        result.put("transactionTypes", counts);
        return result;
    }

    private int seed(List<EdiTrainingDataService.AddSampleRequest> samples) {
        int count = 0;
        for (EdiTrainingDataService.AddSampleRequest sample : samples) {
            try {
                dataService.addSample(sample);
                count++;
            } catch (Exception e) {
                log.warn("Failed to seed sample: {}", e.getMessage());
            }
        }
        return count;
    }

    // ========================================================================
    // X12 850 — Purchase Order (3 samples)
    // ========================================================================

    private List<EdiTrainingDataService.AddSampleRequest> x12_850_samples() {
        return List.of(
                EdiTrainingDataService.AddSampleRequest.builder()
                        .sourceFormat("X12").sourceType("850").sourceVersion("005010").targetFormat("JSON")
                        .notes("X12 850 Purchase Order — Acme Corp to Global Supply")
                        .inputContent(
                                "ISA*00*          *00*          *ZZ*ACMECORP       *ZZ*GLOBALSUPPLY   *240315*1042*U*00501*000000101*0*P*>~" +
                                "GS*PO*ACMECORP*GLOBALSUPPLY*20240315*1042*101*X*005010~" +
                                "ST*850*0001~" +
                                "BEG*00*NE*PO-78234**20240315~" +
                                "N1*BY*Acme Corporation*92*ACME001~" +
                                "N3*100 Innovation Drive~" +
                                "N4*San Francisco*CA*94105*US~" +
                                "N1*SE*Global Supply Inc*92*GLOB001~" +
                                "N3*2500 Commerce Blvd~" +
                                "N4*Chicago*IL*60601*US~" +
                                "PO1*001*500*EA*12.50*PE*VP*WIDGET-A100*UP*012345678901~" +
                                "PO1*002*200*EA*8.75*PE*VP*BOLT-M6X20*UP*012345678902~" +
                                "CTT*2*700~" +
                                "SE*13*0001~" +
                                "GE*1*101~" +
                                "IEA*1*000000101~")
                        .outputContent(
                                "{\n" +
                                "  \"documentType\": \"Purchase Order\",\n" +
                                "  \"poNumber\": \"PO-78234\",\n" +
                                "  \"date\": \"2024-03-15\",\n" +
                                "  \"buyer\": {\n" +
                                "    \"name\": \"Acme Corporation\",\n" +
                                "    \"id\": \"ACME001\",\n" +
                                "    \"address\": { \"street\": \"100 Innovation Drive\", \"city\": \"San Francisco\", \"state\": \"CA\", \"zip\": \"94105\", \"country\": \"US\" }\n" +
                                "  },\n" +
                                "  \"seller\": {\n" +
                                "    \"name\": \"Global Supply Inc\",\n" +
                                "    \"id\": \"GLOB001\",\n" +
                                "    \"address\": { \"street\": \"2500 Commerce Blvd\", \"city\": \"Chicago\", \"state\": \"IL\", \"zip\": \"60601\", \"country\": \"US\" }\n" +
                                "  },\n" +
                                "  \"lineItems\": [\n" +
                                "    { \"lineNumber\": \"001\", \"quantity\": 500, \"unit\": \"EA\", \"unitPrice\": 12.50, \"productCode\": \"WIDGET-A100\", \"upc\": \"012345678901\" },\n" +
                                "    { \"lineNumber\": \"002\", \"quantity\": 200, \"unit\": \"EA\", \"unitPrice\": 8.75, \"productCode\": \"BOLT-M6X20\", \"upc\": \"012345678902\" }\n" +
                                "  ],\n" +
                                "  \"totalLineItems\": 2,\n" +
                                "  \"totalQuantity\": 700\n" +
                                "}")
                        .build(),

                EdiTrainingDataService.AddSampleRequest.builder()
                        .sourceFormat("X12").sourceType("850").sourceVersion("005010").targetFormat("JSON")
                        .notes("X12 850 Purchase Order — TechParts to MegaDistributor")
                        .inputContent(
                                "ISA*00*          *00*          *ZZ*TECHPARTS      *ZZ*MEGADIST       *240520*0830*U*00501*000000202*0*P*>~" +
                                "GS*PO*TECHPARTS*MEGADIST*20240520*0830*202*X*005010~" +
                                "ST*850*0001~" +
                                "BEG*00*NE*PO-99001**20240520~" +
                                "N1*BY*TechParts LLC*92*TECH500~" +
                                "N3*789 Circuit Lane~" +
                                "N4*Austin*TX*78701*US~" +
                                "N1*SE*MegaDistributor Corp*92*MEGA100~" +
                                "N3*456 Warehouse Rd~" +
                                "N4*Dallas*TX*75201*US~" +
                                "PO1*001*1000*EA*3.25*PE*VP*RESISTOR-10K*UP*098765432109~" +
                                "PO1*002*750*EA*0.50*PE*VP*CAP-100UF*UP*098765432110~" +
                                "PO1*003*300*EA*15.00*PE*VP*IC-ATMEGA328*UP*098765432111~" +
                                "CTT*3*2050~" +
                                "SE*14*0001~" +
                                "GE*1*202~" +
                                "IEA*1*000000202~")
                        .outputContent(
                                "{\n" +
                                "  \"documentType\": \"Purchase Order\",\n" +
                                "  \"poNumber\": \"PO-99001\",\n" +
                                "  \"date\": \"2024-05-20\",\n" +
                                "  \"buyer\": {\n" +
                                "    \"name\": \"TechParts LLC\",\n" +
                                "    \"id\": \"TECH500\",\n" +
                                "    \"address\": { \"street\": \"789 Circuit Lane\", \"city\": \"Austin\", \"state\": \"TX\", \"zip\": \"78701\", \"country\": \"US\" }\n" +
                                "  },\n" +
                                "  \"seller\": {\n" +
                                "    \"name\": \"MegaDistributor Corp\",\n" +
                                "    \"id\": \"MEGA100\",\n" +
                                "    \"address\": { \"street\": \"456 Warehouse Rd\", \"city\": \"Dallas\", \"state\": \"TX\", \"zip\": \"75201\", \"country\": \"US\" }\n" +
                                "  },\n" +
                                "  \"lineItems\": [\n" +
                                "    { \"lineNumber\": \"001\", \"quantity\": 1000, \"unit\": \"EA\", \"unitPrice\": 3.25, \"productCode\": \"RESISTOR-10K\", \"upc\": \"098765432109\" },\n" +
                                "    { \"lineNumber\": \"002\", \"quantity\": 750, \"unit\": \"EA\", \"unitPrice\": 0.50, \"productCode\": \"CAP-100UF\", \"upc\": \"098765432110\" },\n" +
                                "    { \"lineNumber\": \"003\", \"quantity\": 300, \"unit\": \"EA\", \"unitPrice\": 15.00, \"productCode\": \"IC-ATMEGA328\", \"upc\": \"098765432111\" }\n" +
                                "  ],\n" +
                                "  \"totalLineItems\": 3,\n" +
                                "  \"totalQuantity\": 2050\n" +
                                "}")
                        .build(),

                EdiTrainingDataService.AddSampleRequest.builder()
                        .sourceFormat("X12").sourceType("850").sourceVersion("004010").targetFormat("JSON")
                        .notes("X12 850 Purchase Order — FreshFoods to OrganicFarms (4010 version)")
                        .inputContent(
                                "ISA*00*          *00*          *ZZ*FRESHFOODS     *ZZ*ORGANICFARMS   *241101*1400*U*00401*000000303*0*P*>~" +
                                "GS*PO*FRESHFOODS*ORGANICFARMS*20241101*1400*303*X*004010~" +
                                "ST*850*0001~" +
                                "BEG*00*NE*PO-55678**20241101~" +
                                "N1*BY*FreshFoods Market*92*FRESH01~" +
                                "N3*321 Harvest Street~" +
                                "N4*Portland*OR*97201*US~" +
                                "N1*SE*Organic Farms Co-op*92*ORGFM01~" +
                                "N3*1000 Valley Road~" +
                                "N4*Eugene*OR*97401*US~" +
                                "PO1*001*2000*LB*2.15*PE*VP*APPLE-FUJI*UP*011111111111~" +
                                "PO1*002*1500*LB*3.40*PE*VP*TOMATO-HEIR*UP*011111111112~" +
                                "CTT*2*3500~" +
                                "SE*13*0001~" +
                                "GE*1*303~" +
                                "IEA*1*000000303~")
                        .outputContent(
                                "{\n" +
                                "  \"documentType\": \"Purchase Order\",\n" +
                                "  \"poNumber\": \"PO-55678\",\n" +
                                "  \"date\": \"2024-11-01\",\n" +
                                "  \"buyer\": {\n" +
                                "    \"name\": \"FreshFoods Market\",\n" +
                                "    \"id\": \"FRESH01\",\n" +
                                "    \"address\": { \"street\": \"321 Harvest Street\", \"city\": \"Portland\", \"state\": \"OR\", \"zip\": \"97201\", \"country\": \"US\" }\n" +
                                "  },\n" +
                                "  \"seller\": {\n" +
                                "    \"name\": \"Organic Farms Co-op\",\n" +
                                "    \"id\": \"ORGFM01\",\n" +
                                "    \"address\": { \"street\": \"1000 Valley Road\", \"city\": \"Eugene\", \"state\": \"OR\", \"zip\": \"97401\", \"country\": \"US\" }\n" +
                                "  },\n" +
                                "  \"lineItems\": [\n" +
                                "    { \"lineNumber\": \"001\", \"quantity\": 2000, \"unit\": \"LB\", \"unitPrice\": 2.15, \"productCode\": \"APPLE-FUJI\", \"upc\": \"011111111111\" },\n" +
                                "    { \"lineNumber\": \"002\", \"quantity\": 1500, \"unit\": \"LB\", \"unitPrice\": 3.40, \"productCode\": \"TOMATO-HEIR\", \"upc\": \"011111111112\" }\n" +
                                "  ],\n" +
                                "  \"totalLineItems\": 2,\n" +
                                "  \"totalQuantity\": 3500\n" +
                                "}")
                        .build()
        );
    }

    // ========================================================================
    // X12 810 — Invoice (3 samples)
    // ========================================================================

    private List<EdiTrainingDataService.AddSampleRequest> x12_810_samples() {
        return List.of(
                EdiTrainingDataService.AddSampleRequest.builder()
                        .sourceFormat("X12").sourceType("810").sourceVersion("005010").targetFormat("JSON")
                        .notes("X12 810 Invoice — Global Supply to Acme Corp")
                        .inputContent(
                                "ISA*00*          *00*          *ZZ*GLOBALSUPPLY   *ZZ*ACMECORP       *240401*0900*U*00501*000000401*0*P*>~" +
                                "GS*IN*GLOBALSUPPLY*ACMECORP*20240401*0900*401*X*005010~" +
                                "ST*810*0001~" +
                                "BIG*20240401*INV-20240401-001*20240315*PO-78234~" +
                                "N1*ST*Acme Corporation*92*ACME001~" +
                                "N1*RE*Global Supply Inc*92*GLOB001~" +
                                "IT1*001*500*EA*12.50**VP*WIDGET-A100~" +
                                "IT1*002*200*EA*8.75**VP*BOLT-M6X20~" +
                                "TDS*800000~" +
                                "SE*9*0001~" +
                                "GE*1*401~" +
                                "IEA*1*000000401~")
                        .outputContent(
                                "{\n" +
                                "  \"documentType\": \"Invoice\",\n" +
                                "  \"invoiceNumber\": \"INV-20240401-001\",\n" +
                                "  \"invoiceDate\": \"2024-04-01\",\n" +
                                "  \"poNumber\": \"PO-78234\",\n" +
                                "  \"poDate\": \"2024-03-15\",\n" +
                                "  \"billTo\": { \"name\": \"Acme Corporation\", \"id\": \"ACME001\" },\n" +
                                "  \"remitTo\": { \"name\": \"Global Supply Inc\", \"id\": \"GLOB001\" },\n" +
                                "  \"lineItems\": [\n" +
                                "    { \"lineNumber\": \"001\", \"quantity\": 500, \"unit\": \"EA\", \"unitPrice\": 12.50, \"productCode\": \"WIDGET-A100\" },\n" +
                                "    { \"lineNumber\": \"002\", \"quantity\": 200, \"unit\": \"EA\", \"unitPrice\": 8.75, \"productCode\": \"BOLT-M6X20\" }\n" +
                                "  ],\n" +
                                "  \"totalAmount\": 8000.00\n" +
                                "}")
                        .build(),

                EdiTrainingDataService.AddSampleRequest.builder()
                        .sourceFormat("X12").sourceType("810").sourceVersion("005010").targetFormat("JSON")
                        .notes("X12 810 Invoice — MegaDistributor to TechParts")
                        .inputContent(
                                "ISA*00*          *00*          *ZZ*MEGADIST       *ZZ*TECHPARTS      *240605*1100*U*00501*000000502*0*P*>~" +
                                "GS*IN*MEGADIST*TECHPARTS*20240605*1100*502*X*005010~" +
                                "ST*810*0001~" +
                                "BIG*20240605*INV-MD-88432*20240520*PO-99001~" +
                                "N1*ST*TechParts LLC*92*TECH500~" +
                                "N1*RE*MegaDistributor Corp*92*MEGA100~" +
                                "IT1*001*1000*EA*3.25**VP*RESISTOR-10K~" +
                                "IT1*002*750*EA*0.50**VP*CAP-100UF~" +
                                "IT1*003*300*EA*15.00**VP*IC-ATMEGA328~" +
                                "TDS*812500~" +
                                "SE*10*0001~" +
                                "GE*1*502~" +
                                "IEA*1*000000502~")
                        .outputContent(
                                "{\n" +
                                "  \"documentType\": \"Invoice\",\n" +
                                "  \"invoiceNumber\": \"INV-MD-88432\",\n" +
                                "  \"invoiceDate\": \"2024-06-05\",\n" +
                                "  \"poNumber\": \"PO-99001\",\n" +
                                "  \"poDate\": \"2024-05-20\",\n" +
                                "  \"billTo\": { \"name\": \"TechParts LLC\", \"id\": \"TECH500\" },\n" +
                                "  \"remitTo\": { \"name\": \"MegaDistributor Corp\", \"id\": \"MEGA100\" },\n" +
                                "  \"lineItems\": [\n" +
                                "    { \"lineNumber\": \"001\", \"quantity\": 1000, \"unit\": \"EA\", \"unitPrice\": 3.25, \"productCode\": \"RESISTOR-10K\" },\n" +
                                "    { \"lineNumber\": \"002\", \"quantity\": 750, \"unit\": \"EA\", \"unitPrice\": 0.50, \"productCode\": \"CAP-100UF\" },\n" +
                                "    { \"lineNumber\": \"003\", \"quantity\": 300, \"unit\": \"EA\", \"unitPrice\": 15.00, \"productCode\": \"IC-ATMEGA328\" }\n" +
                                "  ],\n" +
                                "  \"totalAmount\": 8125.00\n" +
                                "}")
                        .build(),

                EdiTrainingDataService.AddSampleRequest.builder()
                        .sourceFormat("X12").sourceType("810").sourceVersion("004010").targetFormat("JSON")
                        .notes("X12 810 Invoice — OrganicFarms to FreshFoods")
                        .inputContent(
                                "ISA*00*          *00*          *ZZ*ORGANICFARMS   *ZZ*FRESHFOODS     *241115*0800*U*00401*000000603*0*P*>~" +
                                "GS*IN*ORGANICFARMS*FRESHFOODS*20241115*0800*603*X*004010~" +
                                "ST*810*0001~" +
                                "BIG*20241115*INV-OF-7721*20241101*PO-55678~" +
                                "N1*ST*FreshFoods Market*92*FRESH01~" +
                                "N1*RE*Organic Farms Co-op*92*ORGFM01~" +
                                "IT1*001*2000*LB*2.15**VP*APPLE-FUJI~" +
                                "IT1*002*1500*LB*3.40**VP*TOMATO-HEIR~" +
                                "TDS*940000~" +
                                "SE*9*0001~" +
                                "GE*1*603~" +
                                "IEA*1*000000603~")
                        .outputContent(
                                "{\n" +
                                "  \"documentType\": \"Invoice\",\n" +
                                "  \"invoiceNumber\": \"INV-OF-7721\",\n" +
                                "  \"invoiceDate\": \"2024-11-15\",\n" +
                                "  \"poNumber\": \"PO-55678\",\n" +
                                "  \"poDate\": \"2024-11-01\",\n" +
                                "  \"billTo\": { \"name\": \"FreshFoods Market\", \"id\": \"FRESH01\" },\n" +
                                "  \"remitTo\": { \"name\": \"Organic Farms Co-op\", \"id\": \"ORGFM01\" },\n" +
                                "  \"lineItems\": [\n" +
                                "    { \"lineNumber\": \"001\", \"quantity\": 2000, \"unit\": \"LB\", \"unitPrice\": 2.15, \"productCode\": \"APPLE-FUJI\" },\n" +
                                "    { \"lineNumber\": \"002\", \"quantity\": 1500, \"unit\": \"LB\", \"unitPrice\": 3.40, \"productCode\": \"TOMATO-HEIR\" }\n" +
                                "  ],\n" +
                                "  \"totalAmount\": 9400.00\n" +
                                "}")
                        .build()
        );
    }

    // ========================================================================
    // X12 837 — Healthcare Claim Professional (3 samples)
    // ========================================================================

    private List<EdiTrainingDataService.AddSampleRequest> x12_837_samples() {
        return List.of(
                EdiTrainingDataService.AddSampleRequest.builder()
                        .sourceFormat("X12").sourceType("837").sourceVersion("005010").targetFormat("JSON")
                        .notes("X12 837P Healthcare Claim — Dr. Smith, patient Johnson")
                        .inputContent(
                                "ISA*00*          *00*          *ZZ*CLAIMSUB01     *ZZ*PAYERID01      *240201*1200*^*00501*000000701*0*P*:~" +
                                "GS*HC*CLAIMSUB01*PAYERID01*20240201*1200*701*X*005010X222A1~" +
                                "ST*837*0001*005010X222A1~" +
                                "BHT*0019*00*CLM240201001*20240201*1200*CH~" +
                                "NM1*41*2*ClearClaim Solutions*****46*CS12345~" +
                                "NM1*40*2*BlueCross BlueShield*****46*BCBS001~" +
                                "NM1*IL*1*Johnson*Robert****MI*JKL123456789~" +
                                "NM1*82*1*Smith*Sarah*A***XX*1234567890~" +
                                "CLM*PAT-20240201-001*1500***11:B:1*Y*A*Y*Y~" +
                                "HI*ABK:J06.9~" +
                                "HI*ABF:Z23.1~" +
                                "SV1*HC:99213*150*UN*1***1~" +
                                "DTP*472*D8*20240201~" +
                                "SV1*HC:99214*350*UN*1***1~" +
                                "DTP*472*D8*20240201~" +
                                "SV1*HC:87081*75*UN*1***1~" +
                                "DTP*472*D8*20240201~" +
                                "SE*17*0001~" +
                                "GE*1*701~" +
                                "IEA*1*000000701~")
                        .outputContent(
                                "{\n" +
                                "  \"documentType\": \"Healthcare Claim\",\n" +
                                "  \"claimId\": \"PAT-20240201-001\",\n" +
                                "  \"claimAmount\": 1500.00,\n" +
                                "  \"claimDate\": \"2024-02-01\",\n" +
                                "  \"submitter\": { \"name\": \"ClearClaim Solutions\", \"id\": \"CS12345\" },\n" +
                                "  \"payer\": { \"name\": \"BlueCross BlueShield\", \"id\": \"BCBS001\" },\n" +
                                "  \"patient\": { \"lastName\": \"Johnson\", \"firstName\": \"Robert\", \"memberId\": \"JKL123456789\" },\n" +
                                "  \"provider\": { \"lastName\": \"Smith\", \"firstName\": \"Sarah\", \"middleName\": \"A\", \"npi\": \"1234567890\" },\n" +
                                "  \"diagnosisCodes\": [ \"J06.9\", \"Z23.1\" ],\n" +
                                "  \"serviceLines\": [\n" +
                                "    { \"procedureCode\": \"99213\", \"amount\": 150.00, \"units\": 1, \"serviceDate\": \"2024-02-01\" },\n" +
                                "    { \"procedureCode\": \"99214\", \"amount\": 350.00, \"units\": 1, \"serviceDate\": \"2024-02-01\" },\n" +
                                "    { \"procedureCode\": \"87081\", \"amount\": 75.00, \"units\": 1, \"serviceDate\": \"2024-02-01\" }\n" +
                                "  ]\n" +
                                "}")
                        .build(),

                EdiTrainingDataService.AddSampleRequest.builder()
                        .sourceFormat("X12").sourceType("837").sourceVersion("005010").targetFormat("JSON")
                        .notes("X12 837P Healthcare Claim — Dr. Patel, patient Williams")
                        .inputContent(
                                "ISA*00*          *00*          *ZZ*MEDSUBMIT02    *ZZ*UNITEDHLTH     *240315*0930*^*00501*000000802*0*P*:~" +
                                "GS*HC*MEDSUBMIT02*UNITEDHLTH*20240315*0930*802*X*005010X222A1~" +
                                "ST*837*0001*005010X222A1~" +
                                "BHT*0019*00*CLM240315002*20240315*0930*CH~" +
                                "NM1*41*2*MedSubmit Services*****46*MS67890~" +
                                "NM1*40*2*UnitedHealthcare*****46*UHC002~" +
                                "NM1*IL*1*Williams*Maria*T***MI*UHC998877665~" +
                                "NM1*82*1*Patel*Raj*K***XX*9876543210~" +
                                "CLM*PAT-20240315-002*2750***11:B:1*Y*A*Y*Y~" +
                                "HI*ABK:M54.5~" +
                                "SV1*HC:99215*500*UN*1***1~" +
                                "DTP*472*D8*20240315~" +
                                "SV1*HC:72148*1200*UN*1***1~" +
                                "DTP*472*D8*20240315~" +
                                "SV1*HC:20610*450*UN*1***1~" +
                                "DTP*472*D8*20240315~" +
                                "SE*16*0001~" +
                                "GE*1*802~" +
                                "IEA*1*000000802~")
                        .outputContent(
                                "{\n" +
                                "  \"documentType\": \"Healthcare Claim\",\n" +
                                "  \"claimId\": \"PAT-20240315-002\",\n" +
                                "  \"claimAmount\": 2750.00,\n" +
                                "  \"claimDate\": \"2024-03-15\",\n" +
                                "  \"submitter\": { \"name\": \"MedSubmit Services\", \"id\": \"MS67890\" },\n" +
                                "  \"payer\": { \"name\": \"UnitedHealthcare\", \"id\": \"UHC002\" },\n" +
                                "  \"patient\": { \"lastName\": \"Williams\", \"firstName\": \"Maria\", \"memberId\": \"UHC998877665\" },\n" +
                                "  \"provider\": { \"lastName\": \"Patel\", \"firstName\": \"Raj\", \"middleName\": \"K\", \"npi\": \"9876543210\" },\n" +
                                "  \"diagnosisCodes\": [ \"M54.5\" ],\n" +
                                "  \"serviceLines\": [\n" +
                                "    { \"procedureCode\": \"99215\", \"amount\": 500.00, \"units\": 1, \"serviceDate\": \"2024-03-15\" },\n" +
                                "    { \"procedureCode\": \"72148\", \"amount\": 1200.00, \"units\": 1, \"serviceDate\": \"2024-03-15\" },\n" +
                                "    { \"procedureCode\": \"20610\", \"amount\": 450.00, \"units\": 1, \"serviceDate\": \"2024-03-15\" }\n" +
                                "  ]\n" +
                                "}")
                        .build(),

                EdiTrainingDataService.AddSampleRequest.builder()
                        .sourceFormat("X12").sourceType("837").sourceVersion("005010").targetFormat("JSON")
                        .notes("X12 837P Healthcare Claim — Dr. Lee, patient Garcia")
                        .inputContent(
                                "ISA*00*          *00*          *ZZ*HEALTHCLEAR    *ZZ*AETNA01        *240710*1445*^*00501*000000903*0*P*:~" +
                                "GS*HC*HEALTHCLEAR*AETNA01*20240710*1445*903*X*005010X222A1~" +
                                "ST*837*0001*005010X222A1~" +
                                "BHT*0019*00*CLM240710003*20240710*1445*CH~" +
                                "NM1*41*2*HealthClear Network*****46*HCN5555~" +
                                "NM1*40*2*Aetna Health Plans*****46*AETN01~" +
                                "NM1*IL*1*Garcia*Carlos*M***MI*AET556677889~" +
                                "NM1*82*1*Lee*Jennifer*W***XX*5566778899~" +
                                "CLM*PAT-20240710-003*425***11:B:1*Y*A*Y*Y~" +
                                "HI*ABK:J20.9~" +
                                "HI*ABF:R05.9~" +
                                "SV1*HC:99213*150*UN*1***1~" +
                                "DTP*472*D8*20240710~" +
                                "SV1*HC:71046*275*UN*1***1~" +
                                "DTP*472*D8*20240710~" +
                                "SE*15*0001~" +
                                "GE*1*903~" +
                                "IEA*1*000000903~")
                        .outputContent(
                                "{\n" +
                                "  \"documentType\": \"Healthcare Claim\",\n" +
                                "  \"claimId\": \"PAT-20240710-003\",\n" +
                                "  \"claimAmount\": 425.00,\n" +
                                "  \"claimDate\": \"2024-07-10\",\n" +
                                "  \"submitter\": { \"name\": \"HealthClear Network\", \"id\": \"HCN5555\" },\n" +
                                "  \"payer\": { \"name\": \"Aetna Health Plans\", \"id\": \"AETN01\" },\n" +
                                "  \"patient\": { \"lastName\": \"Garcia\", \"firstName\": \"Carlos\", \"memberId\": \"AET556677889\" },\n" +
                                "  \"provider\": { \"lastName\": \"Lee\", \"firstName\": \"Jennifer\", \"middleName\": \"W\", \"npi\": \"5566778899\" },\n" +
                                "  \"diagnosisCodes\": [ \"J20.9\", \"R05.9\" ],\n" +
                                "  \"serviceLines\": [\n" +
                                "    { \"procedureCode\": \"99213\", \"amount\": 150.00, \"units\": 1, \"serviceDate\": \"2024-07-10\" },\n" +
                                "    { \"procedureCode\": \"71046\", \"amount\": 275.00, \"units\": 1, \"serviceDate\": \"2024-07-10\" }\n" +
                                "  ]\n" +
                                "}")
                        .build()
        );
    }

    // ========================================================================
    // X12 835 — Payment/Remittance (3 samples)
    // ========================================================================

    private List<EdiTrainingDataService.AddSampleRequest> x12_835_samples() {
        return List.of(
                EdiTrainingDataService.AddSampleRequest.builder()
                        .sourceFormat("X12").sourceType("835").sourceVersion("005010").targetFormat("JSON")
                        .notes("X12 835 Payment — BlueCross pays claim for Johnson")
                        .inputContent(
                                "ISA*00*          *00*          *ZZ*BCBS001        *ZZ*CLAIMSUB01     *240301*1000*^*00501*000001001*0*P*:~" +
                                "GS*HP*BCBS001*CLAIMSUB01*20240301*1000*1001*X*005010X221A1~" +
                                "ST*835*0001~" +
                                "BPR*I*1350.00*C*ACH*CTX*01*021000089*DA*123456789*1234567890**01*031000053*DA*987654321*20240301~" +
                                "TRN*1*BCBS-TRN-240301001*1234567890~" +
                                "N1*PR*BlueCross BlueShield~" +
                                "N1*PE*ClearClaim Solutions*XX*CS12345~" +
                                "CLP*PAT-20240201-001*1*1500*1350*150*12*BCBS-REF-001~" +
                                "SVC*HC:99213*150*135~" +
                                "SVC*HC:99214*350*315~" +
                                "SVC*HC:87081*75*75~" +
                                "SE*11*0001~" +
                                "GE*1*1001~" +
                                "IEA*1*000001001~")
                        .outputContent(
                                "{\n" +
                                "  \"documentType\": \"Payment Remittance\",\n" +
                                "  \"paymentAmount\": 1350.00,\n" +
                                "  \"paymentMethod\": \"ACH\",\n" +
                                "  \"paymentDate\": \"2024-03-01\",\n" +
                                "  \"traceNumber\": \"BCBS-TRN-240301001\",\n" +
                                "  \"payer\": { \"name\": \"BlueCross BlueShield\" },\n" +
                                "  \"payee\": { \"name\": \"ClearClaim Solutions\", \"npi\": \"CS12345\" },\n" +
                                "  \"claims\": [\n" +
                                "    {\n" +
                                "      \"claimId\": \"PAT-20240201-001\",\n" +
                                "      \"chargedAmount\": 1500.00,\n" +
                                "      \"paidAmount\": 1350.00,\n" +
                                "      \"patientResponsibility\": 150.00,\n" +
                                "      \"payerReference\": \"BCBS-REF-001\",\n" +
                                "      \"services\": [\n" +
                                "        { \"procedureCode\": \"99213\", \"charged\": 150.00, \"paid\": 135.00 },\n" +
                                "        { \"procedureCode\": \"99214\", \"charged\": 350.00, \"paid\": 315.00 },\n" +
                                "        { \"procedureCode\": \"87081\", \"charged\": 75.00, \"paid\": 75.00 }\n" +
                                "      ]\n" +
                                "    }\n" +
                                "  ]\n" +
                                "}")
                        .build(),

                EdiTrainingDataService.AddSampleRequest.builder()
                        .sourceFormat("X12").sourceType("835").sourceVersion("005010").targetFormat("JSON")
                        .notes("X12 835 Payment — UnitedHealthcare pays claim for Williams")
                        .inputContent(
                                "ISA*00*          *00*          *ZZ*UHC002         *ZZ*MEDSUBMIT02    *240415*1400*^*00501*000001102*0*P*:~" +
                                "GS*HP*UHC002*MEDSUBMIT02*20240415*1400*1102*X*005010X221A1~" +
                                "ST*835*0001~" +
                                "BPR*I*2200.00*C*ACH*CTX*01*021000089*DA*555666777*1234567890**01*031000053*DA*888999000*20240415~" +
                                "TRN*1*UHC-TRN-240415002*5556667770~" +
                                "N1*PR*UnitedHealthcare~" +
                                "N1*PE*MedSubmit Services*XX*MS67890~" +
                                "CLP*PAT-20240315-002*1*2750*2200*550*12*UHC-REF-002~" +
                                "SVC*HC:99215*500*400~" +
                                "SVC*HC:72148*1200*1000~" +
                                "SVC*HC:20610*450*400~" +
                                "SE*11*0001~" +
                                "GE*1*1102~" +
                                "IEA*1*000001102~")
                        .outputContent(
                                "{\n" +
                                "  \"documentType\": \"Payment Remittance\",\n" +
                                "  \"paymentAmount\": 2200.00,\n" +
                                "  \"paymentMethod\": \"ACH\",\n" +
                                "  \"paymentDate\": \"2024-04-15\",\n" +
                                "  \"traceNumber\": \"UHC-TRN-240415002\",\n" +
                                "  \"payer\": { \"name\": \"UnitedHealthcare\" },\n" +
                                "  \"payee\": { \"name\": \"MedSubmit Services\", \"npi\": \"MS67890\" },\n" +
                                "  \"claims\": [\n" +
                                "    {\n" +
                                "      \"claimId\": \"PAT-20240315-002\",\n" +
                                "      \"chargedAmount\": 2750.00,\n" +
                                "      \"paidAmount\": 2200.00,\n" +
                                "      \"patientResponsibility\": 550.00,\n" +
                                "      \"payerReference\": \"UHC-REF-002\",\n" +
                                "      \"services\": [\n" +
                                "        { \"procedureCode\": \"99215\", \"charged\": 500.00, \"paid\": 400.00 },\n" +
                                "        { \"procedureCode\": \"72148\", \"charged\": 1200.00, \"paid\": 1000.00 },\n" +
                                "        { \"procedureCode\": \"20610\", \"charged\": 450.00, \"paid\": 400.00 }\n" +
                                "      ]\n" +
                                "    }\n" +
                                "  ]\n" +
                                "}")
                        .build(),

                EdiTrainingDataService.AddSampleRequest.builder()
                        .sourceFormat("X12").sourceType("835").sourceVersion("005010").targetFormat("JSON")
                        .notes("X12 835 Payment — Aetna pays claim for Garcia")
                        .inputContent(
                                "ISA*00*          *00*          *ZZ*AETN01         *ZZ*HEALTHCLEAR    *240820*0915*^*00501*000001203*0*P*:~" +
                                "GS*HP*AETN01*HEALTHCLEAR*20240820*0915*1203*X*005010X221A1~" +
                                "ST*835*0001~" +
                                "BPR*I*382.50*C*ACH*CTX*01*021000089*DA*777888999*1234567890**01*031000053*DA*111222333*20240820~" +
                                "TRN*1*AETN-TRN-240820003*7778889990~" +
                                "N1*PR*Aetna Health Plans~" +
                                "N1*PE*HealthClear Network*XX*HCN5555~" +
                                "CLP*PAT-20240710-003*1*425*382.50*42.50*12*AETN-REF-003~" +
                                "SVC*HC:99213*150*135~" +
                                "SVC*HC:71046*275*247.50~" +
                                "SE*10*0001~" +
                                "GE*1*1203~" +
                                "IEA*1*000001203~")
                        .outputContent(
                                "{\n" +
                                "  \"documentType\": \"Payment Remittance\",\n" +
                                "  \"paymentAmount\": 382.50,\n" +
                                "  \"paymentMethod\": \"ACH\",\n" +
                                "  \"paymentDate\": \"2024-08-20\",\n" +
                                "  \"traceNumber\": \"AETN-TRN-240820003\",\n" +
                                "  \"payer\": { \"name\": \"Aetna Health Plans\" },\n" +
                                "  \"payee\": { \"name\": \"HealthClear Network\", \"npi\": \"HCN5555\" },\n" +
                                "  \"claims\": [\n" +
                                "    {\n" +
                                "      \"claimId\": \"PAT-20240710-003\",\n" +
                                "      \"chargedAmount\": 425.00,\n" +
                                "      \"paidAmount\": 382.50,\n" +
                                "      \"patientResponsibility\": 42.50,\n" +
                                "      \"payerReference\": \"AETN-REF-003\",\n" +
                                "      \"services\": [\n" +
                                "        { \"procedureCode\": \"99213\", \"charged\": 150.00, \"paid\": 135.00 },\n" +
                                "        { \"procedureCode\": \"71046\", \"charged\": 275.00, \"paid\": 247.50 }\n" +
                                "      ]\n" +
                                "    }\n" +
                                "  ]\n" +
                                "}")
                        .build()
        );
    }

    // ========================================================================
    // X12 856 — Ship Notice/ASN (3 samples)
    // ========================================================================

    private List<EdiTrainingDataService.AddSampleRequest> x12_856_samples() {
        return List.of(
                EdiTrainingDataService.AddSampleRequest.builder()
                        .sourceFormat("X12").sourceType("856").sourceVersion("005010").targetFormat("JSON")
                        .notes("X12 856 ASN — Global Supply ships to Acme")
                        .inputContent(
                                "ISA*00*          *00*          *ZZ*GLOBALSUPPLY   *ZZ*ACMECORP       *240320*1500*U*00501*000001301*0*P*>~" +
                                "GS*SH*GLOBALSUPPLY*ACMECORP*20240320*1500*1301*X*005010~" +
                                "ST*856*0001~" +
                                "BSN*00*SHP-2024032001*20240320*1500*0001~" +
                                "HL*1**S~" +
                                "TD5*B*2*FEDX*M*FedEx Ground~" +
                                "REF*BM*794644790135~" +
                                "DTM*011*20240322~" +
                                "N1*ST*Acme Corporation~" +
                                "N3*100 Innovation Drive~" +
                                "N4*San Francisco*CA*94105*US~" +
                                "N1*SF*Global Supply Inc~" +
                                "HL*2*1*O~" +
                                "PRF*PO-78234~" +
                                "HL*3*2*I~" +
                                "SN1*001*500*EA~" +
                                "SE*16*0001~" +
                                "GE*1*1301~" +
                                "IEA*1*000001301~")
                        .outputContent(
                                "{\n" +
                                "  \"documentType\": \"Ship Notice\",\n" +
                                "  \"shipmentId\": \"SHP-2024032001\",\n" +
                                "  \"shipDate\": \"2024-03-20\",\n" +
                                "  \"estimatedDelivery\": \"2024-03-22\",\n" +
                                "  \"carrier\": { \"code\": \"FEDX\", \"name\": \"FedEx Ground\" },\n" +
                                "  \"trackingNumber\": \"794644790135\",\n" +
                                "  \"shipTo\": { \"name\": \"Acme Corporation\", \"address\": { \"street\": \"100 Innovation Drive\", \"city\": \"San Francisco\", \"state\": \"CA\", \"zip\": \"94105\", \"country\": \"US\" } },\n" +
                                "  \"shipFrom\": { \"name\": \"Global Supply Inc\" },\n" +
                                "  \"poNumber\": \"PO-78234\",\n" +
                                "  \"items\": [\n" +
                                "    { \"lineNumber\": \"001\", \"quantity\": 500, \"unit\": \"EA\" }\n" +
                                "  ]\n" +
                                "}")
                        .build(),

                EdiTrainingDataService.AddSampleRequest.builder()
                        .sourceFormat("X12").sourceType("856").sourceVersion("005010").targetFormat("JSON")
                        .notes("X12 856 ASN — MegaDist ships to TechParts")
                        .inputContent(
                                "ISA*00*          *00*          *ZZ*MEGADIST       *ZZ*TECHPARTS      *240525*0900*U*00501*000001402*0*P*>~" +
                                "GS*SH*MEGADIST*TECHPARTS*20240525*0900*1402*X*005010~" +
                                "ST*856*0001~" +
                                "BSN*00*SHP-2024052501*20240525*0900*0001~" +
                                "HL*1**S~" +
                                "TD5*B*2*UPSN*M*UPS Next Day Air~" +
                                "REF*BM*1Z999AA10123456784~" +
                                "DTM*011*20240526~" +
                                "N1*ST*TechParts LLC~" +
                                "N3*789 Circuit Lane~" +
                                "N4*Austin*TX*78701*US~" +
                                "N1*SF*MegaDistributor Corp~" +
                                "HL*2*1*O~" +
                                "PRF*PO-99001~" +
                                "HL*3*2*I~" +
                                "SN1*001*1000*EA~" +
                                "HL*4*2*I~" +
                                "SN1*002*750*EA~" +
                                "SE*18*0001~" +
                                "GE*1*1402~" +
                                "IEA*1*000001402~")
                        .outputContent(
                                "{\n" +
                                "  \"documentType\": \"Ship Notice\",\n" +
                                "  \"shipmentId\": \"SHP-2024052501\",\n" +
                                "  \"shipDate\": \"2024-05-25\",\n" +
                                "  \"estimatedDelivery\": \"2024-05-26\",\n" +
                                "  \"carrier\": { \"code\": \"UPSN\", \"name\": \"UPS Next Day Air\" },\n" +
                                "  \"trackingNumber\": \"1Z999AA10123456784\",\n" +
                                "  \"shipTo\": { \"name\": \"TechParts LLC\", \"address\": { \"street\": \"789 Circuit Lane\", \"city\": \"Austin\", \"state\": \"TX\", \"zip\": \"78701\", \"country\": \"US\" } },\n" +
                                "  \"shipFrom\": { \"name\": \"MegaDistributor Corp\" },\n" +
                                "  \"poNumber\": \"PO-99001\",\n" +
                                "  \"items\": [\n" +
                                "    { \"lineNumber\": \"001\", \"quantity\": 1000, \"unit\": \"EA\" },\n" +
                                "    { \"lineNumber\": \"002\", \"quantity\": 750, \"unit\": \"EA\" }\n" +
                                "  ]\n" +
                                "}")
                        .build(),

                EdiTrainingDataService.AddSampleRequest.builder()
                        .sourceFormat("X12").sourceType("856").sourceVersion("004010").targetFormat("JSON")
                        .notes("X12 856 ASN — OrganicFarms ships to FreshFoods")
                        .inputContent(
                                "ISA*00*          *00*          *ZZ*ORGANICFARMS   *ZZ*FRESHFOODS     *241105*0700*U*00401*000001503*0*P*>~" +
                                "GS*SH*ORGANICFARMS*FRESHFOODS*20241105*0700*1503*X*004010~" +
                                "ST*856*0001~" +
                                "BSN*00*SHP-2024110501*20241105*0700*0001~" +
                                "HL*1**S~" +
                                "TD5*B*2*RLCA*M*Refrigerated Logistics~" +
                                "REF*BM*RFL-2024-887766~" +
                                "DTM*011*20241106~" +
                                "N1*ST*FreshFoods Market~" +
                                "N3*321 Harvest Street~" +
                                "N4*Portland*OR*97201*US~" +
                                "N1*SF*Organic Farms Co-op~" +
                                "HL*2*1*O~" +
                                "PRF*PO-55678~" +
                                "HL*3*2*I~" +
                                "SN1*001*2000*LB~" +
                                "HL*4*2*I~" +
                                "SN1*002*1500*LB~" +
                                "SE*18*0001~" +
                                "GE*1*1503~" +
                                "IEA*1*000001503~")
                        .outputContent(
                                "{\n" +
                                "  \"documentType\": \"Ship Notice\",\n" +
                                "  \"shipmentId\": \"SHP-2024110501\",\n" +
                                "  \"shipDate\": \"2024-11-05\",\n" +
                                "  \"estimatedDelivery\": \"2024-11-06\",\n" +
                                "  \"carrier\": { \"code\": \"RLCA\", \"name\": \"Refrigerated Logistics\" },\n" +
                                "  \"trackingNumber\": \"RFL-2024-887766\",\n" +
                                "  \"shipTo\": { \"name\": \"FreshFoods Market\", \"address\": { \"street\": \"321 Harvest Street\", \"city\": \"Portland\", \"state\": \"OR\", \"zip\": \"97201\", \"country\": \"US\" } },\n" +
                                "  \"shipFrom\": { \"name\": \"Organic Farms Co-op\" },\n" +
                                "  \"poNumber\": \"PO-55678\",\n" +
                                "  \"items\": [\n" +
                                "    { \"lineNumber\": \"001\", \"quantity\": 2000, \"unit\": \"LB\" },\n" +
                                "    { \"lineNumber\": \"002\", \"quantity\": 1500, \"unit\": \"LB\" }\n" +
                                "  ]\n" +
                                "}")
                        .build()
        );
    }

    // ========================================================================
    // EDIFACT ORDERS (2 samples)
    // ========================================================================

    private List<EdiTrainingDataService.AddSampleRequest> edifact_orders_samples() {
        return List.of(
                EdiTrainingDataService.AddSampleRequest.builder()
                        .sourceFormat("EDIFACT").sourceType("ORDERS").sourceVersion("D96A").targetFormat("JSON")
                        .notes("EDIFACT ORDERS — EuroTech to AsiaManufacturing")
                        .inputContent(
                                "UNB+UNOC:3+EUROTECH:ZZZ+ASIAMFG:ZZZ+240601:1200+REF00001++ORDERS'" +
                                "UNH+1+ORDERS:D:96A:UN'" +
                                "BGM+220+ORD-EU-44501+9'" +
                                "DTM+137:20240601:102'" +
                                "NAD+BY+EUROTECH::92++EuroTech GmbH+Industriestr 45+Berlin++10115+DE'" +
                                "NAD+SE+ASIAMFG::92++Asia Manufacturing Ltd+88 Factory Road+Shenzhen++518000+CN'" +
                                "LIN+1++PCB-MAIN-V3:VP'" +
                                "QTY+21:5000'" +
                                "PRI+AAA:4.25'" +
                                "LIN+2++CONN-USB-C:VP'" +
                                "QTY+21:10000'" +
                                "PRI+AAA:0.85'" +
                                "UNS+S'" +
                                "MOA+86:29750'" +
                                "UNT+14+1'" +
                                "UNZ+1+REF00001'")
                        .outputContent(
                                "{\n" +
                                "  \"documentType\": \"Purchase Order\",\n" +
                                "  \"orderNumber\": \"ORD-EU-44501\",\n" +
                                "  \"date\": \"2024-06-01\",\n" +
                                "  \"buyer\": { \"name\": \"EuroTech GmbH\", \"id\": \"EUROTECH\", \"address\": { \"street\": \"Industriestr 45\", \"city\": \"Berlin\", \"zip\": \"10115\", \"country\": \"DE\" } },\n" +
                                "  \"seller\": { \"name\": \"Asia Manufacturing Ltd\", \"id\": \"ASIAMFG\", \"address\": { \"street\": \"88 Factory Road\", \"city\": \"Shenzhen\", \"zip\": \"518000\", \"country\": \"CN\" } },\n" +
                                "  \"lineItems\": [\n" +
                                "    { \"lineNumber\": 1, \"productCode\": \"PCB-MAIN-V3\", \"quantity\": 5000, \"unitPrice\": 4.25 },\n" +
                                "    { \"lineNumber\": 2, \"productCode\": \"CONN-USB-C\", \"quantity\": 10000, \"unitPrice\": 0.85 }\n" +
                                "  ],\n" +
                                "  \"totalAmount\": 29750.00\n" +
                                "}")
                        .build(),

                EdiTrainingDataService.AddSampleRequest.builder()
                        .sourceFormat("EDIFACT").sourceType("ORDERS").sourceVersion("D01B").targetFormat("JSON")
                        .notes("EDIFACT ORDERS — NordicRetail to ItalyTextiles")
                        .inputContent(
                                "UNB+UNOC:3+NORDICRET:ZZZ+ITALYTEX:ZZZ+241015:0900+REF00002++ORDERS'" +
                                "UNH+1+ORDERS:D:01B:UN'" +
                                "BGM+220+ORD-NR-88102+9'" +
                                "DTM+137:20241015:102'" +
                                "NAD+BY+NORDICRET::92++Nordic Retail AB+Storgatan 12+Stockholm++11122+SE'" +
                                "NAD+SE+ITALYTEX::92++Italy Textiles SpA+Via Roma 100+Milano++20121+IT'" +
                                "LIN+1++SILK-BLU-M:VP'" +
                                "QTY+21:800'" +
                                "PRI+AAA:22.50'" +
                                "LIN+2++WOOL-GRY-L:VP'" +
                                "QTY+21:600'" +
                                "PRI+AAA:35.00'" +
                                "LIN+3++COTTON-WHT-S:VP'" +
                                "QTY+21:1200'" +
                                "PRI+AAA:12.75'" +
                                "UNS+S'" +
                                "MOA+86:54300'" +
                                "UNT+17+1'" +
                                "UNZ+1+REF00002'")
                        .outputContent(
                                "{\n" +
                                "  \"documentType\": \"Purchase Order\",\n" +
                                "  \"orderNumber\": \"ORD-NR-88102\",\n" +
                                "  \"date\": \"2024-10-15\",\n" +
                                "  \"buyer\": { \"name\": \"Nordic Retail AB\", \"id\": \"NORDICRET\", \"address\": { \"street\": \"Storgatan 12\", \"city\": \"Stockholm\", \"zip\": \"11122\", \"country\": \"SE\" } },\n" +
                                "  \"seller\": { \"name\": \"Italy Textiles SpA\", \"id\": \"ITALYTEX\", \"address\": { \"street\": \"Via Roma 100\", \"city\": \"Milano\", \"zip\": \"20121\", \"country\": \"IT\" } },\n" +
                                "  \"lineItems\": [\n" +
                                "    { \"lineNumber\": 1, \"productCode\": \"SILK-BLU-M\", \"quantity\": 800, \"unitPrice\": 22.50 },\n" +
                                "    { \"lineNumber\": 2, \"productCode\": \"WOOL-GRY-L\", \"quantity\": 600, \"unitPrice\": 35.00 },\n" +
                                "    { \"lineNumber\": 3, \"productCode\": \"COTTON-WHT-S\", \"quantity\": 1200, \"unitPrice\": 12.75 }\n" +
                                "  ],\n" +
                                "  \"totalAmount\": 54300.00\n" +
                                "}")
                        .build()
        );
    }

    // ========================================================================
    // EDIFACT INVOIC (2 samples)
    // ========================================================================

    private List<EdiTrainingDataService.AddSampleRequest> edifact_invoic_samples() {
        return List.of(
                EdiTrainingDataService.AddSampleRequest.builder()
                        .sourceFormat("EDIFACT").sourceType("INVOIC").sourceVersion("D96A").targetFormat("JSON")
                        .notes("EDIFACT INVOIC — AsiaManufacturing invoices EuroTech")
                        .inputContent(
                                "UNB+UNOC:3+ASIAMFG:ZZZ+EUROTECH:ZZZ+240715:1000+REF00003++INVOIC'" +
                                "UNH+1+INVOIC:D:96A:UN'" +
                                "BGM+380+INV-AM-66201+9'" +
                                "DTM+137:20240715:102'" +
                                "NAD+BY+EUROTECH::92++EuroTech GmbH+Industriestr 45+Berlin++10115+DE'" +
                                "NAD+SE+ASIAMFG::92++Asia Manufacturing Ltd+88 Factory Road+Shenzhen++518000+CN'" +
                                "LIN+1++PCB-MAIN-V3:VP'" +
                                "QTY+47:5000'" +
                                "PRI+AAA:4.25'" +
                                "MOA+203:21250'" +
                                "LIN+2++CONN-USB-C:VP'" +
                                "QTY+47:10000'" +
                                "PRI+AAA:0.85'" +
                                "MOA+203:8500'" +
                                "UNS+S'" +
                                "MOA+86:29750'" +
                                "MOA+176:4760'" +
                                "MOA+77:34510'" +
                                "UNT+18+1'" +
                                "UNZ+1+REF00003'")
                        .outputContent(
                                "{\n" +
                                "  \"documentType\": \"Invoice\",\n" +
                                "  \"invoiceNumber\": \"INV-AM-66201\",\n" +
                                "  \"invoiceDate\": \"2024-07-15\",\n" +
                                "  \"buyer\": { \"name\": \"EuroTech GmbH\", \"id\": \"EUROTECH\" },\n" +
                                "  \"seller\": { \"name\": \"Asia Manufacturing Ltd\", \"id\": \"ASIAMFG\" },\n" +
                                "  \"lineItems\": [\n" +
                                "    { \"lineNumber\": 1, \"productCode\": \"PCB-MAIN-V3\", \"quantity\": 5000, \"unitPrice\": 4.25, \"lineTotal\": 21250.00 },\n" +
                                "    { \"lineNumber\": 2, \"productCode\": \"CONN-USB-C\", \"quantity\": 10000, \"unitPrice\": 0.85, \"lineTotal\": 8500.00 }\n" +
                                "  ],\n" +
                                "  \"subtotal\": 29750.00,\n" +
                                "  \"tax\": 4760.00,\n" +
                                "  \"totalAmount\": 34510.00\n" +
                                "}")
                        .build(),

                EdiTrainingDataService.AddSampleRequest.builder()
                        .sourceFormat("EDIFACT").sourceType("INVOIC").sourceVersion("D01B").targetFormat("JSON")
                        .notes("EDIFACT INVOIC — ItalyTextiles invoices NordicRetail")
                        .inputContent(
                                "UNB+UNOC:3+ITALYTEX:ZZZ+NORDICRET:ZZZ+241101:1100+REF00004++INVOIC'" +
                                "UNH+1+INVOIC:D:01B:UN'" +
                                "BGM+380+INV-IT-99301+9'" +
                                "DTM+137:20241101:102'" +
                                "NAD+BY+NORDICRET::92++Nordic Retail AB+Storgatan 12+Stockholm++11122+SE'" +
                                "NAD+SE+ITALYTEX::92++Italy Textiles SpA+Via Roma 100+Milano++20121+IT'" +
                                "LIN+1++SILK-BLU-M:VP'" +
                                "QTY+47:800'" +
                                "PRI+AAA:22.50'" +
                                "MOA+203:18000'" +
                                "LIN+2++WOOL-GRY-L:VP'" +
                                "QTY+47:600'" +
                                "PRI+AAA:35.00'" +
                                "MOA+203:21000'" +
                                "LIN+3++COTTON-WHT-S:VP'" +
                                "QTY+47:1200'" +
                                "PRI+AAA:12.75'" +
                                "MOA+203:15300'" +
                                "UNS+S'" +
                                "MOA+86:54300'" +
                                "MOA+176:10860'" +
                                "MOA+77:65160'" +
                                "UNT+22+1'" +
                                "UNZ+1+REF00004'")
                        .outputContent(
                                "{\n" +
                                "  \"documentType\": \"Invoice\",\n" +
                                "  \"invoiceNumber\": \"INV-IT-99301\",\n" +
                                "  \"invoiceDate\": \"2024-11-01\",\n" +
                                "  \"buyer\": { \"name\": \"Nordic Retail AB\", \"id\": \"NORDICRET\" },\n" +
                                "  \"seller\": { \"name\": \"Italy Textiles SpA\", \"id\": \"ITALYTEX\" },\n" +
                                "  \"lineItems\": [\n" +
                                "    { \"lineNumber\": 1, \"productCode\": \"SILK-BLU-M\", \"quantity\": 800, \"unitPrice\": 22.50, \"lineTotal\": 18000.00 },\n" +
                                "    { \"lineNumber\": 2, \"productCode\": \"WOOL-GRY-L\", \"quantity\": 600, \"unitPrice\": 35.00, \"lineTotal\": 21000.00 },\n" +
                                "    { \"lineNumber\": 3, \"productCode\": \"COTTON-WHT-S\", \"quantity\": 1200, \"unitPrice\": 12.75, \"lineTotal\": 15300.00 }\n" +
                                "  ],\n" +
                                "  \"subtotal\": 54300.00,\n" +
                                "  \"tax\": 10860.00,\n" +
                                "  \"totalAmount\": 65160.00\n" +
                                "}")
                        .build()
        );
    }

    // ========================================================================
    // HL7 ADT^A01 — Patient Admission (2 samples)
    // ========================================================================

    private List<EdiTrainingDataService.AddSampleRequest> hl7_adt_samples() {
        return List.of(
                EdiTrainingDataService.AddSampleRequest.builder()
                        .sourceFormat("HL7").sourceType("ADT_A01").targetFormat("JSON")
                        .notes("HL7 ADT^A01 Patient Admission — patient Thompson")
                        .inputContent(
                                "MSH|^~\\&|ADMITSYS|MERCY_HOSP|LABSYS|MERCY_HOSP|20240201120000||ADT^A01|MSG00001|P|2.5\r" +
                                "EVN|A01|20240201120000\r" +
                                "PID|1||PAT-44501^^^MERCY||Thompson^James^R||19850315|M|||456 Oak Avenue^^Denver^CO^80201^US||3035551234|||M\r" +
                                "PV1|1|I|ICU^0301^01||||DOC7721^Anderson^Michael^J^^^MD|DOC8832^Rivera^Elena^M^^^MD||MED||||7||DOC7721^Anderson^Michael^J^^^MD|IP|VN-20240201001|||||||||||||||||||20240201120000")
                        .outputContent(
                                "{\n" +
                                "  \"documentType\": \"Patient Admission\",\n" +
                                "  \"messageId\": \"MSG00001\",\n" +
                                "  \"eventType\": \"A01\",\n" +
                                "  \"eventDateTime\": \"2024-02-01T12:00:00\",\n" +
                                "  \"sendingApplication\": \"ADMITSYS\",\n" +
                                "  \"sendingFacility\": \"MERCY_HOSP\",\n" +
                                "  \"patient\": {\n" +
                                "    \"patientId\": \"PAT-44501\",\n" +
                                "    \"lastName\": \"Thompson\",\n" +
                                "    \"firstName\": \"James\",\n" +
                                "    \"middleName\": \"R\",\n" +
                                "    \"dateOfBirth\": \"1985-03-15\",\n" +
                                "    \"gender\": \"M\",\n" +
                                "    \"address\": { \"street\": \"456 Oak Avenue\", \"city\": \"Denver\", \"state\": \"CO\", \"zip\": \"80201\", \"country\": \"US\" },\n" +
                                "    \"phone\": \"3035551234\"\n" +
                                "  },\n" +
                                "  \"visit\": {\n" +
                                "    \"patientClass\": \"I\",\n" +
                                "    \"location\": \"ICU-0301-01\",\n" +
                                "    \"attendingDoctor\": { \"id\": \"DOC7721\", \"name\": \"Anderson, Michael J\" },\n" +
                                "    \"referringDoctor\": { \"id\": \"DOC8832\", \"name\": \"Rivera, Elena M\" },\n" +
                                "    \"visitNumber\": \"VN-20240201001\",\n" +
                                "    \"admitDateTime\": \"2024-02-01T12:00:00\"\n" +
                                "  }\n" +
                                "}")
                        .build(),

                EdiTrainingDataService.AddSampleRequest.builder()
                        .sourceFormat("HL7").sourceType("ADT_A01").targetFormat("JSON")
                        .notes("HL7 ADT^A01 Patient Admission — patient Chen")
                        .inputContent(
                                "MSH|^~\\&|EPICADT|STANFORD_MED|LABINTF|STANFORD_MED|20240918083000||ADT^A01|MSG00002|P|2.5\r" +
                                "EVN|A01|20240918083000\r" +
                                "PID|1||PAT-77802^^^STANFORD||Chen^Lisa^W||19920728|F|||1200 University Ave^^Palo Alto^CA^94301^US||6505559876|||S\r" +
                                "PV1|1|E|ER^0102^05||||DOC3345^Nakamura^Kenji^^^^MD|DOC4456^Gupta^Priya^^^^MD||EMER||||2||DOC3345^Nakamura^Kenji^^^^MD|ER|VN-20240918002|||||||||||||||||||20240918083000")
                        .outputContent(
                                "{\n" +
                                "  \"documentType\": \"Patient Admission\",\n" +
                                "  \"messageId\": \"MSG00002\",\n" +
                                "  \"eventType\": \"A01\",\n" +
                                "  \"eventDateTime\": \"2024-09-18T08:30:00\",\n" +
                                "  \"sendingApplication\": \"EPICADT\",\n" +
                                "  \"sendingFacility\": \"STANFORD_MED\",\n" +
                                "  \"patient\": {\n" +
                                "    \"patientId\": \"PAT-77802\",\n" +
                                "    \"lastName\": \"Chen\",\n" +
                                "    \"firstName\": \"Lisa\",\n" +
                                "    \"middleName\": \"W\",\n" +
                                "    \"dateOfBirth\": \"1992-07-28\",\n" +
                                "    \"gender\": \"F\",\n" +
                                "    \"address\": { \"street\": \"1200 University Ave\", \"city\": \"Palo Alto\", \"state\": \"CA\", \"zip\": \"94301\", \"country\": \"US\" },\n" +
                                "    \"phone\": \"6505559876\"\n" +
                                "  },\n" +
                                "  \"visit\": {\n" +
                                "    \"patientClass\": \"E\",\n" +
                                "    \"location\": \"ER-0102-05\",\n" +
                                "    \"attendingDoctor\": { \"id\": \"DOC3345\", \"name\": \"Nakamura, Kenji\" },\n" +
                                "    \"referringDoctor\": { \"id\": \"DOC4456\", \"name\": \"Gupta, Priya\" },\n" +
                                "    \"visitNumber\": \"VN-20240918002\",\n" +
                                "    \"admitDateTime\": \"2024-09-18T08:30:00\"\n" +
                                "  }\n" +
                                "}")
                        .build()
        );
    }

    // ========================================================================
    // SWIFT MT103 — Wire Transfer (2 samples)
    // ========================================================================

    private List<EdiTrainingDataService.AddSampleRequest> swift_mt103_samples() {
        return List.of(
                EdiTrainingDataService.AddSampleRequest.builder()
                        .sourceFormat("SWIFT_MT").sourceType("MT103").targetFormat("JSON")
                        .notes("SWIFT MT103 Wire Transfer — USD from JPMorgan to HSBC")
                        .inputContent(
                                "{1:F01CHASUS33AXXX0000000000}{2:O1031200240301HSBCGB2LAXXX00000000002403011200N}{4:\n" +
                                ":20:TXN-20240301-001\n" +
                                ":23B:CRED\n" +
                                ":32A:240301USD125000,00\n" +
                                ":50K:/US33001234567890\n" +
                                "ACME CORPORATION\n" +
                                "100 INNOVATION DRIVE\n" +
                                "SAN FRANCISCO CA 94105\n" +
                                ":52A:CHASUS33XXX\n" +
                                ":57A:HSBCGB2LXXX\n" +
                                ":59:/GB82WEST12345698765432\n" +
                                "EUROTECH GMBH\n" +
                                "INDUSTRIESTR 45\n" +
                                "BERLIN 10115\n" +
                                ":70:PAYMENT FOR INV-20240215-001\n" +
                                ":71A:SHA\n" +
                                "-}")
                        .outputContent(
                                "{\n" +
                                "  \"documentType\": \"Wire Transfer\",\n" +
                                "  \"transactionRef\": \"TXN-20240301-001\",\n" +
                                "  \"valueDate\": \"2024-03-01\",\n" +
                                "  \"currency\": \"USD\",\n" +
                                "  \"amount\": 125000.00,\n" +
                                "  \"sender\": {\n" +
                                "    \"account\": \"US33001234567890\",\n" +
                                "    \"name\": \"ACME CORPORATION\",\n" +
                                "    \"address\": \"100 INNOVATION DRIVE, SAN FRANCISCO CA 94105\",\n" +
                                "    \"bank\": \"CHASUS33XXX\"\n" +
                                "  },\n" +
                                "  \"receiver\": {\n" +
                                "    \"account\": \"GB82WEST12345698765432\",\n" +
                                "    \"name\": \"EUROTECH GMBH\",\n" +
                                "    \"address\": \"INDUSTRIESTR 45, BERLIN 10115\",\n" +
                                "    \"bank\": \"HSBCGB2LXXX\"\n" +
                                "  },\n" +
                                "  \"details\": \"PAYMENT FOR INV-20240215-001\",\n" +
                                "  \"chargeBearer\": \"SHA\"\n" +
                                "}")
                        .build(),

                EdiTrainingDataService.AddSampleRequest.builder()
                        .sourceFormat("SWIFT_MT").sourceType("MT103").targetFormat("JSON")
                        .notes("SWIFT MT103 Wire Transfer — EUR from Deutsche Bank to BNP Paribas")
                        .inputContent(
                                "{1:F01DEUTDEFFAXXX0000000000}{2:O1030930241015BNPAFRPPAXXX00000000002410150930N}{4:\n" +
                                ":20:TXN-20241015-002\n" +
                                ":23B:CRED\n" +
                                ":32A:241015EUR87500,00\n" +
                                ":50K:/DE89370400440532013000\n" +
                                "NORDIC RETAIL AB\n" +
                                "STORGATAN 12\n" +
                                "STOCKHOLM 11122\n" +
                                ":52A:DEUTDEFFXXX\n" +
                                ":57A:BNPAFRPPXXX\n" +
                                ":59:/FR7630004000031234567890143\n" +
                                "ITALY TEXTILES SPA\n" +
                                "VIA ROMA 100\n" +
                                "MILANO 20121\n" +
                                ":70:PAYMENT FOR INV-IT-99301\n" +
                                ":71A:OUR\n" +
                                "-}")
                        .outputContent(
                                "{\n" +
                                "  \"documentType\": \"Wire Transfer\",\n" +
                                "  \"transactionRef\": \"TXN-20241015-002\",\n" +
                                "  \"valueDate\": \"2024-10-15\",\n" +
                                "  \"currency\": \"EUR\",\n" +
                                "  \"amount\": 87500.00,\n" +
                                "  \"sender\": {\n" +
                                "    \"account\": \"DE89370400440532013000\",\n" +
                                "    \"name\": \"NORDIC RETAIL AB\",\n" +
                                "    \"address\": \"STORGATAN 12, STOCKHOLM 11122\",\n" +
                                "    \"bank\": \"DEUTDEFFXXX\"\n" +
                                "  },\n" +
                                "  \"receiver\": {\n" +
                                "    \"account\": \"FR7630004000031234567890143\",\n" +
                                "    \"name\": \"ITALY TEXTILES SPA\",\n" +
                                "    \"address\": \"VIA ROMA 100, MILANO 20121\",\n" +
                                "    \"bank\": \"BNPAFRPPXXX\"\n" +
                                "  },\n" +
                                "  \"details\": \"PAYMENT FOR INV-IT-99301\",\n" +
                                "  \"chargeBearer\": \"OUR\"\n" +
                                "}")
                        .build()
        );
    }
}
