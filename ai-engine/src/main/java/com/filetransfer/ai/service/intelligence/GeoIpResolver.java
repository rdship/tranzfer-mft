package com.filetransfer.ai.service.intelligence;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GeoIP resolver that maps IP addresses to geographic and network metadata.
 *
 * <p>Uses the <a href="https://ip-api.com/">ip-api.com</a> free tier (45 requests/min,
 * no API key required for non-commercial use) with aggressive in-memory caching to
 * minimise external API calls. Private/reserved IPs are handled locally without any
 * network request.</p>
 *
 * <h3>Capabilities</h3>
 * <ul>
 *   <li>Single IP resolution with automatic cache population</li>
 *   <li>Batch resolution (up to 100 IPs per API call)</li>
 *   <li>Haversine-based distance calculation between two IPs</li>
 *   <li>Impossible-travel detection (same user, two locations, implausible speed)</li>
 * </ul>
 *
 * <h3>Cache Management</h3>
 * <p>The cache is bounded to {@code ai.geo.cache-size} entries (default 50,000).
 * When full, no eviction policy is applied (new entries are simply not cached);
 * the scheduled pruning in the store tier handles long-term memory management.</p>
 *
 * @see com.filetransfer.ai.service.proxy.GeoAnomalyDetector
 */
@Service
@Slf4j
public class GeoIpResolver {

    /** Maximum plausible travel speed in km/h (commercial aviation). */
    private static final double MAX_TRAVEL_SPEED_KMH = 900.0;

    /** Earth's mean radius in kilometres, used for Haversine calculations. */
    private static final double EARTH_RADIUS_KM = 6_371.0;

    /** Timeout for individual HTTP requests to ip-api.com. */
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(5);

    // ── Record Definitions ─────────────────────────────────────────────

    /**
     * Geographic and network metadata for an IP address.
     *
     * @param countryCode ISO 3166-1 alpha-2 country code
     * @param countryName full country name
     * @param city        city name (may be empty for small localities)
     * @param latitude    WGS-84 latitude
     * @param longitude   WGS-84 longitude
     * @param asn         Autonomous System Number
     * @param asOrg       AS organisation name
     * @param isMobile    whether the IP belongs to a mobile carrier
     * @param isProxy     whether the IP is a known proxy/VPN
     * @param isHosting   whether the IP belongs to a hosting/data-centre provider
     */
    public record GeoInfo(
            String countryCode,
            String countryName,
            String city,
            double latitude,
            double longitude,
            int asn,
            String asOrg,
            boolean isMobile,
            boolean isProxy,
            boolean isHosting
    ) {}

    // ── Configuration ──────────────────────────────────────────────────

    @Value("${ai.geo.cache-size:50000}")
    private int maxCacheSize;

    @Value("${ai.geo.api-base-url:https://ip-api.com}")
    private String apiBaseUrl;

    // ── State ──────────────────────────────────────────────────────────

