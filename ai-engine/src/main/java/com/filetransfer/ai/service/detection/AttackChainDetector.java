package com.filetransfer.ai.service.detection;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * Detects multi-stage attacks by tracking MITRE ATT&CK tactic progression per entity.
 * Monitors kill chain advancement over sliding time windows and generates
 * human-readable attack narratives.
 */
@Service
@Slf4j
public class AttackChainDetector {

    // ── Kill Chain Stages (ordered per MITRE ATT&CK) ──────────────────

    private static final List<String> KILL_CHAIN = List.of(
            "TA0043", // Reconnaissance
            "TA0042", // Resource Development
            "TA0001", // Initial Access
            "TA0002", // Execution
            "TA0003", // Persistence
            "TA0004", // Privilege Escalation
            "TA0005", // Defense Evasion
            "TA0006", // Credential Access
            "TA0007", // Discovery
            "TA0008", // Lateral Movement
            "TA0009", // Collection
            "TA0011", // Command and Control
            "TA0010", // Exfiltration
            "TA0040"  // Impact
    );

    private static final Map<String, String> TACTIC_NAMES = Map.ofEntries(
            Map.entry("TA0043", "Reconnaissance"),
            Map.entry("TA0042", "Resource Development"),
            Map.entry("TA0001", "Initial Access"),
            Map.entry("TA0002", "Execution"),
            Map.entry("TA0003", "Persistence"),
            Map.entry("TA0004", "Privilege Escalation"),
            Map.entry("TA0005", "Defense Evasion"),
            Map.entry("TA0006", "Credential Access"),
            Map.entry("TA0007", "Discovery"),
            Map.entry("TA0008", "Lateral Movement"),
            Map.entry("TA0009", "Collection"),
            Map.entry("TA0011", "Command and Control"),
            Map.entry("TA0010", "Exfiltration"),
            Map.entry("TA0040", "Impact")
    );

    // ── Timeline Tracking ─────────────────────────────────────────────

    private final ConcurrentHashMap<String, AttackTimeline> entityTimelines = new ConcurrentHashMap<>();

    private static class AttackTimeline {
        final String entityId;
        final List<TacticObservation> observations = new CopyOnWriteArrayList<>();
        volatile Instant lastActivity = Instant.now();

        AttackTimeline(String entityId) {
            this.entityId = entityId;
        }
    }

    record TacticObservation(String tacticId, String techniqueId, Instant timestamp,
                             double confidence, String evidence) {}

    // ── Record Tactic Observation ─────────────────────────────────────

    /**
     * Record a MITRE ATT&CK tactic observation for an entity.
     * Called by other detection services when suspicious activity is identified.
     */
    public void recordTactic(String entityId, String tacticId, String techniqueId,
                              double confidence, String evidence) {
        if (entityId == null || tacticId == null) return;

        AttackTimeline timeline = entityTimelines.computeIfAbsent(
                entityId, k -> new AttackTimeline(entityId));

        TacticObservation observation = new TacticObservation(
                tacticId, techniqueId, Instant.now(), confidence, evidence);
        timeline.observations.add(observation);
        timeline.lastActivity = Instant.now();

        log.info("Recorded tactic {} ({}) for entity {}: {}",
                tacticId, TACTIC_NAMES.getOrDefault(tacticId, "Unknown"), entityId, evidence);

        // Check for escalation on every new observation
        ChainAnalysis analysis = analyzeChain(entityId);
        if (analysis != null && analysis.stagesReached() >= 3) {
            log.warn("ALERT: Attack chain escalation for {} - {} stages reached, risk={}",
                    entityId, analysis.stagesReached(), analysis.riskLevel());
        }
    }

    // ── Chain Analysis ────────────────────────────────────────────────

