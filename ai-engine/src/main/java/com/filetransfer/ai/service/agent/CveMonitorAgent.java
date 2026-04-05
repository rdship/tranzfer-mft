package com.filetransfer.ai.service.agent;

import com.filetransfer.ai.entity.threat.SecurityEnums.AlertSeverity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Monitors the National Vulnerability Database (NVD) for new CVEs.
 *
 * <p>Runs every hour. Fetches recently published CVEs via the NVD API 2.0,
 * parses CVSS scores and affected product information, and cross-references
 * them against the known platform technology stack (Spring Boot, PostgreSQL,
 * Apache commons, Jackson, etc.).
 *
 * <p>When a high-severity CVE is found that affects a known dependency,
 * a {@link SecurityAlert} is generated and stored for dashboard display
 * and operator notification.
 */
@Component
@Slf4j
public class CveMonitorAgent extends BackgroundAgent {

    private static final DateTimeFormatter NVD_DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS").withZone(ZoneOffset.UTC);

    /**
     * Known platform dependencies to match against CVE affected products.
     * Maps a search keyword to the component name for alert context.
     */
    private static final Map<String, String> WATCHED_DEPENDENCIES = Map.ofEntries(
            Map.entry("spring", "Spring Framework"),
            Map.entry("spring_boot", "Spring Boot"),
            Map.entry("spring_security", "Spring Security"),
            Map.entry("postgresql", "PostgreSQL"),
            Map.entry("hibernate", "Hibernate ORM"),
            Map.entry("jackson", "Jackson JSON"),
            Map.entry("tomcat", "Apache Tomcat"),
            Map.entry("apache_commons", "Apache Commons"),
            Map.entry("netty", "Netty"),
            Map.entry("log4j", "Log4j"),
            Map.entry("slf4j", "SLF4J"),
            Map.entry("lombok", "Lombok"),
            Map.entry("java", "Java/JDK"),
            Map.entry("openssl", "OpenSSL"),
            Map.entry("linux_kernel", "Linux Kernel")
    );

    /** CVSS score threshold above which we generate an alert. */
    private static final double CVSS_ALERT_THRESHOLD = 7.0;

    private final HttpClient httpClient;

    /** Tracks CVE IDs we have already processed to avoid duplicates. */
    private final Set<String> processedCveIds = ConcurrentHashMap.newKeySet();

    /** Generated security alerts from CVE matches. */
    private final Map<String, SecurityAlert> activeAlerts = new ConcurrentHashMap<>();

    @Value("${ai.agents.cve.nvd-api-key:}")
    private String nvdApiKey;

    @Value("${ai.agents.cve.lookback-hours:2}")
    private int lookbackHours;

    public CveMonitorAgent() {
        super("cve-monitor", "CVE Monitor Agent", AgentPriority.MEDIUM);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public void execute() {
        int newCves = fetchRecentCves();
        itemsProcessed.addAndGet(newCves);
        log.info("CVE monitoring complete: {} new CVEs evaluated, {} active alerts",
                newCves, activeAlerts.size());
    }

    @Override
    protected String getSchedule() {
        return "every 1 hour";
    }

    // ── NVD API Fetch ─────────────────────────────────────────────────

    /**
     * Fetches recently published CVEs from NVD API 2.0.
     *
     * @return number of new CVEs processed
     */
    private int fetchRecentCves() {
        try {
            Instant startDate = Instant.now().minus(Duration.ofHours(lookbackHours));
            Instant endDate = Instant.now();

            String startStr = NVD_DATE_FORMAT.format(startDate);
            String endStr = NVD_DATE_FORMAT.format(endDate);

            String url = String.format(
                    "https://services.nvd.nist.gov/rest/json/cves/2.0?pubStartDate=%s&pubEndDate=%s&resultsPerPage=100",
                    startStr, endStr);

            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(30))
                    .GET();

            // NVD API key is optional but increases rate limits
            if (nvdApiKey != null && !nvdApiKey.isBlank()) {
                requestBuilder.header("apiKey", nvdApiKey);
            }

            HttpResponse<String> response = httpClient.send(
                    requestBuilder.build(), HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 403) {
                log.warn("NVD API rate limit exceeded — will retry next cycle");
                return 0;
            }
            if (response.statusCode() != 200) {
                log.warn("NVD API returned HTTP {}", response.statusCode());
                return 0;
            }

            return parseCveResponse(response.body());

        } catch (Exception e) {
            log.warn("NVD CVE fetch failed: {}", e.getMessage());
            return 0;
        }
    }

