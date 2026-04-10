package com.filetransfer.edi.map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for MapResolver: standard map resolution, unknown-type handling,
 * partner override priority, map listing, and bidirectional reverse resolution.
 *
 * Constructs MapResolver directly and invokes buildStandardMapIndex via
 * reflection (loads from classpath). No Spring context required.
 */
class MapResolverTest {

    private MapResolver resolver;

    @BeforeEach
    void setUp() throws Exception {
        resolver = new MapResolver();
        // Invoke @PostConstruct manually — loads standard maps from classpath
        Method init = MapResolver.class.getDeclaredMethod("init");
        init.setAccessible(true);
        init.invoke(resolver);
    }

    // === Standard map resolution ===

    @Test
    void resolve_standardMap_found() {
        Optional<ConversionMapDefinition> result =
                resolver.resolve("X12_850", "PURCHASE_ORDER_INH", null);

        assertTrue(result.isPresent(), "Should find standard X12_850 -> PURCHASE_ORDER_INH map");
        assertEquals("X12_850--INHOUSE_PURCHASE_ORDER", result.get().getMapId());
        assertEquals("X12_850", result.get().getSourceType());
        assertEquals("PURCHASE_ORDER_INH", result.get().getTargetType());
        assertNotNull(result.get().getFieldMappings());
        assertFalse(result.get().getFieldMappings().isEmpty());
    }

    // === Unknown types ===

    @Test
    void resolve_unknownTypes_returnsEmpty() {
        Optional<ConversionMapDefinition> result =
                resolver.resolve("UNKNOWN", "UNKNOWN", null);

        assertTrue(result.isEmpty(), "Unknown source/target types should resolve to empty");
    }

    // === Partner override priority ===

    @Test
    void resolve_partnerOverride_takesPriority() throws Exception {
        // Inject a partner map into the partner cache so it takes priority over standard
        ConversionMapDefinition partnerMap = ConversionMapDefinition.builder()
                .mapId("PARTNER_CUSTOM_850")
                .name("Partner Custom PO Map")
                .sourceType("X12_850")
                .targetType("PURCHASE_ORDER_INH")
                .status("ACTIVE")
                .confidence(1.0)
                .fieldMappings(List.of(
                        ConversionMapDefinition.FieldMapping.builder()
                                .sourcePath("BEG.03").targetPath("poNumber")
                                .transform("COPY").confidence(1.0).build()))
                .build();

        // Access the partnerMapCache and inject a cached entry
        Field cacheField = MapResolver.class.getDeclaredField("partnerMapCache");
        cacheField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> cache = (Map<String, Object>) cacheField.get(resolver);

        // Build a CacheEntry using the private record constructor via reflection
        Class<?> cacheEntryClass = Class.forName("com.filetransfer.edi.map.MapResolver$CacheEntry");
        var constructor = cacheEntryClass.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        Object cacheEntry = constructor.newInstance(partnerMap, System.currentTimeMillis() + 300_000L);

        cache.put("PARTNER-123:X12_850:PURCHASE_ORDER_INH", cacheEntry);

        // Resolve with partnerId — should return the partner map, not standard
        Optional<ConversionMapDefinition> result =
                resolver.resolve("X12_850", "PURCHASE_ORDER_INH", "PARTNER-123");

        assertTrue(result.isPresent(), "Partner map should be found");
        assertEquals("PARTNER_CUSTOM_850", result.get().getMapId(),
                "Partner map should take priority over standard map");
    }

    // === List available maps ===

    @Test
    void listAvailableMaps_returnsAllStandard() {
        List<MapSummary> maps = resolver.listAvailableMaps();

        assertNotNull(maps);
        assertTrue(maps.size() >= 31,
                "Should have at least 31 standard maps, but found " + maps.size());

        // Verify all entries have required fields
        for (MapSummary summary : maps) {
            assertNotNull(summary.mapId(), "mapId should not be null");
            assertNotNull(summary.sourceType(), "sourceType should not be null");
            assertNotNull(summary.targetType(), "targetType should not be null");
            assertEquals("STANDARD", summary.category(), "Category should be STANDARD");
        }
    }

    // === Bidirectional map resolution ===

    @Test
    void resolve_bidirectionalMap_worksInReverse() {
        // X12_850--EDIFACT_ORDERS--BIDI has bidirectional=true
        // Forward: X12_850 -> EDIFACT_ORDERS should work
        Optional<ConversionMapDefinition> forward =
                resolver.resolve("X12_850", "EDIFACT_ORDERS", null);
        assertTrue(forward.isPresent(), "Forward direction of BIDI map should resolve");

        // Reverse: EDIFACT_ORDERS -> X12_850 should also work
        Optional<ConversionMapDefinition> reverse =
                resolver.resolve("EDIFACT_ORDERS", "X12_850", null);
        assertTrue(reverse.isPresent(), "Reverse direction of BIDI map should resolve");
        assertTrue(reverse.get().isBidirectional(), "Resolved map should be marked bidirectional");
    }

    // === getStandardMapById ===

    @Test
    void getStandardMapById_existingMap_returnsMap() {
        Optional<ConversionMapDefinition> result =
                resolver.getStandardMapById("X12_850--INHOUSE_PURCHASE_ORDER");

        assertTrue(result.isPresent());
        assertEquals("X12_850--INHOUSE_PURCHASE_ORDER", result.get().getMapId());
    }

    @Test
    void getStandardMapById_nonexistent_returnsEmpty() {
        Optional<ConversionMapDefinition> result =
                resolver.getStandardMapById("DOES_NOT_EXIST");

        assertTrue(result.isEmpty());
    }

    // === Standard map count ===

    @Test
    void getStandardMapCount_matchesListSize() {
        int count = resolver.getStandardMapCount();
        assertTrue(count >= 31, "Should have at least 31 standard maps indexed, but found " + count);
    }
}
