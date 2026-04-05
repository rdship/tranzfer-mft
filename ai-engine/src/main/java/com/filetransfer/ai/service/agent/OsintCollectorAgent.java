package com.filetransfer.ai.service.agent;

import com.filetransfer.ai.entity.threat.SecurityEnums.IndicatorType;
import com.filetransfer.ai.entity.threat.SecurityEnums.ThreatLevel;
import com.filetransfer.ai.service.proxy.IpReputationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Collects threat intelligence from open-source intelligence (OSINT) feeds.
 *
 * <p>Runs every 15 minutes. Pulls indicators of compromise (IOCs) from publicly
 * available threat feeds and normalizes them into a unified format for use in
 * real-time verdict computation.
 *
 * <h3>Supported Feeds</h3>
 * <ul>
 *   <li><b>AbuseIPDB</b> — reported malicious IP addresses (requires API key)</li>
 *   <li><b>AlienVault OTX</b> — threat pulse indicators (requires API key)</li>
 *   <li><b>URLhaus</b> — malicious URLs (free, no key required)</li>
 *   <li><b>ThreatFox</b> — IOCs from malware analysis (free, no key required)</li>
 *   <li><b>FeodoTracker</b> — botnet C2 server IP addresses (free, no key required)</li>
 * </ul>
 *
 * <p>Feeds that require API keys are silently skipped when the key is not configured,
 * so the agent remains functional with zero configuration.
 */
@Component
@Slf4j
public class OsintCollectorAgent extends BackgroundAgent {

    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(30);
    private static final Pattern IP_PATTERN =
            Pattern.compile("^(\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3})$");

    private final HttpClient httpClient;
    private final IpReputationService reputationService;

    /**
     * In-memory threat indicator store. Maps indicator value to its metadata.
     * In a production deployment this would be backed by a persistent store.
     */
    private final Map<String, ThreatIndicator> threatStore = new ConcurrentHashMap<>();

    @Value("${ai.agents.osint.abuseipdb-api-key:}")
    private String abuseIpDbKey;

    @Value("${ai.agents.osint.otx-api-key:}")
    private String otxApiKey;

    @Value("${ai.agents.osint.fetch-timeout-seconds:30}")
    private int fetchTimeoutSeconds;

