package com.filetransfer.shared.matching;

import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Immutable, pre-compiled flow rule for zero-I/O matching.
 * <p>All patterns, CIDRs, and numeric thresholds are compiled at load time.
 * Evaluation is a pure {@code Predicate.test()} call — no deserialization, no parsing.</p>
 */
public final class CompiledFlowRule {

    private final UUID flowId;
    private final String flowName;
    private final int priority;
    private final String direction;
    private final Set<String> protocols;
    private final Predicate<MatchContext> matcher;

    public CompiledFlowRule(UUID flowId, String flowName, int priority,
                            String direction, Set<String> protocols,
                            Predicate<MatchContext> matcher) {
        this.flowId = flowId;
        this.flowName = flowName;
        this.priority = priority;
        this.direction = direction;
        this.protocols = protocols != null ? Set.copyOf(protocols) : Set.of();
        this.matcher = matcher;
    }

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
}
