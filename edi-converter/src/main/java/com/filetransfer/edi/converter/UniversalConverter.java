package com.filetransfer.edi.converter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.csv.CsvMapper;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.filetransfer.edi.model.EdiDocument;
import com.filetransfer.edi.service.CanonicalMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Universal output converter — renders EdiDocument into any target format.
 * Supports: JSON, XML, CSV, YAML, FLAT (fixed-width), TIF (TranzFer Internal),
 *           X12, EDIFACT, HL7, SWIFT_MT (via Canonical model bridge)
 */
@Service @Slf4j @RequiredArgsConstructor
public class UniversalConverter {

    private final CanonicalMapper canonicalMapper;

    private final ObjectMapper jsonMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private final XmlMapper xmlMapper = (XmlMapper) new XmlMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private final YAMLMapper yamlMapper = new YAMLMapper();

    public String convert(EdiDocument doc, String targetFormat) {
        return switch (targetFormat.toUpperCase()) {
            case "JSON" -> toJson(doc);
            case "XML" -> toXml(doc);
            case "CSV" -> toCsv(doc);
            case "YAML" -> toYaml(doc);
            case "FLAT", "FIXED" -> toFlatFile(doc);
            case "TIF", "INTERNAL" -> toTif(doc);
            case "X12", "EDIFACT", "HL7", "SWIFT_MT", "SWIFT" -> toEdi(doc, targetFormat);
            default -> throw new IllegalArgumentException("Unsupported target format: " + targetFormat);
        };
    }

    /**
     * Cross-format EDI conversion via the Canonical model bridge:
     *   Source EDI → EdiDocument → CanonicalDocument → Target EDI
     */
    private String toEdi(EdiDocument doc, String targetFormat) {
        var canonical = canonicalMapper.toCanonical(doc);
        return canonicalMapper.fromCanonical(canonical, targetFormat);
    }

    private String toJson(EdiDocument doc) {
        try {
            Map<String, Object> output = buildOutputMap(doc);
            return jsonMapper.writeValueAsString(output);
        } catch (Exception e) { return "{\"error\":\"" + e.getMessage() + "\"}"; }
    }

    private String toXml(EdiDocument doc) {
        try {
            Map<String, Object> output = buildOutputMap(doc);
            return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" + xmlMapper.writeValueAsString(output);
        } catch (Exception e) { return "<error>" + e.getMessage() + "</error>"; }
    }

    private String toCsv(EdiDocument doc) {
        StringBuilder csv = new StringBuilder();
        csv.append("segment_id");
        // Find max elements
        int maxElems = doc.getSegments().stream()
                .mapToInt(s -> s.getElements() != null ? s.getElements().size() : 0).max().orElse(5);
        for (int i = 1; i <= maxElems; i++) csv.append(",element_").append(i);
        csv.append("\n");

        for (EdiDocument.Segment seg : doc.getSegments()) {
            csv.append(escapeCsv(seg.getId()));
            List<String> elems = seg.getElements() != null ? seg.getElements() : List.of();
            for (int i = 0; i < maxElems; i++) {
                csv.append(",");
                if (i < elems.size()) csv.append(escapeCsv(elems.get(i)));
            }
            csv.append("\n");
        }
        return csv.toString();
    }

    private String toYaml(EdiDocument doc) {
        try {
            return yamlMapper.writeValueAsString(buildOutputMap(doc));
        } catch (Exception e) { return "error: " + e.getMessage(); }
    }

