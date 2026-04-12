package com.filetransfer.ai.service.intelligence;

import com.filetransfer.ai.entity.intelligence.IndicatorType;
import com.filetransfer.ai.entity.intelligence.ThreatIndicator;
import com.filetransfer.ai.entity.intelligence.ThreatLevel;
import com.filetransfer.ai.repository.intelligence.ThreatIndicatorRepository;
import jakarta.annotation.PostConstruct;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Central store for all threat intelligence indicators of compromise (IOCs).
 *
 * <p>Combines an in-memory hot cache (for microsecond lookups during real-time
 * verdict computation) with database persistence via {@link ThreatIndicatorRepository}.
 * Indicators are keyed by {@code type:value} for deduplication and fast retrieval.</p>
 *
 * <h3>Design Decisions</h3>
 * <ul>
 *   <li>Four type-specific maps (IP, DOMAIN, HASH, URL) serve the hot path; a
 *       composite key map backs the generic {@link #lookup(String, String)} path.</li>
 *   <li>Writes are acknowledged to callers immediately; database persistence is
 *       performed on a 5-minute schedule so ingestion never blocks the verdict pipeline.</li>
 *   <li>A scheduled pruning job ages out stale indicators (not seen in configured days)
 *       and downgrades indicators not refreshed in 30 days.</li>
 *   <li>On startup, recent indicators (last 90 days) are loaded from the database
 *       into the in-memory cache.</li>
 *   <li>Supports STIX 2.1 export and IP enrichment for analyst workflows.</li>
 * </ul>
 *
 * @see ThreatIndicator
 * @see ThreatIndicatorRepository
 */
@Service
@Slf4j
public class ThreatIntelligenceStore {

    private final ThreatIndicatorRepository repository;
    private final MitreAttackMapper mitreMapper;

    /** IP address indicators — hot path for verdict computation. */
    private final ConcurrentHashMap<String, ThreatIndicator> ipIndex = new ConcurrentHashMap<>();

    /** Domain name indicators — hot path for DNS/domain checks. */
    private final ConcurrentHashMap<String, ThreatIndicator> domainIndex = new ConcurrentHashMap<>();

    /** File hash indicators (MD5, SHA1, SHA256, JA3, JA4) — hot path for file scanning. */
    private final ConcurrentHashMap<String, ThreatIndicator> hashIndex = new ConcurrentHashMap<>();

    /** URL indicators — hot path for URL reputation checks. */
    private final ConcurrentHashMap<String, ThreatIndicator> urlIndex = new ConcurrentHashMap<>();

    /** All indicators by composite key {@code TYPE:value} for generic lookup. */
    private final ConcurrentHashMap<String, ThreatIndicator> allIndicators = new ConcurrentHashMap<>();

    // ── Metrics ────────────────────────────────────────────────────────

    private final AtomicLong totalIndicators = new AtomicLong(0);
    private final AtomicLong totalLookups = new AtomicLong(0);
    private final AtomicLong totalHits = new AtomicLong(0);
    private final AtomicLong ingestCount = new AtomicLong(0);

    // ── Configuration ──────────────────────────────────────────────────

    @Value("${ai.intelligence.max-indicators:500000}")
    private int maxIndicators;

    @Value("${ai.intelligence.stale-days:90}")
    private int staleDays;

    public ThreatIntelligenceStore(ThreatIndicatorRepository repository,
                                    MitreAttackMapper mitreMapper) {
        this.repository = repository;
        this.mitreMapper = mitreMapper;
    }

    // ── Startup Loading ────────────────────────────────────────────────

    /**
     * Loads recent indicators from the database into the in-memory cache on startup.
     * Only indicators seen within the configured stale-days window are loaded.
     */
    @org.springframework.scheduling.annotation.Async
    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void loadFromDatabase() {
        try {
            Instant cutoff = Instant.now().minus(Duration.ofDays(staleDays));
            List<ThreatIndicator> recent = repository.findByLastSeenAfter(cutoff);

            for (ThreatIndicator indicator : recent) {
                String compositeKey = compositeKey(indicator.getType(), indicator.getValue());
                allIndicators.put(compositeKey, indicator);
                putInTypeMap(indicator);
            }

            totalIndicators.set(allIndicators.size());
            log.info("Loaded {} threat indicators from database into in-memory cache (cutoff: last {} days)",
                    recent.size(), staleDays);
        } catch (Exception e) {
            log.warn("Failed to load threat indicators from database on startup. "
                    + "Store will start empty and populate as feeds ingest. Error: {}", e.getMessage());
        }
    }

    // ── Ingestion ──────────────────────────────────────────────────────

    /**
     * Ingests a single threat indicator into the store.
     *
     * <p>If an indicator with the same type and value already exists, the existing
     * entry is merged (sources combined, threat level upgraded if higher, sightings
     * incremented, lastSeen refreshed). New indicators are inserted directly.</p>
     *
     * @param indicator the indicator to ingest
     */
    public void ingest(ThreatIndicator indicator) {
        if (indicator == null || indicator.getType() == null || indicator.getValue() == null) {
            log.warn("Skipping null or incomplete indicator during ingestion");
            return;
        }

        String compositeKey = compositeKey(indicator.getType(), indicator.getValue());
        ThreatIndicator existing = allIndicators.get(compositeKey);

        if (existing != null) {
            existing.mergeFrom(indicator);
            putInTypeMap(existing);
            log.debug("Merged indicator: type={}, value={}, sightings={}",
                    existing.getType(), maskValue(existing.getValue()), existing.getSightings());
        } else {
            // Enforce capacity limit
            if (totalIndicators.get() >= maxIndicators) {
                evictLeastValuable();
            }

            if (indicator.getIocId() == null) {
                indicator.setIocId(UUID.randomUUID());
            }
            if (indicator.getFirstSeen() == null) {
                indicator.setFirstSeen(Instant.now());
            }
            indicator.setLastSeen(Instant.now());

            allIndicators.put(compositeKey, indicator);
            putInTypeMap(indicator);
            totalIndicators.incrementAndGet();
            log.debug("Ingested new indicator: type={}, value={}, threat={}",
                    indicator.getType(), maskValue(indicator.getValue()), indicator.getThreatLevel());
        }

        ingestCount.incrementAndGet();
    }

    /**
     * Bulk ingest indicators from a threat feed. Returns detailed results
     * including counts of new, updated, and errored indicators.
     *
     * @param indicators the indicators to ingest
     * @param feedSource the source feed name (added to each indicator's sources)
     * @return detailed results of the ingestion operation
     */
    public IngestResult bulkIngest(List<ThreatIndicator> indicators, String feedSource) {
        if (indicators == null || indicators.isEmpty()) {
            return IngestResult.builder()
                    .newIndicators(0).updatedIndicators(0)
                    .duplicatesSkipped(0).errors(0)
                    .processingTime(Duration.ZERO)
                    .build();
        }

        Instant start = Instant.now();
        int newCount = 0;
        int updatedCount = 0;
        int errorCount = 0;

        for (ThreatIndicator indicator : indicators) {
            try {
                if (feedSource != null && !feedSource.isBlank()) {
                    indicator.addSource(feedSource);
                }

                String compositeKey = compositeKey(indicator.getType(), indicator.getValue());
                boolean isUpdate = allIndicators.containsKey(compositeKey);

                ingest(indicator);

                if (isUpdate) {
                    updatedCount++;
                } else {
                    newCount++;
                }
            } catch (Exception e) {
                errorCount++;
                log.warn("Error ingesting indicator (type={}, value={}): {}",
                        indicator.getType(), maskValue(indicator.getValue()), e.getMessage());
            }
        }

        Duration processingTime = Duration.between(start, Instant.now());
        log.info("Bulk ingest from '{}': {} new, {} updated, {} errors in {} ms",
                feedSource, newCount, updatedCount, errorCount, processingTime.toMillis());

        return IngestResult.builder()
                .newIndicators(newCount)
                .updatedIndicators(updatedCount)
                .duplicatesSkipped(0)
                .errors(errorCount)
                .processingTime(processingTime)
                .build();
    }

    // ── Lookup Methods (Hot Path — target < 1ms) ───────────────────────

    /**
     * Fast lookup: is this IP address a known threat indicator?
     *
     * @param ip the IP address to look up
     * @return the matching indicator, if present
     */
    public Optional<ThreatIndicator> lookupIp(String ip) {
        totalLookups.incrementAndGet();
        ThreatIndicator result = ipIndex.get(normalizeValue(ip));
        if (result != null) {
            totalHits.incrementAndGet();
            result.incrementSightings();
        }
        return Optional.ofNullable(result);
    }

    /**
     * Fast lookup for a domain name.
     *
     * @param domain the domain name to look up
     * @return the matching indicator, if present
     */
    public Optional<ThreatIndicator> lookupDomain(String domain) {
        totalLookups.incrementAndGet();
        ThreatIndicator result = domainIndex.get(normalizeValue(domain));
        if (result != null) {
            totalHits.incrementAndGet();
            result.incrementSightings();
        }
        return Optional.ofNullable(result);
    }

    /**
     * Fast lookup for a file hash (MD5, SHA-1, or SHA-256).
     *
     * @param hash the hash value to look up
     * @return the matching indicator, if present
     */
    public Optional<ThreatIndicator> lookupHash(String hash) {
        totalLookups.incrementAndGet();
        ThreatIndicator result = hashIndex.get(normalizeValue(hash));
        if (result != null) {
            totalHits.incrementAndGet();
            result.incrementSightings();
        }
        return Optional.ofNullable(result);
    }

    /**
     * Fast lookup for a URL.
     *
     * @param url the URL to look up
     * @return the matching indicator, if present
     */
    public Optional<ThreatIndicator> lookupUrl(String url) {
        totalLookups.incrementAndGet();
        ThreatIndicator result = urlIndex.get(normalizeValue(url));
        if (result != null) {
            totalHits.incrementAndGet();
            result.incrementSightings();
        }
        return Optional.ofNullable(result);
    }

    /**
     * Generic lookup by indicator type and value.
     *
     * @param type  the indicator type name (e.g. "IP", "DOMAIN", "HASH_SHA256")
     * @param value the observable value
     * @return the matching indicator, if present
     */
    public Optional<ThreatIndicator> lookup(String type, String value) {
        if (type == null || value == null) {
            return Optional.empty();
        }
        totalLookups.incrementAndGet();

        try {
            IndicatorType indicatorType = IndicatorType.valueOf(type.strip().toUpperCase());
            String key = compositeKey(indicatorType, value);
            ThreatIndicator result = allIndicators.get(key);
            if (result != null) {
                totalHits.incrementAndGet();
                result.incrementSightings();
            }
            return Optional.ofNullable(result);
        } catch (IllegalArgumentException e) {
            log.debug("Unknown indicator type for lookup: {}", type);
            return Optional.empty();
        }
    }

    // ── Search ─────────────────────────────────────────────────────────

    /**
     * Search indicators by flexible criteria object.
     *
     * @param criteria the search criteria
     * @return list of matching indicators, limited by criteria.limit
     */
    public List<ThreatIndicator> search(IndicatorSearchCriteria criteria) {
        if (criteria == null) {
            return Collections.emptyList();
        }

        return allIndicators.values().stream()
                .filter(ind -> criteria.getType() == null || ind.getType() == criteria.getType())
                .filter(ind -> criteria.getMinThreatLevel() == null
                        || ind.getThreatLevel().ordinal() >= criteria.getMinThreatLevel().ordinal())
                .filter(ind -> criteria.getSource() == null || criteria.getSource().isBlank()
                        || ind.getSourcesList().contains(criteria.getSource()))
                .filter(ind -> criteria.getTag() == null || criteria.getTag().isBlank()
                        || ind.getTagsList().contains(criteria.getTag()))
                .filter(ind -> criteria.getSince() == null
                        || (ind.getLastSeen() != null && ind.getLastSeen().isAfter(criteria.getSince())))
                .sorted(Comparator.comparing(ThreatIndicator::getLastSeen,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(criteria.getLimit() > 0 ? criteria.getLimit() : 100)
                .collect(Collectors.toList());
    }

    /**
     * Search indicators with simple string-based filters.
     *
     * @param query       text to match against the indicator value (substring match, nullable)
     * @param type        indicator type filter (nullable)
     * @param threatLevel threat level filter (nullable)
     * @param limit       maximum number of results
     * @return matching indicators sorted by lastSeen descending
     */
    public List<ThreatIndicator> search(String query, String type, String threatLevel, int limit) {
        return allIndicators.values().stream()
                .filter(ind -> {
                    if (query != null && !query.isBlank()) {
                        return ind.getValue().toLowerCase().contains(query.toLowerCase());
                    }
                    return true;
                })
                .filter(ind -> {
                    if (type != null && !type.isBlank()) {
                        try {
                            return ind.getType() == IndicatorType.valueOf(type.strip().toUpperCase());
                        } catch (IllegalArgumentException e) {
                            return false;
                        }
                    }
                    return true;
                })
                .filter(ind -> {
                    if (threatLevel != null && !threatLevel.isBlank()) {
                        try {
                            return ind.getThreatLevel() == ThreatLevel.valueOf(threatLevel.strip().toUpperCase());
                        } catch (IllegalArgumentException e) {
                            return false;
                        }
                    }
                    return true;
                })
                .sorted(Comparator.comparing(ThreatIndicator::getLastSeen,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(Math.max(1, Math.min(limit, 1000)))
                .toList();
    }

    // ── Threat Scoring ─────────────────────────────────────────────────

    /**
     * Computes a threat score (0-100) for a given IP address.
     *
     * <p>The score is derived from multiple signals:</p>
     * <ul>
     *   <li><b>Direct IOC match</b> — high base score depending on threat level</li>
     *   <li><b>Subnet match</b> — partial score if the /24 subnet is known-bad</li>
     *   <li><b>Multi-source confirmation</b> — bonus for indicators reported by multiple feeds</li>
     *   <li><b>Recency</b> — indicators seen recently receive a slight boost</li>
     * </ul>
     *
     * @param ip the IP address to score
     * @return threat score from 0 (benign) to 100 (confirmed malicious)
     */
    public int getThreatScore(String ip) {
        if (ip == null || ip.isBlank()) {
            return 0;
        }

        String normalized = normalizeValue(ip);
        int score = 0;

        // Direct IOC match
        ThreatIndicator direct = ipIndex.get(normalized);
        if (direct != null) {
            score = switch (direct.getThreatLevel()) {
                case CRITICAL -> 90;
                case HIGH -> 75;
                case MEDIUM -> 50;
                case LOW -> 25;
                case UNKNOWN -> 15;
            };

            // Multi-source bonus
            int sourceCount = direct.getSourcesList().size();
            if (sourceCount > 1) {
                score += Math.min(sourceCount * 2, 10);
            }

            // Recency bonus
            if (direct.getLastSeen() != null
                    && direct.getLastSeen().isAfter(Instant.now().minus(Duration.ofHours(24)))) {
                score += 5;
            }

            // Confidence factor
            score = (int) Math.round(score * direct.getEffectiveConfidence());
        }

        // Subnet match (/24) if no direct match found or to augment score
        if (score < 50) {
            int subnetScore = computeSubnetScore(normalized);
            score = Math.max(score, subnetScore);
        }

        return Math.min(100, Math.max(0, score));
    }

    // ── Dashboard Summary ──────────────────────────────────────────────

    /**
     * Get a pre-aggregated threat intelligence summary for the dashboard.
     *
     * @return summary map containing total indicators by type, by threat level,
     *         by source, top 10 most-sighted, recently added, and feed freshness
     */
    public Map<String, Object> getSummary() {
        Map<String, Object> summary = new LinkedHashMap<>();

        // Totals
        summary.put("totalIndicators", totalIndicators.get());
        summary.put("totalLookups", totalLookups.get());
        summary.put("totalHits", totalHits.get());
        long lookups = totalLookups.get();
        long hits = totalHits.get();
        summary.put("hitRate", lookups > 0
                ? String.format("%.2f%%", (double) hits / lookups * 100) : "0.00%");
        summary.put("totalIngested", ingestCount.get());

        // Counts by type
        Map<String, Long> byType = new LinkedHashMap<>();
        for (IndicatorType type : IndicatorType.values()) {
            long count = allIndicators.values().stream()
                    .filter(i -> i.getType() == type).count();
            if (count > 0) byType.put(type.name(), count);
        }
        summary.put("byType", byType);

        // Counts by threat level
        Map<String, Long> byLevel = new LinkedHashMap<>();
        for (ThreatLevel level : ThreatLevel.values()) {
            long count = allIndicators.values().stream()
                    .filter(i -> i.getThreatLevel() == level).count();
            if (count > 0) byLevel.put(level.name(), count);
        }
        summary.put("byThreatLevel", byLevel);

        // Counts by source
        Map<String, Long> bySource = new LinkedHashMap<>();
        allIndicators.values().forEach(ind ->
                ind.getSourcesList().forEach(src -> bySource.merge(src, 1L, Long::sum)));
        summary.put("bySources", bySource);

        // Top 10 most-sighted indicators
        List<Map<String, Object>> topSighted = allIndicators.values().stream()
                .sorted(Comparator.comparingInt(ThreatIndicator::getSightings).reversed())
                .limit(10)
                .map(this::indicatorToSummaryMap)
                .collect(Collectors.toList());
        summary.put("topSighted", topSighted);

        // Recently added (last 24 hours)
        Instant dayAgo = Instant.now().minus(Duration.ofHours(24));
        List<Map<String, Object>> recentlyAdded = allIndicators.values().stream()
                .filter(i -> i.getFirstSeen() != null && i.getFirstSeen().isAfter(dayAgo))
                .sorted(Comparator.comparing(ThreatIndicator::getFirstSeen).reversed())
                .limit(10)
                .map(this::indicatorToSummaryMap)
                .collect(Collectors.toList());
        summary.put("recentlyAdded", recentlyAdded);

        // Feed freshness: most recent lastSeen per source
        Map<String, String> feedFreshness = new LinkedHashMap<>();
        allIndicators.values().forEach(ind ->
                ind.getSourcesList().forEach(src -> {
                    String existingStr = feedFreshness.get(src);
                    Instant existing = existingStr != null ? Instant.parse(existingStr) : Instant.MIN;
                    if (ind.getLastSeen() != null && ind.getLastSeen().isAfter(existing)) {
                        feedFreshness.put(src, ind.getLastSeen().toString());
                    }
                }));
        summary.put("feedFreshness", feedFreshness);

        // Index sizes
        Map<String, Integer> indexSizes = new LinkedHashMap<>();
        indexSizes.put("ip", ipIndex.size());
        indexSizes.put("domain", domainIndex.size());
        indexSizes.put("hash", hashIndex.size());
        indexSizes.put("url", urlIndex.size());
        summary.put("indexSizes", indexSizes);

        // Freshness distribution
        Instant now = Instant.now();
        long last24h = allIndicators.values().stream()
                .filter(ind -> ind.getLastSeen() != null && ind.getLastSeen().isAfter(now.minus(Duration.ofHours(24))))
                .count();
        long last7d = allIndicators.values().stream()
                .filter(ind -> ind.getLastSeen() != null && ind.getLastSeen().isAfter(now.minus(Duration.ofDays(7))))
                .count();
        long last30d = allIndicators.values().stream()
                .filter(ind -> ind.getLastSeen() != null && ind.getLastSeen().isAfter(now.minus(Duration.ofDays(30))))
                .count();
        summary.put("freshnessDistribution", Map.of(
                "last24Hours", last24h,
                "last7Days", last7d,
                "last30Days", last30d));

        return summary;
    }

    // ── Eviction ───────────────────────────────────────────────────────

    /**
     * Evict stale indicators that have not been sighted within the configured
     * staleness window ({@code ai.intelligence.stale-days}).
     *
     * <p>Called by the maintenance agent or scheduled internally.  Indicators not
     * seen in more than 30 days (but less than stale-days) are downgraded by one
     * threat level instead of being removed.</p>
     *
     * @return the number of indicators removed
     */
    public int evictStale() {
        log.info("Evicting stale indicators (not seen in > {} days)...", staleDays);
        Instant now = Instant.now();
        Instant staleThreshold = now.minus(Duration.ofDays(staleDays));
        Instant downgradeThreshold = now.minus(Duration.ofDays(30));

        int removed = 0;
        int downgraded = 0;

        Iterator<Map.Entry<String, ThreatIndicator>> it = allIndicators.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, ThreatIndicator> entry = it.next();
            ThreatIndicator indicator = entry.getValue();

            if (indicator.getLastSeen() != null && indicator.getLastSeen().isBefore(staleThreshold)) {
                it.remove();
                removeFromTypeMap(indicator);
                totalIndicators.decrementAndGet();
                removed++;
            } else if (indicator.getLastSeen() != null && indicator.getLastSeen().isBefore(downgradeThreshold)) {
                ThreatLevel current = indicator.getThreatLevel();
                if (current != null && current != ThreatLevel.UNKNOWN && current != ThreatLevel.LOW) {
                    ThreatLevel downgradeTarget = switch (current) {
                        case CRITICAL -> ThreatLevel.HIGH;
                        case HIGH -> ThreatLevel.MEDIUM;
                        case MEDIUM -> ThreatLevel.LOW;
                        default -> current;
                    };
                    indicator.setThreatLevel(downgradeTarget);
                    downgraded++;
                }
            }
        }

        log.info("Stale eviction complete: {} removed (>{}d stale), {} downgraded (>30d stale), {} remaining",
                removed, staleDays, downgraded, totalIndicators.get());
        return removed;
    }

    // ── STIX 2.1 Export ────────────────────────────────────────────────

    /**
     * Export matching indicators in STIX 2.1 JSON bundle format.
     *
     * @param criteria the search criteria for selecting indicators to export
     * @return STIX 2.1 bundle as a JSON string
     */
    public String exportStix(IndicatorSearchCriteria criteria) {
        List<ThreatIndicator> indicators = search(criteria);

        StringBuilder sb = new StringBuilder(indicators.size() * 512);
        sb.append("{\n");
        sb.append("  \"type\": \"bundle\",\n");
        sb.append("  \"id\": \"bundle--").append(UUID.randomUUID()).append("\",\n");
        sb.append("  \"spec_version\": \"2.1\",\n");
        sb.append("  \"created\": \"").append(Instant.now()).append("\",\n");
        sb.append("  \"objects\": [\n");

        for (int i = 0; i < indicators.size(); i++) {
            ThreatIndicator ind = indicators.get(i);
            sb.append("    {\n");
            sb.append("      \"type\": \"indicator\",\n");
            sb.append("      \"spec_version\": \"2.1\",\n");
            sb.append("      \"id\": \"indicator--").append(ind.getIocId()).append("\",\n");
            sb.append("      \"created\": \"").append(ind.getFirstSeen()).append("\",\n");
            sb.append("      \"modified\": \"").append(ind.getLastSeen()).append("\",\n");
            sb.append("      \"name\": \"").append(escapeJson(ind.getType() + ": " + ind.getValue())).append("\",\n");
            sb.append("      \"pattern\": \"").append(escapeJson(toStixPattern(ind))).append("\",\n");
            sb.append("      \"pattern_type\": \"stix\",\n");
            sb.append("      \"valid_from\": \"").append(ind.getFirstSeen()).append("\",\n");
            sb.append("      \"confidence\": ").append((int) (ind.getConfidence() * 100)).append(",\n");
            sb.append("      \"labels\": [\"").append(ind.getThreatLevel()).append("\"]");

            // Add MITRE kill chain phases if present
            List<String> mitreTechniques = ind.getMitreTechniquesList();
            if (!mitreTechniques.isEmpty()) {
                sb.append(",\n      \"kill_chain_phases\": [\n");
                for (int j = 0; j < mitreTechniques.size(); j++) {
                    String techId = mitreTechniques.get(j);
                    MitreAttackMapper.TechniqueInfo techInfo = mitreMapper.getTechnique(techId);
                    String phaseName = techInfo != null && !techInfo.getTactics().isEmpty()
                            ? resolveFirstTacticName(techInfo.getTactics().get(0))
                            : "unknown";
                    sb.append("        {\"kill_chain_name\": \"mitre-attack\", \"phase_name\": \"")
                            .append(escapeJson(phaseName)).append("\"}");
                    if (j < mitreTechniques.size() - 1) sb.append(",");
                    sb.append("\n");
                }
                sb.append("      ]");
            }

            sb.append("\n    }");
            if (i < indicators.size() - 1) sb.append(",");
            sb.append("\n");
        }

        sb.append("  ]\n");
        sb.append("}\n");

        log.info("Exported {} indicators in STIX 2.1 format", indicators.size());
        return sb.toString();
    }

    // ── IP Enrichment ──────────────────────────────────────────────────

    /**
     * Get enrichment context for a given IP address — combines all available
     * intelligence sources and related data into a comprehensive dossier.
     *
     * @param ip the IP address to enrich
     * @return enrichment map containing threat indicators, reputation data,
     *         associated MITRE techniques, related domains, and historical activity
     */
    public Map<String, Object> getIpEnrichment(String ip) {
        Map<String, Object> enrichment = new LinkedHashMap<>();
        enrichment.put("ip", ip);
        enrichment.put("timestamp", Instant.now().toString());

        // Primary threat indicator lookup
        Optional<ThreatIndicator> indicator = lookupIp(ip);
        if (indicator.isPresent()) {
            ThreatIndicator ind = indicator.get();
            enrichment.put("known", true);
            enrichment.put("threatIndicator", indicatorToSummaryMap(ind));
            enrichment.put("threatLevel", ind.getThreatLevel().name());
            enrichment.put("confidence", ind.getConfidence());
            enrichment.put("effectiveConfidence", ind.getEffectiveConfidence());
            enrichment.put("sources", ind.getSourcesList());
            enrichment.put("tags", ind.getTagsList());
            enrichment.put("sightings", ind.getSightings());
            enrichment.put("firstSeen", ind.getFirstSeen() != null ? ind.getFirstSeen().toString() : null);
            enrichment.put("lastSeen", ind.getLastSeen() != null ? ind.getLastSeen().toString() : null);
            enrichment.put("threatScore", getThreatScore(ip));

            // MITRE technique enrichment
            List<String> mitreTechniques = ind.getMitreTechniquesList();
            if (!mitreTechniques.isEmpty()) {
                List<Map<String, Object>> techniqueDetails = mitreTechniques.stream()
                        .map(techId -> {
                            MitreAttackMapper.TechniqueInfo info = mitreMapper.getTechnique(techId);
                            if (info == null) return null;
                            Map<String, Object> detail = new LinkedHashMap<>();
                            detail.put("id", info.getId());
                            detail.put("name", info.getName());
                            detail.put("tactics", info.getTactics());
                            detail.put("severity", info.getSeverity());
                            detail.put("description", info.getDescription());
                            return detail;
                        })
                        .filter(Objects::nonNull)
                        .collect(Collectors.toList());
                enrichment.put("mitreTechniques", techniqueDetails);

                // Attack chain analysis if multiple techniques
                if (mitreTechniques.size() > 1) {
                    MitreAttackMapper.AttackChainAnalysis chain =
                            mitreMapper.analyzeAttackChain(mitreTechniques);
                    Map<String, Object> chainMap = new LinkedHashMap<>();
                    chainMap.put("currentStage", chain.getCurrentStage());
                    chainMap.put("progressionScore", chain.getProgressionScore());
                    chainMap.put("predictedNextTechniques", chain.getPredictedNextTechniques());
                    chainMap.put("riskAssessment", chain.getRiskAssessment());
                    enrichment.put("attackChainAnalysis", chainMap);
                }
            }

            // Context from JSON field
            if (ind.getContextJson() != null && !ind.getContextJson().isBlank()) {
                enrichment.put("additionalContext", ind.getContextJson());
            }
        } else {
            enrichment.put("known", false);
            enrichment.put("threatLevel", "UNKNOWN");
            enrichment.put("threatScore", getThreatScore(ip));
        }

        // Find related domains (indicators that share sources/tags with this IP)
        List<Map<String, Object>> relatedDomains = findRelatedIndicators(ip, domainIndex);
        if (!relatedDomains.isEmpty()) {
            enrichment.put("relatedDomains", relatedDomains);
        }

        // Find related hashes
        List<Map<String, Object>> relatedHashes = findRelatedIndicators(ip, hashIndex);
        if (!relatedHashes.isEmpty()) {
            enrichment.put("relatedHashes", relatedHashes);
        }

        // Subnet neighbours
        String subnetPrefix = extractSubnetPrefix(normalizeValue(ip));
        if (subnetPrefix != null) {
            List<Map<String, Object>> subnetNeighbours = ipIndex.entrySet().stream()
                    .filter(e -> !e.getKey().equals(normalizeValue(ip)))
                    .filter(e -> subnetPrefix.equals(extractSubnetPrefix(e.getKey())))
                    .limit(5)
                    .map(e -> indicatorToSummaryMap(e.getValue()))
                    .collect(Collectors.toList());
            if (!subnetNeighbours.isEmpty()) {
                enrichment.put("subnetNeighbours", subnetNeighbours);
            }
        }

        return enrichment;
    }

    // ── Periodic Persistence ───────────────────────────────────────────

    /**
     * Persists all in-memory indicators to the database.
     * Runs every 5 minutes to ensure data survives restarts.
     */
    @Scheduled(fixedDelay = 300_000)
    @SchedulerLock(name = "threatIntelPersist", lockAtLeastFor = "290s", lockAtMostFor = "600s")
    public void persistToDatabase() {
        if (allIndicators.isEmpty()) {
            return;
        }

        log.info("Persisting {} threat indicators to database...", allIndicators.size());
        long start = System.currentTimeMillis();

        int saved = 0;
        int errors = 0;

        for (ThreatIndicator indicator : allIndicators.values()) {
            try {
                repository.save(indicator);
                saved++;
            } catch (Exception e) {
                errors++;
                if (errors <= 5) {
                    log.warn("Error persisting indicator (type={}, value={}): {}",
                            indicator.getType(), maskValue(indicator.getValue()), e.getMessage());
                }
            }
        }

        long elapsed = System.currentTimeMillis() - start;
        if (errors > 0) {
            log.warn("Persisted {} indicators ({} errors) in {} ms", saved, errors, elapsed);
        } else {
            log.info("Persisted {} indicators in {} ms", saved, elapsed);
        }
    }

    // ── Accessors ──────────────────────────────────────────────────────

    /**
     * Returns a snapshot of all indicators currently in the in-memory cache.
     * Intended for graph rebuilds and bulk export. Do not call on the hot path.
     *
     * @return unmodifiable collection of all cached indicators
     */
    public Collection<ThreatIndicator> getAllIndicators() {
        return Collections.unmodifiableCollection(allIndicators.values());
    }

    // ── Private Helpers ────────────────────────────────────────────────

    /**
     * Places an indicator in the appropriate type-specific map for fast hot-path lookup.
     */
    private void putInTypeMap(ThreatIndicator indicator) {
        String normalized = normalizeValue(indicator.getValue());
        switch (indicator.getType()) {
            case IP, CIDR -> ipIndex.put(normalized, indicator);
            case DOMAIN -> domainIndex.put(normalized, indicator);
            case HASH_MD5, HASH_SHA1, HASH_SHA256, JA3, JA4 -> hashIndex.put(normalized, indicator);
            case URL -> urlIndex.put(normalized, indicator);
            default -> { /* stored in allIndicators only */ }
        }
    }

    /**
     * Removes an indicator from the appropriate type-specific map.
     */
    private void removeFromTypeMap(ThreatIndicator indicator) {
        String normalized = normalizeValue(indicator.getValue());
        switch (indicator.getType()) {
            case IP, CIDR -> ipIndex.remove(normalized);
            case DOMAIN -> domainIndex.remove(normalized);
            case HASH_MD5, HASH_SHA1, HASH_SHA256, JA3, JA4 -> hashIndex.remove(normalized);
            case URL -> urlIndex.remove(normalized);
            default -> { /* only in allIndicators */ }
        }
    }

    /**
     * Computes a composite key for deduplication: {@code TYPE:normalized_value}.
     */
    private String compositeKey(IndicatorType type, String value) {
        return type.name() + ":" + normalizeValue(value);
    }

    /**
     * Normalizes a value for consistent lookup: trims whitespace and lowercases.
     */
    private String normalizeValue(String value) {
        return value == null ? "" : value.strip().toLowerCase();
    }

    /**
     * Masks an indicator value for safe logging (shows first/last chars only).
     */
    private String maskValue(String value) {
        if (value == null || value.length() <= 6) {
            return "***";
        }
        return value.substring(0, 3) + "***" + value.substring(value.length() - 3);
    }

    /**
     * Evict the least valuable indicator when capacity is reached.
     * Priority for eviction: lowest threat level, fewest sightings, oldest lastSeen.
     */
    private void evictLeastValuable() {
        allIndicators.entrySet().stream()
                .min(Comparator.<Map.Entry<String, ThreatIndicator>>comparingInt(e ->
                        e.getValue().getThreatLevel() != null ? e.getValue().getThreatLevel().ordinal() : 0)
                        .thenComparingInt(e -> e.getValue().getSightings())
                        .thenComparing(e -> e.getValue().getLastSeen() != null
                                ? e.getValue().getLastSeen() : Instant.MIN))
                .ifPresent(victim -> {
                    allIndicators.remove(victim.getKey());
                    removeFromTypeMap(victim.getValue());
                    totalIndicators.decrementAndGet();
                    log.debug("Evicted least-valuable indicator: type={}, value={}",
                            victim.getValue().getType(), maskValue(victim.getValue().getValue()));
                });
    }

    /**
     * Computes a threat score contribution from /24 subnet matches.
     */
    private int computeSubnetScore(String ip) {
        String prefix = extractSubnetPrefix(ip);
        if (prefix == null) {
            return 0;
        }

        List<ThreatIndicator> subnetMatches = ipIndex.entrySet().stream()
                .filter(e -> {
                    String p = extractSubnetPrefix(e.getKey());
                    return prefix.equals(p);
                })
                .map(Map.Entry::getValue)
                .toList();

        if (subnetMatches.isEmpty()) {
            return 0;
        }

        double avgLevel = subnetMatches.stream()
                .mapToInt(ind -> switch (ind.getThreatLevel()) {
                    case CRITICAL -> 90;
                    case HIGH -> 75;
                    case MEDIUM -> 50;
                    case LOW -> 25;
                    case UNKNOWN -> 10;
                })
                .average()
                .orElse(0.0);

        int subnetScore = (int) Math.round(avgLevel * 0.6);
        if (subnetMatches.size() >= 5) {
            subnetScore += 10;
        } else if (subnetMatches.size() >= 2) {
            subnetScore += 5;
        }

        return Math.min(60, subnetScore);
    }

    /**
     * Extracts the /24 subnet prefix from an IPv4 address.
     */
    private String extractSubnetPrefix(String ip) {
        if (ip == null || !ip.contains(".")) {
            return null;
        }
        String[] octets = ip.split("\\.");
        if (octets.length != 4) {
            return null;
        }
        return octets[0] + "." + octets[1] + "." + octets[2];
    }

    /**
     * Find indicators related to a given IP by shared source or tag overlap.
     */
    private List<Map<String, Object>> findRelatedIndicators(String ip,
                                                             ConcurrentHashMap<String, ThreatIndicator> targetIndex) {
        ThreatIndicator ipIndicator = ipIndex.get(normalizeValue(ip));
        if (ipIndicator == null) {
            return Collections.emptyList();
        }

        Set<String> ipSources = new HashSet<>(ipIndicator.getSourcesList());
        Set<String> ipTags = new HashSet<>(ipIndicator.getTagsList());

        if (ipSources.isEmpty() && ipTags.isEmpty()) {
            return Collections.emptyList();
        }

        return targetIndex.values().stream()
                .filter(ind -> {
                    boolean sharedSource = ind.getSourcesList().stream().anyMatch(ipSources::contains);
                    boolean sharedTag = ind.getTagsList().stream().anyMatch(ipTags::contains);
                    return sharedSource || sharedTag;
                })
                .limit(10)
                .map(this::indicatorToSummaryMap)
                .collect(Collectors.toList());
    }

    /**
     * Convert an indicator to a lightweight summary map for API responses.
     */
    private Map<String, Object> indicatorToSummaryMap(ThreatIndicator ind) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("iocId", ind.getIocId() != null ? ind.getIocId().toString() : null);
        map.put("type", ind.getType().name());
        map.put("value", ind.getValue());
        map.put("threatLevel", ind.getThreatLevel() != null ? ind.getThreatLevel().name() : "UNKNOWN");
        map.put("confidence", ind.getConfidence());
        map.put("sightings", ind.getSightings());
        map.put("sources", ind.getSourcesList());
        map.put("firstSeen", ind.getFirstSeen() != null ? ind.getFirstSeen().toString() : null);
        map.put("lastSeen", ind.getLastSeen() != null ? ind.getLastSeen().toString() : null);
        return map;
    }

    /**
     * Convert an indicator to a STIX 2.1 pattern string.
     */
    private String toStixPattern(ThreatIndicator ind) {
        return switch (ind.getType()) {
            case IP -> "[ipv4-addr:value = '" + ind.getValue() + "']";
            case DOMAIN -> "[domain-name:value = '" + ind.getValue() + "']";
            case URL -> "[url:value = '" + ind.getValue() + "']";
            case HASH_MD5 -> "[file:hashes.MD5 = '" + ind.getValue() + "']";
            case HASH_SHA1 -> "[file:hashes.'SHA-1' = '" + ind.getValue() + "']";
            case HASH_SHA256 -> "[file:hashes.'SHA-256' = '" + ind.getValue() + "']";
            case EMAIL -> "[email-addr:value = '" + ind.getValue() + "']";
            case CIDR -> "[ipv4-addr:value = '" + ind.getValue() + "']";
            case USER_AGENT -> "[network-traffic:extensions.'http-request-ext'.request_header.'User-Agent' = '"
                    + ind.getValue() + "']";
            default -> "[artifact:payload_bin = '" + ind.getValue() + "']";
        };
    }

    /**
     * Resolve a tactic ID to a STIX-compatible phase name.
     */
    private String resolveFirstTacticName(String tacticId) {
        MitreAttackMapper.Tactic tactic = MitreAttackMapper.Tactic.fromId(tacticId);
        if (tactic == null) return "unknown";
        return tactic.getName().toLowerCase().replace(" ", "-");
    }

    /**
     * Escape special characters for JSON string values.
     */
    private String escapeJson(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    // ── Inner Classes ──────────────────────────────────────────────────

    /**
     * Result of a bulk ingestion operation.
     */
    @Data
    @Builder
    public static class IngestResult {
        private int newIndicators;
        private int updatedIndicators;
        private int duplicatesSkipped;
        private int errors;
        private Duration processingTime;
    }

    /**
     * Criteria for searching the threat intelligence store.
     */
    @Data
    @Builder
    public static class IndicatorSearchCriteria {
        private IndicatorType type;
        private ThreatLevel minThreatLevel;
        private String source;
        private String tag;
        private Instant since;
        @Builder.Default
        private int limit = 100;
    }
}
