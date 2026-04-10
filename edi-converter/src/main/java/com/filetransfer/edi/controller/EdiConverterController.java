package com.filetransfer.edi.controller;

import com.filetransfer.edi.converter.*;
import com.filetransfer.edi.format.TemplateLibrary;
import com.filetransfer.edi.map.*;
import com.filetransfer.edi.model.CanonicalDocument;
import com.filetransfer.edi.model.EdiDocument;
import com.filetransfer.edi.parser.*;
import com.filetransfer.edi.service.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RestController @RequestMapping("/api/v1/convert") @RequiredArgsConstructor
public class EdiConverterController {

    private final FormatDetector detector;
    private final UniversalEdiParser parser;
    private final UniversalConverter converter;
    private final EdiExplainer explainer;
    private final SmartValidator validator;
    private final TemplateLibrary templateLibrary;
    private final CanonicalMapper canonicalMapper;
    private final StreamingEdiParser streamingParser;
    private final SelfHealingEngine selfHealingEngine;
    private final SemanticDiffEngine semanticDiffEngine;
    private final ComplianceScorer complianceScorer;
    private final PartnerProfileManager partnerProfileManager;
    private final AiMappingGenerator aiMappingGenerator;
    private final NaturalLanguageEdiCreator nlEdiCreator;
    private final TrainedMapConsumer trainedMapConsumer;
    private final MapResolver mapResolver;
    private final MapBasedConverter mapBasedConverter;
    private final HipaaParser hipaaParser;

    // ===================================================================
    // CORE CONVERSION
    // ===================================================================

    @PostMapping("/detect")
    public Map<String, String> detect(@RequestBody Map<String, String> body) {
        return Map.of("format", detector.detect(body.get("content")));
    }

    @PostMapping("/parse")
    public EdiDocument parse(@RequestBody Map<String, String> body) {
        return parser.parse(body.get("content"));
    }

