package com.filetransfer.shared.flow;

import java.nio.file.Path;
import java.util.Map;

/**
 * Context passed to FlowFunction.executePhysical().
 * Contains all information needed to process a file step.
 */
public record FlowFunctionContext(
    Path inputPath,
    Path workDir,
    Map<String, String> config,
    String trackId,
    String filename
) {}
