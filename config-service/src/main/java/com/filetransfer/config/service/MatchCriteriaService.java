package com.filetransfer.config.service;

import com.filetransfer.shared.matching.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Provides match criteria validation, field catalog, test-match evaluation,
 * and human-readable summarization for the admin API.
 */
@Service
@RequiredArgsConstructor
public class MatchCriteriaService {

    private final FlowMatchEngine matchEngine;

    // ---- Field Catalog ----

    public List<FieldInfo> getMatchFields() {
        return List.of(
            field("filename", "Filename", "string", List.of("EQ", "GLOB", "REGEX", "CONTAINS", "STARTS_WITH", "ENDS_WITH"), null),
            field("extension", "File Extension", "string", List.of("EQ", "IN"), null),
            field("fileSize", "File Size (bytes)", "number", List.of("GT", "LT", "GTE", "LTE", "BETWEEN"), null),
            field("protocol", "Protocol", "enum", List.of("EQ", "IN"), List.of("SFTP", "FTP", "AS2", "AS4", "HTTPS", "FTPS")),
            field("direction", "Direction", "enum", List.of("EQ"), List.of("INBOUND", "OUTBOUND")),
            field("partnerId", "Partner ID", "uuid", List.of("EQ", "IN"), null),
            field("partnerSlug", "Partner Slug", "string", List.of("EQ", "IN"), null),
            field("accountUsername", "Account Username", "string", List.of("EQ", "IN", "REGEX"), null),
            field("sourceAccountId", "Source Account ID", "uuid", List.of("EQ"), null),
            field("sourcePath", "Source Path", "string", List.of("EQ", "CONTAINS", "STARTS_WITH", "REGEX"), null),
            field("sourceIp", "Source IP", "string", List.of("EQ", "IN", "CIDR"), null),
            field("ediStandard", "EDI Standard", "enum", List.of("EQ", "IN"), List.of("X12", "EDIFACT")),
            field("ediType", "EDI Type", "string", List.of("EQ", "IN"), List.of("850", "855", "856", "810", "997", "INVOIC", "ORDERS", "DESADV")),
            field("dayOfWeek", "Day of Week", "enum", List.of("EQ", "IN"), List.of("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY")),
            field("hour", "Hour (0-23)", "number", List.of("EQ", "GTE", "LTE"), null),
            field("metadata", "Metadata Key", "map", List.of("KEY_EQ", "CONTAINS", "EQ"), null)
        );
    }

    // ---- Validation ----

    public ValidationResult validate(MatchCriteria criteria) {
        List<String> errors = matchEngine.validate(criteria);
        String summary = errors.isEmpty() ? summarize(criteria) : null;
        return new ValidationResult(errors.isEmpty(), errors, summary);
    }

    // ---- Test Match ----

    public TestMatchResult testMatch(MatchCriteria criteria, Map<String, Object> fileContext) {
        MatchContext ctx = buildContextFromMap(fileContext);
        boolean matched = matchEngine.matches(criteria, ctx);
        return new TestMatchResult(matched, summarize(criteria));
    }

    // ---- Summarization ----

    public String summarize(MatchCriteria criteria) {
        if (criteria == null) return "Matches all files";
        return summarizeNode(criteria);
    }

    private String summarizeNode(MatchCriteria node) {
        if (node instanceof MatchCondition c) {
            String val = c.values() != null && !c.values().isEmpty()
                    ? c.values().stream().map(Object::toString).collect(Collectors.joining(", "))
                    : c.value() != null ? c.value().toString() : "?";
            String keyPrefix = c.key() != null ? c.key() + "=" : "";
            return c.field() + " " + c.op().name() + " " + keyPrefix + val;
        } else if (node instanceof MatchGroup g) {
            if (g.operator() == MatchGroup.GroupOperator.NOT && !g.conditions().isEmpty()) {
                return "NOT(" + summarizeNode(g.conditions().getFirst()) + ")";
            }
            String joiner = g.operator() == MatchGroup.GroupOperator.AND ? " AND " : " OR ";
            return g.conditions().stream()
                    .map(this::summarizeNode)
                    .collect(Collectors.joining(joiner, "(", ")"));
        }
        return "?";
    }

    // ---- Context builder from test-match request ----

    private MatchContext buildContextFromMap(Map<String, Object> map) {
        MatchContextBuilder b = MatchContext.builder();

        if (map.containsKey("filename")) {
            String filename = str(map, "filename");
            // Derive extension, sourcePath, etc. from filename
            b.withFilename(filename);
            if (filename.contains(".")) {
                b.withExtension(filename.substring(filename.lastIndexOf('.') + 1).toLowerCase());
            }
        }
        if (map.containsKey("extension")) b.withExtension(str(map, "extension"));
        if (map.containsKey("fileSize")) b.withFileSize(longVal(map, "fileSize"));
        if (map.containsKey("protocol")) b.withProtocol(str(map, "protocol"));
        if (map.containsKey("direction")) {
            b.withDirection(MatchContext.Direction.valueOf(str(map, "direction")));
        }
        if (map.containsKey("partnerId")) b.withPartnerId(UUID.fromString(str(map, "partnerId")));
        if (map.containsKey("partnerSlug")) b.withPartnerSlug(str(map, "partnerSlug"));
        if (map.containsKey("accountUsername")) b.withAccountUsername(str(map, "accountUsername"));
        if (map.containsKey("sourceAccountId")) b.withSourceAccountId(UUID.fromString(str(map, "sourceAccountId")));
        if (map.containsKey("sourcePath")) b.withSourcePath(str(map, "sourcePath"));
        if (map.containsKey("sourceIp")) b.withSourceIp(str(map, "sourceIp"));
        if (map.containsKey("ediStandard")) b.withEdiStandard(str(map, "ediStandard"));
        if (map.containsKey("ediType")) b.withEdiType(str(map, "ediType"));
        if (map.containsKey("dayOfWeek")) b.withDayOfWeek(java.time.DayOfWeek.valueOf(str(map, "dayOfWeek")));
        if (map.containsKey("hour")) b.withHour(intVal(map, "hour"));
        if (map.containsKey("timeOfDay")) b.withTimeOfDay(java.time.LocalTime.parse(str(map, "timeOfDay")));
        if (map.containsKey("metadata")) {
            @SuppressWarnings("unchecked")
            Map<String, String> meta = (Map<String, String>) map.get("metadata");
            b.withMetadata(meta);
        }
        return b.build();
    }

    private static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v != null ? v.toString() : null;
    }

    private static long longVal(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v instanceof Number n) return n.longValue();
        return Long.parseLong(v.toString());
    }

    private static int intVal(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v instanceof Number n) return n.intValue();
        return Integer.parseInt(v.toString());
    }

    private static FieldInfo field(String name, String label, String type,
                                    List<String> operators, List<String> valueHints) {
        return new FieldInfo(name, label, type, operators, valueHints);
    }

    // ---- DTOs ----

    public record FieldInfo(String name, String label, String type,
                            List<String> operators, List<String> valueHints) {}

    public record ValidationResult(boolean valid, List<String> errors, String summary) {}

    public record TestMatchResult(boolean matched, String summary) {}
}
