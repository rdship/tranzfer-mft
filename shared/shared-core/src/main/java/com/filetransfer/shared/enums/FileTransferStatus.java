package com.filetransfer.shared.enums;

public enum FileTransferStatus {
    PENDING,
    IN_OUTBOX,
    DOWNLOADED,
    MOVED_TO_SENT,
    FAILED,
    /**
     * Flow-based terminal success. Set by {@code FlowProcessingEngine} when a
     * {@code FlowExecution} reaches {@code FlowStatus.COMPLETED} — closes the
     * gap where mailbox/transform flows previously left the record in PENDING
     * forever (R100 tester report: status=PENDING, flowStatus=COMPLETED).
     * Distinct from MOVED_TO_SENT (which is the sender-archive transition on
     * legacy non-flow direct deliveries) and from IN_OUTBOX/DOWNLOADED (which
     * describe partner-pickup lifecycle for mailbox drops awaiting download).
     */
    COMPLETED
}