    /**
     * Parses the NVD API 2.0 JSON response and evaluates each CVE.
     */
    private int parseCveResponse(String responseBody) {
        int count = 0;

        // Extract CVE IDs
        List<String> cveIds = extractJsonStringValues(responseBody, "id");
        List<String> descriptions = extractJsonStringValues(responseBody, "value");
        List<String> baseScores = extractJsonNumericValues(responseBody, "baseScore");

        for (int i = 0; i < cveIds.size(); i++) {
            String cveId = cveIds.get(i);
            if (!cveId.startsWith("CVE-")) {
                continue;
            }
            if (processedCveIds.contains(cveId)) {
                continue;
            }

            processedCveIds.add(cveId);
            count++;

            // Get description (may be at different index due to JSON nesting)
            String description = i < descriptions.size() ? descriptions.get(i) : "No description available";

            // Get CVSS base score
            double cvssScore = 0.0;
            if (i < baseScores.size()) {
                try {
                    cvssScore = Double.parseDouble(baseScores.get(i));
                } catch (NumberFormatException ignored) {
                    // Score not parseable
                }
            }

            // Check if CVE affects any of our watched dependencies
            String matchedComponent = matchDependency(cveId, description);

            if (matchedComponent != null && cvssScore >= CVSS_ALERT_THRESHOLD) {
                AlertSeverity severity = cvssScore >= 9.0 ? AlertSeverity.CRITICAL
                        : cvssScore >= 7.0 ? AlertSeverity.HIGH
                        : AlertSeverity.MEDIUM;

                SecurityAlert alert = new SecurityAlert(
                        cveId,
                        "CVE affecting " + matchedComponent + ": " + cveId,
                        description,
                        severity,
                        cvssScore,
                        matchedComponent,
                        Instant.now()
                );
                activeAlerts.put(cveId, alert);

                log.warn("HIGH-SEVERITY CVE detected: {} (CVSS={}) affects {} — {}",
                        cveId, cvssScore, matchedComponent,
                        truncate(description, 120));
            } else if (matchedComponent != null) {
                log.info("CVE {} (CVSS={}) affects {} but below alert threshold",
                        cveId, cvssScore, matchedComponent);
            }
        }

        return count;
    }

    /**
     * Checks whether a CVE description or ID references any watched dependency.
     *
     * @return the matched component name, or null if no match
     */
    private String matchDependency(String cveId, String description) {
        String searchText = (cveId + " " + description).toLowerCase();
        for (Map.Entry<String, String> entry : WATCHED_DEPENDENCIES.entrySet()) {
            if (searchText.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    // ── JSON Helpers ──────────────────────────────────────────────────

    private List<String> extractJsonStringValues(String json, String key) {
        List<String> values = new ArrayList<>();
        Pattern pattern = Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]+)\"");
        Matcher matcher = pattern.matcher(json);
        while (matcher.find()) {
            values.add(matcher.group(1));
        }
        return values;
    }

    private List<String> extractJsonNumericValues(String json, String key) {
        List<String> values = new ArrayList<>();
        Pattern pattern = Pattern.compile("\"" + key + "\"\\s*:\\s*([\\d.]+)");
        Matcher matcher = pattern.matcher(json);
        while (matcher.find()) {
            values.add(matcher.group(1));
        }
        return values;
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

    // ── Public Accessors ──────────────────────────────────────────────

    /**
     * Returns all active security alerts generated by CVE monitoring.
     *
     * @return unmodifiable list of active alerts
     */
    public List<SecurityAlert> getActiveAlerts() {
        return List.copyOf(activeAlerts.values());
    }

    /**
     * Returns the number of unique CVEs processed since agent startup.
     *
     * @return processed CVE count
     */
    public int getProcessedCveCount() {
        return processedCveIds.size();
    }

    // ── Nested Records ────────────────────────────────────────────────

    /**
     * A security alert generated when a high-severity CVE is found
     * affecting a known platform dependency.
     */
    public record SecurityAlert(
            String cveId,
            String title,
            String description,
            AlertSeverity severity,
            double cvssScore,
            String affectedComponent,
            Instant detectedAt
    ) {}
}