    private final ConcurrentHashMap<String, GeoInfo> cache = new ConcurrentHashMap<>();

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(HTTP_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    // ── Startup Validation ────────────────────────────────────────────

    /**
     * Validates the configured API base URL at startup to prevent SSRF
     * and ensure HTTPS is used in production environments.
     */
    @PostConstruct
    void validateApiBaseUrl() {
        if (apiBaseUrl == null || apiBaseUrl.isBlank()) {
            throw new IllegalStateException("ai.geo.api-base-url must not be blank");
        }

        // Warn if HTTP is used instead of HTTPS
        if (apiBaseUrl.startsWith("http://")) {
            log.warn("GeoIP API base URL is using HTTP ({}). HTTPS is strongly recommended for production. "
                    + "Set ai.geo.api-base-url to an https:// URL.", apiBaseUrl);
        } else if (!apiBaseUrl.startsWith("https://")) {
            throw new IllegalStateException(
                    "ai.geo.api-base-url must use https:// (or http:// for non-production): " + apiBaseUrl);
        }

        // Extract hostname and validate it does not point to internal/loopback addresses
        try {
            URI uri = URI.create(apiBaseUrl);
            String host = uri.getHost();
            if (host == null) {
                throw new IllegalStateException("ai.geo.api-base-url has no valid host: " + apiBaseUrl);
            }

            String hostLower = host.toLowerCase();

            // Block loopback and internal addresses
            if (hostLower.equals("localhost")
                    || hostLower.equals("[::1]")
                    || hostLower.startsWith("127.")
                    || hostLower.startsWith("169.254.")
                    || hostLower.startsWith("10.")
                    || hostLower.startsWith("192.168.")) {
                throw new IllegalStateException(
                        "ai.geo.api-base-url must not point to an internal/loopback address: " + apiBaseUrl);
            }

            // Also block 172.16.0.0/12 range
            if (hostLower.startsWith("172.")) {
                try {
                    int secondOctet = Integer.parseInt(hostLower.split("\\.")[1]);
                    if (secondOctet >= 16 && secondOctet <= 31) {
                        throw new IllegalStateException(
                                "ai.geo.api-base-url must not point to an internal address: " + apiBaseUrl);
                    }
                } catch (NumberFormatException ignored) {
                    // not a numeric IP, allow it
                }
            }

            log.info("GeoIP API base URL validated: {}", apiBaseUrl);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("ai.geo.api-base-url is not a valid URI: " + apiBaseUrl, e);
        }
    }

    // ── Single IP Resolution ───────────────────────────────────────────

    /**
     * Resolves an IP address to geographic and network metadata.
     *
     * <p>Results are cached for subsequent lookups. Private (RFC 1918),
     * loopback, and link-local addresses return {@code Optional.empty()}
     * immediately without an API call.</p>
     *
     * @param ip the IPv4 or IPv6 address to resolve
     * @return geographic info, or empty for private/unresolvable IPs
     */
    public Optional<GeoInfo> resolve(String ip) {
        if (ip == null || ip.isBlank()) {
            return Optional.empty();
        }

        String normalized = ip.strip();
        if (isPrivateIp(normalized)) {
            return Optional.empty();
        }

        GeoInfo cached = cache.get(normalized);
        if (cached != null) {
            return Optional.of(cached);
        }

        GeoInfo fetched = fetchGeoInfo(normalized);
        if (fetched != null && cache.size() < maxCacheSize) {
            cache.put(normalized, fetched);
        }

        return Optional.ofNullable(fetched);
    }

    // ── Batch Resolution ───────────────────────────────────────────────

    /**
     * Resolves multiple IP addresses in a single batch API call.
     *
     * <p>ip-api.com supports up to 100 IPs per batch request. For lists
     * exceeding 100, this method issues multiple sequential batch requests.</p>
     *
     * @param ips the list of IP addresses to resolve
     * @return map of IP to GeoInfo (only successfully resolved IPs are included)
     */
    public Map<String, GeoInfo> resolveBatch(List<String> ips) {
        if (ips == null || ips.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<String, GeoInfo> results = new LinkedHashMap<>();

        // Separate cached from uncached
        List<String> uncached = new ArrayList<>();
        for (String ip : ips) {
            if (ip == null || ip.isBlank()) {
                continue;
            }
            String normalized = ip.strip();
            if (isPrivateIp(normalized)) {
                continue;
            }
            GeoInfo existing = cache.get(normalized);
            if (existing != null) {
                results.put(normalized, existing);
            } else {
                uncached.add(normalized);
            }
        }

        if (uncached.isEmpty()) {
            return results;
        }

        // Process in chunks of 100 (ip-api.com batch limit)
        for (int i = 0; i < uncached.size(); i += 100) {
            List<String> chunk = uncached.subList(i, Math.min(i + 100, uncached.size()));
            Map<String, GeoInfo> batchResult = fetchBatch(chunk);
            results.putAll(batchResult);

            // Cache results
            for (Map.Entry<String, GeoInfo> entry : batchResult.entrySet()) {
                if (cache.size() < maxCacheSize) {
                    cache.put(entry.getKey(), entry.getValue());
                }
            }
        }

        return results;
    }

    // ── Distance Calculation ───────────────────────────────────────────

    /**
     * Calculates the great-circle distance in kilometres between two IP addresses
     * using the Haversine formula.
     *
     * @param ip1 first IP address
     * @param ip2 second IP address
     * @return distance in km, or empty if either IP cannot be resolved
     */
    public OptionalDouble distanceKm(String ip1, String ip2) {
        Optional<GeoInfo> geo1 = resolve(ip1);
        Optional<GeoInfo> geo2 = resolve(ip2);

        if (geo1.isEmpty() || geo2.isEmpty()) {
            return OptionalDouble.empty();
        }

        double dist = haversine(
                geo1.get().latitude(), geo1.get().longitude(),
                geo2.get().latitude(), geo2.get().longitude());

        return OptionalDouble.of(dist);
    }

    // ── Impossible Travel Detection ────────────────────────────────────

    /**
     * Determines whether travel between two IPs within the given time duration
     * is physically impossible.
     *
     * <p>Uses a maximum plausible travel speed of 900 km/h (the approximate
     * cruise speed of a commercial airliner). If the required speed exceeds
     * this threshold, the travel is deemed impossible.</p>
     *
     * @param ip1         first IP address (earlier location)
     * @param ip2         second IP address (later location)
     * @param timeBetween the elapsed time between the two observations
     * @return {@code true} if the travel is physically impossible
     */
    public boolean isImpossibleTravel(String ip1, String ip2, Duration timeBetween) {
        if (timeBetween == null || timeBetween.isNegative() || timeBetween.isZero()) {
            // Zero or negative duration with different locations is inherently suspicious
            // but we need geo data to confirm they are actually different locations
            OptionalDouble dist = distanceKm(ip1, ip2);
            return dist.isPresent() && dist.getAsDouble() > 50.0;
        }

        OptionalDouble dist = distanceKm(ip1, ip2);
        if (dist.isEmpty()) {
            return false;
        }

        double distanceKm = dist.getAsDouble();

        // Same location (within 50km) is never impossible travel
        if (distanceKm < 50.0) {
            return false;
        }

        double hours = timeBetween.toMillis() / 3_600_000.0;
        if (hours <= 0.0) {
            return true;
        }

        double requiredSpeed = distanceKm / hours;

        boolean impossible = requiredSpeed > MAX_TRAVEL_SPEED_KMH;
        if (impossible) {
            log.info("Impossible travel detected: {:.0f}km in {:.1f}h = {:.0f}km/h (max {}km/h)",
                    distanceKm, hours, requiredSpeed, MAX_TRAVEL_SPEED_KMH);
        }

        return impossible;
    }

    // ── Cache Management ───────────────────────────────────────────────

    /**
     * Returns the current cache size.
     *
     * @return number of cached IP resolutions
     */
    public int getCacheSize() {
        return cache.size();
    }

    /**
     * Clears the entire geo-resolution cache.
     */
    public void clearCache() {
        cache.clear();
        log.info("GeoIP cache cleared");
    }

    /**
     * Manually injects a geo-resolution into the cache.
     * Useful for testing or for IPs resolved through other means.
     *
     * @param ip   the IP address
     * @param info the geo information
     */
    public void cacheResult(String ip, GeoInfo info) {
        if (ip != null && info != null && cache.size() < maxCacheSize) {
            cache.put(ip.strip(), info);
        }
    }

    // ── Private IP Detection ───────────────────────────────────────────

    /**
     * Checks whether an IP address is a private, loopback, or link-local address
     * per RFC 1918, RFC 4193, and related standards.
     *
     * @param ip the IP address to check
     * @return {@code true} if the IP is non-routable
     */
    boolean isPrivateIp(String ip) {
        if (ip == null || ip.isBlank()) {
            return true;
        }

        // IPv6 loopback and link-local
        if (ip.equals("::1") || ip.startsWith("fe80:") || ip.startsWith("fc00:") || ip.startsWith("fd")) {
            return true;
        }

        // IPv4
        if (!ip.contains(".")) {
            return false;
        }

        String[] parts = ip.split("\\.");
        if (parts.length != 4) {
            return true; // malformed = treat as private
        }

        try {
            int first = Integer.parseInt(parts[0]);
            int second = Integer.parseInt(parts[1]);

            // 10.0.0.0/8
            if (first == 10) return true;
            // 172.16.0.0/12
            if (first == 172 && second >= 16 && second <= 31) return true;
            // 192.168.0.0/16
            if (first == 192 && second == 168) return true;
            // 127.0.0.0/8 (loopback)
            if (first == 127) return true;
            // 169.254.0.0/16 (link-local)
            if (first == 169 && second == 254) return true;
            // 0.0.0.0/8
            if (first == 0) return true;
            // 100.64.0.0/10 (carrier-grade NAT, RFC 6598)
            if (first == 100 && second >= 64 && second <= 127) return true;

            return false;
        } catch (NumberFormatException e) {
            return true; // malformed = treat as private
        }
    }

    // ── HTTP Fetch (Single IP) ─────────────────────────────────────────

    /**
     * Fetches geo information for a single IP from ip-api.com.
     *
     * @param ip the IP address
     * @return geo info, or null if the API call fails
     */
    private GeoInfo fetchGeoInfo(String ip) {
        try {
            String url = apiBaseUrl + "/json/" + ip
                    + "?fields=status,countryCode,country,city,lat,lon,as,org,mobile,proxy,hosting";

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(HTTP_TIMEOUT)
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 429) {
                log.warn("GeoIP rate limit exceeded for ip-api.com; returning null for IP {}", maskIp(ip));
                return null;
            }

            if (response.statusCode() != 200) {
                log.warn("GeoIP lookup failed for {}: HTTP {}", maskIp(ip), response.statusCode());
                return null;
            }

            return parseGeoResponse(response.body());

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("GeoIP lookup interrupted for {}", maskIp(ip));
            return null;
        } catch (Exception e) {
            log.debug("GeoIP lookup error for {}: {}", maskIp(ip), e.getMessage());
            return null;
        }
    }

    // ── HTTP Fetch (Batch) ─────────────────────────────────────────────

    /**
     * Fetches geo information for a batch of IPs using ip-api.com's batch endpoint.
     *
     * @param ips list of IPs (max 100)
     * @return map of IP to GeoInfo
     */
    private Map<String, GeoInfo> fetchBatch(List<String> ips) {
        Map<String, GeoInfo> results = new LinkedHashMap<>();

        try {
            // Build JSON array body
            StringBuilder body = new StringBuilder("[");
            for (int i = 0; i < ips.size(); i++) {
                if (i > 0) body.append(",");
                body.append("{\"query\":\"").append(escapeJson(ips.get(i)))
                        .append("\",\"fields\":\"status,query,countryCode,country,city,lat,lon,as,org,mobile,proxy,hosting\"}");
            }
            body.append("]");

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiBaseUrl + "/batch"))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 429) {
                log.warn("GeoIP batch rate limit exceeded; skipping {} IPs", ips.size());
                return results;
            }

