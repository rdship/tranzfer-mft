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
 * Attack campaign entity linking related {@link ThreatIndicator}s, {@link SecurityAlert}s,
 * and {@link SecurityEvent}s into a cohesive adversary operation.
 *
 * <p>A campaign represents a coordinated set of malicious activities — often attributed
 * to a specific {@link ThreatActor} — that shares common TTPs, infrastructure, or
 * targeting. Campaigns may span days to years and are tracked from first sighting
 * through conclusion.</p>
 *
 * <h3>Status lifecycle</h3>
 * <ul>
 *   <li>{@code active} — ongoing activity observed.</li>
 *   <li>{@code dormant} — no recent activity but infrastructure may still be live.</li>
 *   <li>{@code concluded} — campaign infrastructure dismantled or activity ceased.</li>
 * </ul>
 *
 * @see ThreatActor
 * @see ThreatIndicator
 * @see SecurityAlert
 */
@Entity
@Table(name = "attack_campaigns", indexes = {
    @Index(name = "idx_attack_campaign_name", columnList = "name"),
    @Index(name = "idx_attack_campaign_status", columnList = "status"),
    @Index(name = "idx_attack_campaign_actor_id", columnList = "actor_id"),
    @Index(name = "idx_attack_campaign_first_seen", columnList = "first_seen"),
    @Index(name = "idx_attack_campaign_last_seen", columnList = "last_seen")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AttackCampaign {

    @Id
    @Column(updatable = false, nullable = false)
    private UUID campaignId;

    /** Analyst-assigned campaign name (e.g. "Operation ShadowHammer"). */
    @Column(length = 255, nullable = false)
    private String name;

    /** Free-text description of the campaign's objectives and methods. */
    @Column(columnDefinition = "TEXT")
    private String description;

    /** Earliest event attributed to this campaign. */
    @Column(name = "first_seen")
    private Instant firstSeen;

    /** Most recent event attributed to this campaign. */
    @Column(name = "last_seen")
    private Instant lastSeen;

    /**
     * Campaign lifecycle status: {@code active}, {@code dormant}, or {@code concluded}.
     */
    @Column(length = 20)
    private String status;

    /** UUID of the attributed {@link ThreatActor}, or {@code null} if unattributed. */
    @Column(name = "actor_id", columnDefinition = "uuid")
    private String actorId;

    /** Comma-separated MITRE ATT&CK technique IDs observed in this campaign. */
    @Column(columnDefinition = "TEXT")
    private String ttps;

    /** Comma-separated industry sectors targeted by this campaign. */
    @Column(name = "target_sectors", columnDefinition = "TEXT")
    private String targetSectors;

    /** Comma-separated UUIDs of {@link ThreatIndicator}s linked to this campaign. */
    @Column(name = "ioc_ids", columnDefinition = "TEXT")
    private String iocIds;

    /** Comma-separated UUIDs of {@link SecurityAlert}s linked to this campaign. */
    @Column(name = "alert_ids", columnDefinition = "TEXT")
    private String alertIds;

    /** Total number of {@link SecurityEvent}s correlated to this campaign. */
    @Column(name = "event_count")
    private int eventCount;

    /** Attribution / correlation confidence, 0.0–1.0. */
    private double confidence;

    // ── Lifecycle Callbacks ───────────────────────────────────────────

    @PrePersist
    void prePersist() {
        if (campaignId == null) {
            campaignId = UUID.randomUUID();
        }
        if (firstSeen == null) {
            firstSeen = Instant.now();
        }
        lastSeen = Instant.now();
        if (status == null) {
            status = "active";
        }
    }

    // ── Convenience Methods ───────────────────────────────────────────

    /**
     * Returns the TTP (MITRE technique ID) list.
     */
    public List<String> getTtpsList() {
        return splitCsv(ttps);
    }

    /**
     * Returns the target sectors as an immutable list.
     */
    public List<String> getTargetSectorsList() {
        return splitCsv(targetSectors);
    }

    /**
     * Returns the linked IOC IDs as a list of UUIDs.
     */
    public List<UUID> getIocIdList() {
        return splitCsv(iocIds).stream()
            .map(UUID::fromString)
            .collect(Collectors.toUnmodifiableList());
    }

    /**
     * Returns the linked alert IDs as a list of UUIDs.
     */
    public List<UUID> getAlertIdList() {
        return splitCsv(alertIds).stream()
            .map(UUID::fromString)
            .collect(Collectors.toUnmodifiableList());
    }

    /**
     * Links an IOC to this campaign if not already linked.
     */
    public void addIoc(UUID iocId) {
        if (iocId == null) return;
        iocIds = appendCsvUnique(iocIds, iocId.toString());
    }

    /**
     * Links an alert to this campaign if not already linked.
     */
    public void addAlert(UUID alertId) {
        if (alertId == null) return;
        alertIds = appendCsvUnique(alertIds, alertId.toString());
    }

    /**
     * Adds a MITRE technique ID if not already present.
     */
    public void addTtp(String technique) {
        if (technique == null || technique.isBlank()) return;
        ttps = appendCsvUnique(ttps, technique.strip());
    }

    /**
     * Increments the event count and refreshes {@code lastSeen}.
     */
    public void recordEvent() {
        this.eventCount++;
        this.lastSeen = Instant.now();
    }

    /**
     * Returns the parsed actor ID as a UUID, or {@code null} if unset.
     */
    public UUID getActorUuid() {
        return actorId != null && !actorId.isBlank() ? UUID.fromString(actorId) : null;
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
