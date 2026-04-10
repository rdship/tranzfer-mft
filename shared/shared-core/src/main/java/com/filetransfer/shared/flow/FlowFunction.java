package com.filetransfer.shared.flow;

/**
 * Universal plugin interface for file flow processing functions.
 * Implementations can be BUILT_IN (same JVM), WASM (sandboxed), gRPC (sidecar), or CONTAINER.
 */
public interface FlowFunction {

    /** Execute this function on a physical file (local disk I/O mode). Returns output file path. */
    String executePhysical(FlowFunctionContext ctx) throws Exception;

    /** Function type identifier (e.g. "ENCRYPT_PGP", "COMPRESS_GZIP"). Must be unique. */
    String type();

    /** I/O mode: STREAMING (chunk-by-chunk), MATERIALIZING (needs full file), METADATA_ONLY (no bytes) */
    IOMode ioMode();

    /** Human-readable description for the function catalog. */
    default String description() { return type(); }

    /** JSON Schema for step config validation. Null = no config needed. */
    default String configSchema() { return null; }
}
