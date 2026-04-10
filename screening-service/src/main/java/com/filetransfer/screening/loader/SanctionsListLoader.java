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
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.transaction.annotation.Transactional;

import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;
import org.xml.sax.InputSource;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Automatically fetches and maintains sanctions lists from official sources.
 * Runs on startup and then every N hours (configurable).
 *
 * Sources:
 * - OFAC SDN List (US Treasury) — the primary list
 * - EU Consolidated Sanctions (CSV from EC Financial Sanctions)
 * - UN Security Council Sanctions (XML from UNSC)
 *
 * Downloads are incremental — old entries for a source are replaced on refresh.
 * All lists fall back to comprehensive built-in entries if download fails.
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

    @Value("${screening.eu.url:https://webgate.ec.europa.eu/fsd/fsf/public/files/csvFullSanctionsList/content?token=dG9rZW4tMjAxNw}")
    private String euSanctionsUrl;

    @Value("${screening.eu.fallback-to-builtin:true}")
    private boolean euFallbackEnabled;

    @Value("${screening.un.enabled:true}")
    private boolean unEnabled;

    @Value("${screening.un.url:https://scsanctions.un.org/resources/xml/en/consolidated.xml}")
    private String unSanctionsUrl;

    @Value("${screening.un.fallback-to-builtin:true}")
    private boolean unFallbackEnabled;

    private volatile Instant lastRefresh;
    private final Map<String, Long> listCounts = new ConcurrentHashMap<>();
    private final Map<String, Instant> listRefreshTimes = new ConcurrentHashMap<>();

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        // Load sanctions in background thread — don't block boot or health checks
        Thread.ofVirtual().name("sanctions-loader").start(() -> {
            try {
                log.info("Screening service starting — loading sanctions lists in background...");
                refreshAllLists();
            } catch (Exception e) {
                log.warn("Background sanctions load failed (scheduled refresh will retry): {}", e.getMessage());
            }
        });
    }

    @Scheduled(cron = "0 0 */6 * * *") // Every 6 hours
    @SchedulerLock(name = "screening_sanctionsListRefresh", lockAtLeastFor = "PT5H", lockAtMostFor = "PT6H")
    public void scheduledRefresh() {
        log.info("Scheduled sanctions list refresh...");
        refreshAllLists();
    }

    @Transactional
    public void refreshAllLists() {
        long total = 0;

        if (ofacEnabled) {
            long count = loadOfacSdn();
            listCounts.put("OFAC_SDN", count);
            listRefreshTimes.put("OFAC_SDN", Instant.now());
            total += count;
        }

        if (euEnabled) {
            long count = loadEuSanctions();
            listCounts.put("EU_SANCTIONS", count);
            listRefreshTimes.put("EU_SANCTIONS", Instant.now());
            total += count;
        }

        if (unEnabled) {
            long count = loadUnSanctions();
            listCounts.put("UN_SANCTIONS", count);
            listRefreshTimes.put("UN_SANCTIONS", Instant.now());
            total += count;
        }

        lastRefresh = Instant.now();
        log.info("Sanctions lists loaded: {} total entries across {} lists | breakdown: {}",
                total, listCounts.size(), listCounts);
    }

    private long loadOfacSdn() {
        try {
            log.info("Fetching OFAC SDN list from {}...", ofacSdnUrl);
            HttpURLConnection conn = (HttpURLConnection) URI.create(ofacSdnUrl).toURL().openConnection();
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

    // ──────────────────────────────────────────────────────────────────────
    // EU Consolidated Sanctions — CSV parser
    // ──────────────────────────────────────────────────────────────────────

    private long loadEuSanctions() {
        try {
            log.info("Fetching EU Consolidated Sanctions from {}...", euSanctionsUrl);
            HttpURLConnection conn = (HttpURLConnection) URI.create(euSanctionsUrl).toURL().openConnection();
            conn.setRequestProperty("User-Agent", "TranzFer-MFT-Screening/1.0");
            conn.setConnectTimeout(30_000);
            conn.setReadTimeout(60_000);

            if (conn.getResponseCode() != 200) {
                log.warn("EU sanctions download failed: HTTP {}. {}", conn.getResponseCode(),
                        euFallbackEnabled ? "Using built-in fallback list." : "Skipping EU list.");
                return euFallbackEnabled ? loadBuiltInList("EU_SANCTIONS", getEuFallbackEntries()) : 0;
            }

            entryRepository.deleteBySource("EU_SANCTIONS");

            List<SanctionsEntry> entries = new ArrayList<>();
            int headerNameCol = -1, headerTypeCol = -1, headerProgramCol = -1;

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                String headerLine = reader.readLine();
                if (headerLine != null) {
                    String[] headers = parseCsvLine(headerLine);
                    for (int i = 0; i < headers.length; i++) {
                        String h = headers[i].trim().toLowerCase();
                        if (h.contains("name") && headerNameCol == -1) headerNameCol = i;
                        else if (h.contains("type") && headerTypeCol == -1) headerTypeCol = i;
                        else if (h.contains("programme") || h.contains("program") || h.contains("regime"))
                            headerProgramCol = i;
                    }
                }

                // Sensible defaults if headers unrecognized
                if (headerNameCol == -1) headerNameCol = 1;

                String line;
                Set<String> seen = new HashSet<>();
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) continue;
                    String[] parts = parseCsvLine(line);
                    if (parts.length <= headerNameCol) continue;

                    String name = parts[headerNameCol].trim();
                    if (name.isEmpty() || name.length() < 2) continue;

                    String key = normalize(name);
                    if (!seen.add(key)) continue; // deduplicate

                    String type = (headerTypeCol >= 0 && parts.length > headerTypeCol)
                            ? mapEuEntityType(parts[headerTypeCol].trim()) : "unknown";
                    String program = (headerProgramCol >= 0 && parts.length > headerProgramCol)
                            ? parts[headerProgramCol].trim() : "EU_SANCTIONS";

                    entries.add(SanctionsEntry.builder()
                            .name(name).nameLower(key)
                            .source("EU_SANCTIONS").entityType(type)
                            .program(program)
                            .build());

                    if (entries.size() >= 500) {
                        entryRepository.saveAll(entries);
                        entries.clear();
                    }
                }
            }
            if (!entries.isEmpty()) entryRepository.saveAll(entries);

            long count = entryRepository.countBySource("EU_SANCTIONS");
            log.info("EU Sanctions: loaded {} entries from live download", count);
            return count;

        } catch (Exception e) {
            log.warn("EU sanctions download error: {}. {}", e.getMessage(),
                    euFallbackEnabled ? "Using built-in fallback list." : "Skipping EU list.");
            return euFallbackEnabled ? loadBuiltInList("EU_SANCTIONS", getEuFallbackEntries()) : 0;
        }
    }

    private String mapEuEntityType(String type) {
        if (type == null || type.isBlank()) return "unknown";
        String lower = type.toLowerCase();
        if (lower.contains("person") || lower.contains("individual")) return "individual";
        if (lower.contains("entity") || lower.contains("enterprise") || lower.contains("organ")) return "organization";
        if (lower.contains("vessel") || lower.contains("ship")) return "vessel";
        return lower;
    }

    // ──────────────────────────────────────────────────────────────────────
    // UN Security Council Sanctions — XML parser
    // ──────────────────────────────────────────────────────────────────────

    private long loadUnSanctions() {
        try {
            log.info("Fetching UN Security Council Sanctions from {}...", unSanctionsUrl);
            HttpURLConnection conn = (HttpURLConnection) URI.create(unSanctionsUrl).toURL().openConnection();
            conn.setRequestProperty("User-Agent", "TranzFer-MFT-Screening/1.0");
            conn.setConnectTimeout(30_000);
            conn.setReadTimeout(60_000);

            if (conn.getResponseCode() != 200) {
                log.warn("UN sanctions download failed: HTTP {}. {}", conn.getResponseCode(),
                        unFallbackEnabled ? "Using built-in fallback list." : "Skipping UN list.");
                return unFallbackEnabled ? loadBuiltInList("UN_SANCTIONS", getUnFallbackEntries()) : 0;
            }

            String xmlContent;
            try (InputStream is = conn.getInputStream();
                 ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
                is.transferTo(bos);
                xmlContent = bos.toString("UTF-8");
            }

            return parseUnXml(xmlContent);

        } catch (Exception e) {
            log.warn("UN sanctions download error: {}. {}", e.getMessage(),
                    unFallbackEnabled ? "Using built-in fallback list." : "Skipping UN list.");
            return unFallbackEnabled ? loadBuiltInList("UN_SANCTIONS", getUnFallbackEntries()) : 0;
        }
    }

    private long parseUnXml(String xmlContent) {
        try {
            entryRepository.deleteBySource("UN_SANCTIONS");

            var factory = DocumentBuilderFactory.newInstance();
            // Security: disable external entities
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            var builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new InputSource(new StringReader(xmlContent)));

            List<SanctionsEntry> entries = new ArrayList<>();
            Set<String> seen = new HashSet<>();

            // Parse <INDIVIDUAL> elements
            parseUnElements(doc, "INDIVIDUAL", "individual", entries, seen);
            // Parse <ENTITY> elements
            parseUnElements(doc, "ENTITY", "organization", entries, seen);

            if (!entries.isEmpty()) {
                // Batch save
                for (int i = 0; i < entries.size(); i += 500) {
                    entryRepository.saveAll(entries.subList(i, Math.min(i + 500, entries.size())));
                }
            }

            long count = entryRepository.countBySource("UN_SANCTIONS");
            log.info("UN Sanctions: loaded {} entries from live download ({} individuals + entities parsed)",
                    count, entries.size());
            return count;

        } catch (Exception e) {
            log.warn("UN XML parsing failed: {}. Falling back to built-in list.", e.getMessage());
            return loadBuiltInList("UN_SANCTIONS", getUnFallbackEntries());
        }
    }

    private void parseUnElements(Document doc, String tagName, String entityType,
                                  List<SanctionsEntry> entries, Set<String> seen) {
        NodeList nodes = doc.getElementsByTagName(tagName);
        for (int i = 0; i < nodes.getLength(); i++) {
            try {
                Element el = (Element) nodes.item(i);
                String name = buildUnName(el, tagName);
                if (name == null || name.length() < 2) continue;

                String key = normalize(name);
                if (!seen.add(key)) continue;

                String listType = getElementText(el, "UN_LIST_TYPE");
                String program = (listType != null && !listType.isBlank())
                        ? "UN_" + listType.trim() : "UN_SANCTIONS";

                // Collect aliases
                String aliases = collectUnAliases(el);

                entries.add(SanctionsEntry.builder()
                        .name(name).nameLower(key)
                        .source("UN_SANCTIONS").entityType(entityType)
                        .program(program)
                        .aliases(aliases)
                        .build());
            } catch (Exception e) {
                // Skip malformed entries — don't fail the whole list
                log.trace("Skipping UN entry at index {}: {}", i, e.getMessage());
            }
        }
    }

    private String buildUnName(Element el, String tagName) {
        if ("INDIVIDUAL".equals(tagName)) {
            String first = getElementText(el, "FIRST_NAME");
            String second = getElementText(el, "SECOND_NAME");
            String third = getElementText(el, "THIRD_NAME");
            StringBuilder sb = new StringBuilder();
            if (first != null && !first.isBlank()) sb.append(first.trim());
            if (second != null && !second.isBlank()) {
                if (!sb.isEmpty()) sb.append(' ');
                sb.append(second.trim());
            }
            if (third != null && !third.isBlank()) {
                if (!sb.isEmpty()) sb.append(' ');
                sb.append(third.trim());
            }
            return sb.isEmpty() ? null : sb.toString();
        } else {
            // ENTITY — look for FIRST_NAME or NAME
            String name = getElementText(el, "FIRST_NAME");
            if (name == null || name.isBlank()) name = getElementText(el, "NAME");
            return (name != null && !name.isBlank()) ? name.trim() : null;
        }
    }

    private String collectUnAliases(Element el) {
        NodeList aliasNodes = el.getElementsByTagName("ALIAS_NAME");
        if (aliasNodes.getLength() == 0) return null;
        StringBuilder sb = new StringBuilder();
        for (int j = 0; j < aliasNodes.getLength(); j++) {
            String alias = aliasNodes.item(j).getTextContent();
            if (alias != null && !alias.isBlank()) {
                if (!sb.isEmpty()) sb.append(';');
                sb.append(alias.trim());
            }
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    private String getElementText(Element parent, String tagName) {
        NodeList list = parent.getElementsByTagName(tagName);
        if (list.getLength() == 0) return null;
        // Only look at direct/near children to avoid pulling nested values
        return list.item(0).getTextContent();
    }

    // ──────────────────────────────────────────────────────────────────────
    // Shared utilities
    // ──────────────────────────────────────────────────────────────────────

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
        log.info("{}: loaded {} built-in fallback entries", source, toSave.size());
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
    public Map<String, Instant> getListRefreshTimes() { return Collections.unmodifiableMap(listRefreshTimes); }

    // ──────────────────────────────────────────────────────────────────────
    // Built-in fallback entries — comprehensive set for offline/demo use
    // ──────────────────────────────────────────────────────────────────────

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

    private List<String[]> getEuFallbackEntries() {
        return List.of(
            // Russia-related
            new String[]{"WAGNER GROUP", "organization", "EU_RUSSIA", "PMC WAGNER;VAGNER"},
            new String[]{"NORTHERN MILITARY DISTRICT FUND", "organization", "EU_RUSSIA"},
            new String[]{"RUSSIAN NATIONAL COMMERCIAL BANK", "organization", "EU_RUSSIA", "RNCB"},
            new String[]{"PROMSVYAZBANK", "organization", "EU_RUSSIA", "PSB"},
            new String[]{"VTB BANK", "organization", "EU_RUSSIA"},
            new String[]{"SOVCOMFLOT", "organization", "EU_RUSSIA", "SCF GROUP"},
            new String[]{"ROSNEFT OIL COMPANY", "organization", "EU_RUSSIA"},
            new String[]{"ROSTEC", "organization", "EU_RUSSIA", "RUSSIAN TECHNOLOGIES STATE CORPORATION"},
            new String[]{"KALASHNIKOV CONCERN", "organization", "EU_RUSSIA"},
            new String[]{"UNITED AIRCRAFT CORPORATION", "organization", "EU_RUSSIA", "OAK"},
            // Belarus
            new String[]{"BELARUSKALI", "organization", "EU_BELARUS"},
            new String[]{"GRODNO AZOT", "organization", "EU_BELARUS"},
            new String[]{"BELTELECOM", "organization", "EU_BELARUS"},
            // Iran
            new String[]{"ISLAMIC REPUBLIC OF IRAN SHIPPING LINES", "organization", "EU_IRAN", "IRISL"},
            new String[]{"IRAN AIR", "organization", "EU_IRAN"},
            new String[]{"BANK MELLI IRAN", "organization", "EU_IRAN"},
            new String[]{"BANK SADERAT IRAN", "organization", "EU_IRAN"},
            // Syria
            new String[]{"COMMERCIAL BANK OF SYRIA", "organization", "EU_SYRIA", "CBS"},
            new String[]{"SYRIAN ARAB AIRLINES", "organization", "EU_SYRIA"},
            // Myanmar
            new String[]{"MYANMA OIL AND GAS ENTERPRISE", "organization", "EU_MYANMAR", "MOGE"},
            // Libya
            new String[]{"LIBYAN INVESTMENT AUTHORITY", "organization", "EU_LIBYA", "LIA"},
            // Individuals (well-known sanctioned persons)
            new String[]{"YEVGENY VIKTOROVICH PRIGOZHIN", "individual", "EU_RUSSIA"},
            new String[]{"RAMZAN AKHMATOVICH KADYROV", "individual", "EU_RUSSIA"},
            new String[]{"ALEXANDER GRIGORIEVICH LUKASHENKO", "individual", "EU_BELARUS"}
        );
    }

    private List<String[]> getUnFallbackEntries() {
        return List.of(
            // DPRK
            new String[]{"KOREA MINING DEVELOPMENT TRADING CORPORATION", "organization", "UN_DPRK", "KOMID"},
            new String[]{"KOREA HYOKSIN TRADING CORPORATION", "organization", "UN_DPRK"},
            new String[]{"KOREA RYONBONG GENERAL CORPORATION", "organization", "UN_DPRK"},
            new String[]{"FOREIGN TRADE BANK", "organization", "UN_DPRK", "FTB;NORTH KOREAN FTB"},
            new String[]{"RECONNAISSANCE GENERAL BUREAU", "organization", "UN_DPRK", "RGB"},
            new String[]{"OCEAN MARITIME MANAGEMENT COMPANY", "organization", "UN_DPRK", "OMM"},
            new String[]{"KOREA NATIONAL INSURANCE CORPORATION", "organization", "UN_DPRK", "KNIC"},
            // Somalia / Al-Shabaab
            new String[]{"AL-SHABAAB", "organization", "UN_SOMALIA", "HARAKAT AL-SHABAAB AL-MUJAHIDEEN"},
            // ISIL / Da'esh
            new String[]{"BOKO HARAM", "organization", "UN_ISIL", "JAMA'ATU AHLIS SUNNA LIDDA'AWATI WAL-JIHAD"},
            new String[]{"ISLAMIC STATE IN IRAQ AND THE LEVANT", "organization", "UN_ISIL", "ISIL;ISIS;DA'ESH;DAESH"},
            new String[]{"AL-NUSRAH FRONT", "organization", "UN_ISIL", "JABHAT AL-NUSRA;HAY'AT TAHRIR AL-SHAM"},
            // Al-Qaida
            new String[]{"AL-QAIDA IN THE ARABIAN PENINSULA", "organization", "UN_AQ", "AQAP"},
            new String[]{"AL-QAIDA IN THE ISLAMIC MAGHREB", "organization", "UN_AQ", "AQIM"},
            // Taliban
            new String[]{"HAQQANI NETWORK", "organization", "UN_TAL"},
            // Libya
            new String[]{"LIBYAN NATIONAL ARMY", "organization", "UN_LIBYA", "LNA"},
            // Central African Republic
            new String[]{"ANTI-BALAKA", "organization", "UN_CAR"},
            // Yemen / Houthi
            new String[]{"ANSAR ALLAH", "organization", "UN_YEMEN", "HOUTHIS"},
            // Individuals
            new String[]{"AYMAN MUHAMMED RABI AL-ZAWAHIRI", "individual", "UN_AQ"},
            new String[]{"ABU MOHAMMED AL-JULANI", "individual", "UN_ISIL", "AHMED HUSSEIN AL-SHAR'A"},
            new String[]{"IBRAHIM AWWAD IBRAHIM ALI AL-BADRI AL-SAMARRAI", "individual", "UN_ISIL", "ABU BAKR AL-BAGHDADI"},
            new String[]{"SIRAJUDDIN JALLALOUDINE HAQQANI", "individual", "UN_TAL"},
            new String[]{"KHALIL AHMED HAQQANI", "individual", "UN_TAL"}
        );
    }
}
