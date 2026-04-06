package com.filetransfer.dmz.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EgressFilterTest {

    private EgressFilter filter;

    @BeforeEach
    void setUp() {
        filter = new EgressFilter(EgressFilter.EgressConfig.defaults());
    }

    @Nested
    class BlockedPorts {

        @Test
        void blockedPort_rejectsSmtp() {
            EgressFilter.EgressCheckResult result = filter.checkDestination("93.184.216.34", 25);
            assertFalse(result.allowed());
            assertTrue(result.reason().contains("blocklist"));
        }

        @Test
        void blockedPort_rejectsDns() {
            EgressFilter.EgressCheckResult result = filter.checkDestination("93.184.216.34", 53);
            assertFalse(result.allowed());
            assertTrue(result.reason().contains("blocklist"));
        }
    }

    @Nested
    class LoopbackAndSpecialAddresses {

        @Test
        void loopback_blocked() {
            EgressFilter.EgressCheckResult result = filter.checkDestination("127.0.0.1", 8080);
            assertFalse(result.allowed());
            assertTrue(result.reason().toLowerCase().contains("loopback"));
        }

        @Test
        void ipv6Loopback_blocked() {
            EgressFilter.EgressCheckResult result = filter.checkDestination("::1", 8080);
            assertFalse(result.allowed());
            assertTrue(result.reason().toLowerCase().contains("loopback"));
        }

        @Test
        void linkLocal_blocked() {
            EgressFilter.EgressCheckResult result = filter.checkDestination("169.254.0.1", 8080);
            assertFalse(result.allowed());
            assertTrue(result.reason().toLowerCase().contains("link-local"));
        }

        @Test
        void metadataService_blocked() {
            // 169.254.169.254 is both link-local and the cloud metadata address.
            // The filter checks link-local before metadata, so verify it is blocked
            // (the exact reason may be "link-local" or "metadata" depending on check order).
            EgressFilter.EgressCheckResult result = filter.checkDestination("169.254.169.254", 80);
            assertFalse(result.allowed());
            String reason = result.reason().toLowerCase();
            assertTrue(reason.contains("link-local") || reason.contains("metadata"),
                    "Expected link-local or metadata block, got: " + result.reason());
        }
    }

    @Nested
    class PrivateRanges {

        @Test
        void privateRange_blockedWithoutWhitelist() {
            EgressFilter.EgressCheckResult result = filter.checkDestination("10.0.0.1", 8080);
            assertFalse(result.allowed());
            assertTrue(result.reason().contains("private range"));
        }

        @Test
        void privateRange_allowedWithWhitelist() {
            EgressFilter whitelisted = new EgressFilter(new EgressFilter.EgressConfig(
                    List.of("10.0.0.1:8080"),
                    true,   // blockPrivateRanges
                    true,   // blockLinkLocal
                    true,   // blockMetadataService
                    true,   // blockLoopback
                    false,  // dnsPinning — disable for predictable tests
                    2000,   // maxDnsResolutionMs
                    List.of("25", "53")
            ));

            EgressFilter.EgressCheckResult result = whitelisted.checkDestination("10.0.0.1", 8080);
            assertTrue(result.allowed());
        }
    }

    @Nested
    class PublicIp {

        @Test
        void publicIp_allowed() {
            // Raw IP — no DNS resolution needed
            EgressFilter.EgressCheckResult result = filter.checkDestination("93.184.216.34", 443);
            assertTrue(result.allowed());
        }
    }

    @Nested
    class NullHost {

        @Test
        void nullHost_blocked() {
            EgressFilter.EgressCheckResult result = filter.checkDestination(null, 443);
            assertFalse(result.allowed());
        }
    }

    @Nested
    class DynamicWhitelist {

        @Test
        void addAllowedDestination_dynamicWhitelist() {
            // 10.0.0.50:9090 is private and not whitelisted — should be blocked
            EgressFilter.EgressCheckResult before = filter.checkDestination("10.0.0.50", 9090);
            assertFalse(before.allowed());

            // Add it dynamically
            filter.addAllowedDestination("10.0.0.50:9090");

            EgressFilter.EgressCheckResult after = filter.checkDestination("10.0.0.50", 9090);
            assertTrue(after.allowed());
        }

        @Test
        void removeAllowedDestination_removesEntry() {
            filter.addAllowedDestination("10.0.0.50:9090");

            // Verify it works
            assertTrue(filter.checkDestination("10.0.0.50", 9090).allowed());

            // Remove it
            filter.removeAllowedDestination("10.0.0.50:9090");

            // Should be blocked again (private range, no whitelist)
            assertFalse(filter.checkDestination("10.0.0.50", 9090).allowed());
        }
    }

    @Nested
    class Stats {

        @Test
        void getStats_tracksCounts() {
            // Generate some traffic
            filter.checkDestination("93.184.216.34", 443);  // allowed
            filter.checkDestination("127.0.0.1", 8080);     // blocked (loopback)
            filter.checkDestination("10.0.0.1", 8080);      // blocked (private)

            Map<String, Object> stats = filter.getStats();

            assertEquals(1L, stats.get("totalAllowed"));
            assertEquals(2L, stats.get("totalBlocked"));

            @SuppressWarnings("unchecked")
            Map<String, Long> byReason = (Map<String, Long>) stats.get("blockedByReason");
            assertNotNull(byReason);
            assertTrue(byReason.containsKey("loopback"));
            assertTrue(byReason.containsKey("private_range"));
        }
    }

    @Nested
    class Defaults {

        @Test
        void defaults_factoryMethod_hasSensibleValues() {
            EgressFilter.EgressConfig config = EgressFilter.EgressConfig.defaults();

            assertTrue(config.blockPrivateRanges());
            assertTrue(config.blockLinkLocal());
            assertTrue(config.blockMetadataService());
            assertTrue(config.blockLoopback());
            assertTrue(config.dnsPinning());
            assertEquals(2000, config.maxDnsResolutionMs());
            assertTrue(config.allowedDestinations().isEmpty());
            assertEquals(List.of("25", "53"), config.blockedPorts());
        }
    }
}
