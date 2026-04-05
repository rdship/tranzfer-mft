package com.filetransfer.edi.controller;

import com.filetransfer.edi.converter.*;
import com.filetransfer.edi.format.TemplateLibrary;
import com.filetransfer.edi.model.EdiDocument;
import com.filetransfer.edi.parser.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.*;

@RestController @RequestMapping("/api/v1/convert") @RequiredArgsConstructor
public class EdiConverterController {

    private final FormatDetector detector;
    private final UniversalEdiParser parser;
    private final UniversalConverter converter;
    private final EdiExplainer explainer;
    private final SmartValidator validator;
    private final TemplateLibrary templateLibrary;

    // === Core Conversion ===

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

    // === Human-Readable Explain ===

    @PostMapping("/explain")
    public EdiExplainer.ExplainedDocument explain(@RequestBody Map<String, String> body) {
        EdiDocument doc = parser.parse(body.get("content"));
        return explainer.explain(doc);
    }

    // === Smart Validation ===

    @PostMapping("/validate")
    public SmartValidator.ValidationReport validate(@RequestBody Map<String, String> body) {
        EdiDocument doc = parser.parse(body.get("content"));
        return validator.validate(doc);
    }

    // === Templates ===

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

    // === Formats + Health ===

    @GetMapping("/formats")
    public Map<String, Object> formats() {
        return Map.of(
                "inputFormats", List.of("X12", "EDIFACT", "TRADACOMS", "SWIFT_MT", "HL7", "NACHA", "BAI2", "ISO20022", "FIX", "AUTO"),
                "outputFormats", List.of("JSON", "XML", "CSV", "YAML", "FLAT", "TIF"),
                "totalConversions", "10 input × 6 output = 60 paths",
                "features", List.of("auto-detect", "explain", "validate-with-fix", "templates", "generate")
        );
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("status", "UP", "service", "edi-converter", "version", "2.0",
                "inputFormats", 10, "outputFormats", 6, "templates", templateLibrary.listTemplates().size(),
                "features", List.of("convert", "explain", "validate", "templates", "generate"));
    }
}
