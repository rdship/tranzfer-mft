package com.filetransfer.ai.service.detection;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Explainability engine for all detection verdicts.
 * Generates human-readable explanations, feature importance rankings,
 * and actionable recommendations for security analysts.
 *
 * Every detection in the platform must be explainable — no opaque scoring.
 */
@Service
@Slf4j
public class ExplainabilityEngine {

    // ── Well-known port descriptions ──────────────────────────────────

    private static final Map<Integer, String> PORT_DESCRIPTIONS = Map.ofEntries(
            Map.entry(21, "FTP Control"), Map.entry(22, "SSH"), Map.entry(23, "Telnet"),
            Map.entry(25, "SMTP"), Map.entry(53, "DNS"), Map.entry(80, "HTTP"),
            Map.entry(110, "POP3"), Map.entry(143, "IMAP"), Map.entry(443, "HTTPS"),
            Map.entry(445, "SMB"), Map.entry(990, "FTPS"), Map.entry(993, "IMAPS"),
            Map.entry(995, "POP3S"), Map.entry(1433, "MSSQL"), Map.entry(1521, "Oracle"),
            Map.entry(3306, "MySQL"), Map.entry(3389, "RDP"), Map.entry(5432, "PostgreSQL"),
            Map.entry(5900, "VNC"), Map.entry(8080, "HTTP Proxy"), Map.entry(8443, "HTTPS Alt")
    );

    // ── MITRE technique to description mapping ────────────────────────

    private static final Map<String, String> TECHNIQUE_DESCRIPTIONS = Map.ofEntries(
            Map.entry("T1046", "Network Service Scanning — adversary probing for open ports/services"),
            Map.entry("T1071.001", "C2 via Web Protocols — command-and-control over HTTP/HTTPS"),
            Map.entry("T1071.004", "C2 via DNS — command-and-control or data exfiltration via DNS"),
            Map.entry("T1568.002", "Domain Generation Algorithms — dynamic DNS resolution for C2"),
            Map.entry("T1041", "Exfiltration Over C2 Channel — data theft via established C2"),
            Map.entry("T1048.003", "Exfiltration via DNS — covert data transfer using DNS queries"),
            Map.entry("T1573", "Encrypted Channel — encrypted C2 communications"),
            Map.entry("T1030", "Data Transfer Size Limits — throttled exfiltration to avoid detection"),
            Map.entry("T1018", "Remote System Discovery — identification of networked systems"),
            Map.entry("T1110", "Brute Force — credential guessing attacks"),
            Map.entry("T1078", "Valid Accounts — use of compromised credentials"),
            Map.entry("T1059", "Command and Scripting Interpreter — execution via shell/scripts")
    );

    // ── Verdict Explanation ───────────────────────────────────────────

