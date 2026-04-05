package com.filetransfer.ai.service.intelligence;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Comprehensive MITRE ATT&amp;CK framework mapping engine.
 *
 * <p>Every detection produced by the AI engine must be mapped to one or more
 * MITRE ATT&amp;CK techniques so that analysts can reason about adversary
 * behaviour in a common language.  This service maintains a static knowledge
 * base of tactics, techniques, and sub-techniques and provides methods to:</p>
 * <ul>
 *   <li>Map observed behaviours and signals to matching techniques.</li>
 *   <li>Retrieve technique metadata by ID or tactic.</li>
 *   <li>Compute an ATT&amp;CK detection-coverage matrix for dashboard heatmaps.</li>
 *   <li>Analyse a sequence of detected techniques to infer kill-chain stage
 *       and predict likely next steps.</li>
 *   <li>Map file-transfer-specific (MFT) behaviours to MITRE techniques.</li>
 * </ul>
 */
@Service
@Slf4j
public class MitreAttackMapper {

    // ── MITRE ATT&CK Tactics ────────────────────────────────────────────

    /**
     * The 14 MITRE ATT&amp;CK Enterprise tactics, ordered by typical kill-chain
     * progression.
     */
    @Getter
    @AllArgsConstructor
    public enum Tactic {
        RECONNAISSANCE("TA0043", "Reconnaissance"),
        RESOURCE_DEVELOPMENT("TA0042", "Resource Development"),
        INITIAL_ACCESS("TA0001", "Initial Access"),
        EXECUTION("TA0002", "Execution"),
        PERSISTENCE("TA0003", "Persistence"),
        PRIVILEGE_ESCALATION("TA0004", "Privilege Escalation"),
        DEFENSE_EVASION("TA0005", "Defense Evasion"),
        CREDENTIAL_ACCESS("TA0006", "Credential Access"),
        DISCOVERY("TA0007", "Discovery"),
        LATERAL_MOVEMENT("TA0008", "Lateral Movement"),
        COLLECTION("TA0009", "Collection"),
        COMMAND_AND_CONTROL("TA0011", "Command and Control"),
        EXFILTRATION("TA0010", "Exfiltration"),
        IMPACT("TA0040", "Impact");

        private final String id;
        private final String name;

        private static final Map<String, Tactic> BY_ID = new HashMap<>();
        static {
            for (Tactic t : values()) {
                BY_ID.put(t.id, t);
            }
        }

        public static Tactic fromId(String id) {
            return BY_ID.get(id);
        }
    }

    // ── Technique Metadata ──────────────────────────────────────────────

    /**
     * Full metadata for a single MITRE ATT&amp;CK technique or sub-technique.
     */
    @Data
    @AllArgsConstructor
    @Builder
    public static class TechniqueInfo {
        private String id;
        private String name;
        private List<String> tactics;
        private String description;
        private List<String> detectionHints;
        private String severity;
        private List<String> platforms;
    }

    /**
     * Result of analysing a chain of detected techniques against the kill chain.
     */
    @Data
    @Builder
    public static class AttackChainAnalysis {
        private List<String> detectedTactics;
        private String currentStage;
        private double progressionScore;
        private List<String> predictedNextTechniques;
        private String riskAssessment;
    }

    // ── Static Technique Database ───────────────────────────────────────

    private static final Map<String, TechniqueInfo> TECHNIQUE_DB = new LinkedHashMap<>();

    /** Behaviour keywords mapped to technique IDs for fast lookup. */
    private static final Map<String, List<String>> BEHAVIOR_TECHNIQUE_MAP = new LinkedHashMap<>();

    /** Protocol keywords mapped to technique IDs. */
    private static final Map<String, List<String>> PROTOCOL_TECHNIQUE_MAP = new LinkedHashMap<>();

    /** MFT-specific behaviour types mapped to technique IDs. */
    private static final Map<String, List<String>> MFT_BEHAVIOR_MAP = new LinkedHashMap<>();

    /** Common technique progression chains. */
    private static final Map<String, List<String>> TECHNIQUE_CHAINS = new LinkedHashMap<>();

    static {
        initTechniqueDatabase();
        initBehaviorMappings();
        initProtocolMappings();
        initMftBehaviorMappings();
        initTechniqueChains();
    }

    @PostConstruct
    void init() {
        log.info("MITRE ATT&CK mapper initialized: {} techniques, {} behavior mappings, {} MFT mappings",
                TECHNIQUE_DB.size(), BEHAVIOR_TECHNIQUE_MAP.size(), MFT_BEHAVIOR_MAP.size());
    }

    // ── Public API ──────────────────────────────────────────────────────

    /**
     * Given observed behaviours and signals, return matching MITRE techniques.
     *
     * @param behavior the observed behaviour keyword (e.g., "brute_force", "data_exfiltration")
     * @param protocol the protocol context (e.g., "ssh", "ftp", "http")
     * @param context  additional contextual attributes for narrowing matches
     * @return list of matching techniques, sorted by severity descending
     */
    public List<TechniqueInfo> mapBehaviorToTechniques(String behavior, String protocol,
                                                        Map<String, Object> context) {
        Set<String> matchedIds = new LinkedHashSet<>();

        // Match by behaviour keyword
        if (behavior != null) {
            String normalised = behavior.toLowerCase().replace("-", "_").replace(" ", "_");
            for (Map.Entry<String, List<String>> entry : BEHAVIOR_TECHNIQUE_MAP.entrySet()) {
                if (normalised.contains(entry.getKey()) || entry.getKey().contains(normalised)) {
                    matchedIds.addAll(entry.getValue());
                }
            }
        }

        // Narrow / augment by protocol
        if (protocol != null) {
            String normProto = protocol.toLowerCase();
            List<String> protoTechniques = PROTOCOL_TECHNIQUE_MAP.get(normProto);
            if (protoTechniques != null) {
                if (matchedIds.isEmpty()) {
                    matchedIds.addAll(protoTechniques);
                } else {
                    // Prefer intersection; fall back to union if intersection is empty
                    Set<String> intersection = new LinkedHashSet<>(matchedIds);
                    intersection.retainAll(protoTechniques);
                    if (!intersection.isEmpty()) {
                        matchedIds = intersection;
                    } else {
                        matchedIds.addAll(protoTechniques);
                    }
                }
            }
        }

        // Refine by context attributes
        if (context != null) {
            applyContextRefinements(matchedIds, context);
        }

        return matchedIds.stream()
                .map(TECHNIQUE_DB::get)
                .filter(Objects::nonNull)
                .sorted(Comparator.comparingInt((TechniqueInfo t) -> severityOrdinal(t.getSeverity())).reversed())
                .collect(Collectors.toList());
    }

    /**
     * Retrieve full metadata for a technique by its ID (e.g., T1566.001).
     *
     * @param techniqueId the MITRE technique ID
     * @return the technique info, or {@code null} if unknown
     */
    public TechniqueInfo getTechnique(String techniqueId) {
        return TECHNIQUE_DB.get(techniqueId);
    }

    /**
     * Get all techniques that belong to a given tactic.
     *
     * @param tacticId the tactic ID (e.g., TA0001)
     * @return list of techniques mapped to that tactic
     */
    public List<TechniqueInfo> getTechniquesByTactic(String tacticId) {
        return TECHNIQUE_DB.values().stream()
                .filter(t -> t.getTactics().contains(tacticId))
                .collect(Collectors.toList());
    }

