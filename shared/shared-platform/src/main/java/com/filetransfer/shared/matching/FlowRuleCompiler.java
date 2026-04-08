package com.filetransfer.shared.matching;

import com.filetransfer.shared.entity.FileFlow;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.time.LocalTime;
import java.util.*;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Compiles a FileFlow's MatchCriteria tree into a pre-compiled {@link CompiledFlowRule}.
 * <p>All regex patterns, glob patterns, CIDR ranges, and numeric thresholds
 * are compiled/parsed exactly once at load time — never at match time.</p>
 */
@Slf4j
@Component
public class FlowRuleCompiler {

    public CompiledFlowRule compile(FileFlow flow) {
        Predicate<MatchContext> matcher = flow.getMatchCriteria() != null
                ? compileCriteria(flow.getMatchCriteria())
                : ctx -> true; // null criteria = matches everything (backward compat)

        Set<String> protocols = extractProtocols(flow.getMatchCriteria());

        return new CompiledFlowRule(
                flow.getId(), flow.getName(), flow.getPriority(),
                flow.getDirection(), protocols, matcher);
    }

    // ── Criteria tree compilation ──────────────────────────────────────

    private Predicate<MatchContext> compileCriteria(MatchCriteria criteria) {
        return switch (criteria) {
            case MatchGroup group -> compileGroup(group);
            case MatchCondition cond -> compileCondition(cond);
        };
    }

    private Predicate<MatchContext> compileGroup(MatchGroup group) {
        if (group.conditions() == null || group.conditions().isEmpty()) {
            return ctx -> group.operator() == MatchGroup.GroupOperator.AND;
        }
        List<Predicate<MatchContext>> compiled = group.conditions().stream()
                .map(this::compileCriteria).toList();

        return switch (group.operator()) {
            case AND -> ctx -> {
                for (Predicate<MatchContext> p : compiled) if (!p.test(ctx)) return false;
                return true;
            };
            case OR -> ctx -> {
                for (Predicate<MatchContext> p : compiled) if (p.test(ctx)) return true;
                return false;
            };
            case NOT -> compiled.getFirst().negate();
        };
    }

    private Predicate<MatchContext> compileCondition(MatchCondition cond) {
        String field = cond.field();
        return switch (cond.op()) {
            case EQ          -> compileEq(field, cond.value());
            case IN           -> compileIn(field, cond.values());
            case REGEX        -> compilePattern(field, cond.value(), false);
            case GLOB         -> compilePattern(field, cond.value(), true);
            case CONTAINS     -> compileStringOp(field, cond.value(), String::contains);
            case STARTS_WITH  -> compileStringOp(field, cond.value(), String::startsWith);
            case ENDS_WITH    -> compileStringOp(field, cond.value(), String::endsWith);
            case GT           -> compileLongCmp(field, cond.value(), (a, b) -> a > b);
            case LT           -> compileLongCmp(field, cond.value(), (a, b) -> a < b);
            case GTE          -> compileLongCmp(field, cond.value(), (a, b) -> a >= b);
            case LTE          -> compileLongCmp(field, cond.value(), (a, b) -> a <= b);
            case BETWEEN      -> compileBetween(field, cond.values());
            case CIDR         -> compileCidr(field, cond.value());
            case KEY_EQ       -> compileKeyEq(cond.key(), cond.value());
        };
    }

    // ── Pre-compiled condition builders ────────────────────────────────

    private Predicate<MatchContext> compileEq(String field, Object value) {
        String expected = value != null ? value.toString() : "";
        return ctx -> {
            Object actual = resolveField(field, ctx);
            return actual != null && actual.toString().equalsIgnoreCase(expected);
        };
    }

    private Predicate<MatchContext> compileIn(String field, List<Object> values) {
        if (values == null || values.isEmpty()) return ctx -> false;
        Set<String> normalized = new HashSet<>();
        for (Object v : values) if (v != null) normalized.add(v.toString().toUpperCase());
        return ctx -> {
            Object actual = resolveField(field, ctx);
            return actual != null && normalized.contains(actual.toString().toUpperCase());
        };
    }

    private Predicate<MatchContext> compilePattern(String field, Object value, boolean glob) {
        if (value == null) return ctx -> false;
        String raw = value.toString();
        String regex = glob ? FlowMatchEngine.globToRegex(raw) : raw;
        Pattern pattern = glob
                ? Pattern.compile(regex, Pattern.CASE_INSENSITIVE)
                : Pattern.compile(regex);
        return ctx -> {
            Object actual = resolveField(field, ctx);
            return actual != null && pattern.matcher(actual.toString()).matches();
        };
    }

    @FunctionalInterface
    private interface StringMatcher { boolean test(String a, String b); }

    private Predicate<MatchContext> compileStringOp(String field, Object value, StringMatcher fn) {
        String expected = value != null ? value.toString() : "";
        return ctx -> {
            Object actual = resolveField(field, ctx);
            return actual != null && fn.test(actual.toString(), expected);
        };
    }

    @FunctionalInterface
    private interface LongCmp { boolean test(long a, long b); }

    private Predicate<MatchContext> compileLongCmp(String field, Object value, LongCmp cmp) {
        long expected = toLong(value);
        return ctx -> {
            Object actual = resolveField(field, ctx);
            return actual != null && cmp.test(toLong(actual), expected);
        };
    }

