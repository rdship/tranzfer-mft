package com.filetransfer.ai.entity.threat;

import com.filetransfer.ai.entity.threat.SecurityEnums.AlertSeverity;
import com.filetransfer.ai.entity.threat.SecurityEnums.AlertStatus;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Security alert entity produced by the SecurityAI correlation and scoring engine.
 *
 * <p>An alert represents a high-confidence security finding that requires analyst
 * attention. It aggregates one or more {@link SecurityEvent}s, enriches them with
 * MITRE ATT&CK mappings and AI-generated explanations, and tracks the full triage
 * lifecycle from creation to resolution or escalation.</p>
 *
 * <h3>Alert lifecycle</h3>
 * <pre>
 *   NEW  --->  INVESTIGATING  --->  RESOLVED
 *                    |                  |
 *                    +---> ESCALATED    +---> FALSE_POSITIVE
 * </pre>
 *
 * @see AlertSeverity
 * @see AlertStatus
 * @see SecurityEvent
 */
@Entity
@Table(name = "security_alerts", indexes = {
    @Index(name = "idx_security_alert_timestamp", columnList = "timestamp"),
    @Index(name = "idx_security_alert_severity", columnList = "severity"),
    @Index(name = "idx_security_alert_status", columnList = "status"),
    @Index(name = "idx_security_alert_risk_score", columnList = "risk_score"),
    @Index(name = "idx_security_alert_assigned_to", columnList = "assigned_to")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SecurityAlert {

    @Id
    @Column(updatable = false, nullable = false)
    private UUID alertId;

    /** When the alert was generated. */
    private Instant timestamp;

    /** Short human-readable title, e.g. "Brute-force SSH from 203.0.113.42". */
    @Column(length = 512, nullable = false)
    private String title;

    /** Detailed description of the alert, may include markdown. */
    @Column(columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    private AlertSeverity severity;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private AlertStatus status;

    /** Confidence that this alert is a true positive, 0.0–1.0. */
    private double confidence;

    /** Composite risk score on a 0–100 scale. */
    private double riskScore;

    /** Comma-separated UUIDs of the source {@link SecurityEvent}s that triggered this alert. */
    @Column(columnDefinition = "TEXT")
    private String sourceEventIds;

    /** Comma-separated MITRE ATT&CK tactic IDs. */
    @Column(columnDefinition = "TEXT")
    private String mitreTactics;

    /** Comma-separated MITRE ATT&CK technique IDs. */
    @Column(columnDefinition = "TEXT")
    private String mitreTechniques;

    /** AI-generated natural-language explanation of why this alert was raised. */
    @Column(columnDefinition = "TEXT")
    private String explanation;

    /** JSON array of recommended response actions. */
    @Column(columnDefinition = "TEXT")
    private String recommendedActions;

    /** Username or team the alert is assigned to for triage. */
    @Column(length = 255)
    private String assignedTo;

    /** When the alert was resolved (null while open). */
    private Instant resolvedAt;

    /** Final analyst verdict: {@code true_positive}, {@code false_positive}, {@code benign}. */
    @Column(length = 50)
    private String verdict;

    /** ID of the automated playbook executed in response to this alert. */
    @Column(length = 100)
    private String playbookId;

    /** Comma-separated UUIDs of related / correlated alerts. */
    @Column(columnDefinition = "TEXT")
    private String relatedAlertIds;

    /** Arbitrary enrichment data serialised as a JSON object. */
    @Column(columnDefinition = "TEXT")
    private String enrichmentsJson;

    // ── Lifecycle Callbacks ───────────────────────────────────────────

    @PrePersist
    void prePersist() {
        if (alertId == null) {
            alertId = UUID.randomUUID();
        }
        if (timestamp == null) {
            timestamp = Instant.now();
        }
        if (status == null) {
            status = AlertStatus.NEW;
        }
    }

    // ── Convenience Methods ───────────────────────────────────────────

    /**
     * Returns the source event IDs as a list of UUIDs.
     */
    public List<UUID> getSourceEventIdList() {
        List<String> raw = splitCsv(sourceEventIds);
        return raw.stream()
            .map(UUID::fromString)
            .collect(Collectors.toUnmodifiableList());
    }

    /**
     * Returns the related alert IDs as a list of UUIDs.
     */
    public List<UUID> getRelatedAlertIdList() {
        List<String> raw = splitCsv(relatedAlertIds);
        return raw.stream()
            .map(UUID::fromString)
            .collect(Collectors.toUnmodifiableList());
    }

    /**
     * Returns the MITRE tactic IDs as an immutable list.
     */
    public List<String> getMitreTacticsList() {
        return splitCsv(mitreTactics);
    }

    /**
     * Returns the MITRE technique IDs as an immutable list.
     */
    public List<String> getMitreTechniquesList() {
        return splitCsv(mitreTechniques);
    }

    /**
     * Adds a source event ID if not already present.
     */
    public void addSourceEventId(UUID eventId) {
        if (eventId == null) return;
        sourceEventIds = appendCsvUnique(sourceEventIds, eventId.toString());
    }

    /**
     * Adds a related alert ID if not already present.
     */
    public void addRelatedAlertId(UUID relatedId) {
        if (relatedId == null) return;
        relatedAlertIds = appendCsvUnique(relatedAlertIds, relatedId.toString());
    }

    /**
     * Transitions the alert to {@link AlertStatus#RESOLVED} with the given verdict.
     *
     * @param analystVerdict one of {@code true_positive}, {@code false_positive}, {@code benign}
     */
    public void resolve(String analystVerdict) {
        this.status = AlertStatus.RESOLVED;
        this.resolvedAt = Instant.now();
        this.verdict = analystVerdict;
    }

    /**
     * Transitions the alert to {@link AlertStatus#ESCALATED}.
     */
    public void escalate() {
        this.status = AlertStatus.ESCALATED;
    }

    /**
     * Returns the time elapsed since the alert was created, or {@link Duration#ZERO}
     * if no timestamp is set.
     */
    public Duration getAge() {
        if (timestamp == null) return Duration.ZERO;
        return Duration.between(timestamp, Instant.now());
    }

    /**
     * Returns the mean time to resolution, or {@code null} if the alert is still open.
     */
    public Duration getMttr() {
        if (timestamp == null || resolvedAt == null) return null;
        return Duration.between(timestamp, resolvedAt);
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
