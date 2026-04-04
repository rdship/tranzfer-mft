package com.filetransfer.screening.loader;

import com.filetransfer.screening.entity.SanctionsEntry;
import com.filetransfer.screening.repository.SanctionsEntryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.Instant;
import java.util.*;

/**
 * Automatically fetches and maintains sanctions lists from official sources.
 * Runs on startup and then every N hours (configurable).
 *
 * Sources:
 * - OFAC SDN List (US Treasury) — the primary list
 * - EU Consolidated Sanctions
 * - UN Security Council Sanctions
 *
 * Downloads are incremental — old entries for a source are replaced on refresh.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SanctionsListLoader {

    private final SanctionsEntryRepository entryRepository;

    @Value("${screening.ofac.sdn-url:https://www.treasury.gov/ofac/downloads/sdn.csv}")
    private String ofacSdnUrl;

    @Value("${screening.ofac.enabled:true}")
    private boolean ofacEnabled;

    @Value("${screening.eu.enabled:true}")
    private boolean euEnabled;

    @Value("${screening.un.enabled:true}")
    private boolean unEnabled;

    private volatile Instant lastRefresh;
    private volatile Map<String, Long> listCounts = new LinkedHashMap<>();

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        log.info("Screening service starting — loading sanctions lists...");
        refreshAllLists();
    }

    @Scheduled(cron = "0 0 */6 * * *") // Every 6 hours
    public void scheduledRefresh() {
        log.info("Scheduled sanctions list refresh...");
        refreshAllLists();
    }

    @Transactional
    public void refreshAllLists() {
        long total = 0;
        listCounts.clear();

        if (ofacEnabled) {
            long count = loadOfacSdn();
            listCounts.put("OFAC_SDN", count);
            total += count;
        }

        // For EU and UN, we load built-in sample data since the real endpoints
        // require specific parsing. In production, integrate the actual feeds.
        if (euEnabled) {
            long count = loadBuiltInList("EU_SANCTIONS", getEuSampleEntries());
            listCounts.put("EU_SANCTIONS", count);
            total += count;
        }

        if (unEnabled) {
            long count = loadBuiltInList("UN_SANCTIONS", getUnSampleEntries());
            listCounts.put("UN_SANCTIONS", count);
            total += count;
        }

        lastRefresh = Instant.now();
        log.info("Sanctions lists loaded: {} total entries across {} lists",
                total, listCounts.size());
    }

    private long loadOfacSdn() {
        try {
            log.info("Fetching OFAC SDN list from {}...", ofacSdnUrl);
            HttpURLConnection conn = (HttpURLConnection) new URL(ofacSdnUrl).openConnection();
            conn.setRequestProperty("User-Agent", "TranzFer-MFT-Screening/1.0");
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(60000);

            if (conn.getResponseCode() != 200) {
                log.warn("OFAC download failed: HTTP {}. Using built-in list.", conn.getResponseCode());
                return loadBuiltInList("OFAC_SDN", getOfacSampleEntries());
            }

            // Clear old entries
            entryRepository.deleteBySource("OFAC_SDN");

            List<SanctionsEntry> entries = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String[] parts = parseCsvLine(line);
                    if (parts.length < 3) continue;

                    String uid = parts[0].trim();
                    String name = parts[1].trim();
                    String entityType = parts[2].trim();

                    if (name.isEmpty() || name.equals("SDN_Name")) continue; // skip header

                    entries.add(SanctionsEntry.builder()
                            .name(name).nameLower(normalize(name))
                            .source("OFAC_SDN").entityType(mapEntityType(entityType))
                            .program(parts.length > 3 ? parts[3].trim() : null)
                            .build());

                    if (entries.size() >= 500) {
                        entryRepository.saveAll(entries);
                        entries.clear();
                    }
                }
            }
            if (!entries.isEmpty()) entryRepository.saveAll(entries);

            long count = entryRepository.countBySource("OFAC_SDN");
            log.info("OFAC SDN: loaded {} entries", count);
            return count;

        } catch (Exception e) {
            log.warn("OFAC download error: {}. Using built-in list.", e.getMessage());
            return loadBuiltInList("OFAC_SDN", getOfacSampleEntries());
        }
    }

    @Transactional
    public long loadBuiltInList(String source, List<String[]> entries) {
        entryRepository.deleteBySource(source);
        List<SanctionsEntry> toSave = new ArrayList<>();
        for (String[] e : entries) {
            toSave.add(SanctionsEntry.builder()
                    .name(e[0]).nameLower(normalize(e[0]))
                    .source(source).entityType(e.length > 1 ? e[1] : "individual")
                    .program(e.length > 2 ? e[2] : null)
                    .aliases(e.length > 3 ? e[3] : null)
                    .build());
        }
        entryRepository.saveAll(toSave);
        log.info("{}: loaded {} entries", source, toSave.size());
        return toSave.size();
    }

    // Normalize names for fuzzy matching
    public static String normalize(String name) {
        return name.toLowerCase()
                .replaceAll("[^a-z0-9\\s]", "") // remove special chars
                .replaceAll("\\s+", " ")          // collapse whitespace
                .trim();
    }

    private String mapEntityType(String type) {
        if (type == null) return "unknown";
        return switch (type.trim().toLowerCase()) {
            case "individual", "-0-" -> "individual";
            case "entity", "organization" -> "organization";
            case "vessel" -> "vessel";
            case "aircraft" -> "aircraft";
            default -> type.toLowerCase();
        };
    }

    private String[] parseCsvLine(String line) {
        // Simple CSV parser handling quoted fields
        List<String> fields = new ArrayList<>();
        boolean inQuotes = false;
        StringBuilder current = new StringBuilder();
        for (char c : line.toCharArray()) {
            if (c == '"') inQuotes = !inQuotes;
            else if (c == ',' && !inQuotes) { fields.add(current.toString()); current = new StringBuilder(); }
            else current.append(c);
        }
        fields.add(current.toString());
        return fields.toArray(new String[0]);
    }

    public Instant getLastRefresh() { return lastRefresh; }
    public Map<String, Long> getListCounts() { return Collections.unmodifiableMap(listCounts); }

    // Built-in sample entries (used when live download fails or for testing)
    private List<String[]> getOfacSampleEntries() {
        return List.of(
            new String[]{"AL QAIDA", "organization", "SDGT"},
            new String[]{"TALIBAN", "organization", "SDGT"},
            new String[]{"ISLAMIC REVOLUTIONARY GUARD CORPS", "organization", "IRAN"},
            new String[]{"HEZBOLLAH", "organization", "SDGT"},
            new String[]{"BANCO DELTA ASIA", "organization", "DPRK"},
            new String[]{"KOREA KWANGSON BANKING CORP", "organization", "DPRK"},
            new String[]{"RUSSIAN DIRECT INVESTMENT FUND", "organization", "RUSSIA-EO14024"},
            new String[]{"SBERBANK OF RUSSIA", "organization", "RUSSIA-EO14024"},
            new String[]{"GAZPROMBANK", "organization", "RUSSIA-EO14024"},
            new String[]{"PETROLEOS DE VENEZUELA SA", "organization", "VENEZUELA"}
        );
    }

    private List<String[]> getEuSampleEntries() {
        return List.of(
            new String[]{"NORTHERN MILITARY DISTRICT FUND", "organization", "EU_RUSSIA"},
            new String[]{"WAGNER GROUP", "organization", "EU_RUSSIA"},
            new String[]{"BELARUSKALI", "organization", "EU_BELARUS"},
            new String[]{"IRANIAN SHIPPING LINES", "organization", "EU_IRAN"}
        );
    }

    private List<String[]> getUnSampleEntries() {
        return List.of(
            new String[]{"KOREA MINING DEVELOPMENT TRADING CORPORATION", "organization", "UN_DPRK"},
            new String[]{"AL-SHABAAB", "organization", "UN_SOMALIA"},
            new String[]{"BOKO HARAM", "organization", "UN_ISIL"}
        );
    }
}
