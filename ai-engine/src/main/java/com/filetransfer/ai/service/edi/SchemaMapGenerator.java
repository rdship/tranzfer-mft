package com.filetransfer.ai.service.edi;

import com.filetransfer.ai.entity.edi.ConversionMap;
import com.filetransfer.ai.repository.edi.ConversionMapRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Generates conversion maps from known schema definitions using field-name
 * similarity (via {@link FieldEmbeddingEngine}).  No training samples needed —
 * the generator matches source and target fields by name/semantic similarity
 * and produces a DRAFT map for human review.
 *
 * Typical usage:
 *   POST /api/v1/edi/training/generate-from-schema
 *     { "sourceSchema": "X12_850", "targetSchema": "PURCHASE_ORDER_INH" }
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SchemaMapGenerator {

    private final FieldEmbeddingEngine embeddingEngine;
    private final ConversionMapRepository mapRepo;

    private static final double MATCH_THRESHOLD = 0.35;

    // ===================================================================
    // Known field schemas for common EDI / INHOUSE document types
    // ===================================================================
    private static final Map<String, List<String>> KNOWN_SCHEMAS = Map.ofEntries(
            // --- X12 ---
            Map.entry("X12_850", List.of(
                    "BEG.01:purpose", "BEG.02:orderType", "BEG.03:poNumber", "BEG.05:date",
                    "N1.01:entityCode", "N1.02:name", "N1.04:id",
                    "N3.01:address", "N4.01:city", "N4.02:state", "N4.03:zip", "N4.04:country",
                    "PO1.01:lineNum", "PO1.02:quantity", "PO1.03:unit", "PO1.04:price", "PO1.07:productId")),
            Map.entry("X12_810", List.of(
                    "BIG.01:invoiceDate", "BIG.02:invoiceNumber", "BIG.04:poNumber",
                    "N1.01:entityCode", "N1.02:name", "N1.04:id",
                    "N3.01:address", "N4.01:city", "N4.02:state", "N4.03:zip",
                    "IT1.01:lineNum", "IT1.02:quantity", "IT1.03:unit", "IT1.04:price", "IT1.07:productId",
                    "TDS.01:totalAmount")),
            Map.entry("X12_856", List.of(
                    "BSN.01:purpose", "BSN.02:shipmentId", "BSN.03:date", "BSN.04:time",
                    "HL.01:hlId", "HL.02:parentId", "HL.03:levelCode",
                    "TD1.01:packagingCode", "TD1.02:ladingQuantity",
                    "TD5.01:routingCode", "TD5.02:carrier", "TD5.04:transportMethod",
                    "REF.01:refQualifier", "REF.02:refId",
                    "N1.01:entityCode", "N1.02:name", "N1.04:id",
                    "SN1.01:lineNum", "SN1.02:shippedQty", "SN1.03:unit")),
            Map.entry("X12_997", List.of(
                    "AK1.01:functionalGroupId", "AK1.02:groupControlNumber",
                    "AK2.01:transactionSetId", "AK2.02:controlNumber",
                    "AK5.01:acknowledgementCode",
                    "AK9.01:groupAckCode", "AK9.02:transactionSetsIncluded",
                    "AK9.03:transactionSetsReceived", "AK9.04:transactionSetsAccepted")),
            Map.entry("X12_204", List.of(
                    "B2.02:standardCarrierCode", "B2.04:shipmentId",
                    "B2A.01:purpose",
                    "N1.01:entityCode", "N1.02:name", "N1.04:id",
                    "N3.01:address", "N4.01:city", "N4.02:state", "N4.03:zip",
                    "S5.01:stopSequence", "S5.02:stopReasonCode",
                    "L5.01:lineNum", "L5.02:ladingDescription", "L5.03:commodityCode")),

            // --- EDIFACT ---
            Map.entry("EDIFACT_ORDERS", List.of(
                    "BGM.documentName", "BGM.documentNumber",
                    "DTM.dateTime", "DTM.qualifier",
                    "NAD.partyQualifier", "NAD.partyId", "NAD.partyName",
                    "NAD.street", "NAD.city", "NAD.postalCode", "NAD.country",
                    "LIN.lineNumber", "LIN.itemNumber",
                    "QTY.quantity", "QTY.qualifier",
                    "PRI.price", "PRI.qualifier",
                    "MOA.monetaryAmount", "MOA.qualifier")),
            Map.entry("EDIFACT_INVOIC", List.of(
                    "BGM.documentName", "BGM.documentNumber",
                    "DTM.invoiceDate", "DTM.qualifier",
                    "NAD.partyQualifier", "NAD.partyId", "NAD.partyName",
                    "NAD.street", "NAD.city", "NAD.postalCode", "NAD.country",
                    "LIN.lineNumber", "LIN.itemNumber",
                    "QTY.quantity", "MOA.lineAmount",
                    "MOA.totalAmount", "TAX.taxRate", "TAX.taxAmount")),
            Map.entry("EDIFACT_DESADV", List.of(
                    "BGM.documentName", "BGM.documentNumber",
                    "DTM.despatchDate", "DTM.deliveryDate",
                    "NAD.shipFrom", "NAD.shipTo",
                    "CPS.hierarchicalId", "CPS.parentId",
                    "PAC.numberOfPackages", "PAC.packageType",
                    "LIN.lineNumber", "LIN.itemNumber",
                    "QTY.despatchedQuantity")),

            // --- INHOUSE types ---
            Map.entry("PURCHASE_ORDER_INH", List.of(
                    "documentNumber", "documentDate", "purpose", "orderType",
                    "buyer.name", "buyer.id", "buyer.address", "buyer.city", "buyer.state", "buyer.zip", "buyer.country",
                    "seller.name", "seller.id", "seller.address", "seller.city", "seller.state", "seller.zip",
                    "lineItems.lineNumber", "lineItems.quantity", "lineItems.unitOfMeasure",
                    "lineItems.unitPrice", "lineItems.productId", "lineItems.description")),
            Map.entry("INVOICE_INH", List.of(
                    "invoiceNumber", "invoiceDate", "poNumber",
                    "buyer.name", "buyer.id", "seller.name", "seller.id",
                    "buyer.address", "buyer.city", "buyer.state", "buyer.zip",
                    "lineItems.lineNumber", "lineItems.quantity", "lineItems.unitOfMeasure",
                    "lineItems.unitPrice", "lineItems.productId",
                    "totalAmount", "taxAmount", "netAmount")),
            Map.entry("SHIP_NOTICE_INH", List.of(
                    "shipmentId", "shipDate", "purpose",
                    "carrier", "transportMethod",
                    "shipFrom.name", "shipFrom.id", "shipFrom.address", "shipFrom.city",
                    "shipTo.name", "shipTo.id", "shipTo.address", "shipTo.city",
                    "lineItems.lineNumber", "lineItems.shippedQuantity",
                    "lineItems.unitOfMeasure", "lineItems.productId",
                    "packages.packageType", "packages.quantity")),
            Map.entry("FUNCTIONAL_ACK_INH", List.of(
                    "groupId", "groupControlNumber",
                    "transactionSetId", "controlNumber",
                    "acknowledgementCode",
                    "transactionSetsIncluded", "transactionSetsReceived", "transactionSetsAccepted"))
    );

    /**
     * Generate a DRAFT conversion map by matching field names across two known schemas.
     *
     * @param sourceSchema e.g. "X12_850"
     * @param targetSchema e.g. "PURCHASE_ORDER_INH"
     * @param partnerId    optional partner ID for partner-specific maps
     * @return result containing the generated map and matching details
     */
    @Transactional
    public GenerationResult generate(String sourceSchema, String targetSchema, String partnerId) {
        Instant start = Instant.now();

        List<String> sourceFields = KNOWN_SCHEMAS.get(sourceSchema);
        List<String> targetFields = KNOWN_SCHEMAS.get(targetSchema);

        if (sourceFields == null) {
            return GenerationResult.error("Unknown source schema: " + sourceSchema
                    + ". Known schemas: " + KNOWN_SCHEMAS.keySet());
        }
        if (targetFields == null) {
            return GenerationResult.error("Unknown target schema: " + targetSchema
                    + ". Known schemas: " + KNOWN_SCHEMAS.keySet());
        }

        log.info("Generating map from {} ({} fields) -> {} ({} fields)",
                sourceSchema, sourceFields.size(), targetSchema, targetFields.size());

        // Use FieldEmbeddingEngine to find best matches
        List<FieldEmbeddingEngine.FieldMatch> allMatches =
                embeddingEngine.computeSimilarityMatrix(sourceFields, targetFields, MATCH_THRESHOLD);

        // Greedy assignment: for each target field, pick the highest-scoring unassigned source
        Set<String> assignedSource = new HashSet<>();
        Set<String> assignedTarget = new HashSet<>();
        List<EdiMapTrainingEngine.FieldMapping> fieldMappings = new ArrayList<>();

        for (FieldEmbeddingEngine.FieldMatch match : allMatches) {
            if (assignedSource.contains(match.sourceField()) || assignedTarget.contains(match.targetField())) {
                continue;
            }
            assignedSource.add(match.sourceField());
            assignedTarget.add(match.targetField());

            int confidence = (int) Math.round(match.similarity() * 100);
            fieldMappings.add(EdiMapTrainingEngine.FieldMapping.builder()
                    .sourceField(match.sourceField())
                    .targetField(match.targetField())
                    .transform("COPY")
                    .confidence(confidence)
                    .strategy("SCHEMA_SIMILARITY")
                    .reasoning(match.reasoning())
                    .build());
        }

        // Calculate overall confidence as the average of individual confidences
        int overallConfidence = fieldMappings.isEmpty() ? 0
                : (int) Math.round(fieldMappings.stream()
                        .mapToInt(EdiMapTrainingEngine.FieldMapping::getConfidence)
                        .average().orElse(0));

        // Determine unmapped fields
        List<String> unmappedSource = sourceFields.stream()
                .filter(f -> !assignedSource.contains(f))
                .toList();
        List<String> unmappedTarget = targetFields.stream()
                .filter(f -> !assignedTarget.contains(f))
                .toList();

        // Determine source/target format components
        String sourceFormat = extractFormat(sourceSchema);
        String targetFormat = extractFormat(targetSchema);
        String sourceType = extractType(sourceSchema);
        String targetType = extractType(targetSchema);

        // Build map key
        String mapKey = TrainedMapStore.buildMapKey(sourceFormat, sourceType, targetFormat, targetType, partnerId);

        // Serialize field mappings
        String fieldMappingsJson;
        String unmappedSourceJson;
        String unmappedTargetJson;
        try {
            var objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
            fieldMappingsJson = objectMapper.writeValueAsString(fieldMappings);
            unmappedSourceJson = objectMapper.writeValueAsString(unmappedSource);
            unmappedTargetJson = objectMapper.writeValueAsString(unmappedTarget);
        } catch (Exception e) {
            return GenerationResult.error("Failed to serialize mappings: " + e.getMessage());
        }

        // Store as DRAFT
        ConversionMap draftMap = ConversionMap.builder()
                .mapKey(mapKey)
                .name("Schema-generated: " + sourceSchema + " -> " + targetSchema)
                .sourceFormat(sourceFormat)
                .sourceType(sourceType)
                .targetFormat(targetFormat)
                .targetType(targetType)
                .partnerId(partnerId)
                .status("DRAFT")
                .version(1)
                .active(false) // DRAFT — not active until reviewed
                .confidence(overallConfidence)
                .sampleCount(0) // no samples used
                .fieldMappingCount(fieldMappings.size())
                .fieldMappingsJson(fieldMappingsJson)
                .unmappedSourceFieldsJson(unmappedSourceJson)
                .unmappedTargetFieldsJson(unmappedTargetJson)
                .build();

        draftMap = mapRepo.save(draftMap);

        long durationMs = java.time.Duration.between(start, Instant.now()).toMillis();

        log.info("Generated DRAFT map '{}': {} mappings, {}% confidence, {} unmapped source, {} unmapped target ({}ms)",
                mapKey, fieldMappings.size(), overallConfidence,
                unmappedSource.size(), unmappedTarget.size(), durationMs);

        return GenerationResult.builder()
                .success(true)
                .mapId(draftMap.getId().toString())
                .mapKey(mapKey)
                .status("DRAFT")
                .fieldMappings(fieldMappings)
                .overallConfidence(overallConfidence)
                .unmappedSourceFields(unmappedSource)
                .unmappedTargetFields(unmappedTarget)
                .fieldMappingCount(fieldMappings.size())
                .durationMs(durationMs)
                .build();
    }

    /**
     * List all known schema names.
     */
    public Set<String> listKnownSchemas() {
        return KNOWN_SCHEMAS.keySet();
    }

    // --- Helpers ---

    private String extractFormat(String schema) {
        if (schema.startsWith("X12_")) return "X12";
        if (schema.startsWith("EDIFACT_")) return "EDIFACT";
        if (schema.endsWith("_INH")) return "INHOUSE";
        return schema;
    }

    private String extractType(String schema) {
        if (schema.startsWith("X12_")) return schema.substring(4);
        if (schema.startsWith("EDIFACT_")) return schema.substring(8);
        if (schema.endsWith("_INH")) return schema.substring(0, schema.length() - 4);
        return schema;
    }

    // --- Result DTO ---

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class GenerationResult {
        private boolean success;
        private String error;
        private String mapId;
        private String mapKey;
        private String status;
        private List<EdiMapTrainingEngine.FieldMapping> fieldMappings;
        private int overallConfidence;
        private List<String> unmappedSourceFields;
        private List<String> unmappedTargetFields;
        private int fieldMappingCount;
        private long durationMs;

        public static GenerationResult error(String msg) {
            return GenerationResult.builder().success(false).error(msg).build();
        }
    }
}
