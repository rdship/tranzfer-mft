package com.filetransfer.shared.client;

import com.filetransfer.shared.config.PlatformConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Client for the EDI Converter service (port 8095).
 * Provides EDI parsing, format detection, conversion, validation, and AI-powered mapping.
 *
 * <p>Error strategy: <b>fail-fast</b> for conversion operations;
 * <b>graceful degradation</b> for metadata queries.
 */
@Slf4j
@Component
public class EdiConverterClient extends BaseServiceClient {

    public EdiConverterClient(RestTemplate restTemplate,
                              PlatformConfig platformConfig,
                              ServiceClientProperties props) {
        super(restTemplate, platformConfig, props.getEdiConverter(), "edi-converter");
    }

    // ── Core operations ─────────────────────────────────────────────────

    /** Detect the EDI format of the given content. */
    @SuppressWarnings("unchecked")
    public Map<String, String> detect(String content) {
        try {
            return post("/api/v1/convert/detect", Map.of("content", content), Map.class);
        } catch (Exception e) {
            throw serviceError("detect", e);
        }
    }

    /** Parse EDI content into a structured document. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> parse(String content) {
        try {
            return post("/api/v1/convert/parse", Map.of("content", content), Map.class);
        } catch (Exception e) {
            throw serviceError("parse", e);
        }
    }

    /** Convert EDI content to the target format (JSON, XML, CSV, YAML, etc.). */
    public String convert(String content, String targetFormat) {
        try {
            return post("/api/v1/convert/convert",
                    Map.of("content", content, "target", targetFormat), String.class);
        } catch (Exception e) {
            throw serviceError("convert", e);
        }
    }

    /** Convert a file to the target format. */
    @SuppressWarnings("unchecked")
    public String convertFile(Path filePath, String targetFormat) {
        try {
            Map<String, String> params = targetFormat != null ? Map.of("target", targetFormat) : Map.of();
            Map<String, Object> result = postMultipart("/api/v1/convert/convert/file", filePath, params);
            return result != null ? result.toString() : null;
        } catch (Exception e) {
            throw serviceError("convertFile", e);
        }
    }

    /** Validate EDI content. Returns a validation report. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> validate(String content) {
        try {
            return post("/api/v1/convert/validate", Map.of("content", content), Map.class);
        } catch (Exception e) {
            throw serviceError("validate", e);
        }
    }

    /** Explain EDI content in human-readable form. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> explain(String content) {
        try {
            return post("/api/v1/convert/explain", Map.of("content", content), Map.class);
        } catch (Exception e) {
            throw serviceError("explain", e);
        }
    }

    // ── AI-powered operations ───────────────────────────────────────────

    /** Auto-heal broken EDI content. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> heal(String content, String format) {
        try {
            return post("/api/v1/convert/heal",
                    Map.of("content", content, "format", format), Map.class);
        } catch (Exception e) {
            throw serviceError("heal", e);
        }
    }

    /** Generate EDI from natural language description. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> createFromNaturalLanguage(String text) {
        try {
            return post("/api/v1/convert/create", Map.of("text", text), Map.class);
        } catch (Exception e) {
            throw serviceError("createFromNaturalLanguage", e);
        }
    }

    /** Generate field mapping between source and target EDI formats. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> generateMapping(String source, String target) {
        try {
            return post("/api/v1/convert/mapping/generate",
                    Map.of("source", source, "target", target), Map.class);
        } catch (Exception e) {
            throw serviceError("generateMapping", e);
        }
    }

    /** Semantic diff between two EDI documents. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> diff(String left, String right) {
        try {
            return post("/api/v1/convert/diff",
                    Map.of("left", left, "right", right), Map.class);
        } catch (Exception e) {
            throw serviceError("diff", e);
        }
    }

    /** Compliance scoring for EDI content. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> compliance(String content) {
        try {
            return post("/api/v1/convert/compliance", Map.of("content", content), Map.class);
        } catch (Exception e) {
            throw serviceError("compliance", e);
        }
    }

    // ── Partner profiles ────────────────────────────────────────────────

    /** List all EDI partner profiles. */
    public List<Map<String, Object>> listPartnerProfiles() {
        try {
            return get("/api/v1/convert/partners",
                    new ParameterizedTypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            log.warn("Failed to list EDI partner profiles: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    // ── Templates ───────────────────────────────────────────────────────

    /** List available EDI templates. */
    public List<Map<String, Object>> listTemplates() {
        try {
            return get("/api/v1/convert/templates",
                    new ParameterizedTypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            log.warn("Failed to list EDI templates: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /** Generate EDI from a template. */
    public String generateFromTemplate(String templateId, Map<String, String> values) {
        try {
            return post("/api/v1/convert/templates/" + templateId + "/generate",
                    values, String.class);
        } catch (Exception e) {
            throw serviceError("generateFromTemplate", e);
        }
    }

    /** Get supported formats and features. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> formats() {
        try {
            return get("/api/v1/convert/formats", Map.class);
        } catch (Exception e) {
            log.warn("EDI converter formats unavailable: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    @Override
    protected String healthPath() {
        return "/api/v1/convert/health";
    }
}
