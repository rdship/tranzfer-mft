package com.filetransfer.ai.service.proxy;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Geographic Anomaly Detector — detects geographic anomalies in connection patterns.
 * Uses lightweight IP-to-country mapping (no external DB dependency) with support
 * for external geo resolution.
 *
 * Detects:
 * - New country for a known IP/account
 * - Impossible travel (same account, different countries, too fast)
 * - Connections from high-risk regions
 * - Tor/VPN/proxy exit node patterns
 * - Sudden country distribution shifts
 *
 * Product-agnostic: works with any IP-based connection system.
 */
@Slf4j
@Service
public class GeoAnomalyDetector {

    public record GeoAnomaly(
        String type,          // NEW_COUNTRY, IMPOSSIBLE_TRAVEL, HIGH_RISK_REGION, TOR_EXIT, VPN_DETECTED
        int severity,         // 0-100
        String description,
        Map<String, Object> evidence
    ) {}

    // ── IP-to-Country Cache ────────────────────────────────────────────

    private final ConcurrentHashMap<String, String> ipCountryCache = new ConcurrentHashMap<>();

    // Per-account country history (account -> list of (country, timestamp))
    private final ConcurrentHashMap<String, Deque<CountryEvent>> accountCountryHistory = new ConcurrentHashMap<>();

    // Known country distribution per IP (for detecting sudden shifts)
    private final ConcurrentHashMap<String, Map<String, Integer>> ipCountryDistribution = new ConcurrentHashMap<>();

    private record CountryEvent(String country, Instant timestamp, String ip) {}

    // ── High-Risk Regions (configurable) ───────────────────────────────

    /** Countries with elevated cyber threat activity — configurable via API */
    private final Set<String> highRiskCountries = ConcurrentHashMap.newKeySet();

    /** Known Tor exit node IPs — updated via threat feed */
    private final Set<String> torExitNodes = ConcurrentHashMap.newKeySet();

    /** Known VPN/proxy provider IP ranges (CIDR simplified to /24) */
    private final Set<String> knownVpnPrefixes = ConcurrentHashMap.newKeySet();

    // ── Core Analysis ──────────────────────────────────────────────────

    /**
     * Analyze a connection for geographic anomalies.
     *
     * @param ip       source IP
     * @param country  resolved country code (ISO 3166-1 alpha-2), or null if unknown
     * @param account  optional account/user identifier for travel analysis
     * @return list of detected anomalies (empty = clean)
     */
    public List<GeoAnomaly> analyze(String ip, String country, String account) {
        List<GeoAnomaly> anomalies = new ArrayList<>();

        if (country == null || country.isEmpty()) {
            // Can't do geo analysis without country data
            return anomalies;
        }

        // Cache the resolution
        ipCountryCache.put(ip, country);

        // 1. Tor exit node check
        if (torExitNodes.contains(ip)) {
            anomalies.add(new GeoAnomaly("TOR_EXIT", 60,
                "Connection from known Tor exit node",
                Map.of("ip", ip, "country", country)));
        }

        // 2. VPN/proxy check
        String ipPrefix = getIpPrefix(ip);
        if (ipPrefix != null && knownVpnPrefixes.contains(ipPrefix)) {
            anomalies.add(new GeoAnomaly("VPN_DETECTED", 35,
                "Connection from known VPN/proxy provider",
                Map.of("ip", ip, "prefix", ipPrefix, "country", country)));
        }

        // 3. High-risk country
        if (highRiskCountries.contains(country.toUpperCase())) {
            anomalies.add(new GeoAnomaly("HIGH_RISK_REGION", 45,
                "Connection from high-risk region: " + country,
                Map.of("ip", ip, "country", country)));
        }

        // 4. New country for this IP
        Map<String, Integer> countryDist = ipCountryDistribution
            .computeIfAbsent(ip, k -> new ConcurrentHashMap<>());
        int prevCount = countryDist.getOrDefault(country, 0);
        countryDist.merge(country, 1, Integer::sum);

        if (prevCount == 0 && countryDist.size() > 1) {
            anomalies.add(new GeoAnomaly("NEW_COUNTRY", 40,
                "IP " + ip + " connecting from new country: " + country
                    + " (previously: " + String.join(", ", countryDist.keySet()) + ")",
                Map.of("ip", ip, "newCountry", country,
                    "previousCountries", new ArrayList<>(countryDist.keySet()))));
        }

        // 5. Impossible travel (requires account)
        if (account != null && !account.isEmpty()) {
            GeoAnomaly travelAnomaly = checkImpossibleTravel(account, country, ip);
            if (travelAnomaly != null) {
                anomalies.add(travelAnomaly);
            }

            // Record this event
            accountCountryHistory
                .computeIfAbsent(account, k -> new ArrayDeque<>())
                .addLast(new CountryEvent(country, Instant.now(), ip));
        }

        return anomalies;
    }

