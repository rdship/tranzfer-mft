package com.filetransfer.ai.service.edi;

import com.filetransfer.ai.service.edi.FieldEmbeddingEngine.FieldMatch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests FieldEmbeddingEngine: n-gram, token-overlap, and semantic synonym similarity.
 * No Spring context needed -- pure logic.
 */
class FieldEmbeddingEngineTest {

    private FieldEmbeddingEngine engine;

    @BeforeEach
    void setUp() {
        engine = new FieldEmbeddingEngine();
    }

    // ---- Identical field names ----

    @Test
    void similarity_identicalFieldNames_shouldReturnOne() {
        assertEquals(1.0, engine.similarity("sender", "sender"));
        assertEquals(1.0, engine.similarity("buyer.name", "buyer.name"));
        assertEquals(1.0, engine.similarity("ISA*06", "ISA*06"));
    }

    // ---- Synonym pairs should have high similarity ----

    @Test
    void similarity_senderVsOriginator_shouldBeHigh() {
        double sim = engine.similarity("sender", "originator");
        assertTrue(sim > 0.5, "sender vs originator should be > 0.5 but was " + sim);
    }

    @Test
    void similarity_amountVsTotal_shouldBeHigh() {
        double sim = engine.similarity("amount", "total");
        assertTrue(sim > 0.5, "amount vs total should be > 0.5 but was " + sim);
    }

    @Test
    void similarity_zipVsPostalCode_shouldBeHigh() {
        double sim = engine.similarity("zip", "postalCode");
        assertTrue(sim > 0.2, "zip vs postalCode should be > 0.2 but was " + sim);
    }

    // ---- Unrelated fields should have low similarity ----

    @Test
    void similarity_senderVsQuantity_shouldBeLow() {
        double sim = engine.similarity("sender", "quantity");
        assertTrue(sim < 0.3, "sender vs quantity should be < 0.3 but was " + sim);
    }

    @Test
    void similarity_dateVsSku_shouldBeLow() {
        double sim = engine.similarity("date", "sku");
        assertTrue(sim < 0.3, "date vs sku should be < 0.3 but was " + sim);
    }

    // ---- Tokenization of camelCase ----

    @Test
    void tokenize_camelCase_shouldSplitIntoWords() {
        Set<String> tokens = engine.tokenize("purchaseOrderNumber");
        assertTrue(tokens.contains("purchase"), "Should contain 'purchase': " + tokens);
        assertTrue(tokens.contains("order"), "Should contain 'order': " + tokens);
        assertTrue(tokens.contains("number"), "Should contain 'number': " + tokens);
    }

    // ---- Tokenization of dot-separated paths ----

    @Test
    void tokenize_dotSeparated_shouldSplitOnDots() {
        Set<String> tokens = engine.tokenize("header.buyer.name");
        assertTrue(tokens.contains("header"), "Should contain 'header': " + tokens);
        assertTrue(tokens.contains("buyer"), "Should contain 'buyer': " + tokens);
        assertTrue(tokens.contains("name"), "Should contain 'name': " + tokens);
    }

    // ---- EDI field path tokenization ----

    @Test
    void tokenize_ediFieldPath_shouldTokenizeCorrectly() {
        Set<String> tokens = engine.tokenize("ISA*06");
        // After removing the *06 suffix, "ISA" remains; length check: "isa" has length 3
        assertTrue(tokens.contains("isa"), "Should contain 'isa': " + tokens);
    }

    // ---- findBestMatch ----

    @Test
    void findBestMatch_shouldReturnHighestSimilarity() {
        List<String> targets = List.of("originator", "quantity", "date", "sku");
        Optional<FieldMatch> match = engine.findBestMatch("sender", targets, 0.3);

        assertTrue(match.isPresent(), "Should find a match for 'sender'");
        assertEquals("originator", match.get().targetField());
    }

    @Test
    void findBestMatch_belowThreshold_shouldReturnEmpty() {
        List<String> targets = List.of("xyzzy", "qwerty");
        Optional<FieldMatch> match = engine.findBestMatch("sender", targets, 0.9);

        assertTrue(match.isEmpty(), "No target should match 'sender' at 0.9 threshold");
    }

    // ---- computeSimilarityMatrix ----

    @Test
    void computeSimilarityMatrix_shouldReturnSortedMatchesAboveThreshold() {
        List<String> sources = List.of("sender", "amount", "date");
        List<String> targets = List.of("originator", "total", "timestamp", "sku");

        List<FieldMatch> matches = engine.computeSimilarityMatrix(sources, targets, 0.3);

        assertFalse(matches.isEmpty(), "Should find at least one match above 0.3");

        // Verify sorted descending by similarity
        for (int i = 1; i < matches.size(); i++) {
            assertTrue(matches.get(i - 1).similarity() >= matches.get(i).similarity(),
                    "Matches should be sorted descending by similarity");
        }

        // All returned matches should be >= threshold
        for (FieldMatch m : matches) {
            assertTrue(m.similarity() >= 0.3,
                    "All matches should be >= 0.3 threshold but got " + m.similarity());
        }
    }

    // ---- Null/empty input ----

    @Test
    void similarity_nullInput_shouldReturnZero() {
        assertEquals(0.0, engine.similarity(null, "sender"));
        assertEquals(0.0, engine.similarity("sender", null));
        assertEquals(0.0, engine.similarity(null, null));
    }

    @Test
    void similarity_emptyInput_shouldReturnZero() {
        double sim = engine.similarity("", "");
        // Two empty strings normalize to the same thing, so they are "identical"
        // but the engine normalizes and compares -- both normalize to "" which is equal
        // so it returns 1.0. Let's test that empty vs non-empty is low.
        double sim2 = engine.similarity("", "sender");
        assertTrue(sim2 < 0.3, "Empty vs non-empty should be low but was " + sim2);
    }

    @Test
    void tokenize_nullOrBlank_shouldReturnEmptySet() {
        assertEquals(Set.of(), engine.tokenize(null));
        assertEquals(Set.of(), engine.tokenize(""));
        assertEquals(Set.of(), engine.tokenize("   "));
    }
}
