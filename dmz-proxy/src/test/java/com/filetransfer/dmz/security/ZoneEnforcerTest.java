package com.filetransfer.dmz.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ZoneEnforcerTest {

    private ZoneEnforcer enforcer;

    @BeforeEach
    void setUp() {
        Map<ZoneEnforcer.Zone, List<String>> cidrs = new EnumMap<>(ZoneEnforcer.Zone.class);
        cidrs.put(ZoneEnforcer.Zone.INTERNAL, List.of("10.0.0.0/8"));
        cidrs.put(ZoneEnforcer.Zone.DMZ, List.of("172.16.0.0/12"));
        cidrs.put(ZoneEnforcer.Zone.MANAGEMENT, List.of("192.168.100.0/24"));
        enforcer = new ZoneEnforcer(cidrs, ZoneEnforcer.defaultRules());
    }

    @Nested
    class ClassifyIp {

        @Test
        void classifyIp_internalRange_returnsInternal() {
            assertEquals(ZoneEnforcer.Zone.INTERNAL, enforcer.classifyIp("10.0.0.5"));
        }

        @Test
        void classifyIp_dmzRange_returnsDmz() {
            assertEquals(ZoneEnforcer.Zone.DMZ, enforcer.classifyIp("172.16.0.5"));
        }

        @Test
        void classifyIp_managementRange_returnsManagement() {
            assertEquals(ZoneEnforcer.Zone.MANAGEMENT, enforcer.classifyIp("192.168.100.5"));
        }

        @Test
        void classifyIp_unknownIp_returnsExternal() {
            assertEquals(ZoneEnforcer.Zone.EXTERNAL, enforcer.classifyIp("8.8.8.8"));
        }

        @Test
        void classifyIp_nullIp_returnsExternal() {
            assertEquals(ZoneEnforcer.Zone.EXTERNAL, enforcer.classifyIp(null));
        }

        @Test
        void classifyIp_emptyIp_returnsExternal() {
            assertEquals(ZoneEnforcer.Zone.EXTERNAL, enforcer.classifyIp(""));
        }

        @Test
        void classifyIp_managementHasPriority() {
            // 192.168.100.5 is in both 10.0.0.0/8 (no — it's 192.x) and MANAGEMENT.
            // To test priority, configure overlapping ranges where same IP matches both.
            Map<ZoneEnforcer.Zone, List<String>> cidrs = new EnumMap<>(ZoneEnforcer.Zone.class);
            cidrs.put(ZoneEnforcer.Zone.INTERNAL, List.of("192.168.0.0/16"));
            cidrs.put(ZoneEnforcer.Zone.MANAGEMENT, List.of("192.168.100.0/24"));

            ZoneEnforcer overlapping = new ZoneEnforcer(cidrs, ZoneEnforcer.defaultRules());

            // 192.168.100.5 matches both INTERNAL (192.168.0.0/16) and MANAGEMENT (192.168.100.0/24)
            // MANAGEMENT is checked first, so it should win
            assertEquals(ZoneEnforcer.Zone.MANAGEMENT, overlapping.classifyIp("192.168.100.5"));
        }
    }

    @Nested
    class CheckTransition {

        @Test
        void checkTransition_externalToDmz_allowed() {
            // 8.8.8.8 = EXTERNAL, 172.16.0.5 = DMZ
            ZoneEnforcer.ZoneCheckResult result = enforcer.checkTransition("8.8.8.8", "172.16.0.5", 443);
            assertTrue(result.allowed());
            assertEquals(ZoneEnforcer.Zone.EXTERNAL, result.sourceZone());
            assertEquals(ZoneEnforcer.Zone.DMZ, result.targetZone());
        }

        @Test
        void checkTransition_dmzToInternal_allowed() {
            // 172.16.0.5 = DMZ, 10.0.0.5 = INTERNAL
            ZoneEnforcer.ZoneCheckResult result = enforcer.checkTransition("172.16.0.5", "10.0.0.5", 8080);
            assertTrue(result.allowed());
            assertEquals(ZoneEnforcer.Zone.DMZ, result.sourceZone());
            assertEquals(ZoneEnforcer.Zone.INTERNAL, result.targetZone());
        }

        @Test
        void checkTransition_externalToInternal_blocked() {
            // 8.8.8.8 = EXTERNAL, 10.0.0.5 = INTERNAL
            ZoneEnforcer.ZoneCheckResult result = enforcer.checkTransition("8.8.8.8", "10.0.0.5", 8080);
            assertFalse(result.allowed());
            assertEquals(ZoneEnforcer.Zone.EXTERNAL, result.sourceZone());
            assertEquals(ZoneEnforcer.Zone.INTERNAL, result.targetZone());
        }

        @Test
        void checkTransition_externalToManagement_blocked() {
            // 8.8.8.8 = EXTERNAL, 192.168.100.5 = MANAGEMENT
            ZoneEnforcer.ZoneCheckResult result = enforcer.checkTransition("8.8.8.8", "192.168.100.5", 9090);
            assertFalse(result.allowed());
            assertEquals(ZoneEnforcer.Zone.EXTERNAL, result.sourceZone());
            assertEquals(ZoneEnforcer.Zone.MANAGEMENT, result.targetZone());
        }

        @Test
        void checkTransition_sameZone_alwaysAllowed() {
            // Two IPs in the same zone — always allowed regardless of rules
            ZoneEnforcer.ZoneCheckResult result = enforcer.checkTransition("10.0.0.5", "10.0.0.10", 8080);
            assertTrue(result.allowed());
            assertEquals(ZoneEnforcer.Zone.INTERNAL, result.sourceZone());
            assertEquals(ZoneEnforcer.Zone.INTERNAL, result.targetZone());
            assertTrue(result.reason().contains("Same-zone"));
        }
    }

    @Nested
    class Rules {

        @Test
        void addRule_replacesExistingRule() {
            // Default rules block EXTERNAL -> INTERNAL
            ZoneEnforcer.ZoneCheckResult before = enforcer.checkTransition("8.8.8.8", "10.0.0.5", 8080);
            assertFalse(before.allowed());

            // Replace with an allowing rule
            enforcer.addRule(new ZoneEnforcer.ZoneRule(
                    ZoneEnforcer.Zone.EXTERNAL, ZoneEnforcer.Zone.INTERNAL,
                    true, "Testing override"));

            ZoneEnforcer.ZoneCheckResult after = enforcer.checkTransition("8.8.8.8", "10.0.0.5", 8080);
            assertTrue(after.allowed());

            // Rule count should not have increased (replacement, not addition)
            assertEquals(12, enforcer.getRules().size());
        }

        @Test
        void defaultRules_has12Rules() {
            List<ZoneEnforcer.ZoneRule> rules = ZoneEnforcer.defaultRules();
            assertEquals(12, rules.size());
        }
    }

    @Nested
    class Stats {

        @Test
        void getStats_tracksAllowedAndBlocked() {
            // Generate some allowed and blocked transitions
            enforcer.checkTransition("8.8.8.8", "172.16.0.5", 443);   // EXTERNAL->DMZ allowed
            enforcer.checkTransition("8.8.8.8", "172.16.0.5", 443);   // EXTERNAL->DMZ allowed
            enforcer.checkTransition("8.8.8.8", "10.0.0.5", 8080);    // EXTERNAL->INTERNAL blocked

            Map<String, Object> stats = enforcer.getStats();

            assertEquals(2L, stats.get("totalAllowed"));
            assertEquals(1L, stats.get("totalBlocked"));
            assertEquals(12, stats.get("ruleCount"));

            @SuppressWarnings("unchecked")
            Map<String, Map<String, Long>> zonePairs = (Map<String, Map<String, Long>>) stats.get("zonePairs");
            assertNotNull(zonePairs);
            assertEquals(2L, zonePairs.get("EXTERNAL->DMZ").get("allowed"));
            assertEquals(1L, zonePairs.get("EXTERNAL->INTERNAL").get("blocked"));
        }
    }
}
