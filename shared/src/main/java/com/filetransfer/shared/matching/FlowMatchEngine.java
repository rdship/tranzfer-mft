package com.filetransfer.shared.matching;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Stateless, thread-safe evaluator for composable match criteria.
 * Pure CPU — zero I/O, zero network calls.
 * Target: < 1ms per flow evaluation.
 */
@Component
@Slf4j
public class FlowMatchEngine {

    private final ConcurrentHashMap<String, Pattern> patternCache = new ConcurrentHashMap<>();

    static final int MAX_CRITERIA_DEPTH = 4;

    private static final Set<String> KNOWN_FIELDS = Set.of(
            "filename", "extension", "fileSize", "protocol", "direction",
            "partnerId", "partnerSlug", "accountUsername", "sourceAccountId",
            "sourcePath", "sourceIp", "ediStandard", "ediType",
            "timeOfDay", "dayOfWeek", "hour", "metadata"
    );

    private static final Map<String, Set<String>> FIELD_OPERATORS = Map.ofEntries(
            Map.entry("filename", Set.of("EQ", "GLOB", "REGEX", "CONTAINS", "STARTS_WITH", "ENDS_WITH")),
            Map.entry("extension", Set.of("EQ", "IN")),
            Map.entry("fileSize", Set.of("GT", "LT", "GTE", "LTE", "BETWEEN")),
            Map.entry("protocol", Set.of("EQ", "IN")),
            Map.entry("direction", Set.of("EQ")),
            Map.entry("partnerId", Set.of("EQ", "IN")),
            Map.entry("partnerSlug", Set.of("EQ", "IN")),
            Map.entry("accountUsername", Set.of("EQ", "IN", "REGEX")),
            Map.entry("sourceAccountId", Set.of("EQ")),
            Map.entry("sourcePath", Set.of("EQ", "CONTAINS", "STARTS_WITH", "REGEX")),
            Map.entry("sourceIp", Set.of("EQ", "IN", "CIDR")),
            Map.entry("ediStandard", Set.of("EQ", "IN")),
            Map.entry("ediType", Set.of("EQ", "IN")),
            Map.entry("dayOfWeek", Set.of("EQ", "IN")),
            Map.entry("hour", Set.of("EQ", "GTE", "LTE")),
            Map.entry("timeOfDay", Set.of("EQ", "GTE", "LTE", "BETWEEN")),
            Map.entry("metadata", Set.of("KEY_EQ", "CONTAINS", "EQ"))
    );

    /**
     * Evaluate whether the given criteria matches the context.
     * Null or empty criteria matches everything (backward compat).
     */
    public boolean matches(MatchCriteria criteria, MatchContext context) {
        if (criteria == null) return true;
        if (context == null) return false;
        return evaluate(criteria, context);
    }

    /**
     * Validate criteria structure. Returns empty list if valid.
     */
    public List<String> validate(MatchCriteria criteria) {
        List<String> errors = new ArrayList<>();
        if (criteria == null) return errors;
        validateNode(criteria, errors, 0);
        return errors;
    }

    // --- Core evaluation ---

    private boolean evaluate(MatchCriteria criteria, MatchContext context) {
        return switch (criteria) {
            case MatchGroup group -> evaluateGroup(group, context);
            case MatchCondition cond -> evaluateCondition(cond, context);
        };
    }

    private boolean evaluateGroup(MatchGroup group, MatchContext context) {
        if (group.conditions() == null || group.conditions().isEmpty()) {
            // Empty AND = matches all, empty OR = matches none
            return group.operator() == MatchGroup.GroupOperator.AND;
        }
        return switch (group.operator()) {
            case AND -> group.conditions().stream().allMatch(c -> evaluate(c, context));
            case OR -> group.conditions().stream().anyMatch(c -> evaluate(c, context));
            case NOT -> !evaluate(group.conditions().getFirst(), context);
        };
    }