    /**
     * Generate a human-readable explanation for a network connection verdict.
     */
    public String explainVerdict(String sourceIp, int targetPort, String protocol,
                                  int riskScore, String action, Map<String, Object> signals) {
        StringBuilder sb = new StringBuilder();

        // Connection description
        String portDesc = PORT_DESCRIPTIONS.getOrDefault(targetPort, "port " + targetPort);
        sb.append(String.format("Connection from %s", sourceIp));

        // Add geo info if available
        String geoCountry = signals != null ? (String) signals.get("geo_country") : null;
        if (geoCountry != null && !geoCountry.isBlank()) {
            sb.append(String.format(" (%s)", geoCountry));
        }

        sb.append(String.format(" to %s (%s) was %s with risk score %d/100. ",
                portDesc, protocol != null ? protocol.toUpperCase() : "UNKNOWN", action, riskScore));

        // Top contributing signals
        if (signals != null && !signals.isEmpty()) {
            List<Map.Entry<String, Object>> sortedSignals = signals.entrySet().stream()
                    .filter(e -> e.getValue() instanceof Number)
                    .sorted((a, b) -> Double.compare(
                            ((Number) b.getValue()).doubleValue(),
                            ((Number) a.getValue()).doubleValue()))
                    .limit(3)
                    .toList();

            if (!sortedSignals.isEmpty()) {
                sb.append("Key factors: ");
                List<String> factorDescriptions = new ArrayList<>();
                for (Map.Entry<String, Object> entry : sortedSignals) {
                    factorDescriptions.add(formatSignalName(entry.getKey()) + "=" +
                            formatSignalValue(entry.getValue()));
                }
                sb.append(String.join(", ", factorDescriptions)).append(". ");
            }

            // Non-numeric signals (e.g., lists of techniques)
            List<String> techniques = extractTechniques(signals);
            if (!techniques.isEmpty()) {
                sb.append("MITRE ATT&CK: ");
                sb.append(techniques.stream()
                        .map(t -> {
                            String techId = t.split(" - ")[0].trim();
                            return TECHNIQUE_DESCRIPTIONS.getOrDefault(techId, t);
                        })
                        .collect(Collectors.joining("; ")));
                sb.append(". ");
            }
        }

        // Recommendation
        List<String> recommendations = recommendActions(riskScore, action,
                signals != null ? extractTechniques(signals) : List.of());
        if (!recommendations.isEmpty()) {
            sb.append("Recommended: ").append(recommendations.get(0)).append(".");
        }

        return sb.toString().trim();
    }

    // ── Anomaly Explanation ───────────────────────────────────────────

    /**
     * Generate a human-readable explanation for an anomaly detection result.
     */
    public String explainAnomaly(String entityId, double score,
                                  Map<String, Double> featureContributions) {
        StringBuilder sb = new StringBuilder();

        String severity;
        if (score >= 0.85) severity = "Critical anomaly";
        else if (score >= 0.7) severity = "High anomaly";
        else if (score >= 0.5) severity = "Moderate anomaly";
        else severity = "Low-confidence anomaly";

        sb.append(String.format("%s detected for %s: score %.3f. ", severity, entityId, score));

        if (featureContributions == null || featureContributions.isEmpty()) {
            sb.append("No detailed feature breakdown available.");
            return sb.toString();
        }

        // Rank features by absolute contribution
        List<Map.Entry<String, Double>> ranked = featureContributions.entrySet().stream()
                .sorted((a, b) -> Double.compare(Math.abs(b.getValue()), Math.abs(a.getValue())))
                .toList();

        sb.append("Top contributing factors: ");
        int count = 0;
        for (Map.Entry<String, Double> entry : ranked) {
            if (count >= 5) break;
            count++;
            double value = entry.getValue();
            String direction = value > 0 ? "above" : "below";
            sb.append(String.format("%d) %s deviated %.1f\u03C3 %s baseline",
                    count, formatSignalName(entry.getKey()), Math.abs(value), direction));
            if (count < Math.min(5, ranked.size())) sb.append(", ");
        }
        sb.append(".");

        return sb.toString().trim();
    }

    // ── Attack Chain Explanation ───────────────────────────────────────

