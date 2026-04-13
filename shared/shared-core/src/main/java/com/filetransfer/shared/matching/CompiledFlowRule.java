package com.filetransfer.shared.matching;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Immutable, pre-compiled flow rule for zero-I/O matching.
 * <p>All patterns, CIDRs, and numeric thresholds are compiled at load time.
 * Evaluation is a pure {@code Predicate.test()} call — no deserialization, no parsing.</p>
 *
 * <p><b>Phase 1 optimization:</b> Now carries a flow definition snapshot (steps, destination info)
 * so the RoutingEngine can skip the FileFlow DB fetch after a match. The snapshot is refreshed
 * every 30s alongside the rule registry reload.</p>
 */
public final class CompiledFlowRule {

    private final UUID flowId;
    private final String flowName;
    private final int priority;
    private final String direction;
    private final Set<String> protocols;
    private final Predicate<MatchContext> matcher;

    // Phase 1: pre-loaded flow definition snapshot
    private final FlowSnapshot flowSnapshot;

    public CompiledFlowRule(UUID flowId, String flowName, int priority,
                            String direction, Set<String> protocols,
                            Predicate<MatchContext> matcher) {
        this(flowId, flowName, priority, direction, protocols, matcher, null);
    }

    public CompiledFlowRule(UUID flowId, String flowName, int priority,
                            String direction, Set<String> protocols,
                            Predicate<MatchContext> matcher,
                            FlowSnapshot flowSnapshot) {
        this.flowId = flowId;
        this.flowName = flowName;
        this.priority = priority;
        this.direction = direction;
        this.protocols = protocols != null ? Set.copyOf(protocols) : Set.of();
        this.matcher = matcher;
        this.flowSnapshot = flowSnapshot;
    }

    /**
     * Immutable snapshot of FileFlow fields needed for execution.
     * Carried in-memory — eliminates post-match DB fetch.
     */
    public record FlowSnapshot(
            UUID id,
            String name,
            String filenamePattern,
            UUID sourceAccountId,
            String sourcePath,
            UUID destinationAccountId,
            String destinationPath,
            UUID externalDestinationId,
            UUID partnerId,
            String direction,
            int priority,
            boolean active,
            List<Map<String, Object>> steps,
            Object matchCriteria  // raw MatchCriteria — avoids shared-platform dependency in shared-core
    ) {}

    /** Zero-cost match evaluation — pure CPU, pre-compiled predicates. */
    public boolean matches(MatchContext context) {
        return matcher.test(context);
    }

    /** Fast-path: skip evaluation entirely if direction doesn't match. */
    public boolean directionApplies(String contextDirection) {
        return direction == null || direction.equalsIgnoreCase(contextDirection);
    }

    /** Fast-path: skip evaluation if protocol not in criteria's protocol set. */
    public boolean protocolApplies(String contextProtocol) {
        return protocols.isEmpty() || protocols.contains(contextProtocol);
    }

    public UUID flowId() { return flowId; }
    public String flowName() { return flowName; }
    public int priority() { return priority; }
    public String direction() { return direction; }
    public Set<String> protocols() { return protocols; }
    public FlowSnapshot flowSnapshot() { return flowSnapshot; }
}