    private String toFlatFile(EdiDocument doc) {
        StringBuilder flat = new StringBuilder();
        flat.append(String.format("%-10s%-20s%-20s%-15s%-15s%n",
                "FORMAT", "DOC_TYPE", "DOC_NAME", "SENDER", "RECEIVER"));
        flat.append(String.format("%-10s%-20s%-20s%-15s%-15s%n",
                doc.getSourceFormat(), doc.getDocumentType(), truncate(doc.getDocumentName(), 20),
                truncate(doc.getSenderId(), 15), truncate(doc.getReceiverId(), 15)));
        flat.append(String.format("%-10s%-80s%n", "---", "-".repeat(80)));

        for (EdiDocument.Segment seg : doc.getSegments()) {
            StringBuilder line = new StringBuilder(String.format("%-10s", seg.getId()));
            if (seg.getElements() != null) {
                for (String elem : seg.getElements()) line.append(String.format("%-20s", truncate(elem, 20)));
            }
            flat.append(line.toString().stripTrailing()).append("\n");
        }
        return flat.toString();
    }

    /**
     * TIF — TranzFer Internal Format
     * A self-describing, JSON-based canonical format optimized for our platform.
     * Every conversion through TranzFer produces this as the intermediate representation.
     */
    private String toTif(EdiDocument doc) {
        try {
            Map<String, Object> tif = new LinkedHashMap<>();
            tif.put("_tif_version", "1.0");
            tif.put("_tif_format", "TranzFer Internal Format");
            tif.put("_tif_generated_at", java.time.Instant.now().toString());

            tif.put("source", Map.of(
                    "format", doc.getSourceFormat() != null ? doc.getSourceFormat() : "",
                    "type", doc.getDocumentType() != null ? doc.getDocumentType() : "",
                    "name", doc.getDocumentName() != null ? doc.getDocumentName() : "",
                    "version", doc.getVersion() != null ? doc.getVersion() : ""
            ));

            tif.put("envelope", Map.of(
                    "sender", doc.getSenderId() != null ? doc.getSenderId() : "",
                    "receiver", doc.getReceiverId() != null ? doc.getReceiverId() : "",
                    "date", doc.getDocumentDate() != null ? doc.getDocumentDate() : "",
                    "controlNumber", doc.getControlNumber() != null ? doc.getControlNumber() : ""
            ));

            if (doc.getBusinessData() != null && !doc.getBusinessData().isEmpty()) {
                tif.put("businessData", doc.getBusinessData());
            }

            List<Map<String, Object>> records = new ArrayList<>();
            for (EdiDocument.Segment seg : doc.getSegments()) {
                Map<String, Object> rec = new LinkedHashMap<>();
                rec.put("id", seg.getId());
                if (seg.getElements() != null) rec.put("elements", seg.getElements());
                if (seg.getNamedFields() != null) rec.put("fields", seg.getNamedFields());
                records.add(rec);
            }
            tif.put("records", records);
            tif.put("recordCount", records.size());

            return jsonMapper.writeValueAsString(tif);
        } catch (Exception e) { return "{\"error\":\"" + e.getMessage() + "\"}"; }
    }

    private Map<String, Object> buildOutputMap(EdiDocument doc) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("sourceFormat", doc.getSourceFormat());
        map.put("documentType", doc.getDocumentType());
        map.put("documentName", doc.getDocumentName());
        if (doc.getSenderId() != null) map.put("senderId", doc.getSenderId());
        if (doc.getReceiverId() != null) map.put("receiverId", doc.getReceiverId());
        if (doc.getDocumentDate() != null) map.put("date", doc.getDocumentDate());
        if (doc.getBusinessData() != null) map.put("businessData", doc.getBusinessData());

        List<Map<String, Object>> segs = new ArrayList<>();
        for (EdiDocument.Segment s : doc.getSegments()) {
            Map<String, Object> sm = new LinkedHashMap<>();
            sm.put("id", s.getId());
            if (s.getElements() != null) sm.put("elements", s.getElements());
            if (s.getNamedFields() != null) sm.put("fields", s.getNamedFields());
            segs.add(sm);
        }
        map.put("segments", segs);
        return map;
    }

    private String escapeCsv(String s) {
        if (s == null) return "";
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) return "\"" + s.replace("\"", "\"\"") + "\"";
        return s;
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) : s;
    }
}
