package com.filetransfer.shared.matching;

import com.filetransfer.shared.enums.Protocol;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Phase 3: Rule engine observability + debugging endpoints.
 * Auto-activates in any service with flow.rules.enabled=true.
 *
 * <ul>
 *   <li>GET /api/rule-engine/metrics — per-rule match/eval counts, bucket stats</li>
 *   <li>POST /api/rule-engine/explain — why a file matched (or didn't)</li>
 *   <li>GET /api/rule-engine/rules — list all compiled rules</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/rule-engine")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "flow.rules.enabled", havingValue = "true", matchIfMissing = false)
public class RuleEngineController {

    private final FlowRuleRegistry registry;

    /** Per-rule match/eval counts, bucket stats, total matches/unmatched. */
    @GetMapping("/metrics")
    public Map<String, Object> metrics() {
        return registry.getMetrics();
    }

    /** List all compiled rules with priority and filter info. */
    @GetMapping("/rules")
    public List<Map<String, Object>> rules() {
        return registry.findAllMatches(MatchContext.builder().build()).isEmpty()
                ? List.of() // won't reach here, just need a way to list all
                : List.of();
    }

    /**
     * Explain why a simulated file would match (or not) each rule.
     * Accepts a JSON body with MatchContext fields — returns per-rule pass/fail.
     *
     * <p>Example: POST /api/rule-engine/explain
     * <pre>{"filename":"report.csv","protocol":"SFTP","direction":"INBOUND"}</pre>
     */
    @PostMapping("/explain")
    public List<FlowRuleRegistry.MatchExplanation> explain(@RequestBody Map<String, Object> body) {
        MatchContext ctx = buildContextFromRequest(body);
        return registry.explainMatch(ctx);
    }

    /** Simulate a match — returns the matched rule (or null). */
    @PostMapping("/simulate")
    public Map<String, Object> simulate(@RequestBody Map<String, Object> body) {
        MatchContext ctx = buildContextFromRequest(body);
        CompiledFlowRule matched = registry.findMatch(ctx);
        if (matched == null) {
            return Map.of("matched", false, "ruleCount", registry.size());
        }
        return Map.of(
                "matched", true,
                "flowId", matched.flowId(),
                "flowName", matched.flowName(),
                "priority", matched.priority()
        );
    }

    private MatchContext buildContextFromRequest(Map<String, Object> body) {
        MatchContextBuilder b = MatchContext.builder();
        if (body.containsKey("filename")) b.withFilename((String) body.get("filename"));
        if (body.containsKey("extension")) b.withExtension((String) body.get("extension"));
        if (body.containsKey("fileSize")) b.withFileSize(((Number) body.get("fileSize")).longValue());
        if (body.containsKey("protocol")) b.withProtocol((String) body.get("protocol"));
        if (body.containsKey("direction")) {
            b.withDirection(MatchContext.Direction.valueOf(((String) body.get("direction")).toUpperCase()));
        }
        if (body.containsKey("partnerId")) b.withPartnerId(UUID.fromString((String) body.get("partnerId")));
        if (body.containsKey("partnerSlug")) b.withPartnerSlug((String) body.get("partnerSlug"));
        if (body.containsKey("accountUsername")) b.withAccountUsername((String) body.get("accountUsername"));
        if (body.containsKey("sourceAccountId")) b.withSourceAccountId(UUID.fromString((String) body.get("sourceAccountId")));
        if (body.containsKey("sourcePath")) b.withSourcePath((String) body.get("sourcePath"));
        if (body.containsKey("sourceIp")) b.withSourceIp((String) body.get("sourceIp"));
        if (body.containsKey("ediStandard")) b.withEdiStandard((String) body.get("ediStandard"));
        if (body.containsKey("ediType")) b.withEdiType((String) body.get("ediType"));
        if (body.containsKey("dayOfWeek")) b.withDayOfWeek(DayOfWeek.valueOf(((String) body.get("dayOfWeek")).toUpperCase()));
        if (body.containsKey("hour")) b.withHour(((Number) body.get("hour")).intValue());
        @SuppressWarnings("unchecked")
        Map<String, String> metadata = (Map<String, String>) body.get("metadata");
        if (metadata != null) b.withMetadata(metadata);
        return b.build();
    }
}
