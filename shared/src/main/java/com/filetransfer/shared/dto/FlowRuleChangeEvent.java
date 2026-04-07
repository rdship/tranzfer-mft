package com.filetransfer.shared.dto;

import java.io.Serializable;
import java.util.UUID;

/**
 * Event published when a file flow is created, updated, or deleted.
 * Consumed by all services with a FlowRuleRegistry to hot-reload compiled rules.
 */
public record FlowRuleChangeEvent(
        UUID flowId,
        ChangeType changeType
) implements Serializable {

    public enum ChangeType { CREATED, UPDATED, DELETED }
}
