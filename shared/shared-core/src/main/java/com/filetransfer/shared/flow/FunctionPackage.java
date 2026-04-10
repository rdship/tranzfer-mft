package com.filetransfer.shared.flow;

/**
 * Represents an importable/exportable function package.
 * Contains everything needed to deploy a function on the platform.
 */
public record FunctionPackage(
    FunctionDescriptor descriptor,
    String runtime,          // "BUILT_IN", "GRPC", "WASM", "CONTAINER"
    String endpoint,         // gRPC/HTTP endpoint (for GRPC runtime)
    byte[] wasmModule,       // compiled WASM bytes (for WASM runtime)
    String configSchema      // JSON Schema for step config validation
) {
    /** Create a gRPC function package. */
    public static FunctionPackage grpc(FunctionDescriptor desc, String endpoint, String configSchema) {
        return new FunctionPackage(desc, "GRPC", endpoint, null, configSchema);
    }

    /** Create a WASM function package. */
    public static FunctionPackage wasm(FunctionDescriptor desc, byte[] wasmModule, String configSchema) {
        return new FunctionPackage(desc, "WASM", null, wasmModule, configSchema);
    }
}
