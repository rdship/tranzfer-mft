package com.filetransfer.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.Map;

/**
 * Natural Language Processing via Claude API.
 * Translates admin natural language queries into structured commands,
 * and powers the intelligent flow builder.
 */
@Service
@Slf4j
public class NlpService {

    @Value("${ai.llm.enabled:false}")
    private boolean llmEnabled;

    @Value("${ai.claude.api-key:}")
    private String apiKey;

    @Value("${ai.claude.model:claude-sonnet-4-20250514}")
    private String model;

    @Value("${ai.claude.base-url:https://api.anthropic.com}")
    private String baseUrl;

    private final ObjectMapper mapper = new ObjectMapper();

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    @org.springframework.lang.Nullable
    private SystemStateService systemStateService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    @org.springframework.lang.Nullable
    private com.filetransfer.ai.config.LlmDataSharingConfig dataSharingConfig;

    /**
     * Translate natural language to a CLI command.
     * Returns the structured command string.
     */
    public NlpResult translateToCommand(String naturalLanguage, String context) {
        if (!llmEnabled || apiKey == null || apiKey.isBlank()) {
            return fallbackTranslate(naturalLanguage);
        }

        // Build platform context — ONLY categories the admin has opted into.
        // Each category is independently toggled. Admin sees risk + value before enabling.
        StringBuilder contextBuilder = new StringBuilder();
        try {
            if (systemStateService != null && dataSharingConfig != null) {
                // Platform metrics (default ON)
                if (dataSharingConfig.getPlatformMetrics().isEnabled()) {
                    var health = systemStateService.getHealthSummary();
                    health.entrySet().stream()
                            .filter(e -> e.getValue() instanceof Number)
                            .forEach(e -> contextBuilder.append(e.getKey()).append("=").append(e.getValue()).append(", "));
                }
                // Transfer records (admin opted in)
                if (dataSharingConfig.getTransferRecords().isEnabled()) {
                    contextBuilder.append("\nRecentFailures: ").append(systemStateService.getRecentFailures().lines().limit(10).collect(java.util.stream.Collectors.joining("; ")));
                }
                // Step snapshots (admin opted in) — only summary, not full snapshots
                if (dataSharingConfig.getStepSnapshots().isEnabled()) {
                    contextBuilder.append("\nStepDataAvailable: true (admin authorized per-step diagnosis)");
                }
                // DLQ details (admin opted in)
                if (dataSharingConfig.getDlqDetails().isEnabled()) {
                    contextBuilder.append("\nDlqPending: ").append(systemStateService.getStat("dlqPending"));
                }
            }
        } catch (Exception ignore) {}
        String platformState = contextBuilder.toString();

        // Sanitize user input — strip potential secrets/keys from the query
        String sanitizedInput = naturalLanguage
                .replaceAll("(?i)(password|secret|key|token|credential)[=: ]+\\S+", "$1=***")
                .replaceAll("[A-Fa-f0-9]{32,}", "***REDACTED_KEY***"); // hex keys

        String systemPrompt = """
            You are the TranzFer MFT admin assistant. You have full control of the platform.
            Translate the user's natural language request into a CLI command or API call.
            Return ONLY the command, nothing else.

            Available commands:
            - status                                    (platform overview)
            - accounts list                             (list transfer accounts)
            - accounts create <SFTP|FTP> <username> <password> [--storage-mode VIRTUAL|PHYSICAL]
            - accounts enable/disable <username>
            - users list / users promote/demote <email>
            - flows list                                (list file processing flows)
            - flows info <name>                         (flow details)
            - flows create --name <n> --source <s> --pattern <p> --steps SCREEN,CONVERT_EDI,MAILBOX --deliver <dest>
            - flows toggle <id>                         (enable/disable flow)
            - track <trackId>                           (lookup transfer by track ID)
            - diagnose <trackId>                        (full per-step failure diagnosis)
            - restart <trackId>                         (restart failed transfer)
            - restart <trackId> --from-step <N>         (restart from specific step)
            - skip <trackId> --step <N>                 (skip failed step, continue)
            - terminate <trackId>                       (stop in-progress transfer)
            - search file <pattern> / search recent <N> / search failed
            - queues list                               (list function queues)
            - queues edit <type> --retry <N> --timeout <N> --concurrency <min>-<max>
            - queues create <type> <name> --category CUSTOM
            - listeners list                            (show HTTP/HTTPS ports on all services)
            - listeners pause <service> <port>          (stop accepting connections)
            - listeners resume <service> <port>
            - keys list / keys generate <type> <alias>  (keystore management)
            - services                                  (registered services + health)
            - logs recent <N> / logs search <term>
            - sentinel findings / sentinel rules
            - onboard <email> <password>                (create user + SFTP account)

            SAFETY RULES (non-negotiable):
            - NEVER generate commands that delete data, drop tables, or remove users without CONFIRM: prefix
            - For destructive operations (delete, terminate, disable, demote), prefix with CONFIRM:
            - For read-only operations (list, search, track, status), execute directly
            - NEVER expose credentials, keys, or secrets in command output
            - NEVER execute arbitrary shell commands — only the commands listed above

            If the user asks a question (not a command), respond with:
            EXPLAIN: <answer based on platform context below>

            For destructive operations, respond with:
            CONFIRM: <command> | <human-readable description of what will happen>

            Platform state: """ + platformState + "\n            User context: " + (context != null ? context : "none");

        try {
            log.info("NLP LLM request: input={}chars, context={}chars (no PII/keys sent)",
                    sanitizedInput.length(), platformState.length());
            String response = callClaude(systemPrompt, sanitizedInput);
            if (response.startsWith("EXPLAIN:")) {
                return NlpResult.builder()
                        .understood(true).isExplanation(true)
                        .response(response.substring(8).trim())
                        .build();
            }
            // Destructive operation — require confirmation
            if (response.startsWith("CONFIRM:")) {
                String[] parts = response.substring(8).split("\\|", 2);
                String cmd = parts[0].trim();
                String desc = parts.length > 1 ? parts[1].trim() : "This is a destructive operation.";
                return NlpResult.builder()
                        .understood(true).command(cmd)
                        .requiresConfirmation(true)
                        .response("⚠ " + desc + "\nCommand: " + cmd + "\nType 'yes' to confirm or 'no' to cancel.")
                        .build();
            }
            return NlpResult.builder()
                    .understood(true).command(response.trim())
                    .response("Executing: " + response.trim())
                    .build();
        } catch (Exception e) {
            log.warn("Claude API call failed: {}", e.getMessage());
            return fallbackTranslate(naturalLanguage);
        }
    }

