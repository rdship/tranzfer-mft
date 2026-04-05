package com.filetransfer.ai.service.response;

import com.filetransfer.ai.service.proxy.IpReputationService;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Automated response / playbook engine for the TranzFer AI security pipeline.
 *
 * <p>When threat detections fire (brute force, port scan, data exfiltration, DGA,
 * C2 beaconing, kill chain progression, etc.), the engine matches the alert type
 * and severity against registered playbooks and executes the corresponding
 * response steps automatically.</p>
 *
 * <h3>Safety guarantees</h3>
 * <ul>
 *   <li>Rate limiting prevents runaway automation (max blocks/alerts per hour).</li>
 *   <li>Each step can be marked {@code autoExecute = false} to require approval.</li>
 *   <li>Full audit trail of every execution is retained for forensics.</li>
 *   <li>Playbooks can be enabled/disabled at runtime without restart.</li>
 * </ul>
 *
 * @see IncidentManager
 */
@Service
@Slf4j
public class PlaybookEngine {

    private final IpReputationService ipReputationService;
    private final IncidentManager incidentManager;

    /** Playbook definitions keyed by playbook ID, loaded at startup. */
    private final Map<String, Playbook> playbooks = new ConcurrentHashMap<>();

    /** Execution audit trail (bounded). */
    private final List<PlaybookExecution> executionHistory = new CopyOnWriteArrayList<>();
    private static final int MAX_HISTORY = 500;

    /** Rate limiting: prevent runaway automation. */
    private final Map<String, AtomicInteger> actionCounts = new ConcurrentHashMap<>();
    private static final int MAX_BLOCKS_PER_HOUR = 100;
    private static final int MAX_ALERTS_PER_HOUR = 200;
    private static final int MAX_ESCALATIONS_PER_HOUR = 50;

    public PlaybookEngine(IpReputationService ipReputationService,
                          IncidentManager incidentManager) {
        this.ipReputationService = ipReputationService;
        this.incidentManager = incidentManager;
    }

