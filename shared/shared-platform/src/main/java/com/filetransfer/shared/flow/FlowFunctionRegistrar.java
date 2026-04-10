package com.filetransfer.shared.flow;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring configuration that creates the FlowFunctionRegistry bean and
 * registers all 16 built-in function stubs. Actual implementations will
 * delegate to FlowProcessingEngine until the full migration is complete.
 */
@Configuration
public class FlowFunctionRegistrar {

    @Bean
    public FlowFunctionRegistry flowFunctionRegistry() {
        FlowFunctionRegistry registry = new FlowFunctionRegistry();

        // Register built-in function TYPE stubs — actual implementations
        // will be wired when FlowProcessingEngine is refactored to use the registry.
        // For now, register descriptors so the catalog API works.
        for (String type : new String[]{
                "COMPRESS_GZIP", "DECOMPRESS_GZIP", "COMPRESS_ZIP", "DECOMPRESS_ZIP",
                "ENCRYPT_PGP", "DECRYPT_PGP", "ENCRYPT_AES", "DECRYPT_AES",
                "RENAME", "SCREEN", "EXECUTE_SCRIPT", "MAILBOX",
                "FILE_DELIVERY", "CONVERT_EDI", "ROUTE", "APPROVE"}) {
            final String t = type;
            registry.register(new FlowFunction() {
                @Override
                public String executePhysical(FlowFunctionContext ctx) throws Exception {
                    throw new UnsupportedOperationException(
                        "Built-in function " + t + " — use FlowProcessingEngine directly until migration completes");
                }

                @Override
                public String type() { return t; }

                @Override
                public IOMode ioMode() {
                    return switch (t) {
                        case "COMPRESS_GZIP", "DECOMPRESS_GZIP", "COMPRESS_ZIP", "DECOMPRESS_ZIP" -> IOMode.STREAMING;
                        case "ENCRYPT_PGP", "DECRYPT_PGP", "ENCRYPT_AES", "DECRYPT_AES" -> IOMode.MATERIALIZING;
                        case "SCREEN" -> IOMode.MATERIALIZING;
                        case "EXECUTE_SCRIPT" -> IOMode.MATERIALIZING;
                        case "FILE_DELIVERY" -> IOMode.MATERIALIZING;
                        case "CONVERT_EDI" -> IOMode.MATERIALIZING;
                        case "MAILBOX" -> IOMode.METADATA_ONLY;
                        case "RENAME", "ROUTE", "APPROVE" -> IOMode.METADATA_ONLY;
                        default -> IOMode.MATERIALIZING;
                    };
                }

                @Override
                public String description() {
                    return "Built-in: " + t.toLowerCase().replace('_', ' ');
                }
            });
        }
        return registry;
    }
}
