package com.filetransfer.ai.entity.threat;

import com.filetransfer.ai.entity.threat.SecurityEnums.SourceType;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Unified security event schema for the SecurityAI threat-intelligence pipeline.
 *
 * <p>Every security-relevant observation — network flow, authentication attempt,
 * DNS query, cloud audit log entry, endpoint process execution, or external
 * threat-intel match — is normalised into this single entity before downstream
 * analysis (correlation, ML scoring, alerting).</p>
 *
 * <h3>Design decisions</h3>
 * <ul>
 *   <li><b>Wide table</b> — a single flat row beats a normalised star-schema for
 *       sub-millisecond ML feature extraction; nullable columns are cheap in
 *       PostgreSQL.</li>
 *   <li><b>Comma-separated lists</b> for MITRE IDs / tags — simplicity over
 *       join tables; cardinality is low and always read in bulk.</li>
 *   <li><b>{@link #toFeatureVector()}</b> produces a fixed-size {@code double[]}
 *       suitable for real-time inference without an ETL hop.</li>
 * </ul>
 *
 * @see SourceType
 * @see SecurityAlert
 * @see ThreatIndicator
 */
@Entity
@Table(name = "security_events", indexes = {
    @Index(name = "idx_threat_event_timestamp", columnList = "timestamp"),
    @Index(name = "idx_threat_event_src_ip", columnList = "src_ip"),
    @Index(name = "idx_threat_event_dst_ip", columnList = "dst_ip"),
    @Index(name = "idx_threat_event_severity", columnList = "severity"),
    @Index(name = "idx_threat_event_source_type", columnList = "source_type"),
    @Index(name = "idx_threat_event_flow_id", columnList = "flow_id"),
    @Index(name = "idx_threat_event_host_name", columnList = "host_name"),
    @Index(name = "idx_threat_event_auth_user", columnList = "auth_user")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SecurityEvent {

    /**
     * Dimensionality of the feature vector produced by {@link #toFeatureVector()}.
     *
     * <p>Breakdown (48 features total):
     * <pre>
     *   [0-8]    source-type one-hot   (9)
     *   [9]      severity normalised   (1)
     *   [10]     confidence             (1)
     *   [11-17]  network features       (7)
     *   [18-20]  DNS features           (3)
     *   [21-25]  auth features          (5)
     *   [26-29]  time features          (4)
     *   [30-34]  endpoint features      (5)
     *   [35-39]  cloud features         (5)
     *   [40-44]  geo features           (5)
     *   [45]     tag count              (1)
     *   [46]     MITRE tactic count     (1)
     *   [47]     MITRE technique count  (1)
     * </pre>
     */
    public static final int VECTOR_DIM = 48;

    // ── Identity ──────────────────────────────────────────────────────

    @Id
    @Column(updatable = false, nullable = false)
    private UUID eventId;

    // ── Temporal ──────────────────────────────────────────────────────

    /** When the event actually occurred at the source. */
    private Instant timestamp;

    /** When the event was ingested into the SecurityAI pipeline. */
    private Instant ingestionTime;

    // ── Classification ────────────────────────────────────────────────

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private SourceType sourceType;

    /** Human-readable name of the originating system (e.g. "palo-alto-fw-01"). */
    @Column(length = 255)
    private String source;

    /** Severity on a continuous 0.0–10.0 scale (CVSS-compatible). */
    private double severity;

    /** Confidence that this event is a true positive, 0.0–1.0. */
    private double confidence;

    /** Original log line or message, preserved for forensics. */
    @Column(columnDefinition = "TEXT")
    private String rawLog;

    // ── Network Fields ────────────────────────────────────────────────

    @Column(length = 45)
    private String srcIp;

    @Column(length = 45)
    private String dstIp;

    private Integer srcPort;

    private Integer dstPort;

    @Column(length = 20)
    private String protocol;

    private Long bytesIn;

    private Long bytesOut;

    private Long packetsIn;

    private Long packetsOut;

    @Column(length = 64)
    private String flowId;

    private Long flowDurationMs;

    /** JA3 TLS client fingerprint hash. */
    @Column(length = 32)
    private String ja3Hash;

    @Column(length = 10)
    private String tlsVersion;

    /** TLS Server Name Indication value. */
    @Column(length = 255)
    private String tlsSni;

    // ── DNS Fields ────────────────────────────────────────────────────

    @Column(length = 255)
    private String dnsQuery;

    @Column(length = 10)
    private String dnsRecordType;

    @Column(length = 20)
    private String dnsResponseCode;

    // ── HTTP Fields ───────────────────────────────────────────────────

    @Column(length = 10)
    private String httpMethod;

    @Column(columnDefinition = "TEXT")
    private String httpUrl;

    private Integer httpStatus;

    @Column(length = 512)
    private String httpUserAgent;

    // ── Auth Fields ───────────────────────────────────────────────────

    @Column(length = 255)
    private String authUser;

    @Column(length = 255)
    private String authDomain;

    @Column(length = 50)
    private String authType;

    @Column(length = 50)
    private String authResult;

    private Boolean authMfaUsed;

    // ── Endpoint Fields ───────────────────────────────────────────────

    @Column(length = 255)
    private String hostName;

    @Column(length = 45)
    private String hostIp;

    @Column(length = 255)
    private String processName;

    private Integer processId;

    @Column(length = 255)
    private String parentProcessName;

    @Column(columnDefinition = "TEXT")
    private String commandLine;

    @Column(length = 1024)
    private String filePath;

    @Column(length = 64)
    private String fileHashSha256;

    // ── Cloud Fields ──────────────────────────────────────────────────

    @Column(length = 20)
    private String cloudProvider;

    @Column(length = 100)
    private String cloudService;

    @Column(length = 100)
    private String cloudAction;

    @Column(length = 255)
    private String cloudResourceId;

    @Column(length = 30)
    private String cloudRegion;

    // ── MITRE ATT&CK Mapping ──────────────────────────────────────────

    /** Comma-separated MITRE ATT&CK tactic IDs, e.g. "TA0001,TA0043". */
    @Column(columnDefinition = "TEXT")
    private String mitreTactics;

    /** Comma-separated MITRE ATT&CK technique IDs, e.g. "T1566.001,T1078". */
    @Column(columnDefinition = "TEXT")
    private String mitreTechniques;

    // ── Tags & Enrichment ─────────────────────────────────────────────

    /** Comma-separated free-form tags. */
    @Column(columnDefinition = "TEXT")
    private String tags;

    /** Arbitrary enrichment data serialised as a JSON object. */
    @Column(columnDefinition = "TEXT")
    private String enrichmentsJson;

    // ── Geolocation ───────────────────────────────────────────────────

    @Column(length = 2)
    private String geoCountryCode;

    @Column(length = 100)
    private String geoCity;

    private Double geoLatitude;

    private Double geoLongitude;

    private Integer geoAsn;

    @Column(length = 255)
    private String geoAsOrg;

    private Boolean geoIsTor;

    private Boolean geoIsVpn;

    // ── Lifecycle Callbacks ───────────────────────────────────────────

    @PrePersist
    void prePersist() {
        if (eventId == null) {
            eventId = UUID.randomUUID();
        }
        if (ingestionTime == null) {
            ingestionTime = Instant.now();
        }
        if (confidence == 0.0) {
            confidence = 1.0;
        }
    }

    // ── Comma-Separated Field Helpers ─────────────────────────────────

    /**
     * Returns the MITRE tactic IDs as an immutable list.
     */
    public List<String> getMitreTacticsList() {
        return splitCsv(mitreTactics);
    }

    /**
     * Returns the MITRE technique IDs as an immutable list.
     */
    public List<String> getMitreTechniquesList() {
        return splitCsv(mitreTechniques);
    }

    /**
     * Returns the tags as an immutable list.
     */
    public List<String> getTagsList() {
        return splitCsv(tags);
    }

    /**
     * Appends a tag, avoiding duplicates.
     */
    public void addTag(String tag) {
        if (tag == null || tag.isBlank()) return;
        tags = appendCsvUnique(tags, tag.strip());
    }

    /**
     * Appends a MITRE tactic ID, avoiding duplicates.
     */
    public void addMitreTactic(String tactic) {
        if (tactic == null || tactic.isBlank()) return;
        mitreTactics = appendCsvUnique(mitreTactics, tactic.strip());
    }

    /**
     * Appends a MITRE technique ID, avoiding duplicates.
     */
    public void addMitreTechnique(String technique) {
        if (technique == null || technique.isBlank()) return;
        mitreTechniques = appendCsvUnique(mitreTechniques, technique.strip());
    }

    // ── Feature Vector for ML Models ──────────────────────────────────

    /**
     * Converts this event into a fixed-size feature vector suitable for real-time
     * ML inference (anomaly detection, threat scoring, clustering).
     *
     * <p>All features are normalised to roughly [0, 1] or [-1, 1] ranges so that
     * distance-based models (k-NN, autoencoders) work without additional scaling.</p>
     *
     * @return {@code double[]} of length {@link #VECTOR_DIM}
     */
    public double[] toFeatureVector() {
        double[] v = new double[VECTOR_DIM];
        int idx = 0;

        // ── [0-8] Source-type one-hot (9 values) ──────────────────────
        SourceType[] types = SourceType.values();
        for (SourceType t : types) {
            v[idx++] = (sourceType == t) ? 1.0 : 0.0;
        }

        // ── [9] Severity normalised to 0–1 ───────────────────────────
        v[idx++] = clamp(severity / 10.0, 0.0, 1.0);

        // ── [10] Confidence (already 0–1) ─────────────────────────────
        v[idx++] = clamp(confidence, 0.0, 1.0);

        // ── [11-17] Network features (7) ──────────────────────────────
        // 11: dst port normalised (well-known ports < 1024 vs ephemeral)
        v[idx++] = dstPort != null ? clamp(dstPort / 65535.0, 0.0, 1.0) : 0.0;

        // 12: src port normalised
        v[idx++] = srcPort != null ? clamp(srcPort / 65535.0, 0.0, 1.0) : 0.0;

        // 13: bytes ratio (in / total) — asymmetry indicator
        long bIn = bytesIn != null ? bytesIn : 0L;
        long bOut = bytesOut != null ? bytesOut : 0L;
        long totalBytes = bIn + bOut;
        v[idx++] = totalBytes > 0 ? (double) bIn / totalBytes : 0.5;

        // 14: log-scaled total bytes (cap at ~1 GB for normalisation)
        v[idx++] = totalBytes > 0 ? clamp(Math.log1p(totalBytes) / Math.log1p(1_000_000_000L), 0.0, 1.0) : 0.0;

        // 15: packet ratio (in / total)
        long pIn = packetsIn != null ? packetsIn : 0L;
        long pOut = packetsOut != null ? packetsOut : 0L;
        long totalPackets = pIn + pOut;
        v[idx++] = totalPackets > 0 ? (double) pIn / totalPackets : 0.5;

        // 16: has TLS indicator
        v[idx++] = (tlsVersion != null && !tlsVersion.isBlank()) ? 1.0 : 0.0;

        // 17: flow duration log-scaled (cap at 1 hour)
        long flowMs = flowDurationMs != null ? flowDurationMs : 0L;
        v[idx++] = flowMs > 0 ? clamp(Math.log1p(flowMs) / Math.log1p(3_600_000L), 0.0, 1.0) : 0.0;

        // ── [18-20] DNS features (3) ──────────────────────────────────
        // 18: query length normalised (max 253 chars per RFC)
        int queryLen = dnsQuery != null ? dnsQuery.length() : 0;
        v[idx++] = clamp(queryLen / 253.0, 0.0, 1.0);

        // 19: Shannon entropy of DNS query (DGA / tunnelling detection)
        v[idx++] = dnsQuery != null && !dnsQuery.isEmpty()
            ? clamp(shannonEntropy(dnsQuery) / 4.5, 0.0, 1.0)  // max theoretical ~4.7 for printable ASCII domains
            : 0.0;

        // 20: subdomain depth (number of dots)
        v[idx++] = dnsQuery != null
            ? clamp(dnsQuery.chars().filter(c -> c == '.').count() / 10.0, 0.0, 1.0)
            : 0.0;

        // ── [21-25] Auth features (5) ─────────────────────────────────
        // 21: auth result — success
        v[idx++] = "success".equalsIgnoreCase(authResult) ? 1.0 : 0.0;
        // 22: auth result — failure
        v[idx++] = "failure".equalsIgnoreCase(authResult) ? 1.0 : 0.0;
        // 23: auth result — locked
        v[idx++] = "locked".equalsIgnoreCase(authResult) ? 1.0 : 0.0;
        // 24: MFA used
        v[idx++] = Boolean.TRUE.equals(authMfaUsed) ? 1.0 : 0.0;
        // 25: has auth user (event is auth-related)
        v[idx++] = (authUser != null && !authUser.isBlank()) ? 1.0 : 0.0;

        // ── [26-29] Time features (4) — cyclical encoding ─────────────
        if (timestamp != null) {
            ZonedDateTime zdt = timestamp.atZone(ZoneOffset.UTC);
            double hourRad = 2.0 * Math.PI * zdt.getHour() / 24.0;
            v[idx++] = Math.sin(hourRad);
            v[idx++] = Math.cos(hourRad);
            double dayRad = 2.0 * Math.PI * zdt.getDayOfWeek().getValue() / 7.0;
            v[idx++] = Math.sin(dayRad);
            v[idx++] = Math.cos(dayRad);
        } else {
            idx += 4; // leave as 0.0
        }

        // ── [30-34] Endpoint features (5) ─────────────────────────────
        // 30: has process name
        v[idx++] = (processName != null && !processName.isBlank()) ? 1.0 : 0.0;
        // 31: has parent process
        v[idx++] = (parentProcessName != null && !parentProcessName.isBlank()) ? 1.0 : 0.0;
        // 32: command-line length (log-scaled, proxy for complexity)
        int cmdLen = commandLine != null ? commandLine.length() : 0;
        v[idx++] = cmdLen > 0 ? clamp(Math.log1p(cmdLen) / Math.log1p(10_000), 0.0, 1.0) : 0.0;
        // 33: has file hash (known binary)
        v[idx++] = (fileHashSha256 != null && !fileHashSha256.isBlank()) ? 1.0 : 0.0;
        // 34: has file path
        v[idx++] = (filePath != null && !filePath.isBlank()) ? 1.0 : 0.0;

        // ── [35-39] Cloud features (5) ────────────────────────────────
        // 35-37: provider one-hot (aws, azure, gcp)
        String prov = cloudProvider != null ? cloudProvider.toLowerCase() : "";
        v[idx++] = prov.contains("aws") ? 1.0 : 0.0;
        v[idx++] = prov.contains("azure") ? 1.0 : 0.0;
        v[idx++] = prov.contains("gcp") || prov.contains("google") ? 1.0 : 0.0;
        // 38: has cloud action
        v[idx++] = (cloudAction != null && !cloudAction.isBlank()) ? 1.0 : 0.0;
        // 39: has cloud resource
        v[idx++] = (cloudResourceId != null && !cloudResourceId.isBlank()) ? 1.0 : 0.0;

        // ── [40-44] Geo features (5) ──────────────────────────────────
        // 40: latitude normalised [-90, 90] -> [0, 1]
        v[idx++] = geoLatitude != null ? clamp((geoLatitude + 90.0) / 180.0, 0.0, 1.0) : 0.5;
        // 41: longitude normalised [-180, 180] -> [0, 1]
        v[idx++] = geoLongitude != null ? clamp((geoLongitude + 180.0) / 360.0, 0.0, 1.0) : 0.5;
        // 42: is Tor exit node
        v[idx++] = Boolean.TRUE.equals(geoIsTor) ? 1.0 : 0.0;
        // 43: is VPN
        v[idx++] = Boolean.TRUE.equals(geoIsVpn) ? 1.0 : 0.0;
        // 44: ASN normalised (log-scaled, max ~400k)
        v[idx++] = geoAsn != null && geoAsn > 0
            ? clamp(Math.log1p(geoAsn) / Math.log1p(400_000), 0.0, 1.0)
            : 0.0;

        // ── [45] Tag count ────────────────────────────────────────────
        v[idx++] = clamp(splitCsv(tags).size() / 20.0, 0.0, 1.0);

        // ── [46] MITRE tactic count ───────────────────────────────────
        v[idx++] = clamp(splitCsv(mitreTactics).size() / 14.0, 0.0, 1.0);  // 14 tactics in ATT&CK

        // ── [47] MITRE technique count ────────────────────────────────
        v[idx++] = clamp(splitCsv(mitreTechniques).size() / 50.0, 0.0, 1.0);

        return v;
    }

    // ── Private Utilities ─────────────────────────────────────────────

    /**
     * Splits a comma-separated string into a trimmed, non-blank list.
     */
    private static List<String> splitCsv(String csv) {
        if (csv == null || csv.isBlank()) {
            return Collections.emptyList();
        }
        return Arrays.stream(csv.split(","))
            .map(String::strip)
            .filter(s -> !s.isEmpty())
            .collect(Collectors.toUnmodifiableList());
    }

    /**
     * Appends {@code value} to a CSV string if not already present.
     */
    private static String appendCsvUnique(String csv, String value) {
        if (csv == null || csv.isBlank()) {
            return value;
        }
        List<String> existing = new ArrayList<>(splitCsv(csv));
        if (!existing.contains(value)) {
            existing.add(value);
        }
        return String.join(",", existing);
    }

    /**
     * Computes the Shannon entropy (bits) of the given string.
     *
     * <p>Used primarily for DNS domain-generation-algorithm (DGA) detection:
     * legitimate domains typically have entropy &lt; 3.5, while DGA domains
     * approach the theoretical maximum for their character set.</p>
     *
     * @param s non-null, non-empty string
     * @return entropy in bits
     */
    private static double shannonEntropy(String s) {
        if (s.isEmpty()) return 0.0;

        int[] freq = new int[256];
        for (int i = 0; i < s.length(); i++) {
            freq[s.charAt(i) & 0xFF]++;
        }

        double entropy = 0.0;
        double len = s.length();
        for (int f : freq) {
            if (f > 0) {
                double p = f / len;
                entropy -= p * (Math.log(p) / Math.log(2.0));
            }
        }
        return entropy;
    }

    /**
     * Clamps a value to the closed interval [min, max].
     */
    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
