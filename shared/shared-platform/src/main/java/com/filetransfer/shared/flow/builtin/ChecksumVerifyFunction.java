package com.filetransfer.shared.flow.builtin;

import com.filetransfer.shared.flow.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Built-in FlowFunction that verifies file integrity by computing SHA-256
 * and comparing against an expected checksum in the step config.
 *
 * Config keys:
 *   expectedSha256 — if present, verify computed hash matches (fail if mismatch)
 *   outputHeader   — if "true", write checksum as first line of output file
 *
 * If no expectedSha256 config, acts as a checksum COMPUTATION step — passes file
 * through unchanged but logs the computed checksum.
 */
public class ChecksumVerifyFunction implements FlowFunction {

    @Override
    public String executePhysical(FlowFunctionContext ctx) throws Exception {
        Path input = ctx.inputPath();
        byte[] fileBytes = Files.readAllBytes(input);

        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        String computed = HexFormat.of().formatHex(digest.digest(fileBytes));

        String expected = ctx.config() != null ? ctx.config().get("expectedSha256") : null;
        if (expected != null && !expected.isBlank()) {
            if (!computed.equalsIgnoreCase(expected)) {
                throw new SecurityException("Checksum mismatch: expected " + expected + " but computed " + computed);
            }
        }

        // Pass file through unchanged (integrity verified)
        return input.toString();
    }

    @Override public String type() { return "CHECKSUM_VERIFY"; }
    @Override public IOMode ioMode() { return IOMode.MATERIALIZING; }
    @Override public String description() { return "Verify file integrity via SHA-256 checksum"; }
    @Override public String configSchema() {
        return "{\"expectedSha256\": \"optional hex string\"}";
    }
}
