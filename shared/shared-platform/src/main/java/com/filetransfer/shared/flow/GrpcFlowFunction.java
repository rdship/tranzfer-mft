package com.filetransfer.shared.flow;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.client.RestTemplate;

import java.nio.file.*;
import java.util.Map;

/**
 * FlowFunction adapter for external gRPC/HTTP streaming services.
 * Reads the input file, POSTs it as a streaming request to the external
 * function endpoint, and writes the response to an output file.
 *
 * <p>This is a bridge implementation that uses HTTP streaming.
 * When true gRPC proto stubs are generated, this will be replaced
 * with bidirectional gRPC streaming.
 */
@Slf4j
public class GrpcFlowFunction implements FlowFunction {

    private final String functionType;
    private final String endpoint;     // e.g. "http://partner-function:50051/transform"
    private final IOMode mode;
    private final FunctionDescriptor descriptor;
    private final RestTemplate restTemplate;

    public GrpcFlowFunction(String functionType, String endpoint, IOMode mode,
                             FunctionDescriptor descriptor) {
        this.functionType = functionType;
        this.endpoint = endpoint;
        this.mode = mode;
        this.descriptor = descriptor;
        this.restTemplate = new RestTemplate();
    }

    @Override
    public String executePhysical(FlowFunctionContext ctx) throws Exception {
        Path inputPath = ctx.inputPath();
        Path outputPath = ctx.workDir().resolve(ctx.filename() + ".grpc-out");

        log.info("[GrpcFunction:{}] Calling {} with {} bytes",
                functionType, endpoint, Files.size(inputPath));

        // Read input file and POST to external function endpoint
        byte[] inputBytes = Files.readAllBytes(inputPath);

        // Build request with config as query params
        String url = endpoint;
        if (ctx.config() != null && !ctx.config().isEmpty()) {
            StringBuilder sb = new StringBuilder(endpoint);
            sb.append("?");
            for (Map.Entry<String, String> e : ctx.config().entrySet()) {
                sb.append(e.getKey()).append("=").append(e.getValue()).append("&");
            }
            url = sb.toString();
        }

        byte[] responseBytes = restTemplate.postForObject(url, inputBytes, byte[].class);

        if (responseBytes == null || responseBytes.length == 0) {
            throw new RuntimeException("gRPC function " + functionType + " returned empty response");
        }

        Files.write(outputPath, responseBytes);
        log.info("[GrpcFunction:{}] Output: {} bytes -> {}",
                functionType, responseBytes.length, outputPath.getFileName());

        return outputPath.toString();
    }

    @Override public String type() { return functionType; }
    @Override public IOMode ioMode() { return mode; }
    @Override public String description() { return descriptor.description(); }
    @Override public String configSchema() { return null; }
}
