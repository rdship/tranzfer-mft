package com.filetransfer.shared.matching;

import com.filetransfer.shared.entity.FileFlow;
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

    private final FlowRuleCompiler compiler;

    private final ConcurrentHashMap<UUID, CompiledFlowRule> rulesById = new ConcurrentHashMap<>();

    /** Priority-ordered snapshot — rebuilt on mutation, read without locking. */
    private volatile List<CompiledFlowRule> orderedRules = List.of();

    private final ReadWriteLock orderLock = new ReentrantReadWriteLock();

    public FlowRuleRegistry(FlowRuleCompiler compiler) {
        this.compiler = compiler;
    }

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

    /** Register or update a single flow rule. */
    public void register(FileFlow flow) {
        try {
            CompiledFlowRule compiled = compiler.compile(flow);
            rulesById.put(flow.getId(), compiled);
            rebuildOrderedList();
            log.info("Registered flow rule: {} (id={}, priority={})",
                    flow.getName(), flow.getId(), flow.getPriority());
        } catch (Exception e) {
            log.error("Failed to compile flow rule: {} (id={})", flow.getName(), flow.getId(), e);
        }
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
    public void loadAll(List<FileFlow> flows) {
        rulesById.clear();
        int compiled = 0;
        for (FileFlow flow : flows) {
            try {
                rulesById.put(flow.getId(), compiler.compile(flow));
                compiled++;
            } catch (Exception e) {
                log.error("Failed to compile flow: {} (id={})", flow.getName(), flow.getId(), e);
            }
        }
        rebuildOrderedList();
        log.info("Flow rule registry loaded: {}/{} flows compiled", compiled, flows.size());
    }

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
