package com.filetransfer.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Orchestrates multi-step admin operations from a single natural language request.
 *
 * <p>The admin says ONE thing. The orchestrator:
 * <ol>
 *   <li>Receives an execution plan from NLP (list of API calls with dependency markers)
 *   <li>Executes each step in order, capturing outputs
 *   <li>Resolves references between steps (step 2 uses step 1's output ID)
 *   <li>Returns a unified result showing what was done
 * </ol>
 *
 * <p>Example: "onboard ACME Corp with SFTP, PGP keys, and an EDI processing flow"
 * <pre>
 * Plan:
 *   1. POST /api/partners { name: "ACME Corp" }           → captures: partnerId
 *   2. POST /api/accounts { username: "acme-sftp", partnerId: ${1.id} } → captures: accountId
 *   3. POST /api/v1/keys/generate/pgp { alias: "acme-pgp" }  → captures: keyAlias
 *   4. POST /api/flows/quick { source: "acme-sftp", pattern: ".*\\.edi",
 *          actions: ["SCREEN","DECRYPT_PGP","CONVERT_EDI","MAILBOX"],
 *          encryptionKeyAlias: "${3.alias}", deliverTo: "internal" }
 * </pre>
 *
 * <p>Each step's output is available to subsequent steps via ${N.field} references.
 * If any step fails, execution stops and the error is reported with context.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CommandOrchestrator {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    private static final Pattern REF_PATTERN = Pattern.compile("\\$\\{(\\d+)\\.([a-zA-Z0-9_.]+)\\}");

    /**
     * Execute a plan — ordered list of API operations with dependency resolution.
     *
     * @param plan   list of steps, each with: method, path, body (may contain ${N.field} refs)
     * @param baseUrl the base URL for API calls (e.g., https://onboarding-api:9080)
     * @param authHeader JWT Authorization header value
     * @return execution result with per-step outcomes
     */
    @SuppressWarnings("unchecked")
    public ExecutionResult execute(List<PlanStep> plan, String baseUrl, String authHeader) {
        List<StepResult> results = new ArrayList<>();
        Map<Integer, Map<String, Object>> stepOutputs = new HashMap<>();

        for (int i = 0; i < plan.size(); i++) {
            PlanStep step = plan.get(i);
            String resolvedBody = resolveReferences(step.body(), stepOutputs);
            String resolvedPath = resolveReferences(step.path(), stepOutputs);

            log.info("Orchestrator step {}/{}: {} {} ({})",
                    i + 1, plan.size(), step.method(), resolvedPath, step.description());

            try {
                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);
                if (authHeader != null) headers.set("Authorization", authHeader);

                HttpEntity<String> entity = resolvedBody != null && !resolvedBody.isBlank()
                        ? new HttpEntity<>(resolvedBody, headers)
                        : new HttpEntity<>(headers);

                ResponseEntity<String> response = restTemplate.exchange(
                        baseUrl + resolvedPath,
                        HttpMethod.valueOf(step.method().toUpperCase()),
                        entity, String.class);

                // Parse response and store for reference by later steps
                Map<String, Object> output = new LinkedHashMap<>();
                output.put("status", response.getStatusCode().value());
                try {
                    var parsed = objectMapper.readValue(response.getBody(), Map.class);
                    output.putAll(parsed);
                } catch (Exception e) {
                    output.put("body", response.getBody());
                }
                stepOutputs.put(i + 1, output); // 1-indexed for ${1.field} refs

                results.add(new StepResult(i + 1, step.description(), true,
                        response.getStatusCode().value(), summarize(output)));
                log.info("Orchestrator step {}: OK ({})", i + 1, response.getStatusCode());

            } catch (Exception e) {
                String error = e.getMessage();
                try {
                    if (e instanceof org.springframework.web.client.HttpStatusCodeException hsce) {
                        error = hsce.getResponseBodyAsString();
                    }
                } catch (Exception ignore) {}

                results.add(new StepResult(i + 1, step.description(), false, 500, error));
                log.error("Orchestrator step {}: FAILED — {}", i + 1, error);

                // Stop on failure — don't execute dependent steps
                return new ExecutionResult(false, results,
                        "Step " + (i + 1) + " failed: " + step.description() + ". Remaining steps skipped.");
            }
        }

        return new ExecutionResult(true, results, plan.size() + " operations completed successfully.");
    }

    /** Resolve ${N.field} references in a string using previous step outputs */
    private String resolveReferences(String template, Map<Integer, Map<String, Object>> outputs) {
        if (template == null) return null;
        Matcher m = REF_PATTERN.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            int stepNum = Integer.parseInt(m.group(1));
            String field = m.group(2);
            Map<String, Object> stepOutput = outputs.get(stepNum);
            String value = stepOutput != null && stepOutput.containsKey(field)
                    ? stepOutput.get(field).toString() : m.group(0);
            m.appendReplacement(sb, Matcher.quoteReplacement(value));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** Summarize an output map for display */
    private String summarize(Map<String, Object> output) {
        // Pick key fields for human-readable summary
        StringBuilder sb = new StringBuilder();
        for (String key : List.of("id", "name", "username", "alias", "trackId", "status", "message")) {
            if (output.containsKey(key)) {
                sb.append(key).append("=").append(output.get(key)).append(" ");
            }
        }
        return sb.length() > 0 ? sb.toString().trim() : output.toString();
    }

    /** A single step in the execution plan */
    public record PlanStep(
            String method,      // GET, POST, PUT, DELETE, PATCH
            String path,        // /api/partners, /api/flows/quick — may contain ${N.field}
            String body,        // JSON body — may contain ${N.field} references
            String description  // human-readable: "Create partner ACME Corp"
    ) {}

    /** Result of one executed step */
    public record StepResult(
            int step,
            String description,
            boolean success,
            int httpStatus,
            String output
    ) {}

    /** Overall execution result */
    public record ExecutionResult(
            boolean success,
            List<StepResult> steps,
            String summary
    ) {}
}