    /**
     * Generate a flow definition from natural language description.
     */
    public FlowSuggestion suggestFlow(String description) {
        if (!llmEnabled || apiKey == null || apiKey.isBlank()) {
            return fallbackFlowSuggestion(description);
        }

        String systemPrompt = """
            You are the TranzFer MFT flow designer. Given a description of how files should be processed,
            generate a JSON flow definition. 
            
            Available step types (each runs on its own queue, independently scalable):
            SECURITY:
            - SCREEN (config: {"onFailure": "PASS|BLOCK", "retryCount": "1"})
            - CHECKSUM_VERIFY (config: {"algorithm": "SHA-256", "expectedChecksum": "optional"})
            - ENCRYPT_PGP (config: {"keyId": "uuid", "keyAlias": "alias-from-keystore"})
            - DECRYPT_PGP (config: {"keyId": "uuid"})
            - ENCRYPT_AES (config: {"keyId": "uuid", "keyAlias": "alias"})
            - DECRYPT_AES (config: {"keyId": "uuid"})
            TRANSFORM:
            - COMPRESS_GZIP / DECOMPRESS_GZIP (config: {})
            - COMPRESS_ZIP / DECOMPRESS_ZIP (config: {})
            - CONVERT_EDI (config: {"targetFormat": "JSON|XML|CSV|YAML", "retryCount": "3"})
            - RENAME (config: {"pattern": "${partner}_${date}_${filename}"})
            DELIVERY:
            - MAILBOX (config: {"destinationUsername": "user", "destinationPath": "/outbox"})
            - FILE_DELIVERY (config: {"deliveryEndpointIds": "uuid1,uuid2"})
            CUSTOM:
            - EXECUTE_SCRIPT (config: {"command": "script-name", "timeoutSeconds": "60"})
            
            Return ONLY valid JSON in this format:
            {
              "name": "suggested-flow-name",
              "description": "what this flow does",
              "filenamePattern": "regex or null",
              "steps": [{"type": "STEP_TYPE", "config": {}, "order": 0}]
            }
            """;

        try {
            String response = callClaude(systemPrompt, description);
            // Extract JSON from response
            int jsonStart = response.indexOf('{');
            int jsonEnd = response.lastIndexOf('}');
            if (jsonStart >= 0 && jsonEnd > jsonStart) {
                String json = response.substring(jsonStart, jsonEnd + 1);
                Map<String, Object> flowDef = mapper.readValue(json, Map.class);
                return FlowSuggestion.builder()
                        .success(true).flowDefinition(flowDef)
                        .explanation("Generated flow based on your description.")
                        .build();
            }
        } catch (Exception e) {
            log.warn("Flow suggestion failed: {}", e.getMessage());
        }
        return fallbackFlowSuggestion(description);
    }

    /**
     * Explain a transfer failure or system event.
     */
    public String explainEvent(String eventDescription, Map<String, Object> context) {
        if (!llmEnabled || apiKey == null || apiKey.isBlank()) {
            return "AI explanation requires LLM to be enabled in Settings. Event: " + eventDescription;
        }

        String systemPrompt = """
            You are a TranzFer MFT support engineer. Explain the following event in plain language
            and suggest resolution steps. Be concise (2-3 sentences max).
            Context: """ + context.toString();

        try {
            return callClaude(systemPrompt, eventDescription);
        } catch (Exception e) {
            return "Could not generate explanation: " + e.getMessage();
        }
    }