    public OsintCollectorAgent(IpReputationService reputationService) {
        super("osint-collector", "OSINT Collector Agent", AgentPriority.HIGH);
        this.reputationService = reputationService;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public void execute() {
        int total = 0;

        total += fetchFeodoTracker();
        total += fetchUrlHaus();
        total += fetchThreatFox();

        if (abuseIpDbKey != null && !abuseIpDbKey.isBlank()) {
            total += fetchAbuseIpDb();
        } else {
            log.debug("AbuseIPDB API key not configured — skipping feed");
        }

        if (otxApiKey != null && !otxApiKey.isBlank()) {
            total += fetchOtxPulses();
        } else {
            log.debug("AlienVault OTX API key not configured — skipping feed");
        }

        itemsProcessed.addAndGet(total);
        log.info("OSINT collection complete: {} new IOCs ingested (total store size: {})",
                total, threatStore.size());
    }

    @Override
    protected String getSchedule() {
        return "every 15 minutes";
    }

    // ── Feed: AbuseIPDB ───────────────────────────────────────────────

    /**
     * Fetches the AbuseIPDB blacklist of recently reported malicious IPs.
     *
     * @return number of new indicators ingested
     */
    private int fetchAbuseIpDb() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.abuseipdb.com/api/v2/blacklist?confidenceMinimum=75&limit=500"))
                    .header("Key", abuseIpDbKey)
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(fetchTimeoutSeconds))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.warn("AbuseIPDB returned HTTP {}: {}", response.statusCode(),
                        truncate(response.body(), 200));
                return 0;
            }

            int count = 0;
            // Parse the JSON array of reported IPs.
            // Response shape: {"data":[{"ipAddress":"1.2.3.4","abuseConfidenceScore":100}, ...]}
            String body = response.body();
            for (String ip : extractJsonStringValues(body, "ipAddress")) {
                if (isValidIp(ip) && !threatStore.containsKey(ip)) {
                    ThreatIndicator indicator = normalize(
                            IndicatorType.IP, ip, "abuseipdb",
                            "Reported malicious — high confidence");
                    threatStore.put(ip, indicator);
                    applyIpThreatIntel(ip, indicator);
                    count++;
                }
            }

            log.info("AbuseIPDB: ingested {} new malicious IPs", count);
            return count;

        } catch (Exception e) {
            log.warn("AbuseIPDB fetch failed: {}", e.getMessage());
            return 0;
        }
    }

    // ── Feed: URLhaus ─────────────────────────────────────────────────

    /**
     * Fetches recent malicious URLs from URLhaus (abuse.ch).
     *
     * @return number of new indicators ingested
     */
    private int fetchUrlHaus() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://urlhaus-api.abuse.ch/v1/urls/recent/limit/100/"))
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(fetchTimeoutSeconds))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.warn("URLhaus returned HTTP {}", response.statusCode());
                return 0;
            }

            int count = 0;
            String body = response.body();
            for (String url : extractJsonStringValues(body, "url")) {
                String key = "url:" + url;
                if (!threatStore.containsKey(key)) {
                    ThreatIndicator indicator = normalize(
                            IndicatorType.URL, url, "urlhaus",
                            "Malicious URL — hosting malware or phishing");
                    threatStore.put(key, indicator);
                    count++;
                }
            }

            log.info("URLhaus: ingested {} new malicious URLs", count);
            return count;

        } catch (Exception e) {
            log.warn("URLhaus fetch failed: {}", e.getMessage());
            return 0;
        }
    }

    // ── Feed: FeodoTracker ────────────────────────────────────────────

    /**
     * Fetches the FeodoTracker recommended IP blocklist of botnet C2 servers.
     * This is a plain-text list, one IP per line.
     *
     * @return number of new indicators ingested
     */
    private int fetchFeodoTracker() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://feodotracker.abuse.ch/downloads/ipblocklist_recommended.txt"))
                    .timeout(Duration.ofSeconds(fetchTimeoutSeconds))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.warn("FeodoTracker returned HTTP {}", response.statusCode());
                return 0;
            }

            int count = 0;
            for (String line : response.body().split("\n")) {
                String trimmed = line.trim();
                // Skip comments and empty lines
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                if (isValidIp(trimmed) && !threatStore.containsKey(trimmed)) {
                    ThreatIndicator indicator = normalize(
                            IndicatorType.IP, trimmed, "feodotracker",
                            "Botnet C2 server — recommended block");
                    threatStore.put(trimmed, indicator);
                    applyIpThreatIntel(trimmed, indicator);
                    count++;
                }
            }

            log.info("FeodoTracker: ingested {} new C2 server IPs", count);
            return count;

        } catch (Exception e) {
            log.warn("FeodoTracker fetch failed: {}", e.getMessage());
            return 0;
        }
    }

    // ── Feed: ThreatFox ───────────────────────────────────────────────

    /**
     * Fetches recent IOCs from ThreatFox (abuse.ch) via their API.
     * Supports IPs, domains, URLs, and file hashes.
     *
     * @return number of new indicators ingested
     */
    private int fetchThreatFox() {
        try {
            String requestBody = "{\"query\": \"get_iocs\", \"days\": 1}";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://threatfox-api.abuse.ch/api/v1/"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(fetchTimeoutSeconds))
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.warn("ThreatFox returned HTTP {}", response.statusCode());
                return 0;
            }

            int count = 0;
            String body = response.body();

            // ThreatFox returns IOCs with ioc_type: "ip:port", "domain", "url", "md5_hash", "sha256_hash"
            // Extract ioc_value entries
            for (String iocValue : extractJsonStringValues(body, "ioc_value")) {
                String key = "threatfox:" + iocValue;
                if (threatStore.containsKey(key)) {
                    continue;
                }

                IndicatorType type = classifyIocValue(iocValue);
                String cleanValue = cleanIocValue(iocValue);

                ThreatIndicator indicator = normalize(
                        type, cleanValue, "threatfox",
                        "IOC from malware analysis");
                threatStore.put(key, indicator);

                // If it's an IP, also update reputation
                if (type == IndicatorType.IP && isValidIp(cleanValue)) {
                    applyIpThreatIntel(cleanValue, indicator);
                }
                count++;
            }

            log.info("ThreatFox: ingested {} new IOCs", count);
            return count;

        } catch (Exception e) {
            log.warn("ThreatFox fetch failed: {}", e.getMessage());
            return 0;
        }
    }

    // ── Feed: AlienVault OTX ──────────────────────────────────────────

    /**
     * Fetches threat pulses from AlienVault OTX subscribed feed.
     *
     * @return number of new indicators ingested
     */
    private int fetchOtxPulses() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(
                            "https://otx.alienvault.com/api/v1/pulses/subscribed?modified_since=1d&limit=50"))
                    .header("X-OTX-API-KEY", otxApiKey)
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(fetchTimeoutSeconds))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.warn("OTX returned HTTP {}: {}", response.statusCode(),
                        truncate(response.body(), 200));
                return 0;
            }

            int count = 0;
            String body = response.body();

            // OTX returns pulses with indicators. Extract indicator values.
            for (String indicator : extractJsonStringValues(body, "indicator")) {
                String key = "otx:" + indicator;
                if (threatStore.containsKey(key)) {
                    continue;
                }

                IndicatorType type = classifyIocValue(indicator);
                ThreatIndicator ti = normalize(type, indicator, "otx", "OTX threat pulse indicator");
                threatStore.put(key, ti);

                if (type == IndicatorType.IP && isValidIp(indicator)) {
                    applyIpThreatIntel(indicator, ti);
                }
                count++;
            }

            log.info("OTX: ingested {} new pulse indicators", count);
            return count;

        } catch (Exception e) {
            log.warn("OTX fetch failed: {}", e.getMessage());
            return 0;
        }
    }

    // ── Normalization & Helpers ────────────────────────────────────────

    /**
     * Normalizes a raw feed result into a standard {@link ThreatIndicator}.
     *
     * @param type    indicator type
     * @param value   the IOC value (IP, URL, hash, etc.)
     * @param source  feed source identifier
     * @param context human-readable context about the indicator
     * @return normalized threat indicator
     */
    private ThreatIndicator normalize(IndicatorType type, String value,
                                      String source, String context) {
        return new ThreatIndicator(
                type,
                value,
                source,
                context,
                ThreatLevel.HIGH,
                Instant.now(),
                Instant.now().plus(Duration.ofDays(7))  // 7-day TTL
        );
    }

    /**
     * Applies threat intelligence to the IP reputation service.
     * Lowers the reputation score for known-bad IPs.
     */
    private void applyIpThreatIntel(String ip, ThreatIndicator indicator) {
        IpReputationService.IpReputation rep = reputationService.getOrCreate(ip);
        rep.addTag("osint:" + indicator.source());
        // Penalize reputation for threat-intel-confirmed IPs
        reputationService.recordFailure(ip, "osint:" + indicator.source());
    }

    /**
     * Classifies a raw IOC value string into the appropriate indicator type.
     */
    private IndicatorType classifyIocValue(String value) {
        if (value == null || value.isBlank()) {
            return IndicatorType.DOMAIN;
        }
        // ip:port format from ThreatFox
        if (value.matches("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}(:\\d+)?")) {
            return IndicatorType.IP;
        }
        if (value.startsWith("http://") || value.startsWith("https://")) {
            return IndicatorType.URL;
        }
        if (value.matches("[a-fA-F0-9]{32}")) {
            return IndicatorType.HASH_MD5;
        }
        if (value.matches("[a-fA-F0-9]{40}")) {
            return IndicatorType.HASH_SHA1;
        }
        if (value.matches("[a-fA-F0-9]{64}")) {
            return IndicatorType.HASH_SHA256;
        }
        if (value.contains("@")) {
            return IndicatorType.EMAIL;
        }
        return IndicatorType.DOMAIN;
    }

    /**
     * Cleans up an IOC value (e.g. strips port from ip:port format).
     */
    private String cleanIocValue(String value) {
        if (value == null) {
            return "";
        }
        // Remove port suffix from IP:port
        if (value.matches("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}:\\d+")) {
            return value.substring(0, value.lastIndexOf(':'));
        }
        return value.trim();
    }

    /**
     * Simple JSON string-value extractor. Finds all occurrences of
     * {@code "key":"value"} in a JSON string without requiring a full parser.
     * Sufficient for the simple, well-known feed response shapes.
     */
    private List<String> extractJsonStringValues(String json, String key) {
        List<String> values = new ArrayList<>();
        String searchPattern = "\"" + key + "\"\\s*:\\s*\"([^\"]+)\"";
        Pattern pattern = Pattern.compile(searchPattern);
        Matcher matcher = pattern.matcher(json);
        while (matcher.find()) {
            values.add(matcher.group(1));
        }
        return values;
    }

    private boolean isValidIp(String ip) {
        if (ip == null || ip.isBlank()) {
            return false;
        }
        Matcher matcher = IP_PATTERN.matcher(ip.trim());
        if (!matcher.matches()) {
            return false;
        }
        // Validate each octet is 0-255
        for (String octet : ip.trim().split("\\.")) {
            int val = Integer.parseInt(octet);
            if (val < 0 || val > 255) {
                return false;
            }
        }
        return true;
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

    // ── Public Accessors ──────────────────────────────────────────────

    /**
     * Returns the current number of indicators in the threat store.
     *
     * @return indicator count
     */
    public int getThreatStoreSize() {
        return threatStore.size();
    }

    /**
     * Checks whether a given IOC value exists in the threat store.
     *
     * @param value the IOC value to check
     * @return true if the value is a known threat indicator
     */
    public boolean isKnownThreat(String value) {
        if (value == null) {
            return false;
        }
        return threatStore.containsKey(value)
                || threatStore.containsKey("url:" + value)
                || threatStore.containsKey("threatfox:" + value)
                || threatStore.containsKey("otx:" + value);
    }

    // ── Threat Indicator Record ───────────────────────────────────────

    /**
     * Normalized threat indicator from any OSINT feed.
     */
    public record ThreatIndicator(
            IndicatorType type,
            String value,
            String source,
            String context,
            ThreatLevel threatLevel,
            Instant firstSeen,
            Instant expiresAt
    ) {}
}
