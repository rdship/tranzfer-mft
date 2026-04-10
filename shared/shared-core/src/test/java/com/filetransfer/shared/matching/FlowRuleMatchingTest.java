package com.filetransfer.shared.matching;

import com.filetransfer.shared.enums.Protocol;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration-style tests for the COMPLETE flow matching pipeline:
 * file arrives with attributes -> MatchCondition/MatchGroup built ->
 * CompiledFlowRule with engine-backed predicate -> FlowRuleRegistry.findMatch()
 *
 * Pure JUnit 5, no Spring context.
 */
class FlowRuleMatchingTest {

    private FlowRuleRegistry registry;
    private FlowMatchEngine engine;

    @BeforeEach
    void setUp() {
        registry = new FlowRuleRegistry();
        engine = new FlowMatchEngine();
    }

    // ---- helpers ----

    /**
     * Build a CompiledFlowRule that uses the real FlowMatchEngine for evaluation.
     * This mirrors what FlowRuleCompiler does but without needing FileFlow (shared-platform).
     */
    private CompiledFlowRule compileRule(String name, int priority, MatchCriteria criteria) {
        return compileRule(name, priority, criteria, null, Set.of());
    }

    private CompiledFlowRule compileRule(String name, int priority, MatchCriteria criteria,
                                          String direction, Set<String> protocols) {
        return new CompiledFlowRule(
                UUID.randomUUID(), name, priority,
                direction, protocols,
                ctx -> engine.matches(criteria, ctx));
    }

    private void registerRule(CompiledFlowRule rule) {
        registry.register(rule.flowId(), rule.flowName(), rule);
    }

    // ---- 1. filename GLOB ----

    @Test
    void findMatch_byFilenameGlob_shouldMatchInvoiceXml() {
        MatchCriteria criteria = new MatchCondition(
                "filename", MatchCondition.ConditionOp.GLOB, "*.xml", null, null);
        CompiledFlowRule rule = compileRule("XML-Inbound", 10, criteria);
        registerRule(rule);

        MatchContext ctx = MatchContext.builder()
                .withFilename("invoice.xml")
                .withExtension("xml")
                .build();

        CompiledFlowRule match = registry.findMatch(ctx);
        assertNotNull(match, "Should match *.xml glob");
        assertEquals("XML-Inbound", match.flowName());
    }

    // ---- 2. extension EQ ----

    @Test
    void findMatch_byExtension_shouldMatchCsvFiles() {
        MatchCriteria criteria = new MatchCondition(
                "extension", MatchCondition.ConditionOp.EQ, "csv", null, null);
        CompiledFlowRule rule = compileRule("CSV-Processor", 10, criteria);
        registerRule(rule);

        MatchContext csvCtx = MatchContext.builder()
                .withFilename("report.csv")
                .withExtension("csv")
                .build();
        assertNotNull(registry.findMatch(csvCtx), "Should match csv extension");

        MatchContext xmlCtx = MatchContext.builder()
                .withFilename("report.xml")
                .withExtension("xml")
                .build();
        assertNull(registry.findMatch(xmlCtx), "Should not match xml extension");
    }

    // ---- 3. protocol EQ with fast-path ----

    @Test
    void findMatch_byProtocol_shouldMatchSftpOnly() {
        MatchCriteria criteria = new MatchCondition(
                "protocol", MatchCondition.ConditionOp.EQ, "SFTP", null, null);
        // Include SFTP in the protocol set for fast-path filtering
        CompiledFlowRule rule = compileRule("SFTP-Only", 10, criteria, null, Set.of("SFTP"));
        registerRule(rule);

        MatchContext sftpCtx = MatchContext.builder()
                .withFilename("data.txt")
                .withExtension("txt")
                .withProtocol("SFTP")
                .build();
        assertNotNull(registry.findMatch(sftpCtx), "Should match SFTP protocol");

        MatchContext ftpCtx = MatchContext.builder()
                .withFilename("data.txt")
                .withExtension("txt")
                .withProtocol("FTP")
                .build();
        assertNull(registry.findMatch(ftpCtx), "Should not match FTP protocol");
    }

    // ---- 4. partnerId EQ ----

    @Test
    void findMatch_byPartner_shouldMatchSpecificPartner() {
        UUID partnerId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        MatchCriteria criteria = new MatchCondition(
                "partnerId", MatchCondition.ConditionOp.EQ, partnerId.toString(), null, null);
        CompiledFlowRule rule = compileRule("Partner-Flow", 10, criteria);
        registerRule(rule);

        MatchContext matchCtx = MatchContext.builder()
                .withFilename("file.txt")
                .withPartnerId(partnerId)
                .build();
        assertNotNull(registry.findMatch(matchCtx), "Should match specific partnerId");

        MatchContext otherCtx = MatchContext.builder()
                .withFilename("file.txt")
                .withPartnerId(UUID.fromString("22222222-2222-2222-2222-222222222222"))
                .build();
        assertNull(registry.findMatch(otherCtx), "Should not match different partnerId");
    }

    // ---- 5. fileSize GT ----

    @Test
    void findMatch_byFileSize_shouldMatchLargeFiles() {
        MatchCriteria criteria = new MatchCondition(
                "fileSize", MatchCondition.ConditionOp.GT, 1000000, null, null);
        CompiledFlowRule rule = compileRule("Large-File-Handler", 10, criteria);
        registerRule(rule);

        MatchContext largeCtx = MatchContext.builder()
                .withFilename("bigfile.dat")
                .withFileSize(5_000_000L)
                .build();
        assertNotNull(registry.findMatch(largeCtx), "Should match 5MB file");

        MatchContext smallCtx = MatchContext.builder()
                .withFilename("small.dat")
                .withFileSize(500L)
                .build();
        assertNull(registry.findMatch(smallCtx), "Should not match 500 byte file");
    }