    private Predicate<MatchContext> compileBetween(String field, List<Object> values) {
        if (values == null || values.size() < 2) return ctx -> false;
        // Try time-based BETWEEN first
        try {
            LocalTime from = LocalTime.parse(values.get(0).toString());
            LocalTime to = LocalTime.parse(values.get(1).toString());
            return ctx -> {
                Object actual = resolveField(field, ctx);
                return actual instanceof LocalTime time
                        && !time.isBefore(from) && !time.isAfter(to);
            };
        } catch (Exception ignored) {}
        // Numeric BETWEEN
        long low = toLong(values.get(0));
        long high = toLong(values.get(1));
        return ctx -> {
            Object actual = resolveField(field, ctx);
            if (actual == null) return false;
            long v = toLong(actual);
            return v >= low && v <= high;
        };
    }

    private Predicate<MatchContext> compileCidr(String field, Object value) {
        if (value == null) return ctx -> false;
        String cidr = value.toString();
        if (!cidr.contains("/")) {
            return ctx -> {
                Object actual = resolveField(field, ctx);
                return actual != null && actual.toString().equals(cidr);
            };
        }
        try {
            String[] parts = cidr.split("/");
            byte[] network = InetAddress.getByName(parts[0]).getAddress();
            int prefixLen = Integer.parseInt(parts[1]);
            return ctx -> {
                Object actual = resolveField(field, ctx);
                if (actual == null) return false;
                try {
                    byte[] addr = InetAddress.getByName(actual.toString()).getAddress();
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
                } catch (Exception e) { return false; }
            };
        } catch (Exception e) {
            log.warn("Failed to pre-compile CIDR: {}", cidr);
            return ctx -> false;
        }
    }

    private Predicate<MatchContext> compileKeyEq(String key, Object value) {
        if (key == null || value == null) return ctx -> false;
        String expected = value.toString();
        return ctx -> {
            if (ctx.metadata() == null) return false;
            String actual = ctx.metadata().get(key);
            return actual != null && actual.equals(expected);
        };
    }

    // ── Field resolution (mirrors FlowMatchEngine.resolveField) ───────

    private static Object resolveField(String field, MatchContext ctx) {
        if (field == null) return null;
        if (field.startsWith("metadata.")) {
            String key = field.substring("metadata.".length());
            return ctx.metadata() != null ? ctx.metadata().get(key) : null;
        }
        return switch (field) {
            case "filename"        -> ctx.filename();
            case "extension"       -> ctx.extension();
            case "fileSize"        -> ctx.fileSize() >= 0 ? ctx.fileSize() : null;
            case "protocol"        -> ctx.protocol() != null ? ctx.protocol().name() : null;
            case "direction"       -> ctx.direction() != null ? ctx.direction().name() : null;
            case "partnerId"       -> ctx.partnerId() != null ? ctx.partnerId().toString() : null;
            case "partnerSlug"     -> ctx.partnerSlug();
            case "accountUsername" -> ctx.accountUsername();
            case "sourceAccountId" -> ctx.sourceAccountId() != null ? ctx.sourceAccountId().toString() : null;
            case "sourcePath"      -> ctx.sourcePath();
            case "sourceIp"        -> ctx.sourceIp();
            case "ediStandard"     -> ctx.ediStandard();
            case "ediType"         -> ctx.ediType();
            case "timeOfDay"       -> ctx.timeOfDay();
            case "dayOfWeek"       -> ctx.dayOfWeek() != null ? ctx.dayOfWeek().name() : null;
            case "hour"            -> ctx.timeOfDay() != null ? (long) ctx.timeOfDay().getHour() : null;
            case "metadata"        -> ctx.metadata();
            default -> null;
        };
    }

    // ── Protocol extraction for fast-path indexing ─────────────────────

    private Set<String> extractProtocols(MatchCriteria criteria) {
        if (criteria == null) return Set.of();
        Set<String> result = new HashSet<>();
        extractProtocolsRecursive(criteria, result);
        return result.isEmpty() ? Set.of() : Set.copyOf(result);
    }

    private void extractProtocolsRecursive(MatchCriteria criteria, Set<String> result) {
        switch (criteria) {
            case MatchCondition cond -> {
                if ("protocol".equals(cond.field())) {
                    if (cond.op() == MatchCondition.ConditionOp.EQ && cond.value() != null) {
                        result.add(cond.value().toString().toUpperCase());
                    } else if (cond.op() == MatchCondition.ConditionOp.IN && cond.values() != null) {
                        cond.values().forEach(v -> { if (v != null) result.add(v.toString().toUpperCase()); });
                    }
                }
            }
            case MatchGroup group -> {
                if (group.operator() == MatchGroup.GroupOperator.AND && group.conditions() != null) {
                    group.conditions().forEach(c -> extractProtocolsRecursive(c, result));
                }
            }
        }
    }

    private static long toLong(Object obj) {
        if (obj == null) return 0;
        if (obj instanceof Number n) return n.longValue();
        try { return Long.parseLong(obj.toString()); } catch (NumberFormatException e) { return 0; }
    }
}
