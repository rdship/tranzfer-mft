package com.filetransfer.shared.flow;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Service for importing and exporting flow functions.
 * Handles registration of external functions (gRPC, WASM) into the registry
 * and packaging functions for export.
 */
@Service @RequiredArgsConstructor @Slf4j
public class FunctionImportExportService {

    private final FlowFunctionRegistry registry;
    private final WasmFlowFunction.WasmRuntime wasmRuntime;

    /**
     * Import a function package into the registry.
     * Creates the appropriate adapter (GrpcFlowFunction or WasmFlowFunction)
     * and registers it.
     */
    public void importFunction(FunctionPackage pkg) {
        String type = pkg.descriptor().name().toUpperCase().replace('-', '_');

        switch (pkg.runtime().toUpperCase()) {
            case "GRPC" -> {
                GrpcFlowFunction fn = new GrpcFlowFunction(
                        type, pkg.endpoint(), IOMode.MATERIALIZING, pkg.descriptor());
                registry.register(fn);
                log.info("Imported gRPC function: {} -> {}", type, pkg.endpoint());
            }
            case "WASM" -> {
                if (pkg.wasmModule() != null) {
                    try {
                        wasmRuntime.loadModule(type, pkg.wasmModule());
                    } catch (Exception e) {
                        throw new RuntimeException("Failed to load WASM module: " + e.getMessage(), e);
                    }
                }
                WasmFlowFunction fn = new WasmFlowFunction(type, pkg.descriptor(), wasmRuntime);
                registry.register(fn);
                log.info("Imported WASM function: {} ({} bytes)", type,
                        pkg.wasmModule() != null ? pkg.wasmModule().length : 0);
            }
            default -> throw new IllegalArgumentException("Unsupported runtime: " + pkg.runtime());
        }
    }

    /**
     * Export a function from the registry as a package.
     * Only exportable functions can be exported.
     */
    public FunctionPackage exportFunction(String type) {
        FlowFunction fn = registry.get(type)
                .orElseThrow(() -> new RuntimeException("Function not found: " + type));

        if (fn instanceof GrpcFlowFunction) {
            return FunctionPackage.grpc(
                    new FunctionDescriptor(type, "1.0.0", "TRANSFORM", "SYSTEM", "system", true, fn.description()),
                    "exported-endpoint-placeholder", fn.configSchema());
        } else if (fn instanceof WasmFlowFunction) {
            return FunctionPackage.wasm(
                    new FunctionDescriptor(type, "1.0.0", "TRANSFORM", "SYSTEM", "system", true, fn.description()),
                    null, fn.configSchema());
        } else {
            return new FunctionPackage(
                    new FunctionDescriptor(type, "1.0.0", "TRANSFORM", "SYSTEM", "system", false, fn.description()),
                    "BUILT_IN", null, null, fn.configSchema());
        }
    }

    /** List all importable/exportable functions with their runtime type. */
    public Map<String, FlowFunction> listFunctions() {
        return registry.getAll();
    }
}