    // ---- 6. ediType EQ ----

    @Test
    void findMatch_byEdiType_shouldMatchX12() {
        MatchCriteria criteria = new MatchCondition(
                "ediType", MatchCondition.ConditionOp.EQ, "X12", null, null);
        CompiledFlowRule rule = compileRule("X12-Processor", 10, criteria);
        registerRule(rule);

        MatchContext x12Ctx = MatchContext.builder()
                .withFilename("order.edi")
                .withEdiType("X12")
                .build();
        assertNotNull(registry.findMatch(x12Ctx), "Should match X12 ediType");

        MatchContext edifactCtx = MatchContext.builder()
                .withFilename("order.edi")
                .withEdiType("EDIFACT")
                .build();
        assertNull(registry.findMatch(edifactCtx), "Should not match EDIFACT");
    }

    // ---- 7. priority ordering ----

    @Test
    void findMatch_priorityOrder_shouldReturnHighestPriority() {
        // Both rules match *.edi, but priority 10 should win
        MatchCriteria criteria1 = new MatchCondition(
                "filename", MatchCondition.ConditionOp.GLOB, "*.edi", null, null);
        MatchCriteria criteria2 = new MatchCondition(
                "filename", MatchCondition.ConditionOp.GLOB, "*.edi", null, null);

        CompiledFlowRule highPriority = compileRule("High-Priority", 10, criteria1);
        CompiledFlowRule lowPriority = compileRule("Low-Priority", 50, criteria2);

        // Register low first to ensure ordering is by priority, not insertion
        registerRule(lowPriority);
        registerRule(highPriority);

        MatchContext ctx = MatchContext.builder()
                .withFilename("invoice.edi")
                .withExtension("edi")
                .build();

        CompiledFlowRule match = registry.findMatch(ctx);
        assertNotNull(match);
        assertEquals("High-Priority", match.flowName(),
                "Priority 10 rule should be returned before priority 50");
        assertEquals(10, match.priority());
    }

    // ---- 8. composite AND ----

    @Test
    void findMatch_compositeAndCriteria_shouldRequireAllConditions() {
        MatchCriteria criteria = new MatchGroup(MatchGroup.GroupOperator.AND, List.of(
                new MatchCondition("filename", MatchCondition.ConditionOp.GLOB, "*.edi", null, null),
                new MatchCondition("protocol", MatchCondition.ConditionOp.EQ, "SFTP", null, null),
                new MatchCondition("fileSize", MatchCondition.ConditionOp.GT, 1000, null, null)
        ));
        CompiledFlowRule rule = compileRule("Strict-Rule", 10, criteria);
        registerRule(rule);

        // All conditions met
        MatchContext fullMatch = MatchContext.builder()
                .withFilename("invoice.edi")
                .withExtension("edi")
                .withProtocol("SFTP")
                .withFileSize(5000L)
                .build();
        assertNotNull(registry.findMatch(fullMatch), "Should match when ALL conditions met");

        // Filename matches but protocol wrong
        MatchContext wrongProtocol = MatchContext.builder()
                .withFilename("invoice.edi")
                .withExtension("edi")
                .withProtocol("FTP")
                .withFileSize(5000L)
                .build();
        assertNull(registry.findMatch(wrongProtocol), "Should not match when protocol is wrong");

        // Filename and protocol match but file too small
        MatchContext tooSmall = MatchContext.builder()
                .withFilename("invoice.edi")
                .withExtension("edi")
                .withProtocol("SFTP")
                .withFileSize(500L)
                .build();
        assertNull(registry.findMatch(tooSmall), "Should not match when file too small");
    }

    // ---- 9. composite OR ----

    @Test
    void findMatch_compositeOrCriteria_shouldMatchAnyCondition() {
        MatchCriteria criteria = new MatchGroup(MatchGroup.GroupOperator.OR, List.of(
                new MatchCondition("extension", MatchCondition.ConditionOp.EQ, "xml", null, null),
                new MatchCondition("extension", MatchCondition.ConditionOp.EQ, "json", null, null)
        ));
        CompiledFlowRule rule = compileRule("Data-Files", 10, criteria);
        registerRule(rule);

        MatchContext xmlCtx = MatchContext.builder()
                .withFilename("data.xml")
                .withExtension("xml")
                .build();
        assertNotNull(registry.findMatch(xmlCtx), "Should match xml");

        MatchContext jsonCtx = MatchContext.builder()
                .withFilename("data.json")
                .withExtension("json")
                .build();
        assertNotNull(registry.findMatch(jsonCtx), "Should match json");

        MatchContext csvCtx = MatchContext.builder()
                .withFilename("data.csv")
                .withExtension("csv")
                .build();
        assertNull(registry.findMatch(csvCtx), "Should not match csv");
    }

    // ---- 10. no match ----

    @Test
    void findMatch_noMatch_shouldReturnEmpty() {
        MatchCriteria criteria = new MatchCondition(
                "extension", MatchCondition.ConditionOp.EQ, "pdf", null, null);
        CompiledFlowRule rule = compileRule("PDF-Only", 10, criteria);
        registerRule(rule);

        MatchContext ctx = MatchContext.builder()
                .withFilename("report.xlsx")
                .withExtension("xlsx")
                .build();

        assertNull(registry.findMatch(ctx), "Should return null when no rule matches");
    }
}