    /**
     * Analyze the attack chain progression for a specific entity.
     * Looks at the last 24 hours of tactic observations.
     */
    public ChainAnalysis analyzeChain(String entityId) {
        AttackTimeline timeline = entityTimelines.get(entityId);
        if (timeline == null) {
            return new ChainAnalysis(entityId, 0, 0, "NONE", "N/A", "NONE",
                    Duration.ZERO, List.of(), "No activity recorded for this entity.");
        }

        Instant cutoff = Instant.now().minus(24, ChronoUnit.HOURS);

        // Filter to recent observations
        List<TacticObservation> recent = timeline.observations.stream()
                .filter(obs -> obs.timestamp().isAfter(cutoff))
                .sorted(Comparator.comparing(TacticObservation::timestamp))
                .toList();

        if (recent.isEmpty()) {
            return new ChainAnalysis(entityId, 0, 0, "NONE", "N/A", "NONE",
                    Duration.ZERO, List.of(), "No recent activity (last 24h) for this entity.");
        }

        // Deduplicate by tactic: keep highest-confidence observation per tactic
        Map<String, TacticObservation> bestByTactic = new LinkedHashMap<>();
        for (TacticObservation obs : recent) {
            bestByTactic.merge(obs.tacticId(), obs,
                    (existing, incoming) -> incoming.confidence() > existing.confidence() ? incoming : existing);
        }

        // Build stage details in kill chain order
        List<StageDetail> stages = new ArrayList<>();
        for (String tacticId : KILL_CHAIN) {
            TacticObservation obs = bestByTactic.get(tacticId);
            if (obs != null) {
                stages.add(new StageDetail(
                        tacticId,
                        TACTIC_NAMES.getOrDefault(tacticId, "Unknown"),
                        obs.techniqueId(),
                        obs.timestamp(),
                        obs.confidence(),
                        obs.evidence()
                ));
            }
        }

        int stagesReached = stages.size();
        double progressionPercent = (stagesReached * 100.0) / KILL_CHAIN.size();

        // Current stage = last observed stage in kill chain order
        String currentStage = stages.isEmpty() ? "NONE"
                : stages.get(stages.size() - 1).tacticName();

        // Predict next likely stage
        String predictedNext = predictNextStage(stages);

        // Risk level based on stages reached
        String riskLevel;
        if (stagesReached >= 5) riskLevel = "CRITICAL";
        else if (stagesReached >= 3) riskLevel = "HIGH";
        else if (stagesReached >= 2) riskLevel = "MEDIUM";
        else riskLevel = "LOW";

        // Time span from first to last observation
        Duration timeSpan = Duration.ZERO;
        if (recent.size() > 1) {
            timeSpan = Duration.between(recent.get(0).timestamp(), recent.get(recent.size() - 1).timestamp());
        }

        // Generate narrative
        String narrative = generateNarrative(entityId, stages, timeSpan);

        return new ChainAnalysis(entityId, stagesReached, progressionPercent,
                currentStage, predictedNext, riskLevel, timeSpan, stages, narrative);
    }

    /**
     * Scan all tracked entities for active attack chains.
     * Returns entities with 2+ kill chain stages in the last 24 hours, sorted by risk.
     */
    public List<ChainAnalysis> scanForActiveChains() {
        return entityTimelines.keySet().stream()
                .map(this::analyzeChain)
                .filter(analysis -> analysis.stagesReached() >= 2)
                .sorted((a, b) -> {
                    int riskOrder = riskOrdinal(b.riskLevel()) - riskOrdinal(a.riskLevel());
                    if (riskOrder != 0) return riskOrder;
                    return Integer.compare(b.stagesReached(), a.stagesReached());
                })
                .collect(Collectors.toList());
    }

    // ── Result Records ────────────────────────────────────────────────

    public record ChainAnalysis(
            String entityId,
            int stagesReached,
            double progressionPercent,
            String currentStage,
            String predictedNextStage,
            String riskLevel,         // LOW, MEDIUM, HIGH, CRITICAL
            Duration timeSpan,
            List<StageDetail> stages,
            String narrative          // human-readable attack story
    ) {}

    public record StageDetail(
            String tacticId,
            String tacticName,
            String techniqueId,
            Instant firstSeen,
            double confidence,
            String evidence
    ) {}

    // ── Prediction ────────────────────────────────────────────────────

    /**
     * Predict the next likely attack stage based on current progression.
     * Follows the MITRE ATT&CK kill chain ordering.
     */
    private String predictNextStage(List<StageDetail> observedStages) {
        if (observedStages.isEmpty()) return "Reconnaissance";

        Set<String> observedTactics = observedStages.stream()
                .map(StageDetail::tacticId)
                .collect(Collectors.toSet());

        // Find the highest observed stage index
        int highestIndex = -1;
        for (StageDetail stage : observedStages) {
            int idx = KILL_CHAIN.indexOf(stage.tacticId());
            if (idx > highestIndex) highestIndex = idx;
        }

        // Predict the next unobserved stage after the highest
        for (int i = highestIndex + 1; i < KILL_CHAIN.size(); i++) {
            if (!observedTactics.contains(KILL_CHAIN.get(i))) {
                return TACTIC_NAMES.getOrDefault(KILL_CHAIN.get(i), "Unknown");
            }
        }

        // If all subsequent stages are covered, look for gaps
        for (int i = 0; i < KILL_CHAIN.size(); i++) {
            if (!observedTactics.contains(KILL_CHAIN.get(i))) {
                return TACTIC_NAMES.getOrDefault(KILL_CHAIN.get(i), "Unknown");
            }
        }

        return "Complete (all stages observed)";
    }

    // ── Narrative Generation ──────────────────────────────────────────

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("HH:mm:ss 'UTC'").withZone(ZoneOffset.UTC);