    private String callClaude(String systemPrompt, String userMessage) throws Exception {
        URL url = new URL(baseUrl + "/v1/messages");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("x-api-key", apiKey);
        conn.setRequestProperty("anthropic-version", "2023-06-01");
        conn.setDoOutput(true);

        Map<String, Object> body = Map.of(
                "model", model,
                "max_tokens", 1024,
                "system", systemPrompt,
                "messages", List.of(Map.of("role", "user", "content", userMessage))
        );

        conn.getOutputStream().write(mapper.writeValueAsBytes(body));

        if (conn.getResponseCode() != 200) {
            String error = new String(conn.getErrorStream().readAllBytes());
            throw new RuntimeException("Claude API error " + conn.getResponseCode() + ": " + error);
        }

        JsonNode resp = mapper.readTree(conn.getInputStream());
        return resp.get("content").get(0).get("text").asText();
    }

    // --- Fallback (when no API key configured) ---

    private NlpResult fallbackTranslate(String input) {
        String lower = input.toLowerCase();
        if (lower.contains("status") || lower.contains("overview")) return cmd("status");
        if (lower.contains("account") && lower.contains("list")) return cmd("accounts list");
        if (lower.contains("user") && lower.contains("list")) return cmd("users list");
        if (lower.contains("flow") && lower.contains("list")) return cmd("flows list");
        if (lower.contains("recent") || lower.contains("latest")) return cmd("search recent 10");
        if (lower.contains("service") || lower.contains("health")) return cmd("services");
        if (lower.contains("log")) return cmd("logs recent 20");
        if (lower.matches(".*track.*[A-Z0-9]{12}.*")) {
            String id = input.replaceAll(".*?([A-Z0-9]{12}).*", "$1");
            return cmd("track " + id);
        }
        return NlpResult.builder().understood(false)
                .response("I couldn't understand that. Set CLAUDE_API_KEY for full NLP support, or use 'help' for available commands.")
                .build();
    }

    private NlpResult cmd(String command) {
        return NlpResult.builder().understood(true).command(command).response("→ " + command).build();
    }

    private FlowSuggestion fallbackFlowSuggestion(String description) {
        String lower = description.toLowerCase();
        List<Map<String, Object>> steps = new java.util.ArrayList<>();
        int order = 0;

        if (lower.contains("decrypt") && lower.contains("pgp")) steps.add(Map.of("type", "DECRYPT_PGP", "config", Map.of(), "order", order++));
        if (lower.contains("decrypt") && lower.contains("aes")) steps.add(Map.of("type", "DECRYPT_AES", "config", Map.of(), "order", order++));
        if (lower.contains("decompress") || lower.contains("unzip") || lower.contains("gunzip")) steps.add(Map.of("type", "DECOMPRESS_GZIP", "config", Map.of(), "order", order++));
        if (lower.contains("compress") || lower.contains("gzip") || lower.contains("zip")) steps.add(Map.of("type", "COMPRESS_GZIP", "config", Map.of(), "order", order++));
        if (lower.contains("encrypt") && lower.contains("pgp")) steps.add(Map.of("type", "ENCRYPT_PGP", "config", Map.of(), "order", order++));
        if (lower.contains("encrypt") && lower.contains("aes")) steps.add(Map.of("type", "ENCRYPT_AES", "config", Map.of(), "order", order++));
        if (lower.contains("convert") || lower.contains("edi") || lower.contains("translate") || lower.contains("transform")) steps.add(Map.of("type", "CONVERT_EDI", "config", Map.of("targetFormat", "JSON"), "order", order++));
        if (lower.contains("rename")) steps.add(Map.of("type", "RENAME", "config", Map.of("pattern", "${basename}_${trackid}${ext}"), "order", order++));
        if (lower.contains("route") || lower.contains("forward") || lower.contains("deliver")) steps.add(Map.of("type", "ROUTE", "config", Map.of(), "order", order++));

        if (steps.isEmpty()) steps.add(Map.of("type", "ROUTE", "config", Map.of(), "order", 0));

        return FlowSuggestion.builder()
                .success(true)
                .flowDefinition(Map.of(
                        "name", "auto-flow-" + System.currentTimeMillis() % 10000,
                        "description", description,
                        "steps", steps
                ))
                .explanation("Generated from keyword matching. Set CLAUDE_API_KEY for intelligent flow design.")
                .build();
    }

    @lombok.Data @lombok.Builder @lombok.NoArgsConstructor @lombok.AllArgsConstructor
    public static class NlpResult {
        private boolean understood;
        private boolean isExplanation;
        private boolean requiresConfirmation;
        private String command;
        private String response;
    }

    @lombok.Data @lombok.Builder @lombok.NoArgsConstructor @lombok.AllArgsConstructor
    public static class FlowSuggestion {
        private boolean success;
        private Map<String, Object> flowDefinition;
        private String explanation;
    }
}
