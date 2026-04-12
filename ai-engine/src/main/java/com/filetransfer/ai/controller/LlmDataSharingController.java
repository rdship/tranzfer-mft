package com.filetransfer.ai.controller;

import com.filetransfer.ai.config.LlmDataSharingConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Admin API for controlling what data is shared with the external LLM.
 *
 * <p>The admin sees each data category with:
 * <ul>
 *   <li><b>What data is included</b> — exactly what fields/records are sent
 *   <li><b>What value the LLM provides</b> — what questions become answerable
 *   <li><b>What risk they accept</b> — what sensitive data leaves the system
 * </ul>
 *
 * <p>The admin toggles each category independently. Changes take effect immediately
 * on the next LLM call (no restart needed). All defaults are OFF except platform metrics.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>GET  /api/v1/ai/data-sharing — view all categories with risk/value descriptions
 *   <li>PUT  /api/v1/ai/data-sharing/{category} — enable or disable a specific category
 *   <li>GET  /api/v1/ai/data-sharing/summary — one-line summary of what's currently shared
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/ai/data-sharing")
@RequiredArgsConstructor
public class LlmDataSharingController {

    private final LlmDataSharingConfig config;

    /**
     * Returns all data sharing categories with full risk/value descriptions.
     * Each category shows: enabled, name, dataIncluded, valueProvided, riskDescription.
     */
    @GetMapping
    public Map<String, Object> getAll() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("llmProvider", "Anthropic Claude");
        result.put("dataFlowDescription",
                "When you enable a category, that data is included in the LLM prompt sent to Anthropic's API. "
                + "The LLM uses it to give more accurate, context-aware answers. "
                + "Data is sent over HTTPS (TLS 1.3). Anthropic does not use API data for training. "
                + "You control exactly what leaves your system.");
        result.put("categories", config.getAllCategories());
        return result;
    }

    /**
     * Enable or disable a specific data sharing category.
     * Body: {"enabled": true} or {"enabled": false}
     */
    @PutMapping("/{category}")
    public Map<String, Object> toggle(@PathVariable String category, @RequestBody Map<String, Boolean> body) {
        boolean enabled = Boolean.TRUE.equals(body.get("enabled"));
        var categories = config.getAllCategories();
        var cat = categories.get(category);

        if (cat == null) {
            return Map.of("error", "Unknown category: " + category,
                    "availableCategories", categories.keySet());
        }

        cat.setEnabled(enabled);
        log.info("LLM data sharing: {} {} (admin decision)", category, enabled ? "ENABLED" : "DISABLED");

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("category", category);
        response.put("enabled", enabled);
        response.put("name", cat.getName());
        response.put("whatIsNowShared", enabled ? cat.getDataIncluded() : "Nothing — this category is disabled");
        response.put("whatLlmCanNowAnswer", enabled ? cat.getValueProvided() : "Limited — LLM has no access to this data");
        response.put("riskAccepted", enabled ? cat.getRiskDescription() : "No risk — data stays local");
        return response;
    }

    /**
     * One-line summary of current data sharing state.
     */
    @GetMapping("/summary")
    public Map<String, Object> summary() {
        var categories = config.getAllCategories();
        long enabledCount = categories.values().stream().filter(LlmDataSharingConfig.Category::isEnabled).count();
        var enabledNames = categories.entrySet().stream()
                .filter(e -> e.getValue().isEnabled())
                .map(e -> e.getValue().getName())
                .toList();
        var disabledNames = categories.entrySet().stream()
                .filter(e -> !e.getValue().isEnabled())
                .map(e -> e.getValue().getName())
                .toList();

        return Map.of(
                "totalCategories", categories.size(),
                "enabledCount", enabledCount,
                "sharingWith", "Anthropic Claude API (HTTPS, no training use)",
                "enabledCategories", enabledNames,
                "disabledCategories", disabledNames,
                "recommendation", enabledCount <= 1
                        ? "Enable 'Transfer Records' and 'Step Snapshots' for significantly better failure diagnosis."
                        : enabledCount >= 5
                        ? "Most categories enabled — LLM has comprehensive platform awareness. Review risk descriptions."
                        : "Balanced configuration — good trade-off between privacy and AI capability.");
    }
}
