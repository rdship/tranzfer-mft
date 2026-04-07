package com.filetransfer.notification.service;

import com.filetransfer.shared.entity.NotificationRule;
import com.filetransfer.shared.repository.NotificationRuleRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RuleMatcherTest {

    @Mock
    private NotificationRuleRepository ruleRepository;

    @InjectMocks
    private RuleMatcher ruleMatcher;

    // ── matchesPattern ──────────────────────────────────────────────────

    @Nested
    @DisplayName("matchesPattern")
    class MatchesPatternTests {

        @Test
        @DisplayName("null eventType returns false")
        void nullEventType_returnsFalse() {
            assertThat(ruleMatcher.matchesPattern(null, "transfer.*")).isFalse();
        }

        @Test
        @DisplayName("null pattern returns false")
        void nullPattern_returnsFalse() {
            assertThat(ruleMatcher.matchesPattern("transfer.completed", null)).isFalse();
        }

        @Test
        @DisplayName("both null returns false")
        void bothNull_returnsFalse() {
            assertThat(ruleMatcher.matchesPattern(null, null)).isFalse();
        }

        @Test
        @DisplayName("'#' matches everything")
        void hashMatchesEverything() {
            assertThat(ruleMatcher.matchesPattern("transfer.completed", "#")).isTrue();
            assertThat(ruleMatcher.matchesPattern("screening.completed.extra", "#")).isTrue();
        }

        @Test
        @DisplayName("'*' matches everything")
        void starAloneMatchesEverything() {
            assertThat(ruleMatcher.matchesPattern("transfer.completed", "*")).isTrue();
            assertThat(ruleMatcher.matchesPattern("anything", "*")).isTrue();
        }

        @Test
        @DisplayName("exact match")
        void exactMatch() {
            assertThat(ruleMatcher.matchesPattern("transfer.completed", "transfer.completed")).isTrue();
        }

        @Test
        @DisplayName("wildcard matches single segment")
        void wildcardMatchesSingleSegment() {
            assertThat(ruleMatcher.matchesPattern("transfer.completed", "transfer.*")).isTrue();
            assertThat(ruleMatcher.matchesPattern("transfer.failed", "transfer.*")).isTrue();
        }

        @Test
        @DisplayName("wildcard does NOT cross segment boundary")
        void wildcardDoesNotCrossSegments() {
            assertThat(ruleMatcher.matchesPattern("transfer.completed.extra", "transfer.*")).isFalse();
        }

        @Test
        @DisplayName("multi-segment '#' matches deep paths")
        void multiSegmentHash() {
            assertThat(ruleMatcher.matchesPattern("transfer.completed.extra", "transfer.#")).isTrue();
            assertThat(ruleMatcher.matchesPattern("transfer.completed", "transfer.#")).isTrue();
        }

        @Test
        @DisplayName("no match for different prefix")
        void noMatchDifferentPrefix() {
            assertThat(ruleMatcher.matchesPattern("screening.completed", "transfer.completed")).isFalse();
        }
    }

    // ── findMatchingRules ───────────────────────────────────────────────

    @Nested
    @DisplayName("findMatchingRules")
    class FindMatchingRulesTests {

        @Test
        @DisplayName("returns only enabled rules matching the event type")
        void returnsMatchingEnabledRules() {
            NotificationRule matchingRule = NotificationRule.builder()
                    .id(UUID.randomUUID())
                    .name("transfer-email")
                    .eventTypePattern("transfer.*")
                    .channel("EMAIL")
                    .recipients(List.of("admin@tranzfer.io"))
                    .enabled(true)
                    .build();

            NotificationRule nonMatchingRule = NotificationRule.builder()
                    .id(UUID.randomUUID())
                    .name("screening-email")
                    .eventTypePattern("screening.*")
                    .channel("EMAIL")
                    .recipients(List.of("admin@tranzfer.io"))
                    .enabled(true)
                    .build();

            when(ruleRepository.findByEnabledTrue())
                    .thenReturn(List.of(matchingRule, nonMatchingRule));

            List<NotificationRule> result = ruleMatcher.findMatchingRules("transfer.completed");

            assertThat(result).containsExactly(matchingRule);
        }

        @Test
        @DisplayName("empty enabled rules returns empty list")
        void emptyEnabledRules_returnsEmptyList() {
            when(ruleRepository.findByEnabledTrue()).thenReturn(Collections.emptyList());

            List<NotificationRule> result = ruleMatcher.findMatchingRules("transfer.completed");

            assertThat(result).isEmpty();
        }
    }

    // ── matchesConditions ───────────────────────────────────────────────

    @Nested
    @DisplayName("matchesConditions")
    class MatchesConditionsTests {

        @Test
        @DisplayName("null conditions returns true")
        void nullConditions_returnsTrue() {
            NotificationRule rule = NotificationRule.builder()
                    .id(UUID.randomUUID())
                    .name("rule")
                    .eventTypePattern("transfer.*")
                    .channel("EMAIL")
                    .recipients(List.of("a@b.com"))
                    .conditions(null)
                    .build();

            assertThat(ruleMatcher.matchesConditions(rule, Map.of("key", "value"))).isTrue();
        }

        @Test
        @DisplayName("empty conditions returns true")
        void emptyConditions_returnsTrue() {
            NotificationRule rule = NotificationRule.builder()
                    .id(UUID.randomUUID())
                    .name("rule")
                    .eventTypePattern("transfer.*")
                    .channel("EMAIL")
                    .recipients(List.of("a@b.com"))
                    .conditions(Collections.emptyMap())
                    .build();

            assertThat(ruleMatcher.matchesConditions(rule, Map.of("key", "value"))).isTrue();
        }

        @Test
        @DisplayName("null payload with non-empty conditions returns false")
        void nullPayload_returnsFalse() {
            NotificationRule rule = NotificationRule.builder()
                    .id(UUID.randomUUID())
                    .name("rule")
                    .eventTypePattern("transfer.*")
                    .channel("EMAIL")
                    .recipients(List.of("a@b.com"))
                    .conditions(Map.of("severity", "CRITICAL"))
                    .build();

            assertThat(ruleMatcher.matchesConditions(rule, null)).isFalse();
        }

        @Test
        @DisplayName("all conditions match returns true")
        void allConditionsMatch_returnsTrue() {
            NotificationRule rule = NotificationRule.builder()
                    .id(UUID.randomUUID())
                    .name("rule")
                    .eventTypePattern("transfer.*")
                    .channel("EMAIL")
                    .recipients(List.of("a@b.com"))
                    .conditions(Map.of("severity", "CRITICAL", "protocol", "SFTP"))
                    .build();

            Map<String, Object> payload = Map.of("severity", "CRITICAL", "protocol", "SFTP", "extra", "ignored");

            assertThat(ruleMatcher.matchesConditions(rule, payload)).isTrue();
        }

        @Test
        @DisplayName("one condition doesn't match returns false")
        void oneConditionMismatch_returnsFalse() {
            NotificationRule rule = NotificationRule.builder()
                    .id(UUID.randomUUID())
                    .name("rule")
                    .eventTypePattern("transfer.*")
                    .channel("EMAIL")
                    .recipients(List.of("a@b.com"))
                    .conditions(Map.of("severity", "CRITICAL", "protocol", "SFTP"))
                    .build();

            Map<String, Object> payload = Map.of("severity", "CRITICAL", "protocol", "FTP");

            assertThat(ruleMatcher.matchesConditions(rule, payload)).isFalse();
        }

        @Test
        @DisplayName("null required value is treated as match")
        void nullRequiredValue_treatedAsMatch() {
            // Build a conditions map that allows null values
            Map<String, Object> conditions = new java.util.HashMap<>();
            conditions.put("severity", null);

            NotificationRule rule = NotificationRule.builder()
                    .id(UUID.randomUUID())
                    .name("rule")
                    .eventTypePattern("transfer.*")
                    .channel("EMAIL")
                    .recipients(List.of("a@b.com"))
                    .conditions(conditions)
                    .build();

            Map<String, Object> payload = Map.of("severity", "ANYTHING");

            assertThat(ruleMatcher.matchesConditions(rule, payload)).isTrue();
        }
    }
}