    /**
     * Calculate ATT&amp;CK detection-coverage matrix.
     *
     * <p>Returns a map keyed by tactic ID, where each value is a map of
     * technique IDs to coverage status (detected / not detected).  This
     * is used by the dashboard to render a MITRE heatmap.</p>
     *
     * @return coverage matrix suitable for JSON serialisation
     */
    public Map<String, Object> getCoverageMatrix() {
        Map<String, Object> matrix = new LinkedHashMap<>();
        Map<String, Object> summary = new LinkedHashMap<>();

        int totalTechniques = TECHNIQUE_DB.size();
        int coveredTechniques = 0;

        for (Tactic tactic : Tactic.values()) {
            List<TechniqueInfo> tacticTechniques = getTechniquesByTactic(tactic.getId());
            List<Map<String, Object>> techniqueEntries = new ArrayList<>();

            for (TechniqueInfo tech : tacticTechniques) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("id", tech.getId());
                entry.put("name", tech.getName());
                entry.put("severity", tech.getSeverity());
                entry.put("covered", true);  // all listed techniques have detection logic
                entry.put("detectionHints", tech.getDetectionHints());
                techniqueEntries.add(entry);
                coveredTechniques++;
            }

            Map<String, Object> tacticEntry = new LinkedHashMap<>();
            tacticEntry.put("tacticId", tactic.getId());
            tacticEntry.put("tacticName", tactic.getName());
            tacticEntry.put("techniques", techniqueEntries);
            tacticEntry.put("techniqueCount", tacticTechniques.size());

            matrix.put(tactic.getId(), tacticEntry);
        }

        summary.put("totalTechniques", totalTechniques);
        summary.put("coveredTechniques", coveredTechniques);
        summary.put("coveragePercent", totalTechniques > 0
                ? Math.round((double) coveredTechniques / totalTechniques * 100.0) : 0);
        matrix.put("summary", summary);

