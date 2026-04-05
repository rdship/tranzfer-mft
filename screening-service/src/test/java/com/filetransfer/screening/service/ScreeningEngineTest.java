package com.filetransfer.screening.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the Jaro-Winkler similarity algorithm and CSV parsing logic
 * in ScreeningEngine without requiring Spring context or database.
 */
class ScreeningEngineTest {

    // ---- Jaro-Winkler similarity tests ----

    @Test
    void jaroWinkler_exactMatch_shouldReturn1() {
        assertEquals(1.0, ScreeningEngine.jaroWinklerSimilarity("john smith", "john smith"));
    }

    @Test
    void jaroWinkler_nullInputs_shouldReturn0() {
        assertEquals(0.0, ScreeningEngine.jaroWinklerSimilarity(null, "test"));
        assertEquals(0.0, ScreeningEngine.jaroWinklerSimilarity("test", null));
        assertEquals(0.0, ScreeningEngine.jaroWinklerSimilarity(null, null));
    }

    @Test
    void jaroWinkler_completelyDifferent_shouldReturnLowScore() {
        double score = ScreeningEngine.jaroWinklerSimilarity("abcdef", "zyxwvu");
        assertTrue(score < 0.5, "Completely different strings should score below 0.5, got: " + score);
    }

    @Test
    void jaroWinkler_similarNames_shouldReturnHighScore() {
        // Classic AML test case: slight misspelling
        double score = ScreeningEngine.jaroWinklerSimilarity("muhammad ali", "mohammad ali");
        assertTrue(score > 0.82, "Similar names should score above threshold, got: " + score);
    }

    @Test
    void jaroWinkler_transposedChars_shouldScoreHigh() {
        double score = ScreeningEngine.jaroWinklerSimilarity("john", "jonh");
        assertTrue(score > 0.9, "Transposed chars should score high, got: " + score);
    }

    @ParameterizedTest
    @CsvSource({
        "'martha', 'marhta', 0.96",    // classic Jaro test case
        "'dwayne', 'duane', 0.82",      // moderate similarity
        "'dixon', 'dicksonx', 0.76",    // lower similarity
    })
    void jaroWinkler_knownPairs_shouldExceedMinimum(String s1, String s2, double minScore) {
        double score = ScreeningEngine.jaroWinklerSimilarity(s1, s2);
        assertTrue(score >= minScore,
                String.format("Score for '%s' vs '%s' = %.4f, expected >= %.2f", s1, s2, score, minScore));
    }

    @Test
    void jaroWinkler_emptyStrings_shouldHandle() {
        double score = ScreeningEngine.jaroWinklerSimilarity("", "");
        assertEquals(1.0, score); // equal strings
    }

    @Test
    void jaroWinkler_oneEmpty_shouldReturn0() {
        double score = ScreeningEngine.jaroWinklerSimilarity("hello", "");
        assertEquals(0.0, score);
    }

    @Test
    void jaroWinkler_symmetry() {
        double ab = ScreeningEngine.jaroWinklerSimilarity("john smith", "jon smith");
        double ba = ScreeningEngine.jaroWinklerSimilarity("jon smith", "john smith");
        assertEquals(ab, ba, 0.001, "Jaro-Winkler should be approximately symmetric");
    }

    @Test
    void jaroWinkler_singleCharStrings() {
        assertEquals(1.0, ScreeningEngine.jaroWinklerSimilarity("a", "a"));
        assertEquals(0.0, ScreeningEngine.jaroWinklerSimilarity("a", "b"));
    }

    @Test
    void jaroWinkler_commonPrefixBoost() {
        // Same prefix should boost score (Winkler component)
        double withPrefix = ScreeningEngine.jaroWinklerSimilarity("johnson", "johnsen");
        double withoutPrefix = ScreeningEngine.jaroWinklerSimilarity("xohnson", "xohnsen");
        // Both should be high, but the common 'john' prefix should provide a boost
        assertTrue(withPrefix >= withoutPrefix,
                "Common prefix should boost: " + withPrefix + " vs " + withoutPrefix);
    }

    // ---- Sanctions name matching realism tests ----

    @Test
    void jaroWinkler_sanctionsRealWorld_shouldDetectVariants() {
        // Variant spellings commonly used to evade sanctions
        assertTrue(ScreeningEngine.jaroWinklerSimilarity("osama bin laden", "usama bin ladin") > 0.82);
        assertTrue(ScreeningEngine.jaroWinklerSimilarity("saddam hussein", "sadam husein") > 0.82);
    }
}
