package com.filetransfer.shared.flow;

/**
 * Describes how a FlowFunction interacts with file bytes during processing.
 */
public enum IOMode {

    /** Can process chunk-by-chunk (gzip, rename). */
    STREAMING,

    /** Needs full file bytes (encryption via REST, screening). */
    MATERIALIZING,

    /** Doesn't touch file bytes (rename, route, approve). */
    METADATA_ONLY
}