    /**
     * Generate an explanation for a detected attack chain.
     */
    public String explainAttackChain(String entityId, List<Map<String, Object>> stages) {
        if (stages == null || stages.isEmpty()) {
            return "No attack chain stages observed for " + entityId + ".";
        }

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Attack chain analysis for %s: %d stage(s) detected. ",
                entityId, stages.size()));

        for (int i = 0; i < stages.size(); i++) {
            Map<String, Object> stage = stages.get(i);
            String tacticName = (String) stage.getOrDefault("tacticName", "Unknown");
            String techniqueId = (String) stage.getOrDefault("techniqueId", "");
            String evidence = (String) stage.getOrDefault("evidence", "");
            Number confidence = (Number) stage.getOrDefault("confidence", 0);

            sb.append(String.format("Stage %d: %s", i + 1, tacticName));
            if (techniqueId != null && !techniqueId.isBlank()) {
                String techDesc = TECHNIQUE_DESCRIPTIONS.get(techniqueId);
                if (techDesc != null) {
                    sb.append(String.format(" (%s)", techDesc));
                } else {
                    sb.append(String.format(" [%s]", techniqueId));
                }
            }
            sb.append(String.format(" (confidence: %.0f%%)", confidence.doubleValue() * 100));
            if (evidence != null && !evidence.isBlank()) {
                sb.append(String.format(" - %s", evidence));
            }
            sb.append(". ");
        }

        // Overall assessment
        if (stages.size() >= 5) {
            sb.append("CRITICAL ALERT: Advanced multi-stage attack in progress. " +
                       "Immediate containment required. Isolate the affected system(s) and " +
                       "preserve forensic evidence.");
        } else if (stages.size() >= 3) {
            sb.append("HIGH ALERT: Attack chain is advancing. Recommend immediate investigation " +
                       "and preemptive blocking of the source entity.");
        } else {
            sb.append("Monitoring recommended. Enable enhanced logging for this entity.");
        }

        return sb.toString().trim();
    }

    // ── Feature Importance Ranking ────────────────────────────────────

    /**
     * Rank features by their contribution to a detection prediction.
     * Uses a simplified additive contribution model (pseudo-SHAP for rule-based systems).
     */
    public List<FeatureContribution> rankFeatureImportance(Map<String, Double> features,
                                                            double prediction) {
        if (features == null || features.isEmpty()) return List.of();

        // Feature weights for the scoring model
        Map<String, Double> weights = Map.ofEntries(
                Map.entry("isolation_forest_score", 0.35),
                Map.entry("z_score", 0.25),
                Map.entry("ewma_score", 0.15),
                Map.entry("seasonal_deviation", 0.25),
                Map.entry("beaconing_confidence", 0.30),
                Map.entry("dga_score", 0.25),
                Map.entry("dns_tunnel_score", 0.20),
                Map.entry("exfil_score", 0.25),
                Map.entry("port_scan_confidence", 0.15),
                Map.entry("ip_reputation", 0.20),
                Map.entry("geo_risk", 0.15),
                Map.entry("connection_rate", 0.10),
                Map.entry("bytes_out_ratio", 0.15),
                Map.entry("protocol_risk", 0.10)
        );

        // Calculate absolute contributions
        double totalContribution = 0;
        Map<String, Double> rawContributions = new LinkedHashMap<>();
        for (Map.Entry<String, Double> entry : features.entrySet()) {
            double weight = weights.getOrDefault(entry.getKey(), 0.10);
            double contribution = entry.getValue() * weight;
            rawContributions.put(entry.getKey(), contribution);
            totalContribution += Math.abs(contribution);
        }

        // Normalize so contributions sum to the prediction value
        final double normalizer = totalContribution > 0 ? prediction / totalContribution : 1.0;

        return rawContributions.entrySet().stream()
                .map(e -> {
                    double normalizedContribution = e.getValue() * normalizer;
                    String direction = e.getValue() > 0 ? "INCREASES_RISK" : "DECREASES_RISK";
                    return new FeatureContribution(
                            e.getKey(),
                            features.get(e.getKey()),
                            Math.abs(normalizedContribution),
                            direction);
                })
                .sorted((a, b) -> Double.compare(b.contribution(), a.contribution()))
                .collect(Collectors.toList());
    }

    public record FeatureContribution(
            String featureName,
            double value,
            double contribution,
            String direction    // INCREASES_RISK or DECREASES_RISK
    ) {}

    // ── Recommendation Engine ─────────────────────────────────────────

    /**
     * Generate actionable recommendations based on risk score, action taken, and detected techniques.
     */
    public List<String> recommendActions(int riskScore, String action,
                                          List<String> mitreTechniques) {
        List<String> recommendations = new ArrayList<>();

        // Risk-based recommendations
        if (riskScore >= 80) {
            recommendations.add("Block source IP at the perimeter firewall immediately");
            recommendations.add("Isolate affected host(s) from the network");
            recommendations.add("Preserve all logs and memory dumps for forensic analysis");
            recommendations.add("Notify the security operations team (P1 incident)");
        } else if (riskScore >= 50) {
            recommendations.add("Add source IP to watchlist for enhanced monitoring");
            recommendations.add("Review file transfer logs for the affected account(s)");
            recommendations.add("Enable verbose logging for connections from this source");
        } else if (riskScore >= 25) {
            recommendations.add("Monitor for recurrence in the next 24 hours");
            recommendations.add("Review baseline profiles for the affected entity");
        }

        // Technique-specific recommendations
        Set<String> techIds = new HashSet<>();
        if (mitreTechniques != null) {
            for (String t : mitreTechniques) {
                String id = t.split(" - ")[0].trim();
                techIds.add(id);
            }
        }

        if (techIds.contains("T1110") || techIds.contains("T1078")) {
            recommendations.add("Rotate credentials for affected accounts");
            recommendations.add("Enable multi-factor authentication if not already active");
            recommendations.add("Review account activity for unauthorized access");
        }
        if (techIds.contains("T1046") || techIds.contains("T1018")) {
            recommendations.add("Review firewall rules — restrict unnecessary port exposure");
            recommendations.add("Verify network segmentation is properly configured");
        }
        if (techIds.contains("T1041") || techIds.contains("T1048.003") || techIds.contains("T1030")) {
            recommendations.add("Review outbound data transfers for sensitive file exfiltration");
            recommendations.add("Implement or verify data loss prevention (DLP) policies");
            recommendations.add("Check if the destination IP/domain is on any threat intelligence feeds");
        }
        if (techIds.contains("T1071.001") || techIds.contains("T1071.004") || techIds.contains("T1573")) {
            recommendations.add("Investigate for command-and-control infrastructure");
            recommendations.add("Block the destination domain/IP at DNS and proxy level");
            recommendations.add("Scan affected hosts for malware/implants");
        }
        if (techIds.contains("T1568.002")) {
            recommendations.add("Block the suspected DGA domain at DNS resolver");
            recommendations.add("Deploy DNS sinkholing for identified DGA patterns");
            recommendations.add("Scan source host for malware generating the DGA queries");
        }
        if (techIds.contains("T1059")) {
            recommendations.add("Review process execution logs on the affected host");
            recommendations.add("Verify application whitelisting policies are enforced");
        }

        // Action-specific adjustments
        if ("BLOCK".equals(action)) {
            recommendations.add("Verify the block is enforced across all network paths");
        } else if ("ALLOW".equals(action) && riskScore >= 30) {
            recommendations.add("Consider tightening the detection threshold for this entity type");
        }

        // Deduplicate while preserving order
        return recommendations.stream().distinct().collect(Collectors.toList());
    }

    // ── Helpers ────────────────────────────────────────────────────────

    /**
     * Format a signal/feature name from snake_case to human-readable.
     */
    private String formatSignalName(String key) {
        if (key == null) return "unknown";
        String spaced = key.replace("_", " ").replace(".", " ");
        StringBuilder result = new StringBuilder();
        boolean capitalizeNext = true;
        for (char c : spaced.toCharArray()) {
            if (c == ' ') {
                result.append(c);
                capitalizeNext = true;
            } else if (capitalizeNext) {
                result.append(Character.toUpperCase(c));
                capitalizeNext = false;
            } else {
                result.append(c);
            }
        }
        return result.toString();
    }

    private String formatSignalValue(Object value) {
        if (value instanceof Double d) {
            return String.format("%.3f", d);
        } else if (value instanceof Float f) {
            return String.format("%.3f", f);
        }
        return String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    private List<String> extractTechniques(Map<String, Object> signals) {
        Object techniques = signals.get("mitre_techniques");
        if (techniques instanceof List<?> list) {
            return list.stream()
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .collect(Collectors.toList());
        }
        return List.of();
    }
}
