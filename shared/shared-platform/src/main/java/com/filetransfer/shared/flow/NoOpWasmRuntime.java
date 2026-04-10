package com.filetransfer.shared.flow;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * No-op WASM runtime — passes data through unchanged.
 * Active when no real WASM runtime (Chicory, GraalWasm) is configured.
 * Useful for development and testing of the plugin pipeline.
 */
@Component
@ConditionalOnMissingBean(name = "chicoryWasmRuntime")
@Slf4j
public class NoOpWasmRuntime implements WasmFlowFunction.WasmRuntime {

    private final Set<String> loadedModules = ConcurrentHashMap.newKeySet();

    @Override
    public byte[] transform(String functionType, byte[] input, Map<String, String> config) {
        return input; // pass-through
    }

    @Override
    public byte[] flush(String functionType) {
        return new byte[0];
    }

    @Override
    public void loadModule(String functionType, byte[] wasmBytes) {
        loadedModules.add(functionType);
        log.info("[NoOpWasm] Module loaded (no-op): {} ({} bytes)", functionType, wasmBytes.length);
    }

    @Override
    public void unloadModule(String functionType) {
        loadedModules.remove(functionType);
    }

    @Override
    public boolean isLoaded(String functionType) {
        return loadedModules.contains(functionType);
    }
}
