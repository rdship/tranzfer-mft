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

        // User role from context — API endpoints enforce @PreAuthorize,
        // but we tell the LLM what the user CAN do so it doesn't suggest
        // commands the user will be denied on.
        String userRole = context != null && context.contains("role=") ?
                context.substring(context.indexOf("role=") + 5).split("[,\\s]")[0] : "USER";

        String systemPrompt = """
            You are the TranzFer MFT admin assistant. You help the user manage the platform.
            Translate the user's natural language request into CLI commands.
            The user's role is: """ + userRole + """

            ROLE PERMISSIONS:
            - ADMIN: can do everything (create, delete, configure, promote users)
            - OPERATOR: can manage accounts, flows, transfers, keys (not users/roles)
            - USER/VIEWER: read-only (list, search, track, status, diagnose)
            If the user requests something above their role, respond:
            EXPLAIN: You need {required role} role to {action}. Contact your admin.

            COMPOUND OPERATIONS — for multi-step requests, return a JSON execution plan.
            Each step can reference outputs from previous steps using ${N.field} notation.
            Format: PLAN: followed by JSON array of steps.
            Example: "onboard ACME with SFTP access and an EDI flow"
            → PLAN: [
                {"method":"POST","path":"/api/partners","body":"{\"name\":\"ACME Corp\",\"type\":\"VENDOR\"}","description":"Create partner ACME Corp"},
                {"method":"POST","path":"/api/accounts","body":"{\"protocol\":\"SFTP\",\"username\":\"acme-sftp\",\"password\":\"AcmePass@1\"}","description":"Create SFTP account"},
                {"method":"POST","path":"/api/flows/quick","body":"{\"source\":\"acme-sftp\",\"filenamePattern\":\".*\\\\.edi\",\"actions\":[\"SCREEN\",\"CONVERT_EDI\"],\"deliverTo\":\"internal\",\"name\":\"acme-edi-flow\"}","description":"Create EDI processing flow"}
              ]
            For simple single operations, return the CLI command directly (no PLAN: prefix).

            Available commands:
            ACCOUNTS:
            - accounts list / accounts create <SFTP|FTP> <username> <password>
            - accounts enable/disable <username>
            USERS:
            - users list / users promote/demote <email> (ADMIN only)
            - onboard <email> <password> (create user + account in one step)
            FLOWS:
            - flows list / flows info <name> / flows toggle <id>
            - flows create --name <n> --source <s> --pattern <p> --steps SCREEN,CONVERT_EDI,MAILBOX --deliver <dest>
            TRANSFERS:
            - track <trackId> / diagnose <trackId>
            - restart <trackId> [--from-step <N>] / skip <trackId> --step <N>
            - terminate <trackId> (CONFIRM required)
            - search file <pattern> / search recent <N> / search failed
            QUEUES:
            - queues list / queues edit <type> --retry <N> --timeout <N>
            - queues create <type> <name> --category CUSTOM
            INFRASTRUCTURE:
            - listeners list / listeners pause/resume <service> <port>
            - keys list / keys generate <type> <alias>
            - services / status / logs recent <N> / sentinel findings

            SAFETY RULES:
            - Destructive operations (delete, terminate, disable, demote) → prefix with CONFIRM:
            - Read-only operations → execute directly
            - NEVER expose secrets in output
            - NEVER run arbitrary shell commands

            If the user asks a question (not a command), respond with:
            EXPLAIN: <answer using platform context below>

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

    /**
     * Pattern-based command translator — works WITHOUT any LLM.
     * Handles all standard admin operations natively using regex + our own API JSON formats.
     * Supports quantity operations, compound plans, and diagnostics.
     */
    private NlpResult fallbackTranslate(String input) {
        String lower = input.toLowerCase().trim();
        String original = input.trim();

        // ── Track ID operations ──
        java.util.regex.Matcher trackM = java.util.regex.Pattern.compile(
                "\\b(TRZ[A-Z0-9]{6,10})\\b", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(original);
        if (trackM.find()) {
            String tid = trackM.group(1).toUpperCase();
            if (lower.contains("diagnose") || lower.contains("why") || lower.contains("fail")) return cmd("diagnose " + tid);
            if (lower.contains("restart")) return cmd("restart " + tid);
            if (lower.contains("skip")) return cmd("skip " + tid + " --step 0");
            if (lower.contains("terminate") || lower.contains("stop")) return confirm("terminate " + tid, "Terminate transfer " + tid);
            return cmd("track " + tid);
        }

        // ── Quantity: "create N accounts/flows/keys" → orchestration plan ──
        java.util.regex.Matcher qtyM = java.util.regex.Pattern.compile(
                "(\\d+)\\s+(?:sftp|ftp|test)?\\s*(account|flow|key)", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(lower);
        if (qtyM.find() && lower.contains("create")) {
            int count = Math.min(Integer.parseInt(qtyM.group(1)), 50);
            String type = qtyM.group(2).toLowerCase();
            String protocol = lower.contains("ftp") && !lower.contains("sftp") ? "FTP" : "SFTP";
            try {
                return plan(buildBulkPlan(type, protocol, count, lower));
            } catch (Exception e) { /* fall through */ }
        }

        // ── Create single account ──
        if (lower.contains("create") && lower.contains("account")) {
            String protocol = lower.contains("ftp") && !lower.contains("sftp") ? "FTP" : "SFTP";
            String username = extractWord(original, "(?:named?|user(?:name)?|called)\\s+(\\S+)");
            if (username == null) username = "test-" + System.currentTimeMillis() % 10000;
            try {
                return plan(List.of(Map.of(
                        "method", "POST", "path", "/api/accounts",
                        "body", mapper.writeValueAsString(Map.of("protocol", protocol, "username", username, "password", "AutoPass@1!")),
                        "description", "Create " + protocol + " account: " + username)));
            } catch (Exception e) { /* fall through */ }
        }

        // ── Create flow ──
        if (lower.contains("create") && lower.contains("flow")) {
            String source = extractWord(original, "(?:for|source|from)\\s+(\\S+)");
            String pattern = extractWord(original, "(?:pattern|criteria|matching)\\s+(\\S+)");
            String deliver = extractWord(original, "(?:deliver|to|destination)\\s+(\\S+)");
            List<String> actions = new java.util.ArrayList<>();
            if (lower.contains("screen")) actions.add("SCREEN");
            if (lower.contains("encrypt")) actions.add("ENCRYPT_PGP");
            if (lower.contains("compress")) actions.add("COMPRESS_GZIP");
            if (lower.contains("convert") || lower.contains("edi")) actions.add("CONVERT_EDI");
            if (lower.contains("checksum")) actions.add("CHECKSUM_VERIFY");
            if (actions.isEmpty()) actions.add("SCREEN");
            String name = (source != null ? source : "auto") + "-flow-" + System.currentTimeMillis() % 10000;
            try {
                var body = new java.util.LinkedHashMap<String, Object>();
                body.put("name", name);
                if (source != null) body.put("source", source);
                if (pattern != null) body.put("filenamePattern", pattern);
                body.put("actions", actions);
                if (deliver != null) body.put("deliverTo", deliver);
                return plan(List.of(Map.of(
                        "method", "POST", "path", "/api/flows/quick",
                        "body", mapper.writeValueAsString(body),
                        "description", "Create flow: " + name)));
            } catch (Exception e) { /* fall through */ }
        }

        // ── Create + flow compound: "create account X and a flow for it" ──
        if (lower.contains("create") && lower.contains("account") && lower.contains("flow")) {
            String username = extractWord(original, "(?:named?|called|user)\\s+(\\S+)");
            if (username == null) username = "test-" + System.currentTimeMillis() % 10000;
            String protocol = lower.contains("ftp") && !lower.contains("sftp") ? "FTP" : "SFTP";
            String pattern = extractWord(original, "(?:pattern|criteria)\\s+(\\S+)");
            if (pattern == null) pattern = ".*";
            try {
                String finalUser = username;
                String finalPattern = pattern;
                return plan(List.of(
                        Map.of("method", "POST", "path", "/api/accounts",
                                "body", mapper.writeValueAsString(Map.of("protocol", protocol, "username", finalUser, "password", "AutoPass@1!")),
                                "description", "Create " + protocol + " account: " + finalUser),
                        Map.of("method", "POST", "path", "/api/flows/quick",
                                "body", mapper.writeValueAsString(Map.of("name", finalUser + "-flow", "source", finalUser, "filenamePattern", finalPattern, "actions", List.of("SCREEN", "MAILBOX"))),
                                "description", "Create flow for " + finalUser)));
            } catch (Exception e) { /* fall through */ }
        }

        // ── Queue operations ──
        if (lower.contains("queue")) {
            if (lower.contains("list")) return cmd("queues list");
            if (lower.contains("create")) {
                String queueType = extractWord(original, "(?:type|named?)\\s+(\\S+)");
                if (queueType != null) {
                    try {
                        return plan(List.of(Map.of("method", "POST", "path", "/api/function-queues",
                                "body", mapper.writeValueAsString(Map.of("functionType", queueType.toUpperCase(), "displayName", queueType, "category", "CUSTOM", "retryCount", 1, "timeoutSeconds", 60)),
                                "description", "Create queue: " + queueType)));
                    } catch (Exception e) { /* fall through */ }
                }
            }
        }

        // ── Key operations ──
        if (lower.contains("key") || lower.contains("certificate")) {
            if (lower.contains("list")) return cmd("keys list");
            if (lower.contains("generate") || lower.contains("create")) {
                String keyType = lower.contains("pgp") ? "pgp" : lower.contains("aes") ? "aes" :
                        lower.contains("ssh") ? "ssh-host" : lower.contains("tls") ? "tls" : "aes";
                String alias = extractWord(original, "(?:alias|named?|called)\\s+(\\S+)");
                if (alias == null) alias = keyType + "-" + System.currentTimeMillis() % 10000;
                try {
                    return plan(List.of(Map.of("method", "POST", "path", "/api/v1/keys/generate/" + keyType,
                            "body", mapper.writeValueAsString(Map.of("alias", alias, "ownerService", "platform")),
                            "description", "Generate " + keyType + " key: " + alias)));
                } catch (Exception e) { /* fall through */ }
            }
        }

        // ── Listener operations ──
        if (lower.contains("listener")) return cmd("listeners list");

        // ── Simple lookups ──
        if (lower.contains("status") || lower.contains("overview")) return cmd("status");
        if (lower.contains("account") && lower.contains("list")) return cmd("accounts list");
        if (lower.contains("user") && lower.contains("list")) return cmd("users list");
        if (lower.contains("flow") && lower.contains("list")) return cmd("flows list");
        if (lower.contains("sentinel") || lower.contains("finding")) return cmd("sentinel findings");
        if (lower.contains("fail")) return cmd("search failed");
        if (lower.contains("recent") || lower.contains("latest")) return cmd("search recent 10");
        if (lower.contains("service") || lower.contains("health")) return cmd("services");
        if (lower.contains("log")) return cmd("logs recent 20");
        if (lower.contains("help")) return cmd("help");

        return NlpResult.builder().understood(false)
                .response("Try: 'create 5 sftp accounts', 'list flows', 'diagnose TRZ-X7K9M2', 'create a flow for acme with screen and mailbox'")
                .build();
    }

    private NlpResult cmd(String command) {
        return NlpResult.builder().understood(true).command(command).response("→ " + command).build();
    }

    private NlpResult confirm(String command, String warning) {
        return NlpResult.builder().understood(true).command(command).requiresConfirmation(true)
                .response("⚠ " + warning + "\nType 'yes' to confirm.").build();
    }

    private NlpResult plan(List<Map<String, String>> steps) {
        try {
            return NlpResult.builder().understood(true)
                    .command(mapper.writeValueAsString(steps))
                    .response("Executing " + steps.size() + " step(s)").build();
        } catch (Exception e) {
            return NlpResult.builder().understood(false).response("Failed to build plan").build();
        }
    }

    /** Extract a word from input using regex group 1 */
    private String extractWord(String input, String regex) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(regex, java.util.regex.Pattern.CASE_INSENSITIVE).matcher(input);
        return m.find() ? m.group(1) : null;
    }

    /** Build bulk creation plan — N accounts or flows */
    private List<Map<String, String>> buildBulkPlan(String type, String protocol, int count, String context) throws Exception {
        var plan = new java.util.ArrayList<Map<String, String>>();
        for (int i = 1; i <= count; i++) {
            if ("account".equals(type)) {
                String user = "test-" + String.format("%03d", i);
                plan.add(Map.of("method", "POST", "path", "/api/accounts",
                        "body", mapper.writeValueAsString(Map.of("protocol", protocol, "username", user, "password", "TestPass@" + i + "!")),
                        "description", "Create " + protocol + " account: " + user));
            } else if ("flow".equals(type)) {
                String name = "test-flow-" + String.format("%03d", i);
                plan.add(Map.of("method", "POST", "path", "/api/flows/quick",
                        "body", mapper.writeValueAsString(Map.of("name", name, "filenamePattern", ".*", "actions", List.of("SCREEN"), "priority", 50 + i)),
                        "description", "Create flow: " + name));
            } else if ("key".equals(type)) {
                String alias = "test-key-" + String.format("%03d", i);
                plan.add(Map.of("method", "POST", "path", "/api/v1/keys/generate/aes",
                        "body", mapper.writeValueAsString(Map.of("alias", alias, "ownerService", "platform")),
                        "description", "Generate AES key: " + alias));
            }
        }
        return plan;
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
