package com.filetransfer.shared.flow;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for FlowFunctionRegistry — the central lookup for all
 * available flow processing functions.
 */
class FlowFunctionRegistryTest {

    private FlowFunctionRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new FlowFunctionRegistry();
    }

    @Test
    void register_newFunction_shouldBeRetrievableByType() {
        FlowFunction fn = stubFunction("COMPRESS_GZIP");
        registry.register(fn);

        Optional<FlowFunction> result = registry.get("COMPRESS_GZIP");

        assertTrue(result.isPresent());
        assertEquals("COMPRESS_GZIP", result.get().type());
    }

    @Test
    void register_duplicate_shouldOverwrite() {
        FlowFunction first = stubFunction("ENCRYPT_PGP");
        FlowFunction second = stubFunction("ENCRYPT_PGP");
        registry.register(first);
        registry.register(second);

        Optional<FlowFunction> result = registry.get("ENCRYPT_PGP");

        assertTrue(result.isPresent());
        assertSame(second, result.get(), "Second registration should replace the first");
    }

    @Test
    void get_unknownType_shouldReturnEmpty() {
        Optional<FlowFunction> result = registry.get("NONEXISTENT_FUNCTION");

        assertTrue(result.isEmpty());
    }

    @Test
    void getAll_afterMultipleRegistrations_shouldReturnAll() {
        registry.register(stubFunction("COMPRESS_GZIP"));
        registry.register(stubFunction("ENCRYPT_PGP"));
        registry.register(stubFunction("RENAME_FILE"));

        Map<String, FlowFunction> all = registry.getAll();

        assertEquals(3, all.size());
        assertTrue(all.containsKey("COMPRESS_GZIP"));
        assertTrue(all.containsKey("ENCRYPT_PGP"));
        assertTrue(all.containsKey("RENAME_FILE"));
    }

    @Test
    void get_caseInsensitive_shouldMatchUppercase() {
        registry.register(stubFunction("compress_gzip"));

        Optional<FlowFunction> result = registry.get("COMPRESS_GZIP");

        assertTrue(result.isPresent(), "Lookup should be case-insensitive");
        assertEquals("compress_gzip", result.get().type());
    }

    @Test
    void size_afterRegistrations_shouldReturnCorrectCount() {
        assertEquals(0, registry.size());

        registry.register(stubFunction("A"));
        registry.register(stubFunction("B"));
        registry.register(stubFunction("C"));

        assertEquals(3, registry.size());
    }

    // ── Helper ──────────────────────────────────────────────────────────────

    private static FlowFunction stubFunction(String type) {
        return new FlowFunction() {
            @Override public String executePhysical(FlowFunctionContext ctx) { return ctx.inputPath().toString(); }
            @Override public String type() { return type; }
            @Override public IOMode ioMode() { return IOMode.STREAMING; }
        };
    }
}