    // ── Impossible Travel Detection ────────────────────────────────────

    private GeoAnomaly checkImpossibleTravel(String account, String currentCountry, String currentIp) {
        Deque<CountryEvent> history = accountCountryHistory.get(account);
        if (history == null || history.isEmpty()) return null;

        CountryEvent lastEvent = history.peekLast();
        if (lastEvent == null) return null;

        // Different country from last connection?
        if (currentCountry.equalsIgnoreCase(lastEvent.country())) return null;

        // How long since last connection?
        long minutesBetween = ChronoUnit.MINUTES.between(lastEvent.timestamp(), Instant.now());

        // Impossible travel: different country in < 60 minutes
        // (even fastest flights take longer for intercontinental)
        if (minutesBetween < 60) {
            return new GeoAnomaly("IMPOSSIBLE_TRAVEL", 80,
                "Account '" + account + "' connected from " + lastEvent.country()
                    + " and " + currentCountry + " within " + minutesBetween + " minutes",
                Map.of(
                    "account", account,
                    "previousCountry", lastEvent.country(),
                    "currentCountry", currentCountry,
                    "minutesBetween", minutesBetween,
                    "previousIp", lastEvent.ip(),
                    "currentIp", currentIp
                ));
        }

        // Suspicious travel: different continent in < 4 hours
        if (minutesBetween < 240 && !sameContinent(lastEvent.country(), currentCountry)) {
            return new GeoAnomaly("IMPOSSIBLE_TRAVEL", 60,
                "Account '" + account + "' crossed continents in " + minutesBetween + " minutes",
                Map.of(
                    "account", account,
                    "previousCountry", lastEvent.country(),
                    "currentCountry", currentCountry,
                    "minutesBetween", minutesBetween
                ));
        }

        return null;
    }

    // ── Continent Mapping (simplified) ─────────────────────────────────

    private static final Map<String, String> COUNTRY_TO_CONTINENT = Map.ofEntries(
        // North America
        Map.entry("US", "NA"), Map.entry("CA", "NA"), Map.entry("MX", "NA"),
        // Europe
        Map.entry("GB", "EU"), Map.entry("DE", "EU"), Map.entry("FR", "EU"),
        Map.entry("NL", "EU"), Map.entry("SE", "EU"), Map.entry("NO", "EU"),
        Map.entry("FI", "EU"), Map.entry("DK", "EU"), Map.entry("IT", "EU"),
        Map.entry("ES", "EU"), Map.entry("PT", "EU"), Map.entry("PL", "EU"),
        Map.entry("CH", "EU"), Map.entry("AT", "EU"), Map.entry("BE", "EU"),
        Map.entry("IE", "EU"), Map.entry("CZ", "EU"), Map.entry("RO", "EU"),
        // Asia
        Map.entry("CN", "AS"), Map.entry("JP", "AS"), Map.entry("KR", "AS"),
        Map.entry("IN", "AS"), Map.entry("SG", "AS"), Map.entry("HK", "AS"),
        Map.entry("TW", "AS"), Map.entry("TH", "AS"), Map.entry("MY", "AS"),
        Map.entry("ID", "AS"), Map.entry("PH", "AS"), Map.entry("VN", "AS"),
        // South America
        Map.entry("BR", "SA"), Map.entry("AR", "SA"), Map.entry("CL", "SA"),
        Map.entry("CO", "SA"), Map.entry("PE", "SA"),
        // Africa
        Map.entry("ZA", "AF"), Map.entry("NG", "AF"), Map.entry("KE", "AF"),
        Map.entry("EG", "AF"),
        // Oceania
        Map.entry("AU", "OC"), Map.entry("NZ", "OC"),
        // Middle East
        Map.entry("AE", "ME"), Map.entry("IL", "ME"), Map.entry("SA", "ME"),
        // Russia/CIS
        Map.entry("RU", "RU"), Map.entry("UA", "RU"), Map.entry("BY", "RU")
    );