    // ══════════════════════════════════════════════════════════════════
    //  Inner model classes
    // ══════════════════════════════════════════════════════════════════

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Playbook {
        private String id;
        private String name;
        private String description;
        private String triggerAlertType;   // BRUTE_FORCE, PORT_SCAN, DATA_EXFIL, DGA, C2_BEACONING, KILL_CHAIN, etc.
        private int minSeverity;           // 0-100 minimum risk score to trigger
        private double minConfidence;      // 0.0-1.0
        private boolean enabled;
        private List<PlaybookStep> steps;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PlaybookStep {
        private String action;         // BLOCK_IP, LOG_ALERT, NOTIFY, THROTTLE, QUARANTINE_FILE, ESCALATE, ENRICH
        private Map<String, String> params;
        private boolean autoExecute;   // true = no approval needed
        private int delaySeconds;      // wait before executing
        private String condition;      // optional condition expression (e.g., "severity > 70")
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PlaybookExecution {
        private String executionId;
        private String playbookId;
        private String triggeredBy;    // alert ID or detection event
        private Instant startedAt;
        private Instant completedAt;
        private String status;         // RUNNING, COMPLETED, FAILED, RATE_LIMITED
        private List<StepResult> stepResults;
        private String triggerContext;  // JSON-style summary of detection details
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StepResult {
        private String action;
        private String status;     // EXECUTED, SKIPPED, FAILED, RATE_LIMITED
        private String result;
        private Instant executedAt;
    }

    // ══════════════════════════════════════════════════════════════════
    //  Playbook registration (loaded at startup)
    // ══════════════════════════════════════════════════════════════════

    @PostConstruct
    public void loadDefaultPlaybooks() {
        // 1. BRUTE_FORCE_RESPONSE
        registerPlaybook(Playbook.builder()
                .id("BRUTE_FORCE_RESPONSE")
                .name("Brute Force Response")
                .description("Responds to brute force login attempts by blocking the source IP, logging an alert, and notifying the security team.")
                .triggerAlertType("BRUTE_FORCE")
                .minSeverity(60)
                .minConfidence(0.7)
                .enabled(true)
                .steps(List.of(
                        step("BLOCK_IP", Map.of("duration", "3600", "reason", "brute_force_detected"), true, 0, null),
                        step("LOG_ALERT", Map.of("level", "HIGH", "category", "AUTH_ATTACK"), true, 0, null),
                        step("NOTIFY", Map.of("channel", "security-team", "urgency", "high"), true, 0, null)
                ))
                .build());

        // 2. PORT_SCAN_RESPONSE
        registerPlaybook(Playbook.builder()
                .id("PORT_SCAN_RESPONSE")
                .name("Port Scan Response")
                .description("Responds to port scanning activity. Throttles at moderate severity, blocks at high severity.")
                .triggerAlertType("PORT_SCAN")
                .minSeverity(40)
                .minConfidence(0.6)
                .enabled(true)
                .steps(List.of(
                        step("THROTTLE", Map.of("rate", "10", "windowSeconds", "60"), true, 0, null),
                        step("LOG_ALERT", Map.of("level", "MEDIUM", "category", "RECON"), true, 0, null),
                        step("BLOCK_IP", Map.of("duration", "7200", "reason", "port_scan_high_severity"), true, 0, "severity > 70")
                ))
                .build());

        // 3. DATA_EXFIL_RESPONSE
        registerPlaybook(Playbook.builder()
                .id("DATA_EXFIL_RESPONSE")
                .name("Data Exfiltration Response")
                .description("Responds to detected data exfiltration. Immediately blocks, creates critical alert, notifies, and escalates.")
                .triggerAlertType("DATA_EXFIL")
                .minSeverity(70)
                .minConfidence(0.75)
                .enabled(true)
                .steps(List.of(
                        step("BLOCK_IP", Map.of("duration", "86400", "reason", "data_exfiltration"), true, 0, null),
                        step("LOG_ALERT", Map.of("level", "CRITICAL", "category", "DATA_EXFIL"), true, 0, null),
                        step("NOTIFY", Map.of("channel", "security-team", "urgency", "critical"), true, 0, null),
                        step("ESCALATE", Map.of("priority", "P1", "recommendation", "Investigate source host for compromised credentials and review recent file transfers"), true, 5, null)
                ))
                .build());

        // 4. C2_BEACONING_RESPONSE
        registerPlaybook(Playbook.builder()
                .id("C2_BEACONING_RESPONSE")
                .name("C2 Beaconing Response")
                .description("Responds to detected command-and-control beaconing. Blocks destination, logs, and initiates source host investigation.")
                .triggerAlertType("C2_BEACONING")
                .minSeverity(60)
                .minConfidence(0.7)
                .enabled(true)
                .steps(List.of(
                        step("BLOCK_IP", Map.of("target", "destination", "duration", "86400", "reason", "c2_beaconing"), true, 0, null),
                        step("LOG_ALERT", Map.of("level", "HIGH", "category", "C2_COMMUNICATION"), true, 0, null),
                        step("ENRICH", Map.of("source", "threat_intel", "type", "c2_infrastructure"), true, 0, null),
                        step("ESCALATE", Map.of("priority", "P2", "recommendation", "Investigate source host for malware and isolate if confirmed"), true, 10, null)
                ))
                .build());

        // 5. KILL_CHAIN_RESPONSE
        registerPlaybook(Playbook.builder()
                .id("KILL_CHAIN_RESPONSE")
                .name("Kill Chain Progression Response")
                .description("Responds to multi-stage attack chain detection (3+ MITRE ATT&CK stages). Maximum severity response.")
                .triggerAlertType("KILL_CHAIN")
                .minSeverity(80)
                .minConfidence(0.8)
                .enabled(true)
                .steps(List.of(
                        step("BLOCK_IP", Map.of("duration", "172800", "reason", "kill_chain_progression"), true, 0, null),
                        step("LOG_ALERT", Map.of("level", "CRITICAL", "category", "KILL_CHAIN"), true, 0, null),
                        step("NOTIFY", Map.of("channel", "security-team", "urgency", "critical"), true, 0, null),
                        step("ESCALATE", Map.of("priority", "P1", "recommendation", "Active multi-stage attack — immediate containment required"), true, 0, null),
                        step("QUARANTINE_FILE", Map.of("scope", "source_ip_transfers"), true, 5, null)
                ))
                .build());

        // 6. DGA_RESPONSE
        registerPlaybook(Playbook.builder()
                .id("DGA_RESPONSE")
                .name("DGA Domain Response")
                .description("Responds to domain generation algorithm (DGA) domain detection. Blocks resolution and investigates the source host.")
                .triggerAlertType("DGA")
                .minSeverity(50)
                .minConfidence(0.65)
                .enabled(true)
                .steps(List.of(
                        step("BLOCK_IP", Map.of("target", "domain", "reason", "dga_domain_detected"), true, 0, null),
                        step("LOG_ALERT", Map.of("level", "HIGH", "category", "DGA_DETECTION"), true, 0, null),
                        step("ENRICH", Map.of("source", "threat_intel", "type", "dga_analysis"), true, 0, null),
                        step("ESCALATE", Map.of("priority", "P2", "recommendation", "Investigate source host for malware generating DGA domains"), true, 15, null)
                ))
                .build());

        // 7. DDOS_RESPONSE
        registerPlaybook(Playbook.builder()
                .id("DDOS_RESPONSE")
                .name("DDoS Response")
                .description("Responds to DDoS threshold exceedance. Enables aggressive rate limiting, alerts ops, and escalates.")
                .triggerAlertType("DDOS")
                .minSeverity(70)
                .minConfidence(0.6)
                .enabled(true)
                .steps(List.of(
                        step("THROTTLE", Map.of("rate", "1", "windowSeconds", "60", "scope", "global"), true, 0, null),
                        step("LOG_ALERT", Map.of("level", "CRITICAL", "category", "DDOS"), true, 0, null),
                        step("NOTIFY", Map.of("channel", "ops-team", "urgency", "critical"), true, 0, null),
                        step("ESCALATE", Map.of("priority", "P1", "recommendation", "Enable upstream DDoS mitigation and consider blackholing affected prefixes"), true, 0, null)
                ))
                .build());

        // 8. THREAT_INTEL_MATCH_RESPONSE
        registerPlaybook(Playbook.builder()
                .id("THREAT_INTEL_MATCH_RESPONSE")
                .name("Threat Intel Match Response")
                .description("Responds when an IP or domain matches a known threat intelligence feed entry. Blocks, logs, and enriches with feed context.")
                .triggerAlertType("THREAT_INTEL_MATCH")
                .minSeverity(50)
                .minConfidence(0.7)
                .enabled(true)
                .steps(List.of(
                        step("BLOCK_IP", Map.of("duration", "43200", "reason", "threat_intel_match"), true, 0, null),
                        step("LOG_ALERT", Map.of("level", "HIGH", "category", "THREAT_INTEL"), true, 0, null),
                        step("ENRICH", Map.of("source", "threat_feed", "type", "ioc_context"), true, 0, null)
                ))
                .build());

        log.info("PlaybookEngine initialized with {} default playbooks: {}",
                playbooks.size(), playbooks.keySet());
    }

    // ══════════════════════════════════════════════════════════════════
    //  Playbook Execution
    // ══════════════════════════════════════════════════════════════════

    /**
     * Execute a specific playbook by ID.
     *
     * @param playbookId    the playbook identifier
     * @param triggerSource the alert ID or detection event that triggered execution
     * @param context       detection context (must contain keys like "ip", "severity", "confidence", etc.)
     * @return the execution record with step results
     */
    public PlaybookExecution execute(String playbookId, String triggerSource, Map<String, Object> context) {
        Playbook playbook = playbooks.get(playbookId);
        if (playbook == null) {
            log.warn("Playbook not found: {}", playbookId);
            return PlaybookExecution.builder()
                    .executionId(UUID.randomUUID().toString())
                    .playbookId(playbookId)
                    .triggeredBy(triggerSource)
                    .startedAt(Instant.now())
                    .completedAt(Instant.now())
                    .status("FAILED")
                    .stepResults(List.of())
                    .triggerContext("Playbook not found: " + playbookId)
                    .build();
        }

        if (!playbook.isEnabled()) {
            log.info("Playbook {} is disabled, skipping execution triggered by {}", playbookId, triggerSource);
            return PlaybookExecution.builder()
                    .executionId(UUID.randomUUID().toString())
                    .playbookId(playbookId)
                    .triggeredBy(triggerSource)
                    .startedAt(Instant.now())
                    .completedAt(Instant.now())
                    .status("SKIPPED")
                    .stepResults(List.of())
                    .triggerContext("Playbook disabled")
                    .build();
        }

        String executionId = UUID.randomUUID().toString();
        Instant startedAt = Instant.now();
        List<StepResult> stepResults = new ArrayList<>();
        String overallStatus = "COMPLETED";

        log.info("Executing playbook {} (trigger: {}, context keys: {})",
                playbookId, triggerSource, context != null ? context.keySet() : "null");

        Map<String, Object> safeContext = context != null ? new HashMap<>(context) : new HashMap<>();

        for (PlaybookStep step : playbook.getSteps()) {
            try {
                // Evaluate condition if present
                if (step.getCondition() != null && !step.getCondition().isBlank()) {
                    if (!evaluateCondition(step.getCondition(), safeContext)) {
                        stepResults.add(StepResult.builder()
                                .action(step.getAction())
                                .status("SKIPPED")
                                .result("Condition not met: " + step.getCondition())
                                .executedAt(Instant.now())
                                .build());
                        continue;
                    }
                }

                // Check auto-execute flag
                if (!step.isAutoExecute()) {
                    stepResults.add(StepResult.builder()
                            .action(step.getAction())
                            .status("SKIPPED")
                            .result("Manual approval required (autoExecute=false)")
                            .executedAt(Instant.now())
                            .build());
                    continue;
                }

                // Apply delay if configured
                if (step.getDelaySeconds() > 0) {
                    try {
                        Thread.sleep(Math.min(step.getDelaySeconds() * 1000L, 30_000L)); // cap at 30s
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        stepResults.add(StepResult.builder()
                                .action(step.getAction())
                                .status("FAILED")
                                .result("Interrupted during delay")
                                .executedAt(Instant.now())
                                .build());
                        overallStatus = "FAILED";
                        break;
                    }
                }

                StepResult result = executeStep(step, safeContext);
                stepResults.add(result);

                if ("FAILED".equals(result.getStatus())) {
                    overallStatus = "FAILED";
                    log.warn("Playbook {} step {} failed: {}", playbookId, step.getAction(), result.getResult());
                } else if ("RATE_LIMITED".equals(result.getStatus())) {
                    overallStatus = "RATE_LIMITED";
                    log.warn("Playbook {} step {} rate-limited", playbookId, step.getAction());
                }

            } catch (Exception e) {
                log.error("Unexpected error executing step {} in playbook {}: {}",
                        step.getAction(), playbookId, e.getMessage(), e);
                stepResults.add(StepResult.builder()
                        .action(step.getAction())
                        .status("FAILED")
                        .result("Exception: " + e.getMessage())
                        .executedAt(Instant.now())
                        .build());
                overallStatus = "FAILED";
            }
        }

        PlaybookExecution execution = PlaybookExecution.builder()
                .executionId(executionId)
                .playbookId(playbookId)
                .triggeredBy(triggerSource)
                .startedAt(startedAt)
                .completedAt(Instant.now())
                .status(overallStatus)
                .stepResults(stepResults)
                .triggerContext(buildTriggerContextSummary(safeContext))
                .build();

        recordExecution(execution);

        log.info("Playbook {} execution {} completed with status {} ({} steps, {}ms)",
                playbookId, executionId, overallStatus, stepResults.size(),
                Duration.between(startedAt, Instant.now()).toMillis());

        return execution;
    }

    /**
     * Trigger all matching playbooks for a detection event.
     *
     * <p>Finds playbooks whose {@code triggerAlertType}, {@code minSeverity}, and
     * {@code minConfidence} match the supplied parameters, then executes each one.</p>
     *
     * @param alertType  the alert type (BRUTE_FORCE, PORT_SCAN, DATA_EXFIL, etc.)
     * @param severity   the risk score 0-100
     * @param confidence the confidence 0.0-1.0
     * @param context    detection context map
     * @return list of playbook executions triggered
     */
    public List<PlaybookExecution> triggerForDetection(String alertType, int severity, double confidence,
                                                       Map<String, Object> context) {
        if (alertType == null || alertType.isBlank()) {
            log.warn("triggerForDetection called with null/blank alertType");
            return List.of();
        }

        Map<String, Object> enrichedContext = context != null ? new HashMap<>(context) : new HashMap<>();
        enrichedContext.put("alertType", alertType);
        enrichedContext.put("severity", severity);
        enrichedContext.put("confidence", confidence);

        List<Playbook> matchingPlaybooks = playbooks.values().stream()
                .filter(Playbook::isEnabled)
                .filter(p -> alertType.equalsIgnoreCase(p.getTriggerAlertType()))
                .filter(p -> severity >= p.getMinSeverity())
                .filter(p -> confidence >= p.getMinConfidence())
                .toList();

        if (matchingPlaybooks.isEmpty()) {
            log.debug("No playbooks matched for alertType={}, severity={}, confidence={}",
                    alertType, severity, confidence);
            return List.of();
        }

        log.info("Detection event matched {} playbook(s) for alertType={}, severity={}, confidence={}",
                matchingPlaybooks.size(), alertType, severity, confidence);

        List<PlaybookExecution> executions = new ArrayList<>();
        for (Playbook playbook : matchingPlaybooks) {
            String triggerSource = String.format("detection:%s:sev%d:conf%.2f:%s",
                    alertType, severity, confidence, UUID.randomUUID().toString().substring(0, 8));
            PlaybookExecution execution = execute(playbook.getId(), triggerSource, enrichedContext);
            executions.add(execution);
        }

        return executions;
    }

    // ══════════════════════════════════════════════════════════════════
    //  Step Execution Dispatch
    // ══════════════════════════════════════════════════════════════════

    private StepResult executeStep(PlaybookStep step, Map<String, Object> context) {
        return switch (step.getAction()) {
            case "BLOCK_IP" -> executeBlockIp(step, context);
            case "THROTTLE" -> executeThrottle(step, context);
            case "LOG_ALERT" -> executeLogAlert(step, context);
            case "NOTIFY" -> executeNotify(step, context);
            case "ESCALATE" -> executeEscalate(step, context);
            case "QUARANTINE_FILE" -> executeQuarantine(step, context);
            case "ENRICH" -> executeEnrich(step, context);
            default -> StepResult.builder()
                    .action(step.getAction())
                    .status("FAILED")
                    .result("Unknown action: " + step.getAction())
                    .executedAt(Instant.now())
                    .build();
        };
    }

    // ── BLOCK_IP ─────────────────────────────────────────────────────

    private StepResult executeBlockIp(PlaybookStep step, Map<String, Object> context) {
        String actionName = "BLOCK_IP";

        if (!checkRateLimit("BLOCK", MAX_BLOCKS_PER_HOUR)) {
            return rateLimitedResult(actionName);
        }

        String ip = resolveIp(step, context);
        if (ip == null || ip.isBlank()) {
            return StepResult.builder()
                    .action(actionName)
                    .status("FAILED")
                    .result("No IP address found in context or step params")
                    .executedAt(Instant.now())
                    .build();
        }

        // Check if already blocked to avoid redundant operations
        if (ipReputationService.isBlocked(ip)) {
            return StepResult.builder()
                    .action(actionName)
                    .status("EXECUTED")
                    .result("IP " + ip + " already blocked")
                    .executedAt(Instant.now())
                    .build();
        }

        String reason = step.getParams() != null
                ? step.getParams().getOrDefault("reason", "playbook_response")
                : "playbook_response";

        try {
            ipReputationService.blockIp(ip, "playbook:" + reason);
            incrementRateCounter("BLOCK");

            log.info("BLOCK_IP executed: ip={}, reason={}", ip, reason);
            return StepResult.builder()
                    .action(actionName)
                    .status("EXECUTED")
                    .result("Blocked IP " + ip + " (reason: " + reason + ")")
                    .executedAt(Instant.now())
                    .build();
        } catch (Exception e) {
            log.error("BLOCK_IP failed for ip={}: {}", ip, e.getMessage(), e);
            return StepResult.builder()
                    .action(actionName)
                    .status("FAILED")
                    .result("Failed to block IP " + ip + ": " + e.getMessage())
                    .executedAt(Instant.now())
                    .build();
        }
    }

    // ── THROTTLE ─────────────────────────────────────────────────────

    private StepResult executeThrottle(PlaybookStep step, Map<String, Object> context) {
        String actionName = "THROTTLE";

        String ip = resolveIp(step, context);
        String rate = step.getParams() != null ? step.getParams().getOrDefault("rate", "10") : "10";
        String window = step.getParams() != null ? step.getParams().getOrDefault("windowSeconds", "60") : "60";
        String scope = step.getParams() != null ? step.getParams().getOrDefault("scope", "ip") : "ip";

        if (ip == null && "ip".equals(scope)) {
            return StepResult.builder()
                    .action(actionName)
                    .status("FAILED")
                    .result("No IP address found for throttle action")
                    .executedAt(Instant.now())
                    .build();
        }

        try {
            // Apply throttle via reputation score penalty (proportional to rate restriction)
            if (ip != null) {
                IpReputationService.IpReputation rep = ipReputationService.getOrCreate(ip);
                int rateInt = Integer.parseInt(rate);
                double penalty = Math.max(-30.0, -5.0 * (10.0 / Math.max(rateInt, 1)));
                rep.adjustScore(penalty);
                rep.addTag("throttled:rate_" + rate + "_window_" + window);
            }

            String target = "global".equals(scope) ? "global" : ("ip=" + ip);
            log.info("THROTTLE executed: target={}, rate={}/{}s", target, rate, window);
            return StepResult.builder()
                    .action(actionName)
                    .status("EXECUTED")
                    .result("Throttle applied: " + target + " rate=" + rate + "/" + window + "s")
                    .executedAt(Instant.now())
                    .build();
        } catch (Exception e) {
            log.error("THROTTLE failed: {}", e.getMessage(), e);
            return StepResult.builder()
                    .action(actionName)
                    .status("FAILED")
                    .result("Throttle failed: " + e.getMessage())
                    .executedAt(Instant.now())
                    .build();
        }
    }

    // ── LOG_ALERT ────────────────────────────────────────────────────

    private StepResult executeLogAlert(PlaybookStep step, Map<String, Object> context) {
        String actionName = "LOG_ALERT";

        if (!checkRateLimit("ALERT", MAX_ALERTS_PER_HOUR)) {
            return rateLimitedResult(actionName);
        }

        String level = step.getParams() != null ? step.getParams().getOrDefault("level", "HIGH") : "HIGH";
        String category = step.getParams() != null ? step.getParams().getOrDefault("category", "SECURITY") : "SECURITY";
        String ip = resolveIp(step, context);
        String alertType = context.getOrDefault("alertType", "UNKNOWN").toString();
        int severity = extractInt(context, "severity", 0);

        String alertMessage = String.format("[%s] %s alert — category=%s, source_ip=%s, severity=%d, context=%s",
                level, alertType, category, ip != null ? ip : "unknown", severity, summarizeContext(context));

        switch (level.toUpperCase()) {
            case "CRITICAL" -> log.error("SECURITY ALERT: {}", alertMessage);
            case "HIGH" -> log.warn("SECURITY ALERT: {}", alertMessage);
            case "MEDIUM" -> log.info("SECURITY ALERT: {}", alertMessage);
            default -> log.info("SECURITY ALERT: {}", alertMessage);
        }

        incrementRateCounter("ALERT");

        return StepResult.builder()
                .action(actionName)
                .status("EXECUTED")
                .result("Alert logged: [" + level + "] " + category + " — " + alertType)
                .executedAt(Instant.now())
                .build();
    }

    // ── NOTIFY ───────────────────────────────────────────────────────

    private StepResult executeNotify(PlaybookStep step, Map<String, Object> context) {
        String actionName = "NOTIFY";

        if (!checkRateLimit("ALERT", MAX_ALERTS_PER_HOUR)) {
            return rateLimitedResult(actionName);
        }

        String channel = step.getParams() != null ? step.getParams().getOrDefault("channel", "security-team") : "security-team";
        String urgency = step.getParams() != null ? step.getParams().getOrDefault("urgency", "high") : "high";
        String alertType = context.getOrDefault("alertType", "UNKNOWN").toString();
        String ip = resolveIp(step, context);
        int severity = extractInt(context, "severity", 0);

        String notification = String.format(
                "NOTIFICATION [%s] to=%s: %s detected from %s (severity=%d). Immediate review recommended.",
                urgency.toUpperCase(), channel, alertType, ip != null ? ip : "unknown", severity);

        // Log the notification; in production this would dispatch to notification-service
        log.warn("NOTIFY: {}", notification);
        incrementRateCounter("ALERT");

        return StepResult.builder()
                .action(actionName)
                .status("EXECUTED")
                .result("Notification dispatched: channel=" + channel + ", urgency=" + urgency + ", type=" + alertType)
                .executedAt(Instant.now())
                .build();
    }

    // ── ESCALATE ─────────────────────────────────────────────────────

    private StepResult executeEscalate(PlaybookStep step, Map<String, Object> context) {
        String actionName = "ESCALATE";

        if (!checkRateLimit("ESCALATION", MAX_ESCALATIONS_PER_HOUR)) {
            return rateLimitedResult(actionName);
        }

        String priority = step.getParams() != null ? step.getParams().getOrDefault("priority", "P2") : "P2";
        String recommendation = step.getParams() != null
                ? step.getParams().getOrDefault("recommendation", "Manual investigation required")
                : "Manual investigation required";
        String alertType = context.getOrDefault("alertType", "UNKNOWN").toString();
        String ip = resolveIp(step, context);
        int severity = extractInt(context, "severity", 0);

        // Create or update an incident via the IncidentManager
        try {
            String incidentSeverity = "P1".equals(priority) ? "CRITICAL" : "HIGH";
            String title = String.format("%s detected — %s", alertType, ip != null ? "from " + ip : "source unknown");

            @SuppressWarnings("unchecked")
            List<String> techniques = context.containsKey("mitreTechniques")
                    ? (List<String>) context.get("mitreTechniques")
                    : List.of();

            List<String> alertIds = new ArrayList<>();
            if (context.containsKey("alertId")) {
                alertIds.add(context.get("alertId").toString());
            }

            incidentManager.createIncident(
                    title,
                    incidentSeverity,
                    alertIds,
                    techniques,
                    recommendation + " | Priority: " + priority + " | Severity: " + severity
            );

            incrementRateCounter("ESCALATION");

            log.warn("ESCALATE: priority={}, alertType={}, ip={}, recommendation={}",
                    priority, alertType, ip, recommendation);

            return StepResult.builder()
                    .action(actionName)
                    .status("EXECUTED")
                    .result("Escalated to incident: priority=" + priority + ", recommendation=" + recommendation)
                    .executedAt(Instant.now())
                    .build();
        } catch (Exception e) {
            log.error("ESCALATE failed: {}", e.getMessage(), e);
            return StepResult.builder()
                    .action(actionName)
                    .status("FAILED")
                    .result("Escalation failed: " + e.getMessage())
                    .executedAt(Instant.now())
                    .build();
        }
    }

    // ── QUARANTINE_FILE ──────────────────────────────────────────────

    private StepResult executeQuarantine(PlaybookStep step, Map<String, Object> context) {
        String actionName = "QUARANTINE_FILE";

        String scope = step.getParams() != null ? step.getParams().getOrDefault("scope", "file") : "file";
        String ip = resolveIp(step, context);
        String fileId = context.containsKey("fileId") ? context.get("fileId").toString() : null;
        String trackId = context.containsKey("trackId") ? context.get("trackId").toString() : null;

        StringBuilder result = new StringBuilder("Quarantine flagged: scope=").append(scope);

        if ("source_ip_transfers".equals(scope) && ip != null) {
            // Flag all recent transfers from this IP for quarantine review
            result.append(", ip=").append(ip).append(" — all pending transfers from this IP flagged for quarantine review");
            log.warn("QUARANTINE: Flagging all transfers from IP {} for quarantine review", ip);
        } else if (fileId != null) {
            result.append(", fileId=").append(fileId);
            log.warn("QUARANTINE: File {} flagged for quarantine", fileId);
        } else if (trackId != null) {
            result.append(", trackId=").append(trackId);
            log.warn("QUARANTINE: Transfer {} flagged for quarantine", trackId);
        } else {
            result.append(" — no specific target identified, quarantine request logged for manual review");
            log.warn("QUARANTINE: No specific target, flagged for manual review. Context: {}", summarizeContext(context));
        }

        return StepResult.builder()
                .action(actionName)
                .status("EXECUTED")
                .result(result.toString())
                .executedAt(Instant.now())
                .build();
    }

    // ── ENRICH ───────────────────────────────────────────────────────

    private StepResult executeEnrich(PlaybookStep step, Map<String, Object> context) {
        String actionName = "ENRICH";

        String enrichSource = step.getParams() != null ? step.getParams().getOrDefault("source", "threat_intel") : "threat_intel";
        String enrichType = step.getParams() != null ? step.getParams().getOrDefault("type", "general") : "general";
        String ip = resolveIp(step, context);

        StringBuilder enrichmentResult = new StringBuilder("Enrichment requested: source=")
                .append(enrichSource).append(", type=").append(enrichType);

        // Pull existing reputation data as enrichment
        if (ip != null) {
            try {
                IpReputationService.IpReputation rep = ipReputationService.getOrCreate(ip);
                Map<String, Object> enrichment = new LinkedHashMap<>();
                enrichment.put("reputationScore", rep.getScore());
                enrichment.put("totalConnections", rep.getTotalConnections());
                enrichment.put("failedConnections", rep.getFailedConnections());
                enrichment.put("tags", new ArrayList<>(rep.getTags()));
                enrichment.put("countries", new ArrayList<>(rep.getCountries()));
                enrichment.put("isBlocked", ipReputationService.isBlocked(ip));
                enrichment.put("firstSeen", rep.getFirstSeen().toString());
                enrichment.put("lastSeen", rep.getLastSeen().toString());

                // Store enrichment back into context for downstream steps
                context.put("enrichment_" + enrichSource, enrichment);

                enrichmentResult.append(", ip=").append(ip)
                        .append(", reputation=").append(Math.round(rep.getScore()))
                        .append(", tags=").append(rep.getTags());

                log.info("ENRICH: {} enrichment for ip={}: reputation={}, tags={}",
                        enrichType, ip, rep.getScore(), rep.getTags());
            } catch (Exception e) {
                log.error("ENRICH failed for ip={}: {}", ip, e.getMessage(), e);
                enrichmentResult.append(", error=").append(e.getMessage());
            }
        } else {
            enrichmentResult.append(" — no IP available, enrichment deferred to manual review");
        }

        return StepResult.builder()
                .action(actionName)
                .status("EXECUTED")
                .result(enrichmentResult.toString())
                .executedAt(Instant.now())
                .build();
    }

    // ══════════════════════════════════════════════════════════════════
    //  Management APIs
    // ══════════════════════════════════════════════════════════════════

    /** Returns all registered playbooks. */
    public List<Playbook> getAllPlaybooks() {
        return new ArrayList<>(playbooks.values());
    }

    /** Returns a specific playbook by ID. */
    public Optional<Playbook> getPlaybook(String id) {
        return Optional.ofNullable(playbooks.get(id));
    }

    /** Registers or updates a playbook. */
    public void registerPlaybook(Playbook playbook) {
        if (playbook == null || playbook.getId() == null) {
            throw new IllegalArgumentException("Playbook and playbook ID must not be null");
        }
        playbooks.put(playbook.getId(), playbook);
        log.info("Playbook registered: {} ({})", playbook.getId(), playbook.getName());
    }

    /** Updates an existing playbook (full replacement). */
    public void updatePlaybook(Playbook playbook) {
        if (playbook == null || playbook.getId() == null) {
            throw new IllegalArgumentException("Playbook and playbook ID must not be null");
        }
        if (!playbooks.containsKey(playbook.getId())) {
            throw new IllegalArgumentException("Playbook not found: " + playbook.getId());
        }
        playbooks.put(playbook.getId(), playbook);
        log.info("Playbook updated: {} ({})", playbook.getId(), playbook.getName());
    }

    /** Enables a playbook for automatic execution. */
    public void enablePlaybook(String id) {
        Playbook playbook = playbooks.get(id);
        if (playbook == null) {
            throw new IllegalArgumentException("Playbook not found: " + id);
        }
        playbook.setEnabled(true);
        log.info("Playbook enabled: {}", id);
    }

    /** Disables a playbook (stops automatic execution). */
    public void disablePlaybook(String id) {
        Playbook playbook = playbooks.get(id);
        if (playbook == null) {
            throw new IllegalArgumentException("Playbook not found: " + id);
        }
        playbook.setEnabled(false);
        log.info("Playbook disabled: {}", id);
    }

    /** Returns the most recent playbook executions. */
    public List<PlaybookExecution> getRecentExecutions(int limit) {
        int effectiveLimit = Math.max(1, Math.min(limit, executionHistory.size()));
        int fromIndex = Math.max(0, executionHistory.size() - effectiveLimit);
        List<PlaybookExecution> recent = new ArrayList<>(executionHistory.subList(fromIndex, executionHistory.size()));
        Collections.reverse(recent); // most recent first
        return recent;
    }

    /** Returns aggregated playbook statistics. */
    public Map<String, Object> getPlaybookStats() {
        Map<String, Object> stats = new LinkedHashMap<>();

        stats.put("totalPlaybooks", playbooks.size());
        stats.put("enabledPlaybooks", playbooks.values().stream().filter(Playbook::isEnabled).count());
        stats.put("totalExecutions", executionHistory.size());

        // Executions per playbook
        Map<String, Long> executionsPerPlaybook = executionHistory.stream()
                .collect(Collectors.groupingBy(PlaybookExecution::getPlaybookId, Collectors.counting()));
        stats.put("executionsPerPlaybook", executionsPerPlaybook);

        // Status breakdown
        Map<String, Long> statusCounts = executionHistory.stream()
                .collect(Collectors.groupingBy(PlaybookExecution::getStatus, Collectors.counting()));
        stats.put("statusBreakdown", statusCounts);

        // Success rate
        long completed = statusCounts.getOrDefault("COMPLETED", 0L);
        long total = executionHistory.size();
        stats.put("successRate", total > 0 ? String.format("%.1f%%", (completed * 100.0) / total) : "N/A");

        // Average execution time
        double avgMs = executionHistory.stream()
                .filter(e -> e.getStartedAt() != null && e.getCompletedAt() != null)
                .mapToLong(e -> Duration.between(e.getStartedAt(), e.getCompletedAt()).toMillis())
                .average()
                .orElse(0.0);
        stats.put("averageExecutionTimeMs", Math.round(avgMs));

        // Current rate limits
        Map<String, Integer> currentRates = new LinkedHashMap<>();
        actionCounts.forEach((key, count) -> currentRates.put(key, count.get()));
        stats.put("currentHourlyActionCounts", currentRates);
        stats.put("rateLimits", Map.of(
                "maxBlocksPerHour", MAX_BLOCKS_PER_HOUR,
                "maxAlertsPerHour", MAX_ALERTS_PER_HOUR,
                "maxEscalationsPerHour", MAX_ESCALATIONS_PER_HOUR
        ));

        return stats;
    }

    // ══════════════════════════════════════════════════════════════════
    //  Scheduled tasks
    // ══════════════════════════════════════════════════════════════════

    /** Resets hourly rate limit counters. Runs every hour. */
    @Scheduled(fixedDelay = 3600000)
    public void resetRateLimits() {
        if (!actionCounts.isEmpty()) {
            log.info("Resetting rate limit counters. Previous hour: {}", summarizeRateCounts());
            actionCounts.clear();
        }
    }

    // ══════════════════════════════════════════════════════════════════
    //  Private helpers
    // ══════════════════════════════════════════════════════════════════

    private static PlaybookStep step(String action, Map<String, String> params,
                                     boolean autoExecute, int delaySeconds, String condition) {
        return PlaybookStep.builder()
                .action(action)
                .params(params)
                .autoExecute(autoExecute)
                .delaySeconds(delaySeconds)
                .condition(condition)
                .build();
    }

    /**
     * Evaluates a simple condition expression against the context.
     * Supports: "severity > N", "severity >= N", "confidence > N", "confidence >= N".
     */
    private boolean evaluateCondition(String condition, Map<String, Object> context) {
        if (condition == null || condition.isBlank()) {
            return true;
        }

        try {
            String trimmed = condition.trim();

            // Parse "field operator value" patterns
            String[] operators = {">=", "<=", ">", "<", "=="};
            for (String op : operators) {
                int opIndex = trimmed.indexOf(op);
                if (opIndex > 0) {
                    String field = trimmed.substring(0, opIndex).trim();
                    String valueStr = trimmed.substring(opIndex + op.length()).trim();

                    Object contextValue = context.get(field);
                    if (contextValue == null) {
                        log.debug("Condition field '{}' not found in context, evaluating to false", field);
                        return false;
                    }

                    double contextNum = toDouble(contextValue);
                    double thresholdNum = Double.parseDouble(valueStr);

                    return switch (op) {
                        case ">=" -> contextNum >= thresholdNum;
                        case "<=" -> contextNum <= thresholdNum;
                        case ">" -> contextNum > thresholdNum;
                        case "<" -> contextNum < thresholdNum;
                        case "==" -> Math.abs(contextNum - thresholdNum) < 0.001;
                        default -> false;
                    };
                }
            }

            log.warn("Unrecognized condition expression: {}", condition);
            return false;
        } catch (Exception e) {
            log.warn("Failed to evaluate condition '{}': {}", condition, e.getMessage());
            return false;
        }
    }

    private double toDouble(Object value) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        return Double.parseDouble(value.toString());
    }

    /**
     * Resolves the target IP from step params or context.
     * Step param "ip" takes precedence, then context "ip", then context "sourceIp", then "destinationIp".
     */
    private String resolveIp(PlaybookStep step, Map<String, Object> context) {
        // Check step params first
        if (step.getParams() != null) {
            String paramIp = step.getParams().get("ip");
            if (paramIp != null && !paramIp.isBlank()) {
                return paramIp;
            }
            // If target=destination, prefer destination IP
            String target = step.getParams().get("target");
            if ("destination".equals(target) && context.containsKey("destinationIp")) {
                return context.get("destinationIp").toString();
            }
        }
        // Fall through to context
        if (context.containsKey("ip")) return context.get("ip").toString();
        if (context.containsKey("sourceIp")) return context.get("sourceIp").toString();
        if (context.containsKey("destinationIp")) return context.get("destinationIp").toString();
        return null;
    }

    private int extractInt(Map<String, Object> context, String key, int defaultValue) {
        Object value = context.get(key);
        if (value instanceof Number n) return n.intValue();
        if (value != null) {
            try { return Integer.parseInt(value.toString()); } catch (NumberFormatException ignored) {}
        }
        return defaultValue;
    }

    private boolean checkRateLimit(String actionType, int maxPerHour) {
        AtomicInteger counter = actionCounts.computeIfAbsent(actionType, k -> new AtomicInteger(0));
        return counter.get() < maxPerHour;
    }

    private void incrementRateCounter(String actionType) {
        actionCounts.computeIfAbsent(actionType, k -> new AtomicInteger(0)).incrementAndGet();
    }

    private StepResult rateLimitedResult(String action) {
        return StepResult.builder()
                .action(action)
                .status("RATE_LIMITED")
                .result("Rate limit exceeded for this action type")
                .executedAt(Instant.now())
                .build();
    }

    private void recordExecution(PlaybookExecution execution) {
        executionHistory.add(execution);
        // Trim to bounded size
        while (executionHistory.size() > MAX_HISTORY) {
            executionHistory.remove(0);
        }
    }

    private String buildTriggerContextSummary(Map<String, Object> context) {
        if (context == null || context.isEmpty()) return "{}";
        try {
            StringBuilder sb = new StringBuilder("{");
            int count = 0;
            for (Map.Entry<String, Object> entry : context.entrySet()) {
                if (count > 0) sb.append(", ");
                if (count >= 10) { sb.append("..."); break; } // truncate large contexts
                sb.append('"').append(entry.getKey()).append("\": ");
                Object val = entry.getValue();
                if (val instanceof String) {
                    sb.append('"').append(truncate(val.toString(), 100)).append('"');
                } else {
                    sb.append(val);
                }
                count++;
            }
            sb.append("}");
            return sb.toString();
        } catch (Exception e) {
            return "{error: " + e.getMessage() + "}";
        }
    }

    private String summarizeContext(Map<String, Object> context) {
        if (context == null || context.isEmpty()) return "none";
        return context.entrySet().stream()
                .limit(5)
                .map(e -> e.getKey() + "=" + truncate(String.valueOf(e.getValue()), 50))
                .collect(Collectors.joining(", "));
    }

    private String summarizeRateCounts() {
        return actionCounts.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue().get())
                .collect(Collectors.joining(", "));
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "null";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
