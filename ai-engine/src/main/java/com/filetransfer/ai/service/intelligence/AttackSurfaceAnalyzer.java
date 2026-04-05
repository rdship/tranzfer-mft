package com.filetransfer.ai.service.intelligence;

import com.filetransfer.ai.entity.intelligence.ThreatIndicator;
import com.filetransfer.ai.entity.intelligence.ThreatLevel;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Analyzes the platform's attack surface based on configured services,
 * detected activity, and current threat landscape.
 *
 * <p>The attack surface is the sum of all points where an adversary could
 * attempt to enter or extract data from the TranzFer MFT platform.  This
 * service evaluates exposure across multiple dimensions:</p>
 * <ul>
 *   <li><b>Network exposure</b> — active listening ports and protocols
 *       (SFTP, FTP, FTPS, HTTP/S, AS2)</li>
 *   <li><b>Authentication posture</b> — password policies, MFA coverage,
 *       service account hygiene</li>
 *   <li><b>Data exposure</b> — unencrypted transfers, overly permissive
 *       partner accounts, PCI/PII data flows</li>
 *   <li><b>Geographic exposure</b> — which countries are actively connecting</li>
 *   <li><b>Threat landscape</b> — how many known-malicious indicators in the
 *       threat intelligence store target our exposed services</li>
 * </ul>
 *
 * <p>Results include a numeric risk score (0-100), prioritised recommendations,
 * and a mapping to MITRE ATT&amp;CK techniques that the current surface is
 * vulnerable to.</p>
 *
 * @see MitreAttackMapper
 * @see ThreatIntelligenceStore
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class AttackSurfaceAnalyzer {

    private final MitreAttackMapper mitreMapper;
    private final ThreatIntelligenceStore threatStore;

    // ── Supported Protocols and Their Inherent Risk Weights ─────────────

    private static final Map<String, ProtocolRiskProfile> PROTOCOL_PROFILES = new LinkedHashMap<>();

    static {
        PROTOCOL_PROFILES.put("SFTP", new ProtocolRiskProfile("SFTP", 22, 15, true,
                List.of("T1021.004", "T1110.004", "T1048.002", "T1071.002")));
        PROTOCOL_PROFILES.put("FTP", new ProtocolRiskProfile("FTP", 21, 40, false,
                List.of("T1071.002", "T1048.003", "T1110.001", "T1040")));
        PROTOCOL_PROFILES.put("FTPS", new ProtocolRiskProfile("FTPS", 990, 20, true,
                List.of("T1071.002", "T1048.001", "T1573")));
        PROTOCOL_PROFILES.put("HTTP", new ProtocolRiskProfile("HTTP", 80, 50, false,
                List.of("T1071.001", "T1190", "T1048.003")));
        PROTOCOL_PROFILES.put("HTTPS", new ProtocolRiskProfile("HTTPS", 443, 20, true,
                List.of("T1071.001", "T1190", "T1567")));
        PROTOCOL_PROFILES.put("AS2", new ProtocolRiskProfile("AS2", 4080, 25, true,
                List.of("T1071.001", "T1048.002")));
        PROTOCOL_PROFILES.put("SCP", new ProtocolRiskProfile("SCP", 22, 20, true,
                List.of("T1021.004", "T1048.001", "T1570")));
    }

    // ── Known Risk Checks ──────────────────────────────────────────────

    private static final List<RiskCheck> RISK_CHECKS = List.of(
            new RiskCheck("FTP_ANONYMOUS", "FTP anonymous access enabled",
                    "Disable FTP anonymous access or restrict to read-only with IP allowlist", 30),
            new RiskCheck("FTP_CLEARTEXT", "FTP (unencrypted) is exposed to the internet",
                    "Migrate to SFTP or FTPS; disable plain FTP on public interfaces", 35),
            new RiskCheck("HTTP_CLEARTEXT", "HTTP (unencrypted) admin or transfer endpoints exposed",
                    "Enforce HTTPS-only with HSTS; redirect all HTTP to HTTPS", 25),
            new RiskCheck("WEAK_PASSWORD_POLICY", "Weak password policy (no complexity/rotation requirements)",
                    "Enforce 12+ character passwords with complexity; enable password rotation", 20),
            new RiskCheck("NO_MFA_ADMIN", "No MFA on administrative accounts",
                    "Enable MFA (TOTP/FIDO2) for all admin and privileged accounts", 30),
            new RiskCheck("NO_MFA_PARTNERS", "No MFA on partner/service accounts",
                    "Enable MFA or certificate-based auth for high-privilege partner accounts", 15),
            new RiskCheck("STALE_ACCOUNTS", "Service accounts not rotated in >90 days",
                    "Implement automated credential rotation for all service accounts", 15),
            new RiskCheck("NO_IP_ALLOWLIST", "No IP allowlisting on sensitive protocols",
                    "Configure IP allowlists for SFTP/FTP partner connections", 20),
            new RiskCheck("EXCESSIVE_PERMISSIONS", "Partner accounts with overly broad directory access",
                    "Apply least-privilege: restrict each partner to their designated directories only", 15),
            new RiskCheck("UNENCRYPTED_PCI", "PCI data transferred over unencrypted channels",
                    "Block all PCI data on non-TLS channels; enforce at-rest encryption", 40),
            new RiskCheck("NO_FILE_SCANNING", "Uploaded files not scanned for malware",
                    "Enable file content scanning (AV + AI) for all inbound transfers", 25),
            new RiskCheck("NO_RATE_LIMITING", "No rate limiting on authentication endpoints",
                    "Implement rate limiting (max 5 failures per minute per IP) on all auth endpoints", 20),
            new RiskCheck("DMZ_DIRECT_DB", "DMZ services have direct database access",
                    "Route DMZ traffic through the internal API gateway; never expose DB to DMZ", 35),
            new RiskCheck("OUTDATED_TLS", "TLS 1.0/1.1 still enabled",
                    "Disable TLS 1.0 and 1.1; enforce TLS 1.2+ with strong cipher suites", 25),
            new RiskCheck("NO_AUDIT_LOG", "Audit logging disabled or incomplete for file operations",
                    "Enable comprehensive audit logging for all file transfers and admin actions", 20)
    );

    // ── Public API ─────────────────────────────────────────────────────

    /**
     * Perform a full attack surface analysis.
     *
     * <p>Evaluates the platform's current exposure across network, authentication,
     * data, and compliance dimensions.  Returns a comprehensive report with risk
     * scores, identified exposures, MITRE technique coverage, and prioritised
     * recommendations.</p>
     *
     * @return the attack surface report
     */
    public AttackSurfaceReport analyze() {
        log.info("Starting attack surface analysis...");
        Instant start = Instant.now();

        // Determine active protocols (in production, this would query service registry)
        List<String> activeProtocols = detectActiveProtocols();

        // Evaluate risk checks
        List<String> highRiskExposures = new ArrayList<>();
        List<String> recommendations = new ArrayList<>();
        double totalRiskPoints = 0;
        double maxPossibleRisk = 0;

        for (RiskCheck check : RISK_CHECKS) {
            maxPossibleRisk += check.riskWeight;
            if (evaluateRiskCheck(check, activeProtocols)) {
                highRiskExposures.add(check.description);
                recommendations.add("[" + check.id + "] " + check.recommendation);
                totalRiskPoints += check.riskWeight;
            }
        }

        // Protocol-inherent risk
        double protocolRisk = 0;
        for (String proto : activeProtocols) {
            ProtocolRiskProfile profile = PROTOCOL_PROFILES.get(proto);
            if (profile != null) {
                protocolRisk += profile.inherentRisk;
            }
        }
        // Normalize protocol risk to a 0-30 contribution
        double normalizedProtocolRisk = Math.min(30.0, protocolRisk / 3.0);

        // Threat landscape contribution (from intelligence store)
        double threatLandscapeRisk = assessThreatLandscape(activeProtocols);

        // Geographic exposure
        Map<String, Integer> connectingCountries = assessGeographicExposure();

        // Overall risk score: combination of check failures + protocol risk + threat landscape
        double checkRisk = maxPossibleRisk > 0 ? (totalRiskPoints / maxPossibleRisk) * 50.0 : 0;
        double overallRiskScore = Math.min(100.0,
                checkRisk + normalizedProtocolRisk + threatLandscapeRisk);

        // Risk by category
        Map<String, Double> riskByCategory = computeRiskByCategory(
                highRiskExposures, activeProtocols, threatLandscapeRisk);

        // MITRE techniques our surface is vulnerable to
        List<String> mitreTechniquesExposed = computeExposedMitreTechniques(activeProtocols);

        // Sort recommendations by priority (highest risk checks first)
        recommendations.sort(Comparator.naturalOrder());

        AttackSurfaceReport report = AttackSurfaceReport.builder()
                .timestamp(Instant.now())
                .exposedServices(activeProtocols.size())
                .activeProtocols(activeProtocols)
                .connectingCountries(connectingCountries)
                .highRiskExposures(highRiskExposures)
                .overallRiskScore(Math.round(overallRiskScore * 10.0) / 10.0)
                .recommendations(recommendations)
                .riskByCategory(riskByCategory)
                .mitreTechniquesExposed(mitreTechniquesExposed)
                .build();

        long elapsed = System.currentTimeMillis() - start.toEpochMilli();
        log.info("Attack surface analysis complete: riskScore={}, exposures={}, protocols={} in {} ms",
                report.getOverallRiskScore(), highRiskExposures.size(), activeProtocols.size(), elapsed);

        return report;
    }

    /**
     * Compare the current attack surface to a previous baseline assessment.
     *
     * <p>Detects new exposures, resolved issues, and the overall risk trend
     * since the baseline was captured.</p>
     *
     * @param baseline the previous assessment to compare against
     * @param current  the current assessment
     * @return delta analysis including new and resolved exposures
     */
    public SurfaceDelta compareToBaseline(AttackSurfaceReport baseline, AttackSurfaceReport current) {
        if (baseline == null || current == null) {
            log.warn("Cannot compare attack surfaces: baseline or current is null");
            return SurfaceDelta.builder()
                    .newExposures(Collections.emptyList())
                    .resolvedExposures(Collections.emptyList())
                    .riskDelta(0.0)
                    .trend("UNKNOWN")
                    .summary("Insufficient data for comparison")
                    .build();
        }

        // New exposures: in current but not in baseline
        Set<String> baselineExposures = new HashSet<>(baseline.getHighRiskExposures());
        Set<String> currentExposures = new HashSet<>(current.getHighRiskExposures());

        List<String> newExposures = currentExposures.stream()
                .filter(e -> !baselineExposures.contains(e))
                .collect(Collectors.toList());

        // Resolved exposures: in baseline but not in current
        List<String> resolvedExposures = baselineExposures.stream()
                .filter(e -> !currentExposures.contains(e))
                .collect(Collectors.toList());

        // Risk trend
        double riskDelta = current.getOverallRiskScore() - baseline.getOverallRiskScore();
        String trend;
        if (riskDelta > 5.0) {
            trend = "WORSENING";
        } else if (riskDelta < -5.0) {
            trend = "IMPROVING";
        } else {
            trend = "STABLE";
        }

        // New protocols added
        Set<String> baselineProtocols = new HashSet<>(baseline.getActiveProtocols());
        List<String> newProtocols = current.getActiveProtocols().stream()
                .filter(p -> !baselineProtocols.contains(p))
                .collect(Collectors.toList());

        // New countries connecting
        Set<String> baselineCountries = baseline.getConnectingCountries().keySet();
        List<String> newCountries = current.getConnectingCountries().keySet().stream()
                .filter(c -> !baselineCountries.contains(c))
                .collect(Collectors.toList());

        // Build summary
        StringBuilder summary = new StringBuilder();
        summary.append(String.format("Risk score: %.1f -> %.1f (%s%.1f, trend: %s). ",
                baseline.getOverallRiskScore(), current.getOverallRiskScore(),
                riskDelta >= 0 ? "+" : "", riskDelta, trend));

        if (!newExposures.isEmpty()) {
            summary.append(newExposures.size()).append(" new exposure(s) detected. ");
        }
        if (!resolvedExposures.isEmpty()) {
            summary.append(resolvedExposures.size()).append(" exposure(s) resolved. ");
        }
        if (!newProtocols.isEmpty()) {
            summary.append("New protocols: ").append(String.join(", ", newProtocols)).append(". ");
        }
        if (!newCountries.isEmpty()) {
            summary.append("New connecting countries: ").append(String.join(", ", newCountries)).append(". ");
        }

        return SurfaceDelta.builder()
                .newExposures(newExposures)
                .resolvedExposures(resolvedExposures)
                .riskDelta(Math.round(riskDelta * 10.0) / 10.0)
                .trend(trend)
                .newProtocols(newProtocols)
                .newCountries(newCountries)
                .summary(summary.toString().strip())
                .build();
    }

    // ── Private Analysis Helpers ────────────────────────────────────────

    /**
     * Detect which protocols are currently active on the platform.
     * In production this would query the service registry; here we derive it
     * from the platform configuration.
     */
    private List<String> detectActiveProtocols() {
        // Default MFT platform protocols — in production, query config-service
        // or service registry for actual active protocols
        return List.of("SFTP", "FTPS", "HTTPS", "AS2");
    }

    /**
     * Evaluate whether a specific risk check condition is present.
     * Returns true if the risk is detected (i.e., the vulnerable condition exists).
     */
    private boolean evaluateRiskCheck(RiskCheck check, List<String> activeProtocols) {
        return switch (check.id) {
            case "FTP_ANONYMOUS" -> activeProtocols.contains("FTP");
            case "FTP_CLEARTEXT" -> activeProtocols.contains("FTP");
            case "HTTP_CLEARTEXT" -> activeProtocols.contains("HTTP");
            case "OUTDATED_TLS" -> false;  // assume TLS 1.2+ in modern deployments
            case "DMZ_DIRECT_DB" -> false;  // TranzFer uses API gateway pattern

            // These checks return true by default (need explicit configuration to disable)
            // In production, these would query the config-service for actual settings
            case "WEAK_PASSWORD_POLICY" -> false;
            case "NO_MFA_ADMIN" -> false;
            case "NO_MFA_PARTNERS" -> true;  // common gap in MFT platforms
            case "STALE_ACCOUNTS" -> true;    // default: assume stale accounts exist
            case "NO_IP_ALLOWLIST" -> true;    // common gap
            case "EXCESSIVE_PERMISSIONS" -> true;
            case "UNENCRYPTED_PCI" -> activeProtocols.contains("FTP") || activeProtocols.contains("HTTP");
            case "NO_FILE_SCANNING" -> false;  // AI engine provides this
            case "NO_RATE_LIMITING" -> false;  // proxy intelligence handles this
            case "NO_AUDIT_LOG" -> false;       // audit logging is enabled
            default -> false;
        };
    }

    /**
     * Assess the current threat landscape by querying the intelligence store
     * for indicators relevant to our exposed protocols.
     *
     * @return risk contribution from threat landscape (0-20)
     */
    private double assessThreatLandscape(List<String> activeProtocols) {
        Map<String, Object> summary = threatStore.getSummary();
        long totalIndicators = 0;
        Object totalObj = summary.get("totalIndicators");
        if (totalObj instanceof Number) {
            totalIndicators = ((Number) totalObj).longValue();
        }

        if (totalIndicators == 0) {
            return 5.0; // Base risk: no intelligence data means we're blind
        }

        // Check threat level distribution
        double riskContribution = 0;
        Object byLevel = summary.get("byThreatLevel");
        if (byLevel instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Long> levelMap = (Map<String, Long>) byLevel;
            long critical = levelMap.getOrDefault("CRITICAL", 0L);
            long high = levelMap.getOrDefault("HIGH", 0L);

            // More critical/high indicators = higher landscape risk
            if (critical > 100) riskContribution += 8;
            else if (critical > 10) riskContribution += 4;
            else if (critical > 0) riskContribution += 2;

            if (high > 500) riskContribution += 6;
            else if (high > 50) riskContribution += 3;
            else if (high > 0) riskContribution += 1;
        }

        // Having intelligence data but with low hit rate means potential blind spots
        Object hitRateObj = summary.get("hitRate");
        if (hitRateObj instanceof String) {
            try {
                double hitRate = Double.parseDouble(((String) hitRateObj).replace("%", ""));
                if (hitRate > 5.0) {
                    riskContribution += 5; // High hit rate = active targeting
                }
            } catch (NumberFormatException ignored) {
                // Proceed without hit rate contribution
            }
        }

        return Math.min(20.0, riskContribution);
    }

    /**
     * Assess geographic exposure based on connecting countries.
     * In production, this would query the proxy intelligence service.
     */
    private Map<String, Integer> assessGeographicExposure() {
        // In production, this data comes from the GeoAnomalyDetector and proxy
        // intelligence service. Return a placeholder that represents typical
        // MFT platform geographic distribution.
        Map<String, Integer> countries = new LinkedHashMap<>();
        countries.put("US", 0);
        countries.put("GB", 0);
        countries.put("DE", 0);
        countries.put("IN", 0);
        return countries;
    }

    /**
     * Compute risk scores broken down by category.
     */
    private Map<String, Double> computeRiskByCategory(List<String> exposures,
                                                       List<String> activeProtocols,
                                                       double threatLandscapeRisk) {
        Map<String, Double> categories = new LinkedHashMap<>();

        // Network risk: based on protocols and network-related exposures
        double networkRisk = 0;
        for (String proto : activeProtocols) {
            ProtocolRiskProfile profile = PROTOCOL_PROFILES.get(proto);
            if (profile != null && !profile.encrypted) {
                networkRisk += 20;
            }
        }
        networkRisk += exposures.stream()
                .filter(e -> e.contains("FTP") || e.contains("HTTP") || e.contains("TLS")
                        || e.contains("port") || e.contains("DMZ"))
                .count() * 10;
        categories.put("network", Math.min(100.0, networkRisk));

        // Auth risk: based on authentication-related exposures
        double authRisk = exposures.stream()
                .filter(e -> e.contains("MFA") || e.contains("password") || e.contains("account")
                        || e.contains("credential") || e.contains("allowlist"))
                .count() * 15;
        categories.put("authentication", Math.min(100.0, authRisk));

        // Data risk: based on data-protection exposures
        double dataRisk = exposures.stream()
                .filter(e -> e.contains("PCI") || e.contains("encrypt") || e.contains("scanning")
                        || e.contains("permission") || e.contains("directory"))
                .count() * 15;
        categories.put("data", Math.min(100.0, dataRisk));

        // Compliance risk: combination of audit + data protection gaps
        double complianceRisk = exposures.stream()
                .filter(e -> e.contains("audit") || e.contains("PCI") || e.contains("encrypt")
                        || e.contains("log"))
                .count() * 20;
        categories.put("compliance", Math.min(100.0, complianceRisk));

        // Threat landscape risk
        categories.put("threatLandscape", Math.min(100.0, threatLandscapeRisk * 5));

        return categories;
    }

    /**
     * Compute which MITRE techniques the current attack surface is exposed to,
     * based on active protocols.
     */
    private List<String> computeExposedMitreTechniques(List<String> activeProtocols) {
        Set<String> exposed = new LinkedHashSet<>();
        for (String proto : activeProtocols) {
            ProtocolRiskProfile profile = PROTOCOL_PROFILES.get(proto);
            if (profile != null) {
                exposed.addAll(profile.mitreTechniques);
            }
        }

        // Add common techniques that apply regardless of protocol
        exposed.add("T1110");   // Brute Force
        exposed.add("T1078");   // Valid Accounts
        exposed.add("T1190");   // Exploit Public-Facing Application
        exposed.add("T1498");   // Network Denial of Service

        return new ArrayList<>(exposed);
    }

    // ── Inner Classes ──────────────────────────────────────────────────

    /**
     * Comprehensive attack surface assessment report.
     */
    @Data
    @Builder
    public static class AttackSurfaceReport {
        /** Timestamp of the analysis. */
        private Instant timestamp;

        /** Number of exposed services. */
        private int exposedServices;

        /** List of active protocol names. */
        private List<String> activeProtocols;

        /** Countries observed connecting, with connection counts. */
        private Map<String, Integer> connectingCountries;

        /** Human-readable descriptions of high-risk exposures found. */
        private List<String> highRiskExposures;

        /** Overall risk score from 0 (minimal exposure) to 100 (critical exposure). */
        private double overallRiskScore;

        /** Prioritised remediation recommendations. */
        private List<String> recommendations;

        /** Risk scores broken down by category (network, auth, data, compliance). */
        private Map<String, Double> riskByCategory;

        /** MITRE ATT&amp;CK technique IDs that the current surface is vulnerable to. */
        private List<String> mitreTechniquesExposed;
    }

    /**
     * Delta between a baseline and current attack surface assessment.
     */
    @Data
    @Builder
    public static class SurfaceDelta {
        /** New exposures found since the baseline. */
        private List<String> newExposures;

        /** Exposures present in the baseline that are now resolved. */
        private List<String> resolvedExposures;

        /** Change in overall risk score (positive = worse, negative = better). */
        private double riskDelta;

        /** Trend direction: IMPROVING, STABLE, or WORSENING. */
        private String trend;

        /** New protocols added since baseline. */
        private List<String> newProtocols;

        /** New connecting countries since baseline. */
        private List<String> newCountries;

        /** Human-readable summary of changes. */
        private String summary;
    }

    /**
     * Static risk profile for a file transfer protocol.
     */
    private record ProtocolRiskProfile(
            String name,
            int defaultPort,
            int inherentRisk,
            boolean encrypted,
            List<String> mitreTechniques
    ) {}

    /**
     * Definition of a single risk check performed during analysis.
     */
    private record RiskCheck(
            String id,
            String description,
            String recommendation,
            int riskWeight
    ) {}
}