    private boolean sameContinent(String country1, String country2) {
        String c1 = COUNTRY_TO_CONTINENT.getOrDefault(country1.toUpperCase(), "UNKNOWN");
        String c2 = COUNTRY_TO_CONTINENT.getOrDefault(country2.toUpperCase(), "UNKNOWN");
        return c1.equals(c2) && !"UNKNOWN".equals(c1);
    }

    // ── Threat Feed Management ─────────────────────────────────────────

    public void addHighRiskCountry(String countryCode) {
        highRiskCountries.add(countryCode.toUpperCase());
    }

    public void removeHighRiskCountry(String countryCode) {
        highRiskCountries.remove(countryCode.toUpperCase());
    }

    public Set<String> getHighRiskCountries() {
        return Collections.unmodifiableSet(highRiskCountries);
    }

    public void addTorExitNode(String ip) { torExitNodes.add(ip); }
    public void removeTorExitNode(String ip) { torExitNodes.remove(ip); }
    public int getTorExitNodeCount() { return torExitNodes.size(); }

    public void addVpnPrefix(String prefix) { knownVpnPrefixes.add(prefix); }
    public int getVpnPrefixCount() { return knownVpnPrefixes.size(); }

    /** Bulk update Tor exit nodes from threat feed */
    public void updateTorExitNodes(Set<String> nodes) {
        torExitNodes.clear();
        torExitNodes.addAll(nodes);
        log.info("Updated Tor exit node list: {} nodes", nodes.size());
    }

    /** Bulk update VPN prefixes from threat feed */
    public void updateVpnPrefixes(Set<String> prefixes) {
        knownVpnPrefixes.clear();
        knownVpnPrefixes.addAll(prefixes);
        log.info("Updated VPN prefix list: {} prefixes", prefixes.size());
    }

    // ── Resolution Cache ───────────────────────────────────────────────

    public void cacheIpCountry(String ip, String country) {
        ipCountryCache.put(ip, country);
    }

    public Optional<String> getCachedCountry(String ip) {
        return Optional.ofNullable(ipCountryCache.get(ip));
    }

    // ── Stats ──────────────────────────────────────────────────────────

    public Map<String, Object> getStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("cachedIpCountries", ipCountryCache.size());
        stats.put("trackedAccounts", accountCountryHistory.size());
        stats.put("highRiskCountries", new ArrayList<>(highRiskCountries));
        stats.put("torExitNodes", torExitNodes.size());
        stats.put("vpnPrefixes", knownVpnPrefixes.size());

        // Country distribution across all known IPs
        Map<String, Long> countryCount = new HashMap<>();
        ipCountryCache.values().forEach(c ->
            countryCount.merge(c, 1L, Long::sum));
        stats.put("topCountries", countryCount.entrySet().stream()
            .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
            .limit(10)
            .map(e -> Map.of("country", e.getKey(), "ips", e.getValue()))
            .toList());

        return stats;
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    /** Extract /24 prefix from IPv4 address */
    private String getIpPrefix(String ip) {
        if (ip == null || !ip.contains(".")) return null;
        String[] parts = ip.split("\\.");
        if (parts.length != 4) return null;
        return parts[0] + "." + parts[1] + "." + parts[2] + ".0/24";
    }
}