            if (response.statusCode() != 200) {
                log.warn("GeoIP batch lookup failed: HTTP {}", response.statusCode());
                return results;
            }

            // Parse the JSON array response
            parseBatchResponse(response.body(), results);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("GeoIP batch lookup interrupted");
        } catch (Exception e) {
            log.debug("GeoIP batch lookup error: {}", e.getMessage());
        }

        return results;
    }

    // ── JSON Parsing (lightweight, no library dependency) ──────────────

    /** Pattern for extracting a JSON string field value. */
    private static final Pattern STRING_FIELD = Pattern.compile("\"(\\w+)\"\\s*:\\s*\"([^\"]*)\"");
    /** Pattern for extracting a JSON numeric field value. */
    private static final Pattern NUMBER_FIELD = Pattern.compile("\"(\\w+)\"\\s*:\\s*(-?[\\d.]+)");
    /** Pattern for extracting a JSON boolean field value. */
    private static final Pattern BOOLEAN_FIELD = Pattern.compile("\"(\\w+)\"\\s*:\\s*(true|false)");

    /**
     * Parses a single ip-api.com JSON response into a {@link GeoInfo} record.
     */
    private GeoInfo parseGeoResponse(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }

        Map<String, String> stringFields = new HashMap<>();
        Map<String, Double> numberFields = new HashMap<>();
        Map<String, Boolean> booleanFields = new HashMap<>();

        Matcher sm = STRING_FIELD.matcher(json);
        while (sm.find()) {
            stringFields.put(sm.group(1), sm.group(2));
        }

        Matcher nm = NUMBER_FIELD.matcher(json);
        while (nm.find()) {
            try {
                numberFields.put(nm.group(1), Double.parseDouble(nm.group(2)));
            } catch (NumberFormatException ignored) {
                // skip malformed numbers
            }
        }

        Matcher bm = BOOLEAN_FIELD.matcher(json);
        while (bm.find()) {
            booleanFields.put(bm.group(1), Boolean.parseBoolean(bm.group(2)));
        }

        // Check status
        String status = stringFields.get("status");
        if (!"success".equals(status)) {
            return null;
        }

        // Parse ASN from "as" field (format: "AS12345 Organization Name")
        int asn = 0;
        String asField = stringFields.getOrDefault("as", "");
        if (asField.startsWith("AS")) {
            int spaceIdx = asField.indexOf(' ');
            String asnStr = spaceIdx > 0 ? asField.substring(2, spaceIdx) : asField.substring(2);
            try {
                asn = Integer.parseInt(asnStr);
            } catch (NumberFormatException ignored) {
                // keep 0
            }
        }

        return new GeoInfo(
                stringFields.getOrDefault("countryCode", ""),
                stringFields.getOrDefault("country", ""),
                stringFields.getOrDefault("city", ""),
                numberFields.getOrDefault("lat", 0.0),
                numberFields.getOrDefault("lon", 0.0),
                asn,
                stringFields.getOrDefault("org", ""),
                booleanFields.getOrDefault("mobile", false),
                booleanFields.getOrDefault("proxy", false),
                booleanFields.getOrDefault("hosting", false)
        );
    }

    /**
     * Parses the ip-api.com batch response (JSON array of objects).
     * Each element is parsed individually and added to the results map
     * keyed by the "query" field (the IP address).
     */
    private void parseBatchResponse(String jsonArray, Map<String, GeoInfo> results) {
        if (jsonArray == null || jsonArray.isBlank()) {
            return;
        }

        // Split the array into individual objects by finding balanced braces
        int depth = 0;
        int start = -1;

        for (int i = 0; i < jsonArray.length(); i++) {
            char c = jsonArray.charAt(i);
            if (c == '{') {
                if (depth == 0) {
                    start = i;
                }
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0 && start >= 0) {
                    String element = jsonArray.substring(start, i + 1);
                    GeoInfo info = parseGeoResponse(element);

                    // Extract query (IP) from the element
                    Matcher qm = STRING_FIELD.matcher(element);
                    String queryIp = null;
                    while (qm.find()) {
                        if ("query".equals(qm.group(1))) {
                            queryIp = qm.group(2);
                            break;
                        }
                    }

                    if (info != null && queryIp != null) {
                        results.put(queryIp, info);
                    }
                    start = -1;
                }
            }
        }
    }

    // ── Haversine Formula ──────────────────────────────────────────────

    /**
     * Calculates the great-circle distance between two points on Earth
     * using the Haversine formula.
     *
     * @param lat1 latitude of point 1 (degrees)
     * @param lon1 longitude of point 1 (degrees)
     * @param lat2 latitude of point 2 (degrees)
     * @param lon2 longitude of point 2 (degrees)
     * @return distance in kilometres
     */
    private static double haversine(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);

        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);

        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));

        return EARTH_RADIUS_KM * c;
    }

    // ── Utilities ──────────────────────────────────────────────────────

    /**
     * Masks an IP address for safe logging (shows first and last octet only).
     */
    private String maskIp(String ip) {
        if (ip == null || !ip.contains(".")) {
            return "***";
        }
        String[] parts = ip.split("\\.");
        if (parts.length != 4) {
            return "***";
        }
        return parts[0] + ".*.*." + parts[3];
    }

    /**
     * Minimal JSON string escaping (double-quotes and backslashes).
     */
    private String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