    private boolean evaluateCondition(MatchCondition cond, MatchContext context) {
        Object actual = resolveField(cond.field(), context);
        if (actual == null) return false; // missing dimension never matches

        return switch (cond.op()) {
            case EQ -> stringEquals(actual, cond.value());
            case IN -> cond.values() != null && cond.values().stream()
                    .anyMatch(v -> stringEquals(actual, v));
            case REGEX -> matchesRegex(actual.toString(), toString(cond.value()));
            case GLOB -> matchesGlob(actual.toString(), toString(cond.value()));
            case CONTAINS -> actual.toString().contains(toString(cond.value()));
            case STARTS_WITH -> actual.toString().startsWith(toString(cond.value()));
            case ENDS_WITH -> actual.toString().endsWith(toString(cond.value()));
            case GT -> toLong(actual) > toLong(cond.value());
            case LT -> toLong(actual) < toLong(cond.value());
            case GTE -> toLong(actual) >= toLong(cond.value());
            case LTE -> toLong(actual) <= toLong(cond.value());
            case BETWEEN -> {
                if (cond.values() == null || cond.values().size() < 2) yield false;
                if (actual instanceof LocalTime time) {
                    LocalTime from = LocalTime.parse(cond.values().get(0).toString());
                    LocalTime to = LocalTime.parse(cond.values().get(1).toString());
                    yield !time.isBefore(from) && !time.isAfter(to);
                }
                long v = toLong(actual);
                yield v >= toLong(cond.values().get(0)) && v <= toLong(cond.values().get(1));
            }
            case CIDR -> cidrContains(toString(cond.value()), actual.toString());
            case KEY_EQ -> {
                if (cond.key() == null || cond.value() == null) yield false;
                Object resolved = resolveMetadataKey(context, cond.key());
                yield resolved != null && resolved.toString().equals(cond.value().toString());
            }
        };
    }

    // --- Field resolution ---

    private Object resolveField(String field, MatchContext ctx) {
        if (field == null) return null;
        // Handle metadata.* fields
        if (field.startsWith("metadata.")) {
            String key = field.substring("metadata.".length());
            return ctx.metadata() != null ? ctx.metadata().get(key) : null;
        }
        return switch (field) {
            case "filename" -> ctx.filename();
            case "extension" -> ctx.extension();
            case "fileSize" -> ctx.fileSize() >= 0 ? ctx.fileSize() : null;
            case "protocol" -> ctx.protocol() != null ? ctx.protocol().name() : null;
            case "direction" -> ctx.direction() != null ? ctx.direction().name() : null;
            case "partnerId" -> ctx.partnerId() != null ? ctx.partnerId().toString() : null;
            case "partnerSlug" -> ctx.partnerSlug();
            case "accountUsername" -> ctx.accountUsername();
            case "sourceAccountId" -> ctx.sourceAccountId() != null ? ctx.sourceAccountId().toString() : null;
            case "sourcePath" -> ctx.sourcePath();
            case "sourceIp" -> ctx.sourceIp();
            case "ediStandard" -> ctx.ediStandard();
            case "ediType" -> ctx.ediType();
            case "timeOfDay" -> ctx.timeOfDay();
            case "dayOfWeek" -> ctx.dayOfWeek() != null ? ctx.dayOfWeek().name() : null;
            case "hour" -> ctx.timeOfDay() != null ? (long) ctx.timeOfDay().getHour() : null;
            case "metadata" -> ctx.metadata();
            default -> null;
        };
    }

    private Object resolveMetadataKey(MatchContext ctx, String key) {
        if (ctx.metadata() == null) return null;
        return ctx.metadata().get(key);
    }

    // --- Pattern matching ---

    private boolean matchesRegex(String input, String pattern) {
        if (pattern == null) return false;
        try {
            Pattern compiled = patternCache.computeIfAbsent(pattern, Pattern::compile);
            return compiled.matcher(input).matches();
        } catch (PatternSyntaxException e) {
            log.warn("Invalid regex pattern: {}", pattern);
            return false;
        }
    }

    private boolean matchesGlob(String input, String globPattern) {
        if (globPattern == null) return false;
        String regexKey = "glob:" + globPattern;
        Pattern compiled = patternCache.computeIfAbsent(regexKey, k -> {
            String regex = globToRegex(globPattern);
            return Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
        });
        return compiled.matcher(input).matches();
    }

