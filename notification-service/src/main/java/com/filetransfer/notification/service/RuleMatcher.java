package com.filetransfer.notification.service;

import com.filetransfer.shared.entity.integration.NotificationRule;
import com.filetransfer.shared.repository.NotificationRuleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Matches incoming events against notification rules.
 * Supports wildcard patterns in event type matching (e.g. "transfer.*" matches "transfer.completed").
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RuleMatcher {

    private final NotificationRuleRepository ruleRepository;

    /**
     * Find all enabled rules that match the given event type.
     *
     * @param eventType the event type to match against rule patterns
     * @return list of matching rules
     */
    public List<NotificationRule> findMatchingRules(String eventType) {
        List<NotificationRule> enabledRules = ruleRepository.findByEnabledTrue();

        return enabledRules.stream()
                .filter(rule -> matchesPattern(eventType, rule.getEventTypePattern()))
                .toList();
    }

    /**
     * Check if an event type matches a rule pattern.
     * Supports:
     * - Exact match: "transfer.completed" matches "transfer.completed"
     * - Wildcard suffix: "transfer.*" matches "transfer.completed" and "transfer.failed"
     * - Catch-all: "#" or "*" matches everything
     */
    boolean matchesPattern(String eventType, String pattern) {
        if (pattern == null || eventType == null) return false;
        if ("#".equals(pattern) || "*".equals(pattern)) return true;
        if (pattern.equals(eventType)) return true;

        // Convert wildcard pattern to regex
        String regex = pattern
                .replace(".", "\\.")
                .replace("*", "[^.]*")
                .replace("#", ".*");

        return eventType.matches(regex);
    }

    /**
     * Check if a rule's additional conditions match the event payload.
     */
    public boolean matchesConditions(NotificationRule rule, Map<String, Object> payload) {
        Map<String, Object> conditions = rule.getConditions();
        if (conditions == null || conditions.isEmpty()) return true;
        if (payload == null) return false;

        return conditions.entrySet().stream().allMatch(entry -> {
            Object required = entry.getValue();
            Object actual = payload.get(entry.getKey());
            if (required == null) return true;
            return required.toString().equals(String.valueOf(actual));
        });
    }
}
