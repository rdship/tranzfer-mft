package com.filetransfer.shared.flow;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry for all available flow functions. Functions are registered at boot
 * and can be looked up by type string.
 */
public class FlowFunctionRegistry {

    private final Map<String, FlowFunction> functions = new ConcurrentHashMap<>();

    /** Register a flow function. Replaces any existing function with the same type. */
    public void register(FlowFunction fn) {
        functions.put(fn.type().toUpperCase(), fn);
    }

    /** Look up a function by type string (case-insensitive). */
    public Optional<FlowFunction> get(String type) {
        return Optional.ofNullable(functions.get(type.toUpperCase()));
    }

    /** Return an unmodifiable view of all registered functions. */
    public Map<String, FlowFunction> getAll() {
        return Collections.unmodifiableMap(functions);
    }

    /** Number of registered functions. */
    public int size() { return functions.size(); }
}