        return matrix;
    }

    /**
     * Analyse a chain of detected techniques to infer the current attack stage
     * and predict the likely next techniques an adversary would employ.
     *
     * @param detectedTechniques list of MITRE technique IDs observed so far
     * @return analysis with stage, progression score, and predicted next steps
     */
    public AttackChainAnalysis analyzeAttackChain(List<String> detectedTechniques) {
        if (detectedTechniques == null || detectedTechniques.isEmpty()) {
            return AttackChainAnalysis.builder()
                    .detectedTactics(Collections.emptyList())
                    .currentStage("None")
                    .progressionScore(0.0)
                    .predictedNextTechniques(Collections.emptyList())
                    .riskAssessment("No techniques detected.")
                    .build();
        }

        // Determine which tactics have been observed
        Set<String> detectedTacticIds = new LinkedHashSet<>();
        for (String techId : detectedTechniques) {
            TechniqueInfo info = TECHNIQUE_DB.get(techId);
            if (info != null) {
                detectedTacticIds.addAll(info.getTactics());
            }
        }

        // Determine current stage (highest tactic in kill chain)
        String currentStage = "Reconnaissance";
        int maxOrdinal = -1;
        for (String tacticId : detectedTacticIds) {
            Tactic tactic = Tactic.fromId(tacticId);
            if (tactic != null && tactic.ordinal() > maxOrdinal) {
                maxOrdinal = tactic.ordinal();
                currentStage = tactic.getName();
            }
        }

        // Progression score: fraction of kill chain covered
        double progressionScore = (maxOrdinal + 1.0) / Tactic.values().length;

        // Predict next techniques based on known chains
        List<String> predicted = predictNextTechniques(detectedTechniques);

        // Build risk assessment narrative
        String risk = buildRiskAssessment(detectedTechniques, detectedTacticIds,
                currentStage, progressionScore);

        return AttackChainAnalysis.builder()
                .detectedTactics(new ArrayList<>(detectedTacticIds))
                .currentStage(currentStage)
                .progressionScore(Math.min(1.0, progressionScore))
                .predictedNextTechniques(predicted)
                .riskAssessment(risk)
                .build();
    }

    /**
     * Map file-transfer-specific (MFT) behaviours to MITRE ATT&amp;CK techniques.
     *
     * <p>This is the MFT-domain intelligence layer that understands how adversaries
     * abuse managed file transfer platforms.  Supported behaviour types include:</p>
     * <ul>
     *   <li>{@code unusual_upload} — anomalous file upload patterns</li>
     *   <li>{@code large_outbound_transfer} — bulk data exfiltration via transfer protocols</li>
     *   <li>{@code sftp_brute_force} — SSH/SFTP credential stuffing</li>
     *   <li>{@code ftp_bounce} — FTP bounce/proxy attack</li>
     *   <li>{@code dns_tunneling} — covert channel via DNS-encoded file names</li>
     *   <li>{@code protocol_abuse} — non-standard or unexpected protocol usage</li>
     *   <li>{@code credential_theft} — stolen credentials used for file access</li>
     *   <li>{@code lateral_file_movement} — lateral tool/file transfer between hosts</li>
     *   <li>{@code encrypted_exfil} — data exfiltration over encrypted channels</li>
     *   <li>{@code scheduled_exfil} — automated/scheduled data extraction</li>
     *   <li>{@code cloud_transfer} — data staging to cloud storage</li>
     *   <li>{@code partner_impersonation} — masquerading as a trusted trading partner</li>
     *   <li>{@code directory_traversal} — path traversal attempts on file servers</li>
     *   <li>{@code malware_upload} — known-malicious file delivery via MFT</li>
     *   <li>{@code account_manipulation} — MFT account privilege changes</li>
     * </ul>
     *
     * @param behaviorType the MFT-specific behaviour type
     * @param details      additional detail attributes (file size, protocol, partner ID, etc.)
     * @return list of matching MITRE techniques
     */
    public List<TechniqueInfo> mapFileTransferBehavior(String behaviorType,
                                                        Map<String, Object> details) {
        if (behaviorType == null) {
            return Collections.emptyList();
        }

        String normalised = behaviorType.toLowerCase().replace("-", "_").replace(" ", "_");
        List<String> techniqueIds = MFT_BEHAVIOR_MAP.get(normalised);

        if (techniqueIds == null) {
            log.debug("No MFT mapping found for behavior type: {}", behaviorType);
            // Fall back to generic behaviour mapping
            return mapBehaviorToTechniques(behaviorType,
                    details != null ? (String) details.get("protocol") : null, details);
        }

        List<TechniqueInfo> results = techniqueIds.stream()
                .map(TECHNIQUE_DB::get)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        log.debug("MFT behavior '{}' mapped to {} techniques: {}", behaviorType,
                results.size(), techniqueIds);

        return results;
    }

    // ── Private Helpers ─────────────────────────────────────────────────

    private void applyContextRefinements(Set<String> matchedIds, Map<String, Object> context) {
        // Sub-technique refinement based on context
        Object subType = context.get("subType");
        if (subType != null) {
            String sub = subType.toString().toLowerCase();
            Set<String> refined = new LinkedHashSet<>();
            for (String id : matchedIds) {
                // If a sub-technique matches more specifically, prefer it
                String subId = id + "." + sub;
                if (TECHNIQUE_DB.containsKey(subId)) {
                    refined.add(subId);
                } else {
                    refined.add(id);
                }
            }
            matchedIds.clear();
            matchedIds.addAll(refined);
        }
    }

    private List<String> predictNextTechniques(List<String> detected) {
        Set<String> detectedSet = new HashSet<>(detected);
        Set<String> predictions = new LinkedHashSet<>();

        for (Map.Entry<String, List<String>> chain : TECHNIQUE_CHAINS.entrySet()) {
            List<String> sequence = chain.getValue();
            int lastMatchIdx = -1;
            for (int i = 0; i < sequence.size(); i++) {
                if (detectedSet.contains(sequence.get(i))) {
                    lastMatchIdx = i;
                }
            }
            // Predict the technique(s) immediately after the last matched position
            if (lastMatchIdx >= 0 && lastMatchIdx < sequence.size() - 1) {
                for (int i = lastMatchIdx + 1; i < Math.min(lastMatchIdx + 3, sequence.size()); i++) {
                    if (!detectedSet.contains(sequence.get(i))) {
                        predictions.add(sequence.get(i));
                    }
                }
            }
        }

        // Also predict techniques from the next tactic in kill-chain order
        Set<String> detectedTacticIds = new LinkedHashSet<>();
        for (String techId : detected) {
            TechniqueInfo info = TECHNIQUE_DB.get(techId);
            if (info != null) {
                detectedTacticIds.addAll(info.getTactics());
            }
        }

        int maxOrdinal = -1;
        for (String tacticId : detectedTacticIds) {
            Tactic tactic = Tactic.fromId(tacticId);
            if (tactic != null && tactic.ordinal() > maxOrdinal) {
                maxOrdinal = tactic.ordinal();
            }
        }

        if (maxOrdinal >= 0 && maxOrdinal < Tactic.values().length - 1) {
            Tactic nextTactic = Tactic.values()[maxOrdinal + 1];
            TECHNIQUE_DB.values().stream()
                    .filter(t -> t.getTactics().contains(nextTactic.getId()))
                    .filter(t -> "critical".equals(t.getSeverity()) || "high".equals(t.getSeverity()))
                    .limit(3)
                    .forEach(t -> predictions.add(t.getId()));
        }

        return new ArrayList<>(predictions);
    }

    private String buildRiskAssessment(List<String> detectedTechniques,
                                        Set<String> detectedTacticIds,
                                        String currentStage,
                                        double progressionScore) {
        StringBuilder sb = new StringBuilder();

        sb.append("Detected ").append(detectedTechniques.size()).append(" MITRE ATT&CK technique(s) ")
                .append("across ").append(detectedTacticIds.size()).append(" tactic(s). ");
        sb.append("Current attack stage: ").append(currentStage).append(". ");

        if (progressionScore >= 0.7) {
            sb.append("CRITICAL: Adversary has progressed to late-stage kill chain (")
                    .append(String.format("%.0f%%", progressionScore * 100))
                    .append(" progression). Immediate containment recommended. ");
        } else if (progressionScore >= 0.4) {
            sb.append("HIGH RISK: Attack is in mid-stage progression (")
                    .append(String.format("%.0f%%", progressionScore * 100))
                    .append("). Active investigation and containment planning advised. ");
        } else {
            sb.append("MODERATE RISK: Early-stage indicators detected (")
                    .append(String.format("%.0f%%", progressionScore * 100))
                    .append(" progression). Continue monitoring and gather additional telemetry. ");
        }

        // Check for lateral movement or exfiltration — highest urgency
        if (detectedTacticIds.contains(Tactic.EXFILTRATION.getId())) {
            sb.append("DATA EXFILTRATION tactics detected — immediate response required. ");
        }
        if (detectedTacticIds.contains(Tactic.LATERAL_MOVEMENT.getId())) {
            sb.append("LATERAL MOVEMENT observed — adversary may be spreading within the environment. ");
        }

        return sb.toString().strip();
    }

    private static int severityOrdinal(String severity) {
        if (severity == null) return 0;
        return switch (severity.toLowerCase()) {
            case "critical" -> 4;
            case "high" -> 3;
            case "medium" -> 2;
            case "low" -> 1;
            default -> 0;
        };
    }

    // ── Static Initialisation: Technique Database ───────────────────────

    private static void addTechnique(String id, String name, List<String> tactics,
                                      String description, List<String> detectionHints,
                                      String severity, List<String> platforms) {
        TECHNIQUE_DB.put(id, TechniqueInfo.builder()
                .id(id).name(name).tactics(tactics).description(description)
                .detectionHints(detectionHints).severity(severity).platforms(platforms)
                .build());
    }

    private static void initTechniqueDatabase() {
        // ── TA0043 Reconnaissance ───────────────────────────────────────
        addTechnique("T1595", "Active Scanning", List.of("TA0043"),
                "Adversaries may scan victim infrastructure to gather information for targeting.",
                List.of("Port scan detection", "Sequential connection attempts", "Service enumeration patterns"),
                "medium", List.of("network"));
        addTechnique("T1595.001", "Scanning IP Blocks", List.of("TA0043"),
                "Adversaries may scan victim IP blocks to gather information.",
                List.of("Sequential IP access", "Rapid connection to many IPs in a subnet"),
                "medium", List.of("network"));
        addTechnique("T1595.002", "Vulnerability Scanning", List.of("TA0043"),
                "Adversaries may scan for vulnerabilities in victim systems.",
                List.of("Known vulnerability scanner signatures", "Unusual probe patterns"),
                "high", List.of("network"));
        addTechnique("T1589", "Gather Victim Identity Information", List.of("TA0043"),
                "Adversaries may gather identity information about victims.",
                List.of("Username enumeration attempts", "Credential stuffing patterns"),
                "medium", List.of("network", "cloud"));
        addTechnique("T1590", "Gather Victim Network Information", List.of("TA0043"),
                "Adversaries may gather network information about victims.",
                List.of("DNS reconnaissance", "Whois lookups", "Network mapping"),
                "low", List.of("network"));

        // ── TA0042 Resource Development ─────────────────────────────────
        addTechnique("T1583", "Acquire Infrastructure", List.of("TA0042"),
                "Adversaries may buy, lease, or rent infrastructure for operations.",
                List.of("Connections from newly registered domains", "VPS/cloud hosting provider IPs"),
                "medium", List.of("network", "cloud"));
        addTechnique("T1584", "Compromise Infrastructure", List.of("TA0042"),
                "Adversaries may compromise third-party infrastructure to use in operations.",
                List.of("Traffic from known-compromised infrastructure", "Botnet C2 communication"),
                "high", List.of("network"));
        addTechnique("T1587", "Develop Capabilities", List.of("TA0042"),
                "Adversaries may build capabilities (malware, exploits) for use in operations.",
                List.of("Novel malware signatures", "Zero-day exploit indicators"),
                "high", List.of("windows", "linux", "macos"));
        addTechnique("T1588", "Obtain Capabilities", List.of("TA0042"),
                "Adversaries may obtain tools, malware, or exploits from external sources.",
                List.of("Known exploit framework signatures", "Cobalt Strike beacons"),
                "high", List.of("windows", "linux", "macos"));

        // ── TA0001 Initial Access ───────────────────────────────────────
        addTechnique("T1190", "Exploit Public-Facing Application", List.of("TA0001"),
                "Adversaries may exploit vulnerabilities in internet-facing applications.",
                List.of("Exploit payload in HTTP requests", "CVE-specific signatures", "Unexpected application errors"),
                "critical", List.of("windows", "linux", "network", "cloud"));
        addTechnique("T1133", "External Remote Services", List.of("TA0001", "TA0003"),
                "Adversaries may use external remote services (VPN, RDP, SSH) for initial access.",
                List.of("Connections from unexpected geolocations", "Off-hours remote access", "Brute force on remote service ports"),
                "high", List.of("windows", "linux", "network"));
        addTechnique("T1566", "Phishing", List.of("TA0001"),
                "Adversaries may send phishing messages to gain access to victim systems.",
                List.of("Suspicious email attachments", "URL links to malicious domains", "Spear-phishing indicators"),
                "high", List.of("windows", "linux", "macos"));
        addTechnique("T1566.001", "Spearphishing Attachment", List.of("TA0001"),
                "Adversaries send spearphishing emails with malicious attachments.",
                List.of("Macro-enabled documents", "Executable attachments", "Archive files with executables"),
                "high", List.of("windows", "linux", "macos"));
        addTechnique("T1566.002", "Spearphishing Link", List.of("TA0001"),
                "Adversaries send spearphishing emails with malicious links.",
                List.of("URLs to credential harvesting pages", "Shortened/obfuscated URLs in email"),
                "high", List.of("windows", "linux", "macos"));
        addTechnique("T1566.003", "Spearphishing via Service", List.of("TA0001"),
                "Adversaries send phishing messages via third-party services.",
                List.of("Phishing via social media", "Malicious links in messaging platforms"),
                "medium", List.of("windows", "linux", "macos"));
        addTechnique("T1078", "Valid Accounts", List.of("TA0001", "TA0003", "TA0004", "TA0005"),
                "Adversaries may use valid accounts to gain initial access or maintain persistence.",
                List.of("Credential use from unusual location", "Impossible travel", "Off-hours login"),
                "critical", List.of("windows", "linux", "macos", "cloud"));
        addTechnique("T1078.001", "Default Accounts", List.of("TA0001", "TA0003", "TA0004", "TA0005"),
                "Adversaries may use default credentials on systems or devices.",
                List.of("Login with factory-default credentials", "Default admin account usage"),
                "high", List.of("windows", "linux", "network"));
        addTechnique("T1078.002", "Domain Accounts", List.of("TA0001", "TA0003", "TA0004", "TA0005"),
                "Adversaries may use compromised domain accounts.",
                List.of("Domain account login from unexpected host", "Kerberoasting indicators"),
                "high", List.of("windows"));
        addTechnique("T1078.003", "Local Accounts", List.of("TA0001", "TA0003", "TA0004", "TA0005"),
                "Adversaries may use compromised local accounts.",
                List.of("Local account login from remote source", "Password spray against local accounts"),
                "high", List.of("windows", "linux", "macos"));
        addTechnique("T1078.004", "Cloud Accounts", List.of("TA0001", "TA0003", "TA0004", "TA0005"),
                "Adversaries may use compromised cloud accounts.",
                List.of("Cloud account login from new device", "MFA bypass attempts"),
                "critical", List.of("cloud"));
        addTechnique("T1199", "Trusted Relationship", List.of("TA0001"),
                "Adversaries may abuse trusted third-party relationships for access.",
                List.of("Partner account anomalous behavior", "Third-party VPN unusual activity"),
                "high", List.of("windows", "linux", "cloud"));
        addTechnique("T1195", "Supply Chain Compromise", List.of("TA0001"),
                "Adversaries may manipulate products or delivery mechanisms before receipt.",
                List.of("Modified software packages", "Unexpected software update sources"),
                "critical", List.of("windows", "linux", "macos"));

        // ── TA0002 Execution ────────────────────────────────────────────
        addTechnique("T1059", "Command and Scripting Interpreter", List.of("TA0002"),
                "Adversaries may abuse command and script interpreters to execute commands.",
                List.of("Unusual script execution", "PowerShell with encoded commands", "Bash reverse shells"),
                "high", List.of("windows", "linux", "macos"));
        addTechnique("T1059.001", "PowerShell", List.of("TA0002"),
                "Adversaries may abuse PowerShell for execution.",
                List.of("Encoded PowerShell commands", "PowerShell download cradle", "Invoke-Expression usage"),
                "high", List.of("windows"));
        addTechnique("T1059.003", "Windows Command Shell", List.of("TA0002"),
                "Adversaries may abuse the Windows command shell for execution.",
                List.of("cmd.exe spawned by unusual parent", "Batch file execution from temp directories"),
                "medium", List.of("windows"));
        addTechnique("T1059.004", "Unix Shell", List.of("TA0002"),
                "Adversaries may abuse Unix shell for execution.",
                List.of("Reverse shell connections", "Cron-based script execution", "Shell spawned from web server"),
                "high", List.of("linux", "macos"));
        addTechnique("T1203", "Exploitation for Client Execution", List.of("TA0002"),
                "Adversaries may exploit software vulnerabilities in client applications.",
                List.of("Application crash followed by execution", "Exploit document opened"),
                "critical", List.of("windows", "linux", "macos"));
        addTechnique("T1204", "User Execution", List.of("TA0002"),
                "Adversaries may rely on user action to execute malicious content.",
                List.of("User opens malicious attachment", "User clicks phishing link"),
                "medium", List.of("windows", "linux", "macos"));

        // ── TA0003 Persistence ──────────────────────────────────────────
        addTechnique("T1098", "Account Manipulation", List.of("TA0003", "TA0004"),
                "Adversaries may manipulate accounts to maintain access.",
                List.of("Account permission changes", "SSH key added to authorized_keys", "MFA device registration"),
                "high", List.of("windows", "linux", "cloud"));
        addTechnique("T1098.001", "Additional Cloud Credentials", List.of("TA0003", "TA0004"),
                "Adversaries may add credentials to cloud accounts for persistence.",
                List.of("New API key created", "Service principal credential added"),
                "high", List.of("cloud"));
        addTechnique("T1098.004", "SSH Authorized Keys", List.of("TA0003", "TA0004"),
                "Adversaries may modify SSH authorized_keys files for persistent access.",
                List.of("authorized_keys file modification", "New SSH key added for existing account"),
                "high", List.of("linux", "macos"));
        addTechnique("T1136", "Create Account", List.of("TA0003"),
                "Adversaries may create accounts to maintain access.",
                List.of("New account creation outside normal process", "Service account created by non-admin"),
                "high", List.of("windows", "linux", "cloud"));
        addTechnique("T1136.001", "Local Account", List.of("TA0003"),
                "Adversaries may create local accounts for persistence.",
                List.of("New local user account", "Account created via command line"),
                "high", List.of("windows", "linux"));
        addTechnique("T1136.003", "Cloud Account", List.of("TA0003"),
                "Adversaries may create cloud accounts for persistence.",
                List.of("New cloud IAM user", "Programmatic account creation"),
                "high", List.of("cloud"));
        addTechnique("T1053", "Scheduled Task/Job", List.of("TA0002", "TA0003", "TA0004"),
                "Adversaries may abuse task scheduling for persistence or execution.",
                List.of("New scheduled task/cron job", "Task created by unusual user", "Job executing from temp path"),
                "high", List.of("windows", "linux", "macos"));
        addTechnique("T1053.003", "Cron", List.of("TA0002", "TA0003", "TA0004"),
                "Adversaries may abuse cron for persistence on Linux/macOS.",
                List.of("New crontab entry", "Cron job executing suspicious script"),
                "high", List.of("linux", "macos"));
        addTechnique("T1053.005", "Scheduled Task", List.of("TA0002", "TA0003", "TA0004"),
                "Adversaries may abuse Windows Task Scheduler for persistence.",
                List.of("New scheduled task via schtasks", "Task triggering executable from unusual path"),
                "high", List.of("windows"));

        // ── TA0004 Privilege Escalation ─────────────────────────────────
        addTechnique("T1068", "Exploitation for Privilege Escalation", List.of("TA0004"),
                "Adversaries may exploit vulnerabilities to escalate privileges.",
                List.of("Kernel exploit indicators", "Process privilege escalation", "Unusual SUID binary execution"),
                "critical", List.of("windows", "linux", "macos"));
        addTechnique("T1548", "Abuse Elevation Control Mechanism", List.of("TA0004", "TA0005"),
                "Adversaries may circumvent elevation controls to gain higher privileges.",
                List.of("UAC bypass", "Sudo abuse", "SetUID/SetGID manipulation"),
                "high", List.of("windows", "linux", "macos"));

        // ── TA0005 Defense Evasion ──────────────────────────────────────
        addTechnique("T1027", "Obfuscated Files or Information", List.of("TA0005"),
                "Adversaries may obfuscate files or information to evade detection.",
                List.of("Base64-encoded payloads", "Packed executables", "Encrypted archives with executables"),
                "high", List.of("windows", "linux", "macos"));
        addTechnique("T1027.001", "Binary Padding", List.of("TA0005"),
                "Adversaries may pad binaries to change their hash and evade detection.",
                List.of("File size anomalies", "Trailing null bytes in executables"),
                "medium", List.of("windows", "linux"));
        addTechnique("T1027.002", "Software Packing", List.of("TA0005"),
                "Adversaries may pack executables to evade signature-based detection.",
                List.of("Known packer signatures", "High entropy executables"),
                "medium", List.of("windows", "linux"));
        addTechnique("T1562", "Impair Defenses", List.of("TA0005"),
                "Adversaries may disable or modify security tools to avoid detection.",
                List.of("Security service stopped", "Firewall rules modified", "Logging disabled"),
                "critical", List.of("windows", "linux", "cloud"));
        addTechnique("T1562.001", "Disable or Modify Tools", List.of("TA0005"),
                "Adversaries may disable security tools to evade detection.",
                List.of("Antivirus service stopped", "EDR agent killed", "Security agent uninstalled"),
                "critical", List.of("windows", "linux", "macos"));
        addTechnique("T1562.004", "Disable or Modify System Firewall", List.of("TA0005"),
                "Adversaries may disable or modify the system firewall.",
                List.of("Firewall disabled", "New permissive firewall rule", "iptables flush"),
                "high", List.of("windows", "linux"));
        addTechnique("T1218", "System Binary Proxy Execution", List.of("TA0005"),
                "Adversaries may use trusted system binaries to proxy execution of malicious code.",
                List.of("LOLBin execution", "Rundll32 with suspicious arguments", "Mshta executing scripts"),
                "high", List.of("windows"));
        addTechnique("T1218.011", "Rundll32", List.of("TA0005"),
                "Adversaries may abuse Rundll32 to proxy execution.",
                List.of("Rundll32 loading DLL from unusual path", "Rundll32 with URL argument"),
                "high", List.of("windows"));
        addTechnique("T1070", "Indicator Removal", List.of("TA0005"),
                "Adversaries may delete or modify artifacts to remove evidence.",
                List.of("Log file deletion", "Event log cleared", "Timestamp modification"),
                "high", List.of("windows", "linux", "macos"));

        // ── TA0006 Credential Access ────────────────────────────────────
        addTechnique("T1110", "Brute Force", List.of("TA0006"),
                "Adversaries may use brute force techniques to obtain account credentials.",
                List.of("Multiple failed login attempts", "Password spray patterns", "Credential stuffing"),
                "high", List.of("windows", "linux", "cloud", "network"));
        addTechnique("T1110.001", "Password Guessing", List.of("TA0006"),
                "Adversaries may guess passwords to attempt access to accounts.",
                List.of("Sequential password attempts", "Common password list usage"),
                "high", List.of("windows", "linux", "cloud"));
        addTechnique("T1110.002", "Password Cracking", List.of("TA0006"),
                "Adversaries may crack password hashes offline.",
                List.of("Hash extraction from SAM/shadow", "NTLM hash capture"),
                "high", List.of("windows", "linux"));
        addTechnique("T1110.003", "Password Spraying", List.of("TA0006"),
                "Adversaries may spray a single password across many accounts.",
                List.of("Same password tried against multiple accounts", "Low-and-slow auth failures"),
                "high", List.of("windows", "linux", "cloud"));
        addTechnique("T1110.004", "Credential Stuffing", List.of("TA0006"),
                "Adversaries may use stolen credential pairs from breaches.",
                List.of("Known breached credential pairs", "Automated login attempts from proxy networks"),
                "critical", List.of("windows", "linux", "cloud", "network"));
        addTechnique("T1003", "OS Credential Dumping", List.of("TA0006"),
                "Adversaries may dump credentials from the operating system.",
                List.of("LSASS memory access", "SAM database access", "Mimikatz signatures"),
                "critical", List.of("windows", "linux"));
        addTechnique("T1003.001", "LSASS Memory", List.of("TA0006"),
                "Adversaries may access LSASS process memory to extract credentials.",
                List.of("LSASS memory dump", "Procdump targeting LSASS", "Credential guard bypass"),
                "critical", List.of("windows"));
        addTechnique("T1552", "Unsecured Credentials", List.of("TA0006"),
                "Adversaries may search for unsecured credentials in various locations.",
                List.of("Credential files accessed", "Configuration files with passwords read"),
                "high", List.of("windows", "linux", "cloud"));
        addTechnique("T1557", "Adversary-in-the-Middle", List.of("TA0006", "TA0009"),
                "Adversaries may position themselves to intercept communications.",
                List.of("ARP spoofing", "SSL stripping", "Man-in-the-middle proxy"),
                "critical", List.of("windows", "linux", "network"));

        // ── TA0007 Discovery ────────────────────────────────────────────
        addTechnique("T1046", "Network Service Discovery", List.of("TA0007"),
                "Adversaries may scan for services running on remote hosts.",
                List.of("Port scanning from internal host", "Service enumeration", "Banner grabbing"),
                "medium", List.of("windows", "linux", "network"));
        addTechnique("T1135", "Network Share Discovery", List.of("TA0007"),
                "Adversaries may enumerate network shares to identify targets for lateral movement.",
                List.of("SMB share enumeration", "Net share commands", "NFS mount enumeration"),
                "medium", List.of("windows", "linux"));
        addTechnique("T1040", "Network Sniffing", List.of("TA0006", "TA0007"),
                "Adversaries may sniff network traffic to capture credentials or data.",
                List.of("Promiscuous mode enabled", "Packet capture tools running", "Wireshark/tcpdump on server"),
                "high", List.of("windows", "linux", "network"));
        addTechnique("T1018", "Remote System Discovery", List.of("TA0007"),
                "Adversaries may enumerate remote systems on a network.",
                List.of("ARP scan", "Net view commands", "Ping sweep"),
                "low", List.of("windows", "linux"));
        addTechnique("T1082", "System Information Discovery", List.of("TA0007"),
                "Adversaries may gather system information.",
                List.of("System info commands", "WMI queries", "uname -a execution"),
                "low", List.of("windows", "linux", "macos"));
        addTechnique("T1083", "File and Directory Discovery", List.of("TA0007"),
                "Adversaries may enumerate files and directories.",
                List.of("Recursive directory listing", "Unusual directory traversal", "Large-scale file enumeration"),
                "medium", List.of("windows", "linux", "macos"));
        addTechnique("T1057", "Process Discovery", List.of("TA0007"),
                "Adversaries may enumerate running processes.",
                List.of("Process listing commands", "Tasklist/ps output", "Security tool process detection"),
                "low", List.of("windows", "linux", "macos"));

        // ── TA0008 Lateral Movement ─────────────────────────────────────
        addTechnique("T1021", "Remote Services", List.of("TA0008"),
                "Adversaries may use remote services to move laterally within a network.",
                List.of("Unusual remote service login", "Lateral RDP/SSH from compromised host"),
                "high", List.of("windows", "linux", "network"));
        addTechnique("T1021.001", "Remote Desktop Protocol", List.of("TA0008"),
                "Adversaries may use RDP to move laterally.",
                List.of("RDP login from internal host", "RDP session from non-admin workstation"),
                "high", List.of("windows"));
        addTechnique("T1021.002", "SMB/Windows Admin Shares", List.of("TA0008"),
                "Adversaries may use SMB shares for lateral movement.",
                List.of("Admin share access (C$, ADMIN$)", "PsExec execution", "SMB file copy"),
                "high", List.of("windows"));
        addTechnique("T1021.004", "SSH", List.of("TA0008"),
                "Adversaries may use SSH for lateral movement.",
                List.of("SSH login from unexpected internal host", "SSH key-based lateral movement"),
                "high", List.of("linux", "macos"));
        addTechnique("T1021.005", "VNC", List.of("TA0008"),
                "Adversaries may use VNC for lateral movement.",
                List.of("VNC connection from internal host", "Unusual VNC port activity"),
                "medium", List.of("windows", "linux"));
        addTechnique("T1570", "Lateral Tool Transfer", List.of("TA0008"),
                "Adversaries may transfer tools between systems during lateral movement.",
                List.of("File transfer between internal hosts", "Tool staging on network shares"),
                "high", List.of("windows", "linux"));

        // ── TA0009 Collection ───────────────────────────────────────────
        addTechnique("T1114", "Email Collection", List.of("TA0009"),
                "Adversaries may collect email data from mail servers or clients.",
                List.of("Mailbox export", "Email forwarding rules created", "Bulk email download"),
                "high", List.of("windows", "cloud"));
        addTechnique("T1114.002", "Remote Email Collection", List.of("TA0009"),
                "Adversaries may collect email from remote mail servers.",
                List.of("IMAP/POP3 bulk download", "EWS mailbox access", "Graph API mail read"),
                "high", List.of("cloud"));
        addTechnique("T1560", "Archive Collected Data", List.of("TA0009"),
                "Adversaries may compress and encrypt collected data before exfiltration.",
                List.of("Large archive creation", "7zip/rar with password", "Staging directory compression"),
                "medium", List.of("windows", "linux", "macos"));
        addTechnique("T1074", "Data Staged", List.of("TA0009"),
                "Adversaries may stage collected data in a central location before exfiltration.",
                List.of("Data copied to staging directory", "Unusual directory with many files", "Temp directory data accumulation"),
                "medium", List.of("windows", "linux", "cloud"));
        addTechnique("T1119", "Automated Collection", List.of("TA0009"),
                "Adversaries may use automated methods to collect data.",
                List.of("Scripted data collection", "Scheduled data gathering", "API-based bulk data retrieval"),
                "high", List.of("windows", "linux", "cloud"));

        // ── TA0011 Command and Control ──────────────────────────────────
        addTechnique("T1071", "Application Layer Protocol", List.of("TA0011"),
                "Adversaries may communicate using application layer protocols to blend in with normal traffic.",
                List.of("Unusual HTTP POST patterns", "DNS tunneling", "HTTPS beaconing"),
                "high", List.of("windows", "linux", "network"));
        addTechnique("T1071.001", "Web Protocols", List.of("TA0011"),
                "Adversaries may use HTTP/HTTPS for C2 communication.",
                List.of("HTTP beacon intervals", "Unusual User-Agent strings", "POST-heavy traffic to single domain"),
                "high", List.of("windows", "linux", "network"));
        addTechnique("T1071.002", "File Transfer Protocols", List.of("TA0011"),
                "Adversaries may use file transfer protocols (FTP, SFTP) for C2.",
                List.of("FTP to unusual external server", "SFTP data channel anomalies", "Automated FTP scripts"),
                "high", List.of("windows", "linux", "network"));
        addTechnique("T1071.004", "DNS", List.of("TA0011"),
                "Adversaries may use DNS for C2 communication.",
                List.of("High-entropy DNS queries", "Excessive TXT record queries", "DNS query volume anomaly"),
                "high", List.of("windows", "linux", "network"));
        addTechnique("T1105", "Ingress Tool Transfer", List.of("TA0011"),
                "Adversaries may transfer tools or files from an external system into the environment.",
                List.of("Unusual file downloads", "Binary transferred via non-standard protocol", "Tool drop from external URL"),
                "high", List.of("windows", "linux", "network"));
        addTechnique("T1571", "Non-Standard Port", List.of("TA0011"),
                "Adversaries may use non-standard ports for C2 to bypass filtering.",
                List.of("HTTP on non-80/443 port", "SSH on non-22 port", "Known protocol on unusual port"),
                "medium", List.of("windows", "linux", "network"));
        addTechnique("T1572", "Protocol Tunneling", List.of("TA0011"),
                "Adversaries may tunnel network communications through another protocol to evade detection.",
                List.of("DNS tunneling", "ICMP tunneling", "HTTP tunneling", "SSH tunneling"),
                "high", List.of("windows", "linux", "network"));
        addTechnique("T1573", "Encrypted Channel", List.of("TA0011"),
                "Adversaries may use encrypted channels for C2 communication.",
                List.of("Non-standard TLS certificates", "Unusual encrypted traffic volume", "Self-signed certificate connections"),
                "medium", List.of("windows", "linux", "network"));
        addTechnique("T1573.001", "Symmetric Cryptography", List.of("TA0011"),
                "Adversaries may use symmetric encryption for C2.",
                List.of("XOR-encoded traffic", "Custom symmetric encryption in traffic"),
                "medium", List.of("windows", "linux"));
        addTechnique("T1573.002", "Asymmetric Cryptography", List.of("TA0011"),
                "Adversaries may use asymmetric encryption for C2.",
                List.of("RSA-encrypted C2 traffic", "Non-standard TLS handshake"),
                "medium", List.of("windows", "linux"));
        addTechnique("T1090", "Proxy", List.of("TA0011"),
                "Adversaries may use proxies to direct C2 traffic through intermediaries.",
                List.of("Multi-hop connection patterns", "Traffic relayed through compromised hosts", "SOCKS proxy usage"),
                "high", List.of("windows", "linux", "network"));
        addTechnique("T1090.001", "Internal Proxy", List.of("TA0011"),
                "Adversaries may use internal proxies to relay C2 traffic.",
                List.of("Internal host acting as proxy", "Port forwarding on compromised host"),
                "high", List.of("windows", "linux"));
        addTechnique("T1090.002", "External Proxy", List.of("TA0011"),
                "Adversaries may use external proxies to relay C2 traffic.",
                List.of("Traffic to known proxy services", "Tor exit node connections"),
                "high", List.of("windows", "linux", "network"));
        addTechnique("T1090.003", "Multi-hop Proxy", List.of("TA0011"),
                "Adversaries may chain multiple proxies together.",
                List.of("Multi-hop latency patterns", "Tor/I2P traffic", "VPN chaining"),
                "high", List.of("network"));
        addTechnique("T1219", "Remote Access Software", List.of("TA0011"),
                "Adversaries may use legitimate remote access tools for C2.",
                List.of("TeamViewer/AnyDesk/ScreenConnect on server", "Remote access tool installed without approval"),
                "high", List.of("windows", "linux", "macos"));

        // ── TA0010 Exfiltration ─────────────────────────────────────────
        addTechnique("T1048", "Exfiltration Over Alternative Protocol", List.of("TA0010"),
                "Adversaries may exfiltrate data using a protocol different from the existing C2 channel.",
                List.of("Large data transfer on unusual protocol", "FTP/SFTP outbound to unknown server", "DNS exfiltration"),
                "critical", List.of("windows", "linux", "network"));
        addTechnique("T1048.001", "Exfiltration Over Symmetric Encrypted Non-C2 Protocol", List.of("TA0010"),
                "Adversaries may exfiltrate using a symmetric encrypted channel that is not C2.",
                List.of("Encrypted file transfer to external host", "SCP to unknown destination"),
                "critical", List.of("windows", "linux"));
        addTechnique("T1048.002", "Exfiltration Over Asymmetric Encrypted Non-C2 Protocol", List.of("TA0010"),
                "Adversaries may exfiltrate using asymmetric encryption that is not C2.",
                List.of("SFTP to external host", "HTTPS upload to unknown endpoint"),
                "critical", List.of("windows", "linux"));
        addTechnique("T1048.003", "Exfiltration Over Unencrypted Non-C2 Protocol", List.of("TA0010"),
                "Adversaries may exfiltrate data over an unencrypted protocol.",
                List.of("FTP upload to external host", "HTTP POST with large payload", "TFTP outbound transfer"),
                "critical", List.of("windows", "linux"));
        addTechnique("T1041", "Exfiltration Over C2 Channel", List.of("TA0010"),
                "Adversaries may exfiltrate data over the existing C2 channel.",
                List.of("Large outbound data on C2 connection", "Chunked data exfiltration"),
                "high", List.of("windows", "linux"));
        addTechnique("T1537", "Transfer Data to Cloud Account", List.of("TA0010"),
                "Adversaries may exfiltrate data by transferring it to a cloud account.",
                List.of("Data upload to S3/Azure Blob/GCS", "Cloud storage API bulk upload", "rclone usage"),
                "critical", List.of("cloud"));
        addTechnique("T1567", "Exfiltration Over Web Service", List.of("TA0010"),
                "Adversaries may exfiltrate data over web services like cloud storage or paste sites.",
                List.of("Upload to Dropbox/Google Drive/OneDrive", "Data posted to pastebin", "Web service bulk upload"),
                "high", List.of("windows", "linux", "cloud"));
        addTechnique("T1567.002", "Exfiltration to Cloud Storage", List.of("TA0010"),
                "Adversaries may exfiltrate data to cloud storage services.",
                List.of("Bulk upload to cloud storage", "Unauthorized cloud sync client", "rclone to cloud provider"),
                "high", List.of("windows", "linux", "cloud"));
        addTechnique("T1029", "Scheduled Transfer", List.of("TA0010"),
                "Adversaries may schedule data exfiltration to occur at certain times.",
                List.of("Automated outbound transfer at fixed intervals", "Cron-triggered data upload"),
                "high", List.of("windows", "linux"));
        addTechnique("T1030", "Data Transfer Size Limits", List.of("TA0010"),
                "Adversaries may exfiltrate data in fixed-size chunks to avoid detection.",
                List.of("Consistent small-size outbound transfers", "Chunked data exfiltration pattern"),
                "medium", List.of("windows", "linux"));

        // ── TA0040 Impact ───────────────────────────────────────────────
        addTechnique("T1486", "Data Encrypted for Impact", List.of("TA0040"),
                "Adversaries may encrypt data on target systems (ransomware).",
                List.of("Ransomware file encryption", "Mass file extension changes", "Ransom note creation"),
                "critical", List.of("windows", "linux"));
        addTechnique("T1485", "Data Destruction", List.of("TA0040"),
                "Adversaries may destroy data and files on systems.",
                List.of("Mass file deletion", "Disk wiper execution", "Database drop"),
                "critical", List.of("windows", "linux"));
        addTechnique("T1489", "Service Stop", List.of("TA0040"),
                "Adversaries may stop services to render systems or data unavailable.",
                List.of("Critical service stopped", "Database service killed", "File transfer service interrupted"),
                "high", List.of("windows", "linux"));
        addTechnique("T1490", "Inhibit System Recovery", List.of("TA0040"),
                "Adversaries may delete or disable recovery features.",
                List.of("Shadow copies deleted", "Backup service disabled", "Boot recovery disabled"),
                "critical", List.of("windows", "linux"));
        addTechnique("T1491", "Defacement", List.of("TA0040"),
                "Adversaries may deface systems to deliver messaging.",
                List.of("Web content replaced", "Desktop wallpaper changed", "File content overwritten"),
                "medium", List.of("windows", "linux"));
        addTechnique("T1498", "Network Denial of Service", List.of("TA0040"),
                "Adversaries may perform DDoS to degrade or block availability.",
                List.of("Traffic volume spike", "SYN flood", "Application-layer DDoS"),
                "high", List.of("network"));
        addTechnique("T1499", "Endpoint Denial of Service", List.of("TA0040"),
                "Adversaries may perform DoS targeting specific endpoints.",
                List.of("Service resource exhaustion", "Application crash loop", "API abuse"),
                "high", List.of("windows", "linux", "network"));
    }

    private static void initBehaviorMappings() {
        BEHAVIOR_TECHNIQUE_MAP.put("brute_force", List.of("T1110", "T1110.001", "T1110.003", "T1110.004"));
        BEHAVIOR_TECHNIQUE_MAP.put("credential_stuffing", List.of("T1110.004"));
        BEHAVIOR_TECHNIQUE_MAP.put("password_spray", List.of("T1110.003"));
        BEHAVIOR_TECHNIQUE_MAP.put("password_guess", List.of("T1110.001"));
        BEHAVIOR_TECHNIQUE_MAP.put("phishing", List.of("T1566", "T1566.001", "T1566.002"));
        BEHAVIOR_TECHNIQUE_MAP.put("exploit", List.of("T1190", "T1203", "T1068"));
        BEHAVIOR_TECHNIQUE_MAP.put("scanning", List.of("T1595", "T1595.001", "T1595.002", "T1046"));
        BEHAVIOR_TECHNIQUE_MAP.put("port_scan", List.of("T1595.001", "T1046"));
        BEHAVIOR_TECHNIQUE_MAP.put("vulnerability_scan", List.of("T1595.002"));
        BEHAVIOR_TECHNIQUE_MAP.put("data_exfiltration", List.of("T1048", "T1041", "T1567"));
        BEHAVIOR_TECHNIQUE_MAP.put("exfiltration", List.of("T1048", "T1048.001", "T1048.002", "T1048.003"));
        BEHAVIOR_TECHNIQUE_MAP.put("lateral_movement", List.of("T1021", "T1570"));
        BEHAVIOR_TECHNIQUE_MAP.put("remote_access", List.of("T1219", "T1133"));
        BEHAVIOR_TECHNIQUE_MAP.put("command_control", List.of("T1071", "T1105", "T1572"));
        BEHAVIOR_TECHNIQUE_MAP.put("tunneling", List.of("T1572"));
        BEHAVIOR_TECHNIQUE_MAP.put("dns_tunneling", List.of("T1572", "T1071.004"));
        BEHAVIOR_TECHNIQUE_MAP.put("proxy", List.of("T1090", "T1090.001", "T1090.002"));
        BEHAVIOR_TECHNIQUE_MAP.put("ransomware", List.of("T1486", "T1490"));
        BEHAVIOR_TECHNIQUE_MAP.put("data_destruction", List.of("T1485"));
        BEHAVIOR_TECHNIQUE_MAP.put("denial_of_service", List.of("T1498", "T1499"));
        BEHAVIOR_TECHNIQUE_MAP.put("ddos", List.of("T1498"));
        BEHAVIOR_TECHNIQUE_MAP.put("account_manipulation", List.of("T1098", "T1136"));
        BEHAVIOR_TECHNIQUE_MAP.put("account_creation", List.of("T1136", "T1136.001", "T1136.003"));
        BEHAVIOR_TECHNIQUE_MAP.put("credential_dump", List.of("T1003", "T1003.001"));
        BEHAVIOR_TECHNIQUE_MAP.put("persistence", List.of("T1053", "T1098", "T1136"));
        BEHAVIOR_TECHNIQUE_MAP.put("defense_evasion", List.of("T1027", "T1562", "T1070"));
        BEHAVIOR_TECHNIQUE_MAP.put("obfuscation", List.of("T1027", "T1027.001", "T1027.002"));
        BEHAVIOR_TECHNIQUE_MAP.put("impair_defense", List.of("T1562", "T1562.001"));
        BEHAVIOR_TECHNIQUE_MAP.put("valid_account", List.of("T1078", "T1078.001", "T1078.004"));
        BEHAVIOR_TECHNIQUE_MAP.put("cloud_exfiltration", List.of("T1537", "T1567.002"));
        BEHAVIOR_TECHNIQUE_MAP.put("email_collection", List.of("T1114", "T1114.002"));
        BEHAVIOR_TECHNIQUE_MAP.put("data_staging", List.of("T1074", "T1560"));
        BEHAVIOR_TECHNIQUE_MAP.put("scheduled_task", List.of("T1053", "T1053.003", "T1053.005"));
        BEHAVIOR_TECHNIQUE_MAP.put("tool_transfer", List.of("T1105", "T1570"));
        BEHAVIOR_TECHNIQUE_MAP.put("supply_chain", List.of("T1195"));
        BEHAVIOR_TECHNIQUE_MAP.put("trusted_relationship", List.of("T1199"));
        BEHAVIOR_TECHNIQUE_MAP.put("network_sniffing", List.of("T1040"));
        BEHAVIOR_TECHNIQUE_MAP.put("encrypted_channel", List.of("T1573", "T1573.001", "T1573.002"));
    }

    private static void initProtocolMappings() {
        PROTOCOL_TECHNIQUE_MAP.put("ssh", List.of("T1021.004", "T1110.004", "T1133", "T1098.004"));
        PROTOCOL_TECHNIQUE_MAP.put("sftp", List.of("T1021.004", "T1110.004", "T1071.002", "T1048.002"));
        PROTOCOL_TECHNIQUE_MAP.put("ftp", List.of("T1071.002", "T1048.003", "T1105"));
        PROTOCOL_TECHNIQUE_MAP.put("ftps", List.of("T1071.002", "T1048.001", "T1573"));
        PROTOCOL_TECHNIQUE_MAP.put("http", List.of("T1071.001", "T1190", "T1048.003"));
        PROTOCOL_TECHNIQUE_MAP.put("https", List.of("T1071.001", "T1190", "T1573.002", "T1567"));
        PROTOCOL_TECHNIQUE_MAP.put("rdp", List.of("T1021.001", "T1133"));
        PROTOCOL_TECHNIQUE_MAP.put("smb", List.of("T1021.002", "T1135", "T1570"));
        PROTOCOL_TECHNIQUE_MAP.put("vnc", List.of("T1021.005"));
        PROTOCOL_TECHNIQUE_MAP.put("dns", List.of("T1071.004", "T1572"));
        PROTOCOL_TECHNIQUE_MAP.put("as2", List.of("T1071.001", "T1048.002"));
        PROTOCOL_TECHNIQUE_MAP.put("scp", List.of("T1021.004", "T1048.001", "T1570"));
    }

    private static void initMftBehaviorMappings() {
        // File transfer platform specific behaviour mappings
        MFT_BEHAVIOR_MAP.put("unusual_upload",
                List.of("T1105", "T1204", "T1566.001"));
        MFT_BEHAVIOR_MAP.put("large_outbound_transfer",
                List.of("T1048", "T1048.002", "T1048.003", "T1041"));
        MFT_BEHAVIOR_MAP.put("sftp_brute_force",
                List.of("T1110", "T1110.004", "T1021.004"));
        MFT_BEHAVIOR_MAP.put("ftp_brute_force",
                List.of("T1110", "T1110.001", "T1071.002"));
        MFT_BEHAVIOR_MAP.put("ftp_bounce",
                List.of("T1090", "T1090.002"));
        MFT_BEHAVIOR_MAP.put("dns_tunneling",
                List.of("T1572", "T1071.004"));
        MFT_BEHAVIOR_MAP.put("protocol_abuse",
                List.of("T1571", "T1071", "T1572"));
        MFT_BEHAVIOR_MAP.put("credential_theft",
                List.of("T1078", "T1110", "T1552"));
        MFT_BEHAVIOR_MAP.put("lateral_file_movement",
                List.of("T1570", "T1021", "T1074"));
        MFT_BEHAVIOR_MAP.put("encrypted_exfil",
                List.of("T1048.001", "T1048.002", "T1573"));
        MFT_BEHAVIOR_MAP.put("scheduled_exfil",
                List.of("T1029", "T1053", "T1119"));
        MFT_BEHAVIOR_MAP.put("cloud_transfer",
                List.of("T1537", "T1567.002"));
        MFT_BEHAVIOR_MAP.put("partner_impersonation",
                List.of("T1199", "T1078", "T1557"));
        MFT_BEHAVIOR_MAP.put("directory_traversal",
                List.of("T1083", "T1190"));
        MFT_BEHAVIOR_MAP.put("malware_upload",
                List.of("T1105", "T1204", "T1059"));
        MFT_BEHAVIOR_MAP.put("account_manipulation",
                List.of("T1098", "T1136", "T1078"));
        MFT_BEHAVIOR_MAP.put("impossible_travel",
                List.of("T1078", "T1078.004"));
        MFT_BEHAVIOR_MAP.put("data_staging",
                List.of("T1074", "T1560"));
        MFT_BEHAVIOR_MAP.put("bulk_download",
                List.of("T1119", "T1083", "T1048"));
        MFT_BEHAVIOR_MAP.put("non_standard_port",
                List.of("T1571"));
        MFT_BEHAVIOR_MAP.put("self_signed_cert",
                List.of("T1573", "T1583"));
        MFT_BEHAVIOR_MAP.put("service_disruption",
                List.of("T1489", "T1499"));
    }

    private static void initTechniqueChains() {
        // Common attack progression chains observed in MFT environments
        TECHNIQUE_CHAINS.put("credential_to_exfil", List.of(
                "T1110", "T1078", "T1083", "T1074", "T1048"));
        TECHNIQUE_CHAINS.put("phishing_to_exfil", List.of(
                "T1566.001", "T1204", "T1059", "T1083", "T1074", "T1048"));
        TECHNIQUE_CHAINS.put("exploit_to_ransomware", List.of(
                "T1190", "T1059", "T1068", "T1003", "T1021", "T1486"));
        TECHNIQUE_CHAINS.put("recon_to_lateral", List.of(
                "T1595", "T1046", "T1190", "T1078", "T1021", "T1570"));
        TECHNIQUE_CHAINS.put("supply_chain_attack", List.of(
                "T1195", "T1059", "T1053", "T1105", "T1074", "T1048"));
        TECHNIQUE_CHAINS.put("mft_data_theft", List.of(
                "T1110.004", "T1078", "T1083", "T1119", "T1560", "T1048.002"));
        TECHNIQUE_CHAINS.put("insider_threat", List.of(
                "T1078", "T1083", "T1074", "T1560", "T1537"));
        TECHNIQUE_CHAINS.put("cloud_compromise", List.of(
                "T1078.004", "T1098.001", "T1119", "T1537"));
        TECHNIQUE_CHAINS.put("partner_compromise", List.of(
                "T1199", "T1078", "T1083", "T1074", "T1048"));
    }
}