    static String globToRegex(String glob) {
        StringBuilder sb = new StringBuilder("^");
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            switch (c) {
                case '*' -> {
                    if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                        sb.append(".*");
                        i++; // skip second *
                    } else {
                        sb.append("[^/]*");
                    }
                }
                case '?' -> sb.append("[^/]");
                case '.' -> sb.append("\\.");
                case '(' -> sb.append("\\(");
                case ')' -> sb.append("\\)");
                case '[' -> sb.append("\\[");
                case ']' -> sb.append("\\]");
                case '{' -> sb.append("\\{");
                case '}' -> sb.append("\\}");
                case '+' -> sb.append("\\+");
                case '^' -> sb.append("\\^");
                case '$' -> sb.append("\\$");
                case '|' -> sb.append("\\|");
                case '\\' -> sb.append("\\\\");
                default -> sb.append(c);
            }
        }
        sb.append("$");
        return sb.toString();
    }

    // --- CIDR matching ---

    private boolean cidrContains(String cidr, String ip) {
        try {
            if (cidr == null || ip == null) return false;
            if (!cidr.contains("/")) {
                return ip.equals(cidr); // exact match if no prefix
            }
            String[] parts = cidr.split("/");
            byte[] network = InetAddress.getByName(parts[0]).getAddress();
            int prefixLen = Integer.parseInt(parts[1]);
            byte[] addr = InetAddress.getByName(ip).getAddress();

            if (network.length != addr.length) return false;

            int fullBytes = prefixLen / 8;
            int remainBits = prefixLen % 8;

            for (int i = 0; i < fullBytes; i++) {
                if (network[i] != addr[i]) return false;
            }
            if (remainBits > 0 && fullBytes < network.length) {
                int mask = (0xFF << (8 - remainBits)) & 0xFF;
                if ((network[fullBytes] & mask) != (addr[fullBytes] & mask)) return false;
            }
            return true;
        } catch (Exception e) {
            log.warn("CIDR match failed: cidr={} ip={}", cidr, ip);
            return false;
        }
    }

    // --- Helpers ---

    private static boolean stringEquals(Object a, Object b) {
        if (a == null || b == null) return false;
        return a.toString().equalsIgnoreCase(b.toString());
    }

    private static long toLong(Object obj) {
        if (obj == null) return 0;
        if (obj instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(obj.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String toString(Object obj) {
        return obj != null ? obj.toString() : "";
    }

    // dayOfWeek now uses full names (MONDAY, TUESDAY, etc.) to match UI field catalog

    // --- Validation ---

    private void validateNode(MatchCriteria node, List<String> errors, int depth) {
        if (depth > MAX_CRITERIA_DEPTH) {
            errors.add("Criteria nesting too deep (max " + MAX_CRITERIA_DEPTH + ")");
            return;
        }
        switch (node) {
            case MatchGroup group -> {
                if (group.operator() == null) {
                    errors.add("Group missing operator");
                }
                if (group.conditions() == null || group.conditions().isEmpty()) {
                    if (group.operator() == MatchGroup.GroupOperator.NOT) {
                        errors.add("NOT group must have exactly one condition");
                    } else {
                        errors.add("Group has no conditions");
                    }
                } else {
                    if (group.operator() == MatchGroup.GroupOperator.NOT && group.conditions().size() != 1) {
                        errors.add("NOT group must have exactly one condition, found " + group.conditions().size());
                    }
                    for (MatchCriteria child : group.conditions()) {
                        validateNode(child, errors, depth + 1);
                    }
                }
            }
            case MatchCondition cond -> {
                if (cond.field() == null || cond.field().isBlank()) {
                    errors.add("Condition missing field");
                } else if (!cond.field().startsWith("metadata.") && !KNOWN_FIELDS.contains(cond.field())) {
                    errors.add("Unknown field: " + cond.field());
                }
                if (cond.op() == null) {
                    errors.add("Condition missing operator");
                } else if (cond.field() != null && !cond.field().isBlank()) {
                    String lookupField = cond.field().startsWith("metadata.") ? "metadata" : cond.field();
                    Set<String> allowed = FIELD_OPERATORS.get(lookupField);
                    if (allowed != null && !allowed.contains(cond.op().name())) {
                        errors.add("Operator " + cond.op() + " not valid for field " + cond.field()
                                + "; allowed: " + allowed);
                    }
                }
                if (cond.op() == MatchCondition.ConditionOp.IN
                        || cond.op() == MatchCondition.ConditionOp.BETWEEN) {
                    if (cond.values() == null || cond.values().isEmpty()) {
                        errors.add("Condition " + cond.field() + " " + cond.op() + " requires values");
                    }
                }
                if (cond.op() == MatchCondition.ConditionOp.BETWEEN
                        && cond.values() != null && cond.values().size() != 2) {
                    errors.add("BETWEEN requires exactly 2 values");
                }
                if (cond.op() == MatchCondition.ConditionOp.KEY_EQ && cond.key() == null) {
                    errors.add("KEY_EQ requires a key");
                }
            }
        }
    }
}
