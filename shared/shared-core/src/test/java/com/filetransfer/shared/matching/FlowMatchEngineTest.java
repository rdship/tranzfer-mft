package com.filetransfer.shared.matching;

import com.filetransfer.shared.enums.Protocol;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class FlowMatchEngineTest {

    private FlowMatchEngine engine;
    private MatchContext baseContext;

    @BeforeEach
    void setUp() {
        engine = new FlowMatchEngine();
        baseContext = new MatchContext(
                "invoice_850.edi", "edi", 1048576L,
                Protocol.SFTP, MatchContext.Direction.INBOUND,
                UUID.fromString("00000000-0000-0000-0000-000000000001"), "acme-corp",
                "acme-sftp", UUID.fromString("00000000-0000-0000-0000-000000000002"),
                "/inbox/edi/invoice_850.edi", "10.0.0.5",
                "X12", "850",
                LocalTime.of(14, 30), DayOfWeek.MONDAY,
                Map.of("region", "US", "priority", "high")
        );
    }

    // ---- null / empty criteria ----

    @Test
    void nullCriteria_matchesEverything() {
        assertTrue(engine.matches(null, baseContext));
    }

    @Test
    void nullContext_neverMatches() {
        var cond = new MatchCondition("filename", MatchCondition.ConditionOp.EQ, "test", null, null);
        assertFalse(engine.matches(cond, null));
    }

    @Test
    void emptyAndGroup_matchesEverything() {
        var group = new MatchGroup(MatchGroup.GroupOperator.AND, List.of());
        assertTrue(engine.matches(group, baseContext));
    }

    @Test
    void emptyOrGroup_matchesNothing() {
        var group = new MatchGroup(MatchGroup.GroupOperator.OR, List.of());
        assertFalse(engine.matches(group, baseContext));
    }

    // ---- EQ operator ----

    @Test
    void eq_filenameMatch() {
        var cond = new MatchCondition("filename", MatchCondition.ConditionOp.EQ, "invoice_850.edi", null, null);
        assertTrue(engine.matches(cond, baseContext));
    }

    @Test
    void eq_filenameCaseInsensitive() {
        var cond = new MatchCondition("filename", MatchCondition.ConditionOp.EQ, "INVOICE_850.EDI", null, null);
        assertTrue(engine.matches(cond, baseContext));
    }

    @Test
    void eq_filenameNoMatch() {
        var cond = new MatchCondition("filename", MatchCondition.ConditionOp.EQ, "other.txt", null, null);
        assertFalse(engine.matches(cond, baseContext));
    }

    // ---- IN operator ----

    @Test
    void in_protocolMatch() {
        var cond = new MatchCondition("protocol", MatchCondition.ConditionOp.IN, null, List.of("SFTP", "AS2"), null);
        assertTrue(engine.matches(cond, baseContext));
    }

    @Test
    void in_protocolNoMatch() {
        var cond = new MatchCondition("protocol", MatchCondition.ConditionOp.IN, null, List.of("FTP", "AS2"), null);
        assertFalse(engine.matches(cond, baseContext));
    }

    @Test
    void in_extensionMatch() {
        var cond = new MatchCondition("extension", MatchCondition.ConditionOp.IN, null, List.of("csv", "edi", "txt"), null);
        assertTrue(engine.matches(cond, baseContext));
    }

    // ---- REGEX operator ----

    @Test
    void regex_filenameMatch() {
        var cond = new MatchCondition("filename", MatchCondition.ConditionOp.REGEX, ".*_850\\.edi", null, null);
        assertTrue(engine.matches(cond, baseContext));
    }

    @Test
    void regex_filenameNoMatch() {
        var cond = new MatchCondition("filename", MatchCondition.ConditionOp.REGEX, ".*\\.csv", null, null);
        assertFalse(engine.matches(cond, baseContext));
    }

    @Test
    void regex_invalidPattern_returnsFalse() {
        var cond = new MatchCondition("filename", MatchCondition.ConditionOp.REGEX, "[invalid", null, null);
        assertFalse(engine.matches(cond, baseContext));
    }

    // ---- GLOB operator ----

    @Test
    void glob_starDotEdi() {
        var cond = new MatchCondition("filename", MatchCondition.ConditionOp.GLOB, "*.edi", null, null);
        assertTrue(engine.matches(cond, baseContext));
    }

    @Test
    void glob_questionMark() {
        var cond = new MatchCondition("filename", MatchCondition.ConditionOp.GLOB, "invoice_8?0.edi", null, null);
        assertTrue(engine.matches(cond, baseContext));
    }

    @Test
    void glob_exactMatch() {
        var cond = new MatchCondition("filename", MatchCondition.ConditionOp.GLOB, "invoice_850.edi", null, null);
        assertTrue(engine.matches(cond, baseContext));
    }

    @Test
    void glob_noMatch() {
        var cond = new MatchCondition("filename", MatchCondition.ConditionOp.GLOB, "*.csv", null, null);
        assertFalse(engine.matches(cond, baseContext));
    }

    @Test
    void glob_prefixWildcard() {
        var cond = new MatchCondition("filename", MatchCondition.ConditionOp.GLOB, "invoice_*", null, null);
        assertTrue(engine.matches(cond, baseContext));
    }

    // ---- CONTAINS / STARTS_WITH / ENDS_WITH ----

    @Test
    void contains_sourcePathMatch() {
        var cond = new MatchCondition("sourcePath", MatchCondition.ConditionOp.CONTAINS, "/edi/", null, null);
        assertTrue(engine.matches(cond, baseContext));
    }

    @Test
    void startsWith_sourcePathMatch() {
        var cond = new MatchCondition("sourcePath", MatchCondition.ConditionOp.STARTS_WITH, "/inbox", null, null);
        assertTrue(engine.matches(cond, baseContext));
    }

    @Test
    void endsWith_filenameMatch() {
        var cond = new MatchCondition("filename", MatchCondition.ConditionOp.ENDS_WITH, ".edi", null, null);
        assertTrue(engine.matches(cond, baseContext));
    }

    // ---- Numeric operators ----

    @Test
    void gt_fileSizeMatch() {
        var cond = new MatchCondition("fileSize", MatchCondition.ConditionOp.GT, 500000, null, null);
        assertTrue(engine.matches(cond, baseContext));
    }

    @Test
    void lt_fileSizeNoMatch() {
        var cond = new MatchCondition("fileSize", MatchCondition.ConditionOp.LT, 500000, null, null);
        assertFalse(engine.matches(cond, baseContext));
    }

    @Test
    void gte_fileSizeExact() {
        var cond = new MatchCondition("fileSize", MatchCondition.ConditionOp.GTE, 1048576, null, null);
        assertTrue(engine.matches(cond, baseContext));
    }

    @Test
    void lte_fileSizeExact() {
        var cond = new MatchCondition("fileSize", MatchCondition.ConditionOp.LTE, 1048576, null, null);
        assertTrue(engine.matches(cond, baseContext));
    }

    @Test
    void between_fileSizeInRange() {
        var cond = new MatchCondition("fileSize", MatchCondition.ConditionOp.BETWEEN, null, List.of(1000000, 2000000), null);
        assertTrue(engine.matches(cond, baseContext));
    }

    @Test
    void between_fileSizeOutOfRange() {
        var cond = new MatchCondition("fileSize", MatchCondition.ConditionOp.BETWEEN, null, List.of(5000000, 10000000), null);
        assertFalse(engine.matches(cond, baseContext));
    }

    // ---- Partner / Account fields ----

    @Test
    void eq_partnerSlugMatch() {
        var cond = new MatchCondition("partnerSlug", MatchCondition.ConditionOp.EQ, "acme-corp", null, null);
        assertTrue(engine.matches(cond, baseContext));
    }

    @Test
    void eq_accountUsernameMatch() {
        var cond = new MatchCondition("accountUsername", MatchCondition.ConditionOp.EQ, "acme-sftp", null, null);
        assertTrue(engine.matches(cond, baseContext));
    }

    @Test
    void eq_partnerIdMatch() {
        var cond = new MatchCondition("partnerId", MatchCondition.ConditionOp.EQ,
                "00000000-0000-0000-0000-000000000001", null, null);
        assertTrue(engine.matches(cond, baseContext));
    }

    // ---- EDI fields ----

    @Test
    void eq_ediStandardMatch() {
        var cond = new MatchCondition("ediStandard", MatchCondition.ConditionOp.EQ, "X12", null, null);
        assertTrue(engine.matches(cond, baseContext));
    }

    @Test
    void in_ediTypeMatch() {
        var cond = new MatchCondition("ediType", MatchCondition.ConditionOp.IN, null, List.of("850", "855", "997"), null);
        assertTrue(engine.matches(cond, baseContext));
    }

    @Test
    void eq_ediTypeNoMatch() {
        var cond = new MatchCondition("ediType", MatchCondition.ConditionOp.EQ, "997", null, null);
        assertFalse(engine.matches(cond, baseContext));
    }

    // ---- Direction ----

    @Test
    void eq_directionMatch() {
        var cond = new MatchCondition("direction", MatchCondition.ConditionOp.EQ, "INBOUND", null, null);
        assertTrue(engine.matches(cond, baseContext));
    }

    @Test
    void eq_directionNoMatch() {
        var cond = new MatchCondition("direction", MatchCondition.ConditionOp.EQ, "OUTBOUND", null, null);
        assertFalse(engine.matches(cond, baseContext));
    }

    // ---- Time fields ----

    @Test
    void eq_dayOfWeekMatch() {
        var cond = new MatchCondition("dayOfWeek", MatchCondition.ConditionOp.EQ, "MONDAY", null, null);
        assertTrue(engine.matches(cond, baseContext));
    }

    @Test
    void in_dayOfWeekMatch() {
        var cond = new MatchCondition("dayOfWeek", MatchCondition.ConditionOp.IN, null, List.of("MONDAY", "TUESDAY", "WEDNESDAY"), null);
        assertTrue(engine.matches(cond, baseContext));
    }

    @Test
    void eq_hourMatch() {
        var cond = new MatchCondition("hour", MatchCondition.ConditionOp.EQ, 14, null, null);
        assertTrue(engine.matches(cond, baseContext));
    }

    @Test
    void gte_hourMatch() {
        var cond = new MatchCondition("hour", MatchCondition.ConditionOp.GTE, 8, null, null);
        assertTrue(engine.matches(cond, baseContext));
    }

    @Test
    void between_timeOfDayWithinWindow() {
        var cond = new MatchCondition("timeOfDay", MatchCondition.ConditionOp.BETWEEN, null, List.of("08:00", "17:00"), null);
        assertTrue(engine.matches(cond, baseContext));
    }

    @Test
    void between_timeOfDayOutsideWindow() {
        var cond = new MatchCondition("timeOfDay", MatchCondition.ConditionOp.BETWEEN, null, List.of("18:00", "23:00"), null);
        assertFalse(engine.matches(cond, baseContext));
    }

    // ---- CIDR ----

    @Test
    void cidr_ipInRange() {
        var cond = new MatchCondition("sourceIp", MatchCondition.ConditionOp.CIDR, "10.0.0.0/24", null, null);
        assertTrue(engine.matches(cond, baseContext));
    }

    @Test
    void cidr_ipOutOfRange() {
        var cond = new MatchCondition("sourceIp", MatchCondition.ConditionOp.CIDR, "192.168.1.0/24", null, null);
        assertFalse(engine.matches(cond, baseContext));
    }

    @Test
    void cidr_exactMatch() {
        var cond = new MatchCondition("sourceIp", MatchCondition.ConditionOp.CIDR, "10.0.0.5", null, null);
        assertTrue(engine.matches(cond, baseContext));
    }

    // ---- Metadata ----

    @Test
    void keyEq_metadataMatch() {
        var cond = new MatchCondition("metadata", MatchCondition.ConditionOp.KEY_EQ, "US", null, "region");
        assertTrue(engine.matches(cond, baseContext));
    }

    @Test
    void keyEq_metadataNoMatch() {
        var cond = new MatchCondition("metadata", MatchCondition.ConditionOp.KEY_EQ, "EU", null, "region");
        assertFalse(engine.matches(cond, baseContext));
    }

    @Test
    void metadotField_eqMatch() {
        // metadata.region as field name
        var cond = new MatchCondition("metadata.region", MatchCondition.ConditionOp.EQ, "US", null, null);
        assertTrue(engine.matches(cond, baseContext));
    }

    @Test
    void metadotField_containsMatch() {
        var cond = new MatchCondition("metadata.priority", MatchCondition.ConditionOp.CONTAINS, "igh", null, null);
        assertTrue(engine.matches(cond, baseContext));
    }

    // ---- Missing field ----

    @Test
    void missingField_returnsFalse() {
        var ctx = new MatchContext("test.txt", "txt", 100, null, null,
                null, null, null, null, null, null, null, null,
                null, null, Map.of());
        var cond = new MatchCondition("protocol", MatchCondition.ConditionOp.EQ, "SFTP", null, null);
        assertFalse(engine.matches(cond, ctx));
    }

    @Test
    void unknownField_returnsFalse() {
        var cond = new MatchCondition("nonexistent", MatchCondition.ConditionOp.EQ, "val", null, null);
        assertFalse(engine.matches(cond, baseContext));
    }

    // ---- AND group ----

    @Test
    void andGroup_allMatch() {
        var group = new MatchGroup(MatchGroup.GroupOperator.AND, List.of(
                new MatchCondition("protocol", MatchCondition.ConditionOp.EQ, "SFTP", null, null),
                new MatchCondition("filename", MatchCondition.ConditionOp.GLOB, "*.edi", null, null),
                new MatchCondition("partnerSlug", MatchCondition.ConditionOp.EQ, "acme-corp", null, null)
        ));
        assertTrue(engine.matches(group, baseContext));
    }

    @Test
    void andGroup_oneFails() {
        var group = new MatchGroup(MatchGroup.GroupOperator.AND, List.of(
                new MatchCondition("protocol", MatchCondition.ConditionOp.EQ, "FTP", null, null), // fails
                new MatchCondition("filename", MatchCondition.ConditionOp.GLOB, "*.edi", null, null)
        ));
        assertFalse(engine.matches(group, baseContext));
    }

    // ---- OR group ----

    @Test
    void orGroup_oneMatches() {
        var group = new MatchGroup(MatchGroup.GroupOperator.OR, List.of(
                new MatchCondition("protocol", MatchCondition.ConditionOp.EQ, "FTP", null, null),
                new MatchCondition("protocol", MatchCondition.ConditionOp.EQ, "SFTP", null, null)
        ));
        assertTrue(engine.matches(group, baseContext));
    }

    @Test
    void orGroup_noneMatch() {
        var group = new MatchGroup(MatchGroup.GroupOperator.OR, List.of(
                new MatchCondition("protocol", MatchCondition.ConditionOp.EQ, "FTP", null, null),
                new MatchCondition("protocol", MatchCondition.ConditionOp.EQ, "AS2", null, null)
        ));
        assertFalse(engine.matches(group, baseContext));
    }

    // ---- NOT group ----

    @Test
    void notGroup_negatesMatch() {
        var group = new MatchGroup(MatchGroup.GroupOperator.NOT, List.of(
                new MatchCondition("protocol", MatchCondition.ConditionOp.EQ, "FTP", null, null)
        ));
        assertTrue(engine.matches(group, baseContext)); // NOT(false) = true
    }

    @Test
    void notGroup_negatesTrue() {
        var group = new MatchGroup(MatchGroup.GroupOperator.NOT, List.of(
                new MatchCondition("protocol", MatchCondition.ConditionOp.EQ, "SFTP", null, null)
        ));
        assertFalse(engine.matches(group, baseContext)); // NOT(true) = false
    }

    // ---- Complex nested tree ----

    @Test
    void nestedAndOrNot_complexTree() {
        // (protocol IN [SFTP, AS2]) AND (filename GLOB *.edi) AND (NOT (fileSize > 10MB)) AND (ediType=850 OR ediType=855)
        var criteria = new MatchGroup(MatchGroup.GroupOperator.AND, List.of(
                new MatchCondition("protocol", MatchCondition.ConditionOp.IN, null, List.of("SFTP", "AS2"), null),
                new MatchCondition("filename", MatchCondition.ConditionOp.GLOB, "*.edi", null, null),
                new MatchGroup(MatchGroup.GroupOperator.NOT, List.of(
                        new MatchCondition("fileSize", MatchCondition.ConditionOp.GT, 10_000_000, null, null)
                )),
                new MatchGroup(MatchGroup.GroupOperator.OR, List.of(
                        new MatchCondition("ediType", MatchCondition.ConditionOp.EQ, "850", null, null),
                        new MatchCondition("ediType", MatchCondition.ConditionOp.EQ, "855", null, null)
                ))
        ));
        assertTrue(engine.matches(criteria, baseContext));
    }

    @Test
    void nestedAndOrNot_failsDueToNot() {
        // Same as above but fileSize > 500000 (which is true, so NOT makes it false)
        var criteria = new MatchGroup(MatchGroup.GroupOperator.AND, List.of(
                new MatchCondition("protocol", MatchCondition.ConditionOp.EQ, "SFTP", null, null),
                new MatchGroup(MatchGroup.GroupOperator.NOT, List.of(
                        new MatchCondition("fileSize", MatchCondition.ConditionOp.GT, 500_000, null, null) // true → NOT = false
                ))
        ));
        assertFalse(engine.matches(criteria, baseContext));
    }

    @Test
    void nestedAndOrNot_failsDueToOrMiss() {
        // protocol=SFTP AND (ediType=997 OR ediType=855) — ediType is 850, so OR fails
        var criteria = new MatchGroup(MatchGroup.GroupOperator.AND, List.of(
                new MatchCondition("protocol", MatchCondition.ConditionOp.EQ, "SFTP", null, null),
                new MatchGroup(MatchGroup.GroupOperator.OR, List.of(
                        new MatchCondition("ediType", MatchCondition.ConditionOp.EQ, "997", null, null),
                        new MatchCondition("ediType", MatchCondition.ConditionOp.EQ, "855", null, null)
                ))
        ));
        assertFalse(engine.matches(criteria, baseContext));
    }

    // ---- Validation ----

    @Test
    void validate_validCriteria_noErrors() {
        var criteria = new MatchGroup(MatchGroup.GroupOperator.AND, List.of(
                new MatchCondition("filename", MatchCondition.ConditionOp.GLOB, "*.edi", null, null)
        ));
        assertTrue(engine.validate(criteria).isEmpty());
    }

    @Test
    void validate_notGroupWrongChildCount_hasError() {
        var criteria = new MatchGroup(MatchGroup.GroupOperator.NOT, List.of(
                new MatchCondition("a", MatchCondition.ConditionOp.EQ, "1", null, null),
                new MatchCondition("b", MatchCondition.ConditionOp.EQ, "2", null, null)
        ));
        assertFalse(engine.validate(criteria).isEmpty());
    }

    @Test
    void validate_missingField_hasError() {
        var criteria = new MatchCondition(null, MatchCondition.ConditionOp.EQ, "test", null, null);
        assertFalse(engine.validate(criteria).isEmpty());
    }

    @Test
    void validate_inWithoutValues_hasError() {
        var criteria = new MatchCondition("protocol", MatchCondition.ConditionOp.IN, null, null, null);
        assertFalse(engine.validate(criteria).isEmpty());
    }

    @Test
    void validate_betweenWrongValueCount_hasError() {
        var criteria = new MatchCondition("fileSize", MatchCondition.ConditionOp.BETWEEN, null, List.of(100), null);
        assertFalse(engine.validate(criteria).isEmpty());
    }

    @Test
    void validate_nullCriteria_noErrors() {
        assertTrue(engine.validate(null).isEmpty());
    }

    @Test
    void validate_emptyAndGroup_hasError() {
        var criteria = new MatchGroup(MatchGroup.GroupOperator.AND, List.of());
        var errors = engine.validate(criteria);
        assertFalse(errors.isEmpty());
        assertTrue(errors.stream().anyMatch(e -> e.contains("no conditions")));
    }

    @Test
    void validate_emptyOrGroup_hasError() {
        var criteria = new MatchGroup(MatchGroup.GroupOperator.OR, List.of());
        var errors = engine.validate(criteria);
        assertFalse(errors.isEmpty());
        assertTrue(errors.stream().anyMatch(e -> e.contains("no conditions")));
    }

    @Test
    void validate_unknownField_hasError() {
        var criteria = new MatchCondition("bogusField", MatchCondition.ConditionOp.EQ, "val", null, null);
        var errors = engine.validate(criteria);
        assertFalse(errors.isEmpty());
        assertTrue(errors.stream().anyMatch(e -> e.contains("Unknown field")));
    }

    @Test
    void validate_metadotField_noError() {
        var criteria = new MatchCondition("metadata.region", MatchCondition.ConditionOp.EQ, "US", null, null);
        assertTrue(engine.validate(criteria).isEmpty());
    }

    @Test
    void validate_invalidOperatorForField_hasError() {
        // GT is not valid for filename (string field)
        var criteria = new MatchCondition("filename", MatchCondition.ConditionOp.GT, 100, null, null);
        var errors = engine.validate(criteria);
        assertFalse(errors.isEmpty());
        assertTrue(errors.stream().anyMatch(e -> e.contains("not valid for field")));
    }

    @Test
    void validate_validOperatorForField_noError() {
        var criteria = new MatchCondition("fileSize", MatchCondition.ConditionOp.GT, 1000, null, null);
        assertTrue(engine.validate(criteria).isEmpty());
    }

    @Test
    void validate_depthExceedsMax_hasError() {
        // Build nested tree deeper than MAX_CRITERIA_DEPTH
        MatchCriteria leaf = new MatchCondition("filename", MatchCondition.ConditionOp.EQ, "test", null, null);
        for (int i = 0; i < FlowMatchEngine.MAX_CRITERIA_DEPTH + 2; i++) {
            leaf = new MatchGroup(MatchGroup.GroupOperator.AND, List.of(leaf));
        }
        var errors = engine.validate(leaf);
        assertFalse(errors.isEmpty());
        assertTrue(errors.stream().anyMatch(e -> e.contains("too deep")));
    }

    // ---- glob conversion ----

    @ParameterizedTest
    @CsvSource({
            "*.edi, ^[^/]*\\.edi$",
            "invoice_*.txt, ^invoice_[^/]*\\.txt$",
            "??.csv, ^[^/][^/]\\.csv$",
    })
    void globToRegex_correctConversion(String glob, String expectedRegex) {
        assertEquals(expectedRegex, FlowMatchEngine.globToRegex(glob));
    }
}
