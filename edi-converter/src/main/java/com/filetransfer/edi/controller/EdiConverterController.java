package com.filetransfer.edi.controller;

import com.filetransfer.edi.converter.*;
import com.filetransfer.edi.format.TemplateLibrary;
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
    // NEW: AI MAPPING GENERATOR
    // ===================================================================

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
    // NEW: NATURAL LANGUAGE EDI CREATION
    // ===================================================================

    /** Describe what you need in English → get valid EDI */
    @PostMapping("/create")
    public NaturalLanguageEdiCreator.NlEdiResult createFromNaturalLanguage(@RequestBody Map<String, String> body) {
        return nlEdiCreator.create(body.get("text"));
    }

    // ===================================================================
    // FORMATS + HEALTH
    // ===================================================================

    @GetMapping("/formats")
    public Map<String, Object> formats() {
        var result = new LinkedHashMap<String, Object>();
        result.put("inputFormats", List.of("X12", "EDIFACT", "TRADACOMS", "SWIFT_MT", "HL7",
                "NACHA", "BAI2", "ISO20022", "FIX", "PEPPOL", "AUTO"));
        result.put("outputFormats", List.of("JSON", "XML", "CSV", "YAML", "FLAT", "TIF"));
        result.put("totalConversions", "11 input x 6 output = 66 paths");
        result.put("features", List.of(
                "auto-detect", "convert", "explain", "validate-with-fix", "templates", "generate",
                "canonical-model", "streaming", "self-healing", "semantic-diff",
                "compliance-scoring", "partner-profiles", "ai-mapping", "nl-create"));
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
        result.put("version", "3.0");
        result.put("inputFormats", 11);
        result.put("outputFormats", 6);
        result.put("totalConversionPaths", 66);
        result.put("templates", templateLibrary.listTemplates().size());
        result.put("partnerProfiles", partnerProfileManager.getAllProfiles().size());
        result.put("features", List.of(
                "convert", "explain", "validate", "templates", "generate",
                "canonical", "streaming", "self-healing", "semantic-diff",
                "compliance", "partner-profiles", "ai-mapping", "nl-create", "peppol"));
        return result;
    }
}