    /**
     * Generate a human-readable narrative describing the attack chain progression.
     */
    private String generateNarrative(String entityId, List<StageDetail> stages, Duration timeSpan) {
        if (stages.isEmpty()) {
            return "No attack chain activity detected for " + entityId + ".";
        }

        StringBuilder sb = new StringBuilder();

        if (stages.size() == 1) {
            StageDetail s = stages.get(0);
            sb.append(String.format(
                    "Entity %s was observed performing %s at %s. Evidence: %s. " +
                    "Confidence: %.0f%%. Single stage observed — monitoring for escalation.",
                    entityId, s.tacticName().toLowerCase(),
                    TIME_FMT.format(s.firstSeen()), s.evidence(),
                    s.confidence() * 100));
            return sb.toString();
        }

        // Multi-stage narrative
        sb.append(String.format("Attack chain detected from %s spanning %s (%d stages observed). ",
                entityId, formatDuration(timeSpan), stages.size()));

        for (int i = 0; i < stages.size(); i++) {
            StageDetail s = stages.get(i);
            if (i == 0) {
                sb.append(String.format(
                        "Activity began with %s (%s) at %s — %s. ",
                        s.tacticName().toLowerCase(), s.tacticId(),
                        TIME_FMT.format(s.firstSeen()), s.evidence()));
            } else {
                Duration sincePrevious = Duration.between(stages.get(i - 1).firstSeen(), s.firstSeen());
                sb.append(String.format(
                        "%s later, %s (%s) was detected — %s. ",
                        formatDuration(sincePrevious),
                        s.tacticName().toLowerCase(), s.tacticId(), s.evidence()));
            }
        }

        // Risk assessment
        if (stages.size() >= 5) {
            sb.append("CRITICAL: This entity has progressed through 5+ kill chain stages. " +
                       "Immediate investigation and containment recommended.");
        } else if (stages.size() >= 3) {
            sb.append("HIGH RISK: Multi-stage attack in progress. " +
                       "Recommend blocking the entity and investigating all related activity.");
        } else {
            sb.append("Activity warrants monitoring. Consider additional logging and alerting for this entity.");
        }

        return sb.toString();
    }

    private String formatDuration(Duration d) {
        if (d.toHours() > 0) {
            return String.format("%dh %dm", d.toHours(), d.toMinutesPart());
        } else if (d.toMinutes() > 0) {
            return String.format("%dm %ds", d.toMinutes(), d.toSecondsPart());
        } else {
            return String.format("%ds", d.toSeconds());
        }
    }

    // ── Cleanup ───────────────────────────────────────────────────────

    /**
     * Prune old timelines to prevent memory growth.
     * Removes observations older than 48 hours and cleans up inactive entities.
     */
    @Scheduled(fixedDelay = 600_000) // every 10 minutes
    public void pruneOldTimelines() {
        Instant cutoff48h = Instant.now().minus(48, ChronoUnit.HOURS);
        Instant cutoff24h = Instant.now().minus(24, ChronoUnit.HOURS);
        int prunedEntities = 0;
        int prunedObservations = 0;

        Iterator<Map.Entry<String, AttackTimeline>> it = entityTimelines.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, AttackTimeline> entry = it.next();
            AttackTimeline timeline = entry.getValue();

            // Remove old observations
            int before = timeline.observations.size();
            timeline.observations.removeIf(obs -> obs.timestamp().isBefore(cutoff48h));
            prunedObservations += (before - timeline.observations.size());

            // Remove entity if no recent activity
            if (timeline.observations.isEmpty() && timeline.lastActivity.isBefore(cutoff24h)) {
                it.remove();
                prunedEntities++;
            }
        }

        if (prunedEntities > 0 || prunedObservations > 0) {
            log.info("Pruned {} entities and {} observations from attack timelines",
                    prunedEntities, prunedObservations);
        }
    }

    // ── Stats ─────────────────────────────────────────────────────────

    public Map<String, Object> getStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("trackedEntities", entityTimelines.size());

        long totalObservations = entityTimelines.values().stream()
                .mapToLong(t -> t.observations.size())
                .sum();
        stats.put("totalObservations", totalObservations);

        List<ChainAnalysis> activeChains = scanForActiveChains();
        stats.put("activeChainsCount", activeChains.size());

        Map<String, Long> byRisk = activeChains.stream()
                .collect(Collectors.groupingBy(ChainAnalysis::riskLevel, Collectors.counting()));
        stats.put("chainsByRisk", byRisk);

        return stats;
    }

    // ── Helpers ────────────────────────────────────────────────────────

    private int riskOrdinal(String riskLevel) {
        return switch (riskLevel) {
            case "CRITICAL" -> 4;
            case "HIGH" -> 3;
            case "MEDIUM" -> 2;
            case "LOW" -> 1;
            default -> 0;
        };
    }
}
