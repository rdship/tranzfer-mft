package com.filetransfer.shared.matching;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * In-memory registry of pre-compiled flow rules.
 *
 * <p><b>Performance:</b> Sub-microsecond matching via pre-compiled {@code Predicate} evaluation.
 * Rules are indexed by direction+protocol for fast-path filtering.</p>
 *
 * <p><b>Sync:</b> Bulk-loaded from DB at startup. Hot-reloaded via RabbitMQ events
 * when flows change in config-service.</p>
 */
@Slf4j
@Component
public class FlowRuleRegistry {

    private final ConcurrentHashMap<UUID, CompiledFlowRule> rulesById = new ConcurrentHashMap<>();

    /** Priority-ordered snapshot — rebuilt on mutation, read without locking. */
    private volatile List<CompiledFlowRule> orderedRules = List.of();

    /** Set to true after initial bulk load completes. Prevents event-before-init race. */
    private volatile boolean initialized = false;

    private final ReadWriteLock orderLock = new ReentrantReadWriteLock();

    /**
     * Find the first matching rule for the given context.
     * Pure CPU — zero I/O, zero DB calls, sub-microsecond.
     */
    public CompiledFlowRule findMatch(MatchContext context) {
        if (context == null) return null;
        String ctxDirection = context.direction() != null ? context.direction().name() : null;
        String ctxProtocol = context.protocol() != null ? context.protocol().name() : null;

        for (CompiledFlowRule rule : orderedRules) {
            if (!rule.directionApplies(ctxDirection)) continue;
            if (!rule.protocolApplies(ctxProtocol)) continue;
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

    private void rebuildOrderedList() {
        orderLock.writeLock().lock();
        try {
            orderedRules = rulesById.values().stream()
                    .sorted(Comparator.comparingInt(CompiledFlowRule::priority))
                    .toList();
        } finally {
            orderLock.writeLock().unlock();
        }
    }
}
