package com.filetransfer.edi.controller;

import com.filetransfer.edi.converter.UniversalConverter;
import com.filetransfer.edi.model.EdiDocument;
import com.filetransfer.edi.parser.FormatDetector;
import com.filetransfer.edi.parser.UniversalEdiParser;
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

    /** Detect format from content */
    @PostMapping("/detect")
    public Map<String, String> detect(@RequestBody Map<String, String> body) {
        String format = detector.detect(body.get("content"));
        return Map.of("format", format);
    }

    /** Parse any EDI format into universal model */
    @PostMapping("/parse")
    public EdiDocument parse(@RequestBody Map<String, String> body) {
        return parser.parse(body.get("content"));
    }

    /** Convert: any EDI format → any output format */
    @PostMapping("/convert")
    public ResponseEntity<String> convert(@RequestBody Map<String, String> body) {
        String content = body.get("content");
        String target = body.getOrDefault("target", "JSON");

        EdiDocument doc = parser.parse(content);
        String output = converter.convert(doc, target);

        String contentType = switch (target.toUpperCase()) {
            case "JSON", "TIF", "INTERNAL" -> "application/json";
            case "XML" -> "application/xml";
            case "CSV" -> "text/csv";
            case "YAML" -> "application/yaml";
            default -> "text/plain";
        };

        return ResponseEntity.ok().contentType(MediaType.parseMediaType(contentType)).body(output);
    }

    /** Convert file upload */
    @PostMapping(value = "/convert/file", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<String> convertFile(
            @RequestPart("file") MultipartFile file,
            @RequestParam(defaultValue = "JSON") String target) throws Exception {
        String content = new String(file.getBytes());
        EdiDocument doc = parser.parse(content);
        return ResponseEntity.ok().body(converter.convert(doc, target));
    }

    /** List supported formats */
    @GetMapping("/formats")
    public Map<String, Object> formats() {
        return Map.of(
                "inputFormats", List.of(
                        Map.of("id", "X12", "name", "ANSI X12", "types", "837, 835, 850, 856, 810, 270, 271, 997, 834, 820"),
                        Map.of("id", "EDIFACT", "name", "UN/EDIFACT", "types", "ORDERS, INVOIC, DESADV, APERAK, CONTRL"),
                        Map.of("id", "TRADACOMS", "name", "TRADACOMS", "types", "UK retail standard"),
                        Map.of("id", "SWIFT_MT", "name", "SWIFT MT", "types", "MT103, MT202, MT940, MT950"),
                        Map.of("id", "HL7", "name", "HL7 v2", "types", "ADT, ORM, ORU, SIU"),
                        Map.of("id", "NACHA", "name", "NACHA/ACH", "types", "PPD, CCD, CTX"),
                        Map.of("id", "BAI2", "name", "BAI2", "types", "Balance reporting"),
                        Map.of("id", "ISO20022", "name", "ISO 20022", "types", "camt.053, camt.054, pacs.008"),
                        Map.of("id", "FIX", "name", "FIX Protocol", "types", "New Order, Execution Report"),
                        Map.of("id", "AUTO", "name", "Auto-detect", "types", "Any of the above")
                ),
                "outputFormats", List.of("JSON", "XML", "CSV", "YAML", "FLAT", "TIF"),
                "totalConversions", "10 input × 6 output = 60 conversion paths"
        );
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("status", "UP", "service", "edi-converter",
                "inputFormats", 10, "outputFormats", 6, "totalConversionPaths", 60);
    }
}
