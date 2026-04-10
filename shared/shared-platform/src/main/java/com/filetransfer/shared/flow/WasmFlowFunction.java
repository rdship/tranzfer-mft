package com.filetransfer.shared.flow;

import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.nio.file.*;
import java.util.Arrays;
import java.util.Map;

/**
 * FlowFunction adapter for WebAssembly (WASM) sandboxed plugins.
 * Partner-provided WASM modules run in an isolated memory sandbox —
 * they cannot access JVM heap, filesystem, or network unless explicitly granted.
 *
 * <p>This adapter reads the input file in chunks, passes each chunk through
 * the WASM transform function, and writes the output chunks to a new file.
 *
 * <p>Currently uses a pluggable {@link WasmRuntime} interface. When Chicory
 * (pure Java WASM runtime) is added to dependencies, a ChicoryWasmRuntime
 * implementation will be provided.
 */
@Slf4j
public class WasmFlowFunction implements FlowFunction {

    private final String functionType;
    private final IOMode mode;
    private final FunctionDescriptor descriptor;
    private final WasmRuntime runtime;

    public WasmFlowFunction(String functionType, FunctionDescriptor descriptor,
                             WasmRuntime runtime) {
        this.functionType = functionType;
        this.mode = IOMode.STREAMING; // WASM functions process chunk-by-chunk
        this.descriptor = descriptor;
        this.runtime = runtime;
    }

    @Override
    public String executePhysical(FlowFunctionContext ctx) throws Exception {
        Path inputPath = ctx.inputPath();
        Path outputPath = ctx.workDir().resolve(ctx.filename() + ".wasm-out");

        log.info("[WasmFunction:{}] Processing {} ({} bytes)",
                functionType, ctx.filename(), Files.size(inputPath));

        // Stream file through WASM sandbox in chunks
        try (InputStream in = new BufferedInputStream(Files.newInputStream(inputPath));
             OutputStream out = new BufferedOutputStream(Files.newOutputStream(outputPath))) {

            byte[] chunk = new byte[65536]; // 64 KB chunks
            int bytesRead;
            long totalIn = 0, totalOut = 0;

            while ((bytesRead = in.read(chunk)) != -1) {
                byte[] input = bytesRead < chunk.length ? Arrays.copyOf(chunk, bytesRead) : chunk;
                byte[] output = runtime.transform(functionType, input, ctx.config());
                out.write(output);
                totalIn += bytesRead;
                totalOut += output.length;
            }

            // Flush any final output from the WASM module
            byte[] finalOutput = runtime.flush(functionType);
            if (finalOutput != null && finalOutput.length > 0) {
                out.write(finalOutput);
                totalOut += finalOutput.length;
            }

            log.info("[WasmFunction:{}] Done: {} bytes in -> {} bytes out",
                    functionType, totalIn, totalOut);
        }

        return outputPath.toString();
    }

    @Override public String type() { return functionType; }
    @Override public IOMode ioMode() { return mode; }
    @Override public String description() { return descriptor.description(); }
    @Override public String configSchema() { return null; }

    /**
     * Pluggable WASM runtime interface. Implementations provide the actual
     * WebAssembly execution environment (Chicory, GraalWasm, etc.).
     */
    public interface WasmRuntime {
        /** Transform a chunk of data through the WASM function. */
        byte[] transform(String functionType, byte[] input, Map<String, String> config) throws Exception;

        /** Flush any buffered output from the WASM function (called after last chunk). */
        byte[] flush(String functionType) throws Exception;

        /** Load a WASM module from bytes. */
        void loadModule(String functionType, byte[] wasmBytes) throws Exception;

        /** Unload a WASM module. */
        void unloadModule(String functionType);

        /** Check if a module is loaded. */
        boolean isLoaded(String functionType);
    }
}