    @PostMapping("/convert")
    public ResponseEntity<String> convert(@RequestBody Map<String, String> body) {
        EdiDocument doc = parser.parse(body.get("content"));
        String target = body.getOrDefault("target", "JSON");
        String ct = switch (target.toUpperCase()) {
            case "JSON", "TIF" -> "application/json"; case "XML" -> "application/xml";
            case "CSV" -> "text/csv"; case "YAML" -> "application/yaml"; default -> "text/plain";
        };
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(ct)).body(converter.convert(doc, target));
    }

    @PostMapping(value = "/convert/file", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<String> convertFile(@RequestPart("file") MultipartFile file,
            @RequestParam(defaultValue = "JSON") String target) throws Exception {
        EdiDocument doc = parser.parse(new String(file.getBytes()));
        return ResponseEntity.ok().body(converter.convert(doc, target));
    }

    // ===================================================================
    // HUMAN-READABLE EXPLAIN
    // ===================================================================

    @PostMapping("/explain")
    public EdiExplainer.ExplainedDocument explain(@RequestBody Map<String, String> body) {
        EdiDocument doc = parser.parse(body.get("content"));
        return explainer.explain(doc);
    }

    // ===================================================================
    // SMART VALIDATION
    // ===================================================================

    @PostMapping("/validate")
    public SmartValidator.ValidationReport validate(@RequestBody Map<String, String> body) {
        EdiDocument doc = parser.parse(body.get("content"));
        return validator.validate(doc);
    }

    // ===================================================================
    // TEMPLATES
    // ===================================================================

    @GetMapping("/templates")
    public List<TemplateLibrary.Template> listTemplates() {
        return templateLibrary.listTemplates();
    }

    @PostMapping("/templates/{templateId}/generate")
    public ResponseEntity<String> generateFromTemplate(@PathVariable String templateId,
            @RequestBody Map<String, String> values) {
        String edi = templateLibrary.generate(templateId, values);
        return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body(edi);
    }

    // ===================================================================
    // NEW: CANONICAL DATA MODEL
    // ===================================================================

    /** Convert any EDI document to the universal Canonical Data Model */
    @PostMapping("/canonical")
    public CanonicalDocument toCanonical(@RequestBody Map<String, String> body) {
        EdiDocument doc = parser.parse(body.get("content"));
        return canonicalMapper.toCanonical(doc);
    }

    // ===================================================================
    // NEW: STREAMING PARSER
    // ===================================================================

    /** Stream-parse a large file (returns stats, not the full document) */
    @PostMapping("/stream")
    public StreamingEdiParser.StreamResult streamParse(@RequestBody Map<String, String> body) {
        String content = body.get("content");
        String format = body.getOrDefault("format", detector.detect(content));
        List<Map<String, Object>> segments = new ArrayList<>();
        StreamingEdiParser.StreamResult result = streamingParser.stream(content, format, event -> {
            if (event.getType() == StreamingEdiParser.ParseEvent.EventType.SEGMENT && event.getSegment() != null) {
                Map<String, Object> seg = new LinkedHashMap<>();
                seg.put("id", event.getSegment().getId());
                seg.put("position", event.getSegmentNumber());
                if (event.getSegment().getElements() != null) seg.put("elementCount", event.getSegment().getElements().size());
                segments.add(seg);
            }
        });
        // Add segment summary to metadata
        if (result.getMetadata() == null) result.setMetadata(new LinkedHashMap<>());
        result.getMetadata().put("segmentTypes", String.valueOf(segments.stream().map(s -> s.get("id")).distinct().count()));
        return result;
    }

    /** Stream-parse a large uploaded file */
    @PostMapping(value = "/stream/file", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public StreamingEdiParser.StreamResult streamFile(@RequestPart("file") MultipartFile file,
            @RequestParam(defaultValue = "AUTO") String format) throws Exception {
        String content = new String(file.getBytes());
        String detectedFormat = "AUTO".equals(format) ? detector.detect(content) : format;
        return streamingParser.stream(
                new ByteArrayInputStream(file.getBytes()), detectedFormat,
                event -> {} // just collect stats
        );
    }

    // ===================================================================
    // NEW: SELF-HEALING ENGINE
    // ===================================================================

    /** Auto-detect and fix common EDI errors */
    @PostMapping("/heal")
    public SelfHealingEngine.HealingResult heal(@RequestBody Map<String, String> body) {
        String content = body.get("content");
        String format = body.getOrDefault("format", detector.detect(content));
        return selfHealingEngine.heal(content, format);
    }

    // ===================================================================
    // NEW: SEMANTIC DIFF
    // ===================================================================

    /** Compare two EDI documents with field-level semantic diff */
    @PostMapping("/diff")
    public SemanticDiffEngine.DiffResult diff(@RequestBody Map<String, String> body) {
        return semanticDiffEngine.diff(body.get("left"), body.get("right"));
    }

    // ===================================================================
    // NEW: COMPLIANCE SCORING
    // ===================================================================

    /** Score an EDI document 0-100 for compliance */
    @PostMapping("/compliance")
    public ComplianceScorer.ComplianceReport compliance(@RequestBody Map<String, String> body) {
        EdiDocument doc = parser.parse(body.get("content"));
        return complianceScorer.score(doc);
    }

    // ===================================================================
    // NEW: PARTNER PROFILES
    // ===================================================================

    @GetMapping("/partners")
    public List<PartnerProfileManager.PartnerProfile> listPartnerProfiles() {
        return partnerProfileManager.getAllProfiles();
    }

    @GetMapping("/partners/{partnerId}")
    public ResponseEntity<PartnerProfileManager.PartnerProfile> getPartnerProfile(@PathVariable String partnerId) {
        var profile = partnerProfileManager.getProfile(partnerId);
        return profile != null ? ResponseEntity.ok(profile) : ResponseEntity.notFound().build();
    }

    @PostMapping("/partners")
    public PartnerProfileManager.PartnerProfile createPartnerProfile(
            @RequestBody PartnerProfileManager.PartnerProfile profile) {
        return partnerProfileManager.createProfile(profile);
    }

    @PutMapping("/partners/{partnerId}")
    public PartnerProfileManager.PartnerProfile updatePartnerProfile(
            @PathVariable String partnerId,
            @RequestBody PartnerProfileManager.PartnerProfile profile) {
        return partnerProfileManager.updateProfile(partnerId, profile);
    }

    @DeleteMapping("/partners/{partnerId}")
    public Map<String, Boolean> deletePartnerProfile(@PathVariable String partnerId) {
        return Map.of("deleted", partnerProfileManager.deleteProfile(partnerId));
    }

    /** Upload a sample EDI from a new partner → auto-generate their profile */
    @PostMapping("/partners/{partnerId}/analyze")
    public PartnerProfileManager.ProfileAnalysis analyzePartner(
            @PathVariable String partnerId,
            @RequestBody Map<String, String> body) {
        String name = body.getOrDefault("partnerName", partnerId);
        String content = body.get("content");
        String format = body.getOrDefault("format", detector.detect(content));
        return partnerProfileManager.analyzeAndCreateProfile(partnerId, name, content, format);
    }

    /** Apply a partner's profile/rules to an outgoing document */
    @PostMapping("/partners/{partnerId}/apply")
    public ResponseEntity<String> applyPartnerProfile(
            @PathVariable String partnerId,
            @RequestBody Map<String, String> body) {
        String result = partnerProfileManager.applyProfile(partnerId, body.get("content"));
        return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body(result);
    }

    // ===================================================================
    // AI MAPPING GENERATOR
    // ===================================================================

    /** Smart mapping: uses trained map if available, falls back to sample-based */
    @PostMapping("/mapping/smart")
    public AiMappingGenerator.MappingResult smartMapping(@RequestBody Map<String, String> body) {
        return aiMappingGenerator.generateSmart(
                body.get("source"), body.get("target"),
                body.get("targetFormat"), body.get("partnerId"));
    }

    /** Generate mapping from source EDI + target JSON samples */
    @PostMapping("/mapping/generate")
    public AiMappingGenerator.MappingResult generateMapping(@RequestBody Map<String, String> body) {
        return aiMappingGenerator.generateFromSamples(body.get("source"), body.get("target"));
    }

    /** Generate mapping from source EDI + target schema description */
    @PostMapping("/mapping/schema")
    public AiMappingGenerator.MappingResult generateMappingFromSchema(@RequestBody Map<String, String> body) {
        return aiMappingGenerator.generateFromSchema(body.get("source"), body.get("schema"));
    }

    // ===================================================================
    // TRAINED MAP CONVERSION
    // ===================================================================

    /** Convert using a trained map from the AI Engine (highest accuracy path) */
    @PostMapping("/convert/trained")
    public ResponseEntity<?> convertWithTrainedMap(@RequestBody Map<String, String> body) {
        String content = body.get("content");
        String targetFormat = body.getOrDefault("targetFormat", "JSON");
        String partnerId = body.get("partnerId");

        var result = trainedMapConsumer.convertWithTrainedMap(content, targetFormat, partnerId);

        if (result.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of(
                    "error", "No trained map available for this conversion path",
                    "suggestion", "Use POST /api/v1/edi/training/samples on the AI Engine to add training samples, then POST /api/v1/edi/training/train to build a map"));
        }

        var converted = result.get();
        return ResponseEntity.ok(Map.of(
                "output", converted.getOutput(),
                "mapKey", converted.getMapKey(),
                "mapVersion", converted.getMapVersion(),
                "mapConfidence", converted.getMapConfidence(),
                "fieldsApplied", converted.getFieldsApplied(),
                "fieldsSkipped", converted.getFieldsSkipped(),
                "totalMappings", converted.getTotalMappings()));
    }

    /** Check if a trained map exists for a conversion path */
    @GetMapping("/convert/trained/check")
    public Map<String, Object> checkTrainedMap(@RequestParam String sourceFormat,
                                                @RequestParam(required = false) String sourceType,
                                                @RequestParam String targetFormat,
                                                @RequestParam(required = false) String partnerId) {
        boolean exists = trainedMapConsumer.hasTrainedMap(sourceFormat, sourceType, targetFormat, partnerId);
        var result = new LinkedHashMap<String, Object>();
        result.put("available", exists);
        result.put("sourceFormat", sourceFormat);
        result.put("targetFormat", targetFormat);
        if (exists) {
            trainedMapConsumer.getTrainedMap(sourceFormat, sourceType, targetFormat, partnerId)
                    .ifPresent(map -> {
                        result.put("mapKey", map.getMapKey());
                        result.put("version", map.getVersion());
                        result.put("confidence", map.getConfidence());
                        result.put("fieldMappings", map.getFieldMappings().size());
                    });
        }
        return result;
    }

    /**
     * Test conversion with custom field mappings (used by mapping correction flow).
     * Applies the provided mappings to the source content without needing a persisted trained map.
     */
    @PostMapping("/convert/test-mappings")
    public ResponseEntity<?> testCustomMappings(@RequestBody Map<String, Object> body) {
        String sourceContent = (String) body.get("sourceContent");
        if (sourceContent == null || sourceContent.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "sourceContent is required"));
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rawMappings = (List<Map<String, Object>>) body.get("fieldMappings");
        if (rawMappings == null || rawMappings.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "fieldMappings is required"));
        }

        // Convert raw maps to FieldMapping objects
        List<TrainedMapConsumer.FieldMapping> mappings = rawMappings.stream()
                .map(m -> TrainedMapConsumer.FieldMapping.builder()
                        .sourceField((String) m.get("sourceField"))
                        .targetField((String) m.get("targetField"))
                        .transform((String) m.getOrDefault("transform", "DIRECT"))
                        .transformParam((String) m.get("transformParam"))
                        .confidence(m.containsKey("confidence") ? ((Number) m.get("confidence")).intValue() : 100)
                        .strategy((String) m.getOrDefault("strategy", "PARTNER_CORRECTION"))
                        .reasoning((String) m.get("reasoning"))
                        .build())
                .toList();

        var result = trainedMapConsumer.applyCustomMappings(sourceContent, mappings);

        return ResponseEntity.ok(Map.of(
                "output", result.getOutput(),
                "fieldsApplied", result.getFieldsApplied(),
                "fieldsSkipped", result.getFieldsSkipped(),
                "totalMappings", result.getTotalMappings()));
    }

    /** Invalidate the trained map in-memory cache (after retraining) — disk files preserved */
    @PostMapping("/convert/trained/invalidate-cache")
    public Map<String, String> invalidateTrainedMapCache() {
        trainedMapConsumer.invalidateCache();
        return Map.of("status", "cache_invalidated");
    }

    /** List all persisted trained maps on disk */
    @GetMapping("/convert/trained/maps")
    public ResponseEntity<?> listTrainedMaps() {
        return ResponseEntity.ok(trainedMapConsumer.listPersistedMaps());
    }

    /** Get cache statistics (in-memory + persisted counts, hit/miss/fetch counters) */
    @GetMapping("/convert/trained/cache-stats")
    public ResponseEntity<?> getCacheStats() {
        return ResponseEntity.ok(trainedMapConsumer.getCacheStats());
    }

    /** Invalidate both in-memory cache AND persisted disk files */
    @PostMapping("/convert/trained/invalidate-all")
    public ResponseEntity<?> invalidateAll() {
        trainedMapConsumer.invalidateAll();
        return ResponseEntity.ok(Map.of("status", "fully_invalidated", "message", "In-memory cache and persisted disk files cleared"));
    }

    // ===================================================================
    // NATURAL LANGUAGE EDI CREATION
    // ===================================================================

    /** Describe what you need in English → get valid EDI */
    @PostMapping("/create")
    public NaturalLanguageEdiCreator.NlEdiResult createFromNaturalLanguage(@RequestBody Map<String, String> body) {
        return nlEdiCreator.create(body.get("text"));
    }

    // ===================================================================
    // DOCUMENT-TYPE MAP CONVERSION
    // ===================================================================

    /**
     * Convert using a specific map (document-type mapping).
     * This is the production endpoint — converts between specific document types
     * using standard, trained, or partner-specific maps.
     */
    @PostMapping("/convert/map")
    public ResponseEntity<Map<String, Object>> convertWithMap(@RequestBody Map<String, String> request) {
        String content = request.get("content");
        if (content == null || content.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "content is required"));
        }

        String sourceType = request.get("sourceType");    // e.g., "X12_850"
        String targetType = request.get("targetType");     // e.g., "PURCHASE_ORDER_INH"
        String partnerId = request.get("partnerId");       // optional

        // 1. Auto-detect source type if not provided
        if (sourceType == null || sourceType.isEmpty()) {
            sourceType = detectDocumentType(content);
        }

        if (targetType == null || targetType.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "targetType is required",
                    "detectedSourceType", sourceType,
                    "suggestion", "Check available maps at GET /api/v1/convert/maps"));
        }

        // 2. Resolve the map
        Optional<ConversionMapDefinition> map = mapResolver.resolve(sourceType, targetType, partnerId);
        if (map.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "No map found for " + sourceType + " -> " + targetType,
                    "detectedSourceType", sourceType,
                    "requestedTargetType", targetType,
                    "suggestion", "Check available maps at GET /api/v1/convert/maps"));
        }

        // 3. Parse source document
        EdiDocument doc = parser.parse(content);

        // 4. Apply conversion map
        Map<String, Object> converted = mapBasedConverter.convert(doc, map.get());

        var result = new LinkedHashMap<String, Object>();
        result.put("converted", converted);
        result.put("mapUsed", map.get().getMapId());
        result.put("mapVersion", map.get().getVersion());
        result.put("sourceType", sourceType);
        result.put("targetType", targetType);
        result.put("confidence", map.get().getConfidence());
        if (partnerId != null) result.put("partnerId", partnerId);

        return ResponseEntity.ok(result);
    }

    /** List all available conversion maps (standard + trained + partner). */
    @GetMapping("/maps")
    public List<MapSummary> listMaps() {
        return mapResolver.listAvailableMaps();
    }

    /** Get a specific map definition by ID. */
    @GetMapping("/maps/{mapId}")
    public ResponseEntity<ConversionMapDefinition> getMap(@PathVariable String mapId) {
        return mapResolver.getStandardMapById(mapId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Auto-detect the document type from content.
     * Combines format detection with transaction-set-level detection.
     * Returns types like "X12_850", "X12_837P", "EDIFACT_ORDERS", "SWIFT_MT103", "FIX_D".
     */
    @PostMapping("/detect/type")
    public Map<String, String> detectType(@RequestBody Map<String, String> body) {
        String content = body.get("content");
        String docType = detectDocumentType(content);
        String format = detector.detect(content);
        return Map.of("format", format, "documentType", docType);
    }

    /**
     * Detect document type: combines format detection with sub-type detection.
     * e.g., X12 + ST*850 -> "X12_850", EDIFACT + UNH ORDERS -> "EDIFACT_ORDERS"
     */
    private String detectDocumentType(String content) {
        if (content == null || content.isBlank()) return "UNKNOWN";

        String format = detector.detect(content);

        return switch (format) {
            case "X12" -> {
                // Check for HIPAA sub-types first
                String hipaaType = hipaaParser.detectDocumentType(content);
                if (hipaaType != null) yield hipaaType;
                // Generic X12: extract from ST segment
                String txnSet = extractX12TransactionSet(content);
                yield txnSet != null ? "X12_" + txnSet : "X12";
            }
            case "EDIFACT" -> {
                // Extract message type from UNH segment
                String msgType = extractEdifactMessageType(content);
                yield msgType != null ? "EDIFACT_" + msgType : "EDIFACT";
            }
            case "SWIFT_MT" -> {
                // Extract MT type from {2: block
                String mtType = extractSwiftMtType(content);
                yield mtType != null ? "SWIFT_" + mtType : "SWIFT_MT";
            }
            case "FIX" -> {
                // Extract message type from tag 35
                String fixType = extractFixMsgType(content);
                yield fixType != null ? "FIX_" + fixType : "FIX";
            }
            case "TRADACOMS" -> {
                // Extract from MHD segment
                String tradType = extractTradacomsMsgType(content);
                yield tradType != null ? "TRADACOMS_" + tradType : "TRADACOMS";
            }
            case "NACHA" -> "NACHA_ACH";
            case "BAI2" -> "BAI2_BALANCE";
            case "HL7" -> {
                // Extract from MSH-9
                String hl7Type = extractHl7MsgType(content);
                yield hl7Type != null ? "HL7_" + hl7Type : "HL7";
            }
            case "PEPPOL" -> {
                if (content.contains("<Invoice")) yield "PEPPOL_INVOICE";
                else if (content.contains("<CreditNote")) yield "PEPPOL_CREDITNOTE";
                else if (content.contains("<Order")) yield "PEPPOL_ORDER";
                else yield "PEPPOL";
            }
            case "ISO20022" -> {
                if (content.contains("pacs.008")) yield "ISO20022_PACS008";
                else if (content.contains("BkToCstmrStmt")) yield "ISO20022_CAMT053";
                else yield "ISO20022";
            }
            default -> format;
        };
    }

    private String extractX12TransactionSet(String content) {
        // Find ST segment: ST*XXX* where XXX is the transaction set code
        int stPos = content.indexOf("ST");
        while (stPos >= 0 && stPos + 2 < content.length()) {
            char sep = content.charAt(stPos + 2);
            if (!Character.isLetterOrDigit(sep)) {
                int nextSep = content.indexOf(sep, stPos + 3);
                if (nextSep > stPos + 3) {
                    String txnSet = content.substring(stPos + 3, nextSep).trim();
                    if (txnSet.matches("\\d{3}")) return txnSet;
                }
            }
            stPos = content.indexOf("ST", stPos + 1);
        }
        return null;
    }

    private String extractEdifactMessageType(String content) {
        // Find UNH segment: UNH+refnum+msgtype:version:...
        int unhPos = content.indexOf("UNH+");
        if (unhPos < 0) unhPos = content.indexOf("UNH:");
        if (unhPos < 0) return null;
        char elemSep = content.charAt(unhPos + 3);
        // Skip reference number to get to message identifier
        int secondElem = content.indexOf(elemSep, unhPos + 4);
        if (secondElem < 0) return null;
        int thirdElem = content.indexOf(elemSep, secondElem + 1);
        // Extract message type (first component before ':')
        String msgIdField = thirdElem > 0
                ? content.substring(secondElem + 1, thirdElem)
                : content.substring(secondElem + 1, Math.min(secondElem + 20, content.length()));
        int colonPos = msgIdField.indexOf(':');
        return colonPos > 0 ? msgIdField.substring(0, colonPos).trim() : msgIdField.trim();
    }

    private String extractSwiftMtType(String content) {
        // Extract from {2:I103... or {2:O103...
        int blockPos = content.indexOf("{2:");
        if (blockPos < 0) return null;
        String afterBlock = content.substring(blockPos + 3);
        // Skip I/O indicator
        String digits = afterBlock.replaceAll("[^0-9]", "");
        if (digits.length() >= 3) {
            return "MT" + digits.substring(0, 3);
        }
        return null;
    }

    private String extractFixMsgType(String content) {
        // Find 35=X tag
        String delim = content.contains("\001") ? "\001" : "\\|";
        for (String field : content.split(delim)) {
            if (field.startsWith("35=")) {
                return field.substring(3).trim();
            }
        }
        return null;
    }

    private String extractTradacomsMsgType(String content) {
        // Find MHD=ref+TYPE:version
        Matcher m = Pattern.compile("MHD=\\d+\\+([^:']+)").matcher(content);
        if (m.find()) return m.group(1).trim();
        return null;
    }

    private String extractHl7MsgType(String content) {
        // MSH|^~\\&|...|...|...|...|...|...|MSG_TYPE^TRIGGER|
        for (String line : content.split("\\r?\\n")) {
            if (line.startsWith("MSH|")) {
                String[] fields = line.split("\\|", -1);
                if (fields.length > 9) {
                    return fields[9].replace("^", "_").trim();
                }
            }
        }
        return null;
    }

    // ===================================================================
    // FORMATS + HEALTH
    // ===================================================================

    @GetMapping("/formats")
    public Map<String, Object> formats() {
        var result = new LinkedHashMap<String, Object>();
        result.put("inputFormats", List.of("X12", "EDIFACT", "TRADACOMS", "SWIFT_MT", "HL7",
                "NACHA", "BAI2", "ISO20022", "FIX", "PEPPOL", "AUTO"));
        result.put("outputFormats", List.of("JSON", "XML", "CSV", "YAML", "FLAT", "TIF",
                "X12", "EDIFACT", "HL7", "SWIFT_MT"));
        result.put("totalConversions", "11 input x 10 output = 110 paths (including cross-format EDI)");
        result.put("features", List.of(
                "auto-detect", "convert", "explain", "validate-with-fix", "templates", "generate",
                "canonical-model", "streaming", "self-healing", "semantic-diff",
                "compliance-scoring", "partner-profiles", "ai-mapping", "smart-mapping",
                "trained-map-conversion", "nl-create", "compare-suite"));
        result.put("newInV4", Map.of(
                "trainedMapConversion", "Convert using AI-trained maps — more samples = more accuracy",
                "smartMapping", "Auto-selects trained map or falls back to sample-based generation",
                "trainedMapCheck", "Check if a trained map exists before conversion",
                "aiTrainingPipeline", "5-strategy ML training: exact value, statistical, structural, semantic, transform",
                "compareSuite", "Batch comparison of conversion outputs between two systems with summary reports"
        ));
        result.put("newInV3", Map.of(
                "canonical", "Universal JSON schema per document type — one model for all formats",
                "streaming", "StAX-style parser for 100GB+ files — O(1) memory per segment",
                "selfHealing", "Auto-fixes 25+ common EDI errors (missing terminators, counts, padding)",
                "semanticDiff", "Field-level comparison — not character-level, BUSINESS-level",
                "compliance", "0-100 score with A-F grades against standards + partner specs",
                "partnerProfiles", "Per-partner configs — upload a sample, get a working profile in minutes",
                "aiMapping", "Upload source + target → auto-generate mapping rules (no LLM needed)",
                "nlCreate", "Describe in English → valid EDI (purchase orders, invoices, claims, payments)",
                "peppol", "PEPPOL/UBL support for European e-invoicing mandates (2026)"
        ));
        return result;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        var result = new LinkedHashMap<String, Object>();
        result.put("status", "UP");
        result.put("service", "edi-converter");
        result.put("version", "4.0");
        result.put("inputFormats", 11);
        result.put("outputFormats", 10);
        result.put("totalConversionPaths", 110);
        result.put("templates", templateLibrary.listTemplates().size());
        result.put("partnerProfiles", partnerProfileManager.getAllProfiles().size());
        result.put("features", List.of(
                "convert", "explain", "validate", "templates", "generate",
                "canonical", "streaming", "self-healing", "semantic-diff",
                "compliance", "partner-profiles", "ai-mapping", "smart-mapping",
                "trained-map-conversion", "nl-create", "peppol", "compare-suite"));
        return result;
    }
}
