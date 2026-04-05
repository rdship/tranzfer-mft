package com.filetransfer.ai.service.intelligence;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration for external threat intelligence feeds.
 *
 * <p>Each feed can be independently enabled/disabled and configured with
 * API keys, confidence thresholds, and ingestion parameters.  Properties
 * are bound from the {@code ai.intelligence.feeds} namespace in
 * {@code application.yml}.</p>
 *
 * <h3>Supported Feeds</h3>
 * <ul>
 *   <li><b>AbuseIPDB</b> — crowd-sourced IP reputation database</li>
 *   <li><b>URLhaus</b> — malware URL repository by abuse.ch</li>
 *   <li><b>ThreatFox</b> — IOC sharing platform by abuse.ch</li>
 *   <li><b>Feodo Tracker</b> — botnet C2 tracker by abuse.ch</li>
 *   <li><b>AlienVault OTX</b> — open threat exchange pulse feed</li>
 * </ul>
 *
 * <h3>Example Configuration</h3>
 * <pre>{@code
 * ai:
 *   intelligence:
 *     feeds:
 *       enabled: true
 *       abuseipdb:
 *         enabled: true
 *         api-key: ${ABUSEIPDB_API_KEY:}
 *         confidence-minimum: 80
 *         max-age: 30
 *       urlhaus:
 *         enabled: true
 *         max-results: 1000
 *       threatfox:
 *         enabled: true
 *         lookback-days: 7
 *       feodo:
 *         enabled: true
 *       otx:
 *         enabled: false
 *         api-key: ${OTX_API_KEY:}
 *         pulse-limit: 50
 * }</pre>
 */
@Configuration
@ConfigurationProperties(prefix = "ai.intelligence.feeds")
@Data
public class ThreatFeedConfig {

    /** Master switch to enable/disable all threat feed ingestion. */
    private boolean enabled = true;

    /** AbuseIPDB feed configuration. */
    private AbuseIpDb abuseipdb = new AbuseIpDb();

    /** URLhaus feed configuration. */
    private UrlHaus urlhaus = new UrlHaus();

    /** ThreatFox feed configuration. */
    private ThreatFox threatfox = new ThreatFox();

    /** Feodo Tracker feed configuration. */
    private Feodo feodo = new Feodo();

    /** AlienVault OTX feed configuration. */
    private Otx otx = new Otx();

    /**
     * AbuseIPDB — crowd-sourced IP abuse reporting and reputation database.
     * <p>Only IPs with confidence above {@code confidenceMinimum} are ingested
     * to reduce false-positive noise.</p>
     */
    @Data
    public static class AbuseIpDb {
        /** Whether to enable AbuseIPDB feed ingestion. */
        private boolean enabled = true;

        /** AbuseIPDB API key (required for this feed). */
        private String apiKey = "";

        /** Minimum abuse confidence score (0-100) to accept an IP indicator. */
        private int confidenceMinimum = 80;

        /** Maximum indicator age in days; older reports are skipped during ingestion. */
        private int maxAge = 30;

        /** Base URL for AbuseIPDB API v2. */
        private String baseUrl = "https://api.abuseipdb.com/api/v2";
    }

    /**
     * URLhaus — malware URL repository maintained by abuse.ch.
     * <p>Free feed, no API key required. Provides recently discovered
     * malware distribution URLs.</p>
     */
    @Data
    public static class UrlHaus {
        /** Whether to enable URLhaus feed ingestion. */
        private boolean enabled = true;

        /** Maximum number of URL indicators to ingest per sync. */
        private int maxResults = 1000;

        /** Base URL for URLhaus API. */
        private String baseUrl = "https://urlhaus-api.abuse.ch/v1";
    }

    /**
     * ThreatFox — IOC sharing platform by abuse.ch.
     * <p>Free feed providing indicators (hashes, domains, IPs) associated
     * with known malware families and campaigns.</p>
     */
    @Data
    public static class ThreatFox {
        /** Whether to enable ThreatFox feed ingestion. */
        private boolean enabled = true;

        /** Number of days to look back when syncing IOCs. */
        private int lookbackDays = 7;

        /** Base URL for ThreatFox API. */
        private String baseUrl = "https://threatfox-api.abuse.ch/api/v1";
    }

    /**
     * Feodo Tracker — botnet command-and-control infrastructure tracker by abuse.ch.
     * <p>Tracks botnets such as Dridex, Emotet, TrickBot, and QakBot C2 servers.</p>
     */
    @Data
    public static class Feodo {
        /** Whether to enable Feodo Tracker feed ingestion. */
        private boolean enabled = true;

        /** Base URL for Feodo Tracker data export. */
        private String baseUrl = "https://feodotracker.abuse.ch/downloads";
    }

    /**
     * AlienVault OTX (Open Threat Exchange) — community-driven threat intelligence.
     * <p>Requires a free API key. Provides structured threat intelligence "pulses"
     * containing indicators, MITRE mappings, and context.</p>
     */
    @Data
    public static class Otx {
        /** Whether to enable OTX feed ingestion (disabled by default; requires API key). */
        private boolean enabled = false;

        /** OTX API key. */
        private String apiKey = "";

        /** Maximum number of pulses to retrieve per sync. */
        private int pulseLimit = 50;

        /** Base URL for AlienVault OTX API. */
        private String baseUrl = "https://otx.alienvault.com/api/v1";
    }
}
