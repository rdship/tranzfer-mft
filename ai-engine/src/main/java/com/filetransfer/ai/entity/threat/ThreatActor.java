package com.filetransfer.ai.entity.threat;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Threat actor profile for the SecurityAI attribution and campaign-tracking subsystem.
 *
 * <p>Represents a known adversary group, nation-state actor, or criminal organisation.
 * Profiles are populated from external threat-intelligence feeds (STIX/TAXII, MISP)
 * and enriched by internal correlation when attack patterns match known TTPs.</p>
 *
 * <h3>Attribution confidence</h3>
 * <p>The {@code confidence} field (0.0–1.0) reflects how strongly available evidence
 * ties observed activity to this actor. Low-confidence attributions should be treated
 * as hypotheses, not conclusions.</p>
 *
 * @see AttackCampaign
 * @see ThreatIndicator
 */
@Entity
@Table(name = "threat_actors", indexes = {
    @Index(name = "idx_threat_actor_name", columnList = "name"),
    @Index(name = "idx_threat_actor_country", columnList = "country"),
    @Index(name = "idx_threat_actor_motivation", columnList = "motivation"),
    @Index(name = "idx_threat_actor_last_activity", columnList = "lastActivity")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ThreatActor {

    @Id
    @Column(updatable = false, nullable = false)
    private UUID actorId;

    /** Primary name (e.g. "APT29", "FIN7", "Lazarus Group"). */
    @Column(length = 255, nullable = false)
    private String name;

    /** Comma-separated alternative names and tracking designations. */
    @Column(columnDefinition = "TEXT")
    private String aliases;

    /** Free-text description of the actor's history, capabilities, and operations. */
    @Column(columnDefinition = "TEXT")
    private String description;

    /** Suspected country of origin (ISO 3166-1 alpha-2). */
    @Column(length = 2)
    private String country;

    /**
     * Primary motivation: {@code financial}, {@code espionage},
     * {@code hacktivism}, {@code destruction}.
     */
    @Column(length = 30)
    private String motivation;

    /**
     * Sophistication level: {@code low}, {@code medium}, {@code high}, {@code apt}.
     */
    @Column(length = 10)
    private String sophistication;

    /** Earliest known activity attributed to this actor. */
    private Instant activeSince;

    /** Most recent observed activity. */
    private Instant lastActivity;

    /** Comma-separated MITRE ATT&CK technique IDs used by this actor. */
    @Column(columnDefinition = "TEXT")
    private String ttps;

    /** Comma-separated names of tools, malware families, and exploit frameworks. */
    @Column(columnDefinition = "TEXT")
    private String knownTools;

    /** Comma-separated industry sectors targeted (e.g. "finance,energy,government"). */
    @Column(columnDefinition = "TEXT")
    private String targetSectors;

    /** Comma-separated ISO country codes of targeted nations. */
    @Column(columnDefinition = "TEXT")
    private String targetCountries;

    /** Attribution confidence, 0.0–1.0. */
    private double confidence;

    /** Comma-separated intelligence source names. */
    @Column(columnDefinition = "TEXT")
    private String sources;

    // ── Lifecycle Callbacks ───────────────────────────────────────────

    @PrePersist
    void prePersist() {
        if (actorId == null) {
            actorId = UUID.randomUUID();
        }
    }

    // ── Convenience Methods ───────────────────────────────────────────

    /**
     * Returns the aliases as an immutable list.
     */
    public List<String> getAliasesList() {
        return splitCsv(aliases);
    }

    /**
     * Returns the TTP (MITRE technique ID) list.
     */
    public List<String> getTtpsList() {
        return splitCsv(ttps);
    }

    /**
     * Returns the known tools as an immutable list.
     */
    public List<String> getKnownToolsList() {
        return splitCsv(knownTools);
    }

    /**
     * Returns the target sectors as an immutable list.
     */
    public List<String> getTargetSectorsList() {
        return splitCsv(targetSectors);
    }

    /**
     * Returns the target countries as an immutable list.
     */
    public List<String> getTargetCountriesList() {
        return splitCsv(targetCountries);
    }

    /**
     * Returns the intelligence sources as an immutable list.
     */
    public List<String> getSourcesList() {
        return splitCsv(sources);
    }

    /**
     * Adds an alias if not already present.
     */
    public void addAlias(String alias) {
        if (alias == null || alias.isBlank()) return;
        aliases = appendCsvUnique(aliases, alias.strip());
    }

    /**
     * Adds a MITRE technique ID if not already present.
     */
    public void addTtp(String technique) {
        if (technique == null || technique.isBlank()) return;
        ttps = appendCsvUnique(ttps, technique.strip());
    }

    /**
     * Refreshes {@code lastActivity} to the current instant.
     */
    public void recordActivity() {
        this.lastActivity = Instant.now();
    }

    // ── Private Utilities ─────────────────────────────────────────────

    private static List<String> splitCsv(String csv) {
        if (csv == null || csv.isBlank()) {
            return Collections.emptyList();
        }
        return Arrays.stream(csv.split(","))
            .map(String::strip)
            .filter(s -> !s.isEmpty())
            .collect(Collectors.toUnmodifiableList());
    }

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
}
