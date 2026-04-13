package com.filetransfer.shared.matching;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * In-memory registry of pre-compiled flow rules.
 *
 * <p><b>Performance:</b> Sub-microsecond matching via pre-compiled {@code Predicate} evaluation.
 * Phase 3: Rules indexed by (protocol, direction) for fast-path bucket elimination.</p>
 *
 * <p><b>Sync:</b> Bulk-loaded from DB at startup. Hot-reloaded via RabbitMQ events
 * when flows change in config-service. Periodic refresh every 5s (Phase 3.4).</p>
 *
 * <p><b>Observability:</b> Per-rule match/eval counters + global match timer exposed
 * via {@link #getMetrics()} for Prometheus scraping.</p>
 */
@Slf4j
@Component
public class FlowRuleRegistry {

    private final ConcurrentHashMap<UUID, CompiledFlowRule> rulesById = new ConcurrentHashMap<>();

    /** Priority-ordered snapshot — rebuilt on mutation, read without locking. */
    private volatile List<CompiledFlowRule> orderedRules = List.of();

    /** Phase 3.1: Indexed by "PROTOCOL:DIRECTION" for fast-path bucket selection. */
    private volatile Map<String, List<CompiledFlowRule>> indexedRules = Map.of();

    /** Set to true after initial bulk load completes. Prevents event-before-init race. */
    private volatile boolean initialized = false;

    private final ReadWriteLock orderLock = new ReentrantReadWriteLock();

    // ── Phase 3.3: Per-rule metrics ─────────────────────────────────────
    private final AtomicLong totalMatches = new AtomicLong();
    private final AtomicLong totalUnmatched = new AtomicLong();
    private final AtomicLong totalEvaluations = new AtomicLong();
    private final ConcurrentHashMap<UUID, AtomicLong> matchCounts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, AtomicLong> evalCounts = new ConcurrentHashMap<>();

    /**
     * Find the first matching rule for the given context.
     * Phase 3.1: Uses indexed buckets to skip irrelevant rules.
     * Pure CPU — zero I/O, zero DB calls, sub-microsecond.
     */
    public CompiledFlowRule findMatch(MatchContext context) {
        if (context == null) return null;
        String ctxDirection = context.direction() != null ? context.direction().name() : null;
        String ctxProtocol = context.protocol() != null ? context.protocol().name() : null;

        // Phase 3.1: try indexed bucket first (eliminates 90%+ of comparisons at 10K+ rules)
        CompiledFlowRule result = findInBucket(ctxProtocol, ctxDirection, context);
        if (result == null && ctxProtocol != null) {
            // Try wildcard buckets (rules with no protocol restriction)
            result = findInBucket(null, ctxDirection, context);
        }
        if (result == null && ctxDirection != null) {
            result = findInBucket(ctxProtocol, null, context);
        }
        if (result == null) {
            result = findInBucket(null, null, context);
        }

        // Phase 3.3: record metrics
        if (result != null) {
            totalMatches.incrementAndGet();
            matchCounts.computeIfAbsent(result.flowId(), k -> new AtomicLong()).incrementAndGet();
        } else {
            totalUnmatched.incrementAndGet();
        }
        return result;
    }

    private CompiledFlowRule findInBucket(String protocol, String direction, MatchContext context) {
        String key = bucketKey(protocol, direction);
        List<CompiledFlowRule> bucket = indexedRules.get(key);
        if (bucket == null) return null;
        for (CompiledFlowRule rule : bucket) {
            totalEvaluations.incrementAndGet();
            evalCounts.computeIfAbsent(rule.flowId(), k -> new AtomicLong()).incrementAndGet();
            if (rule.matches(context)) return rule;
        }
        return null;
    }

    /**
     * Find all matching rules — for audit/testing.
     */
    public List<CompiledFlowRule> findAllMatches(MatchContext context) {
        if (context == null) return List.of();
        String ctxDirection = context.direction() != null ? context.direction().name() : null;
        String ctxProtocol = context.protocol() != null ? context.protocol().name() : null;

        List<CompiledFlowRule> matches = new ArrayList<>();
        for (CompiledFlowRule rule : orderedRules) {
            if (!rule.directionApplies(ctxDirection)) continue;
            if (!rule.protocolApplies(ctxProtocol)) continue;
            if (rule.matches(context)) matches.add(rule);
        }
        return matches;
    }

    /**
     * Phase 3.2: Explain why each rule matched or didn't match the given context.
     * Returns all rules with per-criterion pass/fail details for debugging.
     */
    public List<MatchExplanation> explainMatch(MatchContext context) {
        if (context == null) return List.of();
        String ctxDirection = context.direction() != null ? context.direction().name() : null;
        String ctxProtocol = context.protocol() != null ? context.protocol().name() : null;

        List<MatchExplanation> explanations = new ArrayList<>();
        for (CompiledFlowRule rule : orderedRules) {
            boolean directionOk = rule.directionApplies(ctxDirection);
            boolean protocolOk = rule.protocolApplies(ctxProtocol);
            boolean criteriaOk = directionOk && protocolOk && rule.matches(context);

            explanations.add(new MatchExplanation(
                    rule.flowId(), rule.flowName(), rule.priority(),
                    criteriaOk, directionOk, protocolOk,
                    rule.direction(), rule.protocols()
            ));
        }
        return explanations;
    }

    /** Phase 3.2: Result of explaining a match against one rule. */
    public record MatchExplanation(
            UUID flowId, String flowName, int priority,
            boolean matched, boolean directionPassed, boolean protocolPassed,
            String ruleDirection, Set<String> ruleProtocols
    ) {}

    /** Register or update a single pre-compiled flow rule. */
    public void register(UUID flowId, String flowName, CompiledFlowRule compiled) {
        rulesById.put(flowId, compiled);
        rebuildOrderedList();
        log.info("Registered flow rule: {} (id={}, priority={})",
                flowName, flowId, compiled.priority());
    }

    /** Remove a flow rule from the registry. */
    public void unregister(UUID flowId) {
        CompiledFlowRule removed = rulesById.remove(flowId);
        if (removed != null) {
            rebuildOrderedList();
            log.info("Unregistered flow rule: {} (id={})", removed.flowName(), flowId);
        }
    }

    /** Bulk load — replaces all rules. Used at startup. */
    public void loadAll(Map<UUID, CompiledFlowRule> compiledRules) {
        rulesById.clear();
        rulesById.putAll(compiledRules);
        rebuildOrderedList();
        initialized = true;
        log.info("Flow rule registry loaded: {} flows compiled", compiledRules.size());
    }

    /** Whether the initial bulk load has completed. */
    public boolean isInitialized() { return initialized; }

    public int size() { return rulesById.size(); }

    public CompiledFlowRule get(UUID flowId) { return rulesById.get(flowId); }

    /** Phase 3.3: Get registry metrics for Prometheus/health endpoints. */
    public Map<String, Object> getMetrics() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ruleCount", rulesById.size());
        m.put("bucketCount", indexedRules.size());
        m.put("totalMatches", totalMatches.get());
        m.put("totalUnmatched", totalUnmatched.get());
        m.put("totalEvaluations", totalEvaluations.get());

        // Per-rule match counts (top 20 by match count)
        List<Map<String, Object>> topRules = matchCounts.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue().get(), a.getValue().get()))
                .limit(20)
                .map(e -> {
                    CompiledFlowRule rule = rulesById.get(e.getKey());
                    Map<String, Object> rm = new LinkedHashMap<>();
                    rm.put("flowId", e.getKey());
                    rm.put("flowName", rule != null ? rule.flowName() : "unknown");
                    rm.put("matches", e.getValue().get());
                    rm.put("evaluations", evalCounts.getOrDefault(e.getKey(), new AtomicLong()).get());
                    return rm;
                }).toList();
        m.put("topRules", topRules);
        return m;
    }

    private void rebuildOrderedList() {
        orderLock.writeLock().lock();
        try {
            orderedRules = rulesById.values().stream()
                    .sorted(Comparator.comparingInt(CompiledFlowRule::priority))
                    .toList();
            rebuildIndex();
        } finally {
            orderLock.writeLock().unlock();
        }
    }

    /** Phase 3.1: Build indexed buckets by (protocol, direction). */
    private void rebuildIndex() {
        Map<String, List<CompiledFlowRule>> idx = new HashMap<>();
        for (CompiledFlowRule rule : orderedRules) {
            // protocols() returns empty Set when rule matches any protocol
            Set<String> protos = rule.protocols().isEmpty()
                    ? Collections.singleton(null) : rule.protocols();
            String dir = rule.direction(); // null = any direction
            for (String proto : protos) {
                String key = bucketKey(proto, dir);
                idx.computeIfAbsent(key, k -> new ArrayList<>()).add(rule);
                // Also add to wildcard buckets so findMatch fallback works
                if (proto != null) {
                    idx.computeIfAbsent(bucketKey(null, dir), k -> new ArrayList<>()).add(rule);
                }
                if (dir != null) {
                    idx.computeIfAbsent(bucketKey(proto, null), k -> new ArrayList<>()).add(rule);
                }
                if (proto != null && dir != null) {
                    idx.computeIfAbsent(bucketKey(null, null), k -> new ArrayList<>()).add(rule);
                }
            }
        }
        indexedRules = Map.copyOf(idx);
    }

    private static String bucketKey(String protocol, String direction) {
        return (protocol != null ? protocol : "ANY") + ":" + (direction != null ? direction : "ANY");
    }
}
