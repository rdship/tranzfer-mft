package com.filetransfer.ai.service.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * Manages security incidents for the TranzFer AI security pipeline.
 *
 * <p>Groups related alerts into incidents, tracks investigation status through the
 * full lifecycle (OPEN -> INVESTIGATING -> CONTAINED -> RESOLVED -> CLOSED), and
 * generates comprehensive reports with timelines, IOC summaries, and MITRE ATT&CK
 * technique mappings.</p>
 *
 * <h3>Incident lifecycle</h3>
 * <pre>
 *   OPEN --> INVESTIGATING --> CONTAINED --> RESOLVED --> CLOSED
 *                  |                            |
 *                  +--- (merge) ----+           +--- (reopen) --> INVESTIGATING
 * </pre>
 *
 * <h3>Automatic incident creation</h3>
 * <p>When the {@link PlaybookEngine} escalates an alert, or when the kill chain
 * correlation engine detects 3+ MITRE ATT&CK stages from a single source, an
 * incident is automatically created with appropriate severity.</p>
 *
 * @see PlaybookEngine
 */
@Service
@Slf4j
public class IncidentManager {

    /** Active (non-closed) incidents keyed by incident ID. */
    private final Map<String, SecurityIncident> activeIncidents = new ConcurrentHashMap<>();

    /** Closed incidents retained for reporting and trend analysis. */
    private final List<SecurityIncident> closedIncidents = new CopyOnWriteArrayList<>();

    private static final int MAX_CLOSED_INCIDENTS = 1000;

    // ══════════════════════════════════════════════════════════════════
    //  Inner model classes
    // ══════════════════════════════════════════════════════════════════

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SecurityIncident {
        private String incidentId;
        private String title;
        private String description;
        private String severity;       // LOW, MEDIUM, HIGH, CRITICAL
        private String status;         // OPEN, INVESTIGATING, CONTAINED, RESOLVED, CLOSED
        private Instant createdAt;
        private Instant updatedAt;
        private Instant resolvedAt;
        private String assignedTo;
        @Builder.Default
        private List<String> alertIds = new ArrayList<>();
        @Builder.Default
        private List<String> iocValues = new ArrayList<>();
        @Builder.Default
        private List<String> affectedIps = new ArrayList<>();
        @Builder.Default
        private List<String> mitreTechniques = new ArrayList<>();
        @Builder.Default
        private List<String> playbookExecutionIds = new ArrayList<>();
        @Builder.Default
        private List<TimelineEntry> timeline = new ArrayList<>();
        private String rootCause;
        private String resolution;
        @Builder.Default
        private Map<String, Object> metadata = new LinkedHashMap<>();
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TimelineEntry {
        private Instant timestamp;
        private String action;
        private String description;
        private String actor;  // "system" or analyst name
    }

    // ══════════════════════════════════════════════════════════════════
    //  Incident Creation
    // ══════════════════════════════════════════════════════════════════

    /**
     * Creates a new security incident from one or more alerts.
     *
     * @param title           short incident title
     * @param severity        severity level: LOW, MEDIUM, HIGH, CRITICAL
     * @param alertIds        related alert IDs
     * @param mitreTechniques MITRE ATT&CK technique IDs
     * @param description     incident description and context
     * @return the created incident
     */
    public SecurityIncident createIncident(String title, String severity, List<String> alertIds,
                                           List<String> mitreTechniques, String description) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("Incident title must not be null or blank");
        }

        String normalizedSeverity = normalizeSeverity(severity);
        String incidentId = "INC-" + UUID.randomUUID().toString().substring(0, 12).toUpperCase();
        Instant now = Instant.now();

        SecurityIncident incident = SecurityIncident.builder()
                .incidentId(incidentId)
                .title(title)
                .description(description)
                .severity(normalizedSeverity)
                .status("OPEN")
                .createdAt(now)
                .updatedAt(now)
                .alertIds(alertIds != null ? new ArrayList<>(alertIds) : new ArrayList<>())
                .mitreTechniques(mitreTechniques != null ? new ArrayList<>(mitreTechniques) : new ArrayList<>())
                .iocValues(new ArrayList<>())
                .affectedIps(new ArrayList<>())
                .playbookExecutionIds(new ArrayList<>())
                .timeline(new ArrayList<>())
                .metadata(new LinkedHashMap<>())
                .build();

        // Add creation event to timeline
        addTimelineEntry(incident, "CREATED",
                "Incident created: " + title + " [" + normalizedSeverity + "]", "system");

        activeIncidents.put(incidentId, incident);

        log.info("Incident created: id={}, title='{}', severity={}, alerts={}, techniques={}",
                incidentId, title, normalizedSeverity,
                alertIds != null ? alertIds.size() : 0,
                mitreTechniques != null ? mitreTechniques : "none");

        return incident;
    }

    /**
     * Automatically creates an incident when attack chain correlation reaches critical threshold.
     *
     * @param entityId       the entity (IP/user) that triggered the chain
     * @param stagesReached  number of MITRE ATT&CK stages observed
     * @param techniques     MITRE technique IDs observed
     * @param narrative      AI-generated narrative of the attack chain
     * @return the created incident
     */
    public SecurityIncident autoCreateFromChain(String entityId, int stagesReached,
                                                List<String> techniques, String narrative) {
        String severity = stagesReached >= 5 ? "CRITICAL" : stagesReached >= 3 ? "HIGH" : "MEDIUM";
        String title = String.format("Attack chain detected: %d stages from %s", stagesReached, entityId);
        String description = String.format(
                "Automated incident: %d MITRE ATT&CK stages detected from entity %s. "
                        + "Techniques: %s. Narrative: %s",
                stagesReached, entityId,
                techniques != null ? String.join(", ", techniques) : "unknown",
                narrative != null ? narrative : "No narrative available");

        SecurityIncident incident = createIncident(title, severity, new ArrayList<>(), techniques, description);

        // Add the entity as an affected IP if it looks like an IP
        if (entityId != null && entityId.matches("\\d+\\.\\d+\\.\\d+\\.\\d+")) {
            addAffectedIp(incident.getIncidentId(), entityId);
        }

        // Add chain-specific metadata
        incident.getMetadata().put("autoCreated", true);
        incident.getMetadata().put("triggerType", "kill_chain_correlation");
        incident.getMetadata().put("stagesReached", stagesReached);
        incident.getMetadata().put("entityId", entityId);

        addTimelineEntry(incident, "AUTO_CREATED",
                "Automatically created from kill chain detection: " + stagesReached + " stages", "system");

        log.warn("Auto-created incident {} from attack chain: entity={}, stages={}, techniques={}",
                incident.getIncidentId(), entityId, stagesReached, techniques);

        return incident;
    }

    // ══════════════════════════════════════════════════════════════════
    //  Incident Updates
    // ══════════════════════════════════════════════════════════════════

    /**
     * Updates the status of an incident.
     *
     * @param incidentId the incident ID
     * @param newStatus  the new status (OPEN, INVESTIGATING, CONTAINED, RESOLVED, CLOSED)
     * @param note       optional note describing the status change
     * @throws IllegalArgumentException if the incident is not found or the status transition is invalid
     */
    public void updateStatus(String incidentId, String newStatus, String note) {
        SecurityIncident incident = findIncidentOrThrow(incidentId);
        String oldStatus = incident.getStatus();
        String normalizedStatus = normalizeStatus(newStatus);

        validateStatusTransition(oldStatus, normalizedStatus);

        incident.setStatus(normalizedStatus);
        incident.setUpdatedAt(Instant.now());

        if ("RESOLVED".equals(normalizedStatus) || "CLOSED".equals(normalizedStatus)) {
            incident.setResolvedAt(Instant.now());
        }

        String timelineNote = note != null && !note.isBlank()
                ? "Status changed: " + oldStatus + " -> " + normalizedStatus + " — " + note
                : "Status changed: " + oldStatus + " -> " + normalizedStatus;

        addTimelineEntry(incident, "STATUS_CHANGE", timelineNote, "system");

        // Move to closed list if CLOSED
        if ("CLOSED".equals(normalizedStatus)) {
            activeIncidents.remove(incidentId);
            closedIncidents.add(incident);
            trimClosedIncidents();
        }

        log.info("Incident {} status updated: {} -> {}{}", incidentId, oldStatus, normalizedStatus,
                note != null ? " (" + note + ")" : "");
    }

    /**
     * Adds an alert ID to an existing incident.
     *
     * @param incidentId the incident ID
     * @param alertId    the alert ID to associate
     */
    public void addAlert(String incidentId, String alertId) {
        SecurityIncident incident = findIncidentOrThrow(incidentId);
        if (alertId != null && !incident.getAlertIds().contains(alertId)) {
            incident.getAlertIds().add(alertId);
            incident.setUpdatedAt(Instant.now());
            addTimelineEntry(incident, "ALERT_ADDED", "Alert associated: " + alertId, "system");
            log.debug("Alert {} added to incident {}", alertId, incidentId);
        }
    }

    /**
     * Adds an IOC value to an existing incident.
     *
     * @param incidentId the incident ID
     * @param iocValue   the IOC value (IP, domain, hash, etc.)
     */
    public void addIoc(String incidentId, String iocValue) {
        SecurityIncident incident = findIncidentOrThrow(incidentId);
        if (iocValue != null && !incident.getIocValues().contains(iocValue)) {
            incident.getIocValues().add(iocValue);
            incident.setUpdatedAt(Instant.now());
            addTimelineEntry(incident, "IOC_ADDED", "IOC added: " + iocValue, "system");
        }
    }

    /**
     * Adds an affected IP address to an existing incident.
     *
     * @param incidentId the incident ID
     * @param ip         the affected IP address
     */
    public void addAffectedIp(String incidentId, String ip) {
        SecurityIncident incident = findIncidentOrThrow(incidentId);
        if (ip != null && !incident.getAffectedIps().contains(ip)) {
            incident.getAffectedIps().add(ip);
            incident.setUpdatedAt(Instant.now());
        }
    }

    /**
     * Associates a playbook execution with an incident.
     *
     * @param incidentId  the incident ID
     * @param executionId the playbook execution ID
     */
    public void addPlaybookExecution(String incidentId, String executionId) {
        SecurityIncident incident = findIncidentOrThrow(incidentId);
        if (executionId != null && !incident.getPlaybookExecutionIds().contains(executionId)) {
            incident.getPlaybookExecutionIds().add(executionId);
            incident.setUpdatedAt(Instant.now());
            addTimelineEntry(incident, "PLAYBOOK_EXECUTED",
                    "Playbook execution associated: " + executionId, "system");
        }
    }

    /**
     * Assigns the incident to an analyst or team.
     *
     * @param incidentId the incident ID
     * @param assignee   analyst name or team identifier
     */
    public void assignTo(String incidentId, String assignee) {
        SecurityIncident incident = findIncidentOrThrow(incidentId);
        String previous = incident.getAssignedTo();
        incident.setAssignedTo(assignee);
        incident.setUpdatedAt(Instant.now());
        addTimelineEntry(incident, "ASSIGNED",
                "Assigned to " + assignee + (previous != null ? " (was: " + previous + ")" : ""), "system");
        log.info("Incident {} assigned to {}", incidentId, assignee);
    }

    /**
     * Sets the root cause for a resolved incident.
     *
     * @param incidentId the incident ID
     * @param rootCause  root cause description
     */
    public void setRootCause(String incidentId, String rootCause) {
        SecurityIncident incident = findIncidentOrThrow(incidentId);
        incident.setRootCause(rootCause);
        incident.setUpdatedAt(Instant.now());
        addTimelineEntry(incident, "ROOT_CAUSE_IDENTIFIED", "Root cause: " + rootCause, "system");
    }

    /**
     * Sets the resolution summary for a resolved incident.
     *
     * @param incidentId the incident ID
     * @param resolution resolution description
     */
    public void setResolution(String incidentId, String resolution) {
        SecurityIncident incident = findIncidentOrThrow(incidentId);
        incident.setResolution(resolution);
        incident.setUpdatedAt(Instant.now());
        addTimelineEntry(incident, "RESOLUTION_SET", "Resolution: " + resolution, "system");
    }

    // ══════════════════════════════════════════════════════════════════
    //  Merge
    // ══════════════════════════════════════════════════════════════════

    /**
     * Merges multiple related incidents into a single consolidated incident.
     *
     * <p>All alerts, IOCs, affected IPs, MITRE techniques, playbook executions, and
     * timelines are combined. The highest severity among the merged incidents is used.
     * Original incidents are moved to CLOSED status.</p>
     *
     * @param incidentIds IDs of incidents to merge (must contain at least 2)
     * @param mergedTitle title for the merged incident
     * @return the merged incident
     */
    public SecurityIncident mergeIncidents(List<String> incidentIds, String mergedTitle) {
        if (incidentIds == null || incidentIds.size() < 2) {
            throw new IllegalArgumentException("At least 2 incident IDs are required for merge");
        }

        List<SecurityIncident> toMerge = new ArrayList<>();
        for (String id : incidentIds) {
            SecurityIncident incident = activeIncidents.get(id);
            if (incident == null) {
                throw new IllegalArgumentException("Active incident not found: " + id);
            }
            toMerge.add(incident);
        }

        // Determine the highest severity
        String highestSeverity = toMerge.stream()
                .map(SecurityIncident::getSeverity)
                .max(Comparator.comparingInt(this::severityOrdinal))
                .orElse("HIGH");

        // Aggregate all data
        Set<String> allAlerts = new LinkedHashSet<>();
        Set<String> allIocs = new LinkedHashSet<>();
        Set<String> allIps = new LinkedHashSet<>();
        Set<String> allTechniques = new LinkedHashSet<>();
        Set<String> allPlaybookExecs = new LinkedHashSet<>();
        List<TimelineEntry> allTimeline = new ArrayList<>();
        Map<String, Object> allMetadata = new LinkedHashMap<>();

        StringBuilder mergedDescription = new StringBuilder("Merged from incidents: ");
        for (SecurityIncident inc : toMerge) {
            allAlerts.addAll(inc.getAlertIds());
            allIocs.addAll(inc.getIocValues());
            allIps.addAll(inc.getAffectedIps());
            allTechniques.addAll(inc.getMitreTechniques());
            allPlaybookExecs.addAll(inc.getPlaybookExecutionIds());
            allTimeline.addAll(inc.getTimeline());
            if (inc.getMetadata() != null) allMetadata.putAll(inc.getMetadata());
            mergedDescription.append(inc.getIncidentId()).append(" (").append(inc.getTitle()).append("), ");
        }

        // Sort timeline chronologically
        allTimeline.sort(Comparator.comparing(TimelineEntry::getTimestamp, Comparator.nullsLast(Comparator.naturalOrder())));

        // Use the earliest createdAt
        Instant earliestCreation = toMerge.stream()
                .map(SecurityIncident::getCreatedAt)
                .filter(Objects::nonNull)
                .min(Instant::compareTo)
                .orElse(Instant.now());

        // Create the merged incident
        String mergedId = "INC-" + UUID.randomUUID().toString().substring(0, 12).toUpperCase();
        SecurityIncident merged = SecurityIncident.builder()
                .incidentId(mergedId)
                .title(mergedTitle)
                .description(mergedDescription.toString())
                .severity(highestSeverity)
                .status("INVESTIGATING")
                .createdAt(earliestCreation)
                .updatedAt(Instant.now())
                .alertIds(new ArrayList<>(allAlerts))
                .iocValues(new ArrayList<>(allIocs))
                .affectedIps(new ArrayList<>(allIps))
                .mitreTechniques(new ArrayList<>(allTechniques))
                .playbookExecutionIds(new ArrayList<>(allPlaybookExecs))
                .timeline(allTimeline)
                .metadata(allMetadata)
                .build();

        // Record the merge in the timeline
        addTimelineEntry(merged, "MERGED",
                "Merged from " + incidentIds.size() + " incidents: " + incidentIds, "system");

        // Close original incidents
        for (SecurityIncident original : toMerge) {
            addTimelineEntry(original, "MERGED_INTO",
                    "Merged into incident " + mergedId, "system");
            original.setStatus("CLOSED");
            original.setResolution("Merged into " + mergedId);
            original.setUpdatedAt(Instant.now());
            activeIncidents.remove(original.getIncidentId());
            closedIncidents.add(original);
        }
        trimClosedIncidents();

        activeIncidents.put(mergedId, merged);

        log.info("Merged {} incidents into {}: alerts={}, iocs={}, techniques={}",
                incidentIds.size(), mergedId, allAlerts.size(), allIocs.size(), allTechniques.size());

        return merged;
    }

    // ══════════════════════════════════════════════════════════════════
    //  Queries
    // ══════════════════════════════════════════════════════════════════

    /** Returns all active (non-closed) incidents. */
    public List<SecurityIncident> getActiveIncidents() {
        return activeIncidents.values().stream()
                .sorted(Comparator.comparingInt((SecurityIncident i) -> severityOrdinal(i.getSeverity())).reversed()
                        .thenComparing(SecurityIncident::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .collect(Collectors.toList());
    }

    /** Returns a specific incident by ID (searches both active and closed). */
    public SecurityIncident getIncident(String incidentId) {
        SecurityIncident incident = activeIncidents.get(incidentId);
        if (incident != null) return incident;

        return closedIncidents.stream()
                .filter(i -> incidentId.equals(i.getIncidentId()))
                .findFirst()
                .orElse(null);
    }

    /** Returns active incidents filtered by severity. */
    public List<SecurityIncident> getIncidentsBySeverity(String severity) {
        String normalized = normalizeSeverity(severity);
        return activeIncidents.values().stream()
                .filter(i -> normalized.equals(i.getSeverity()))
                .sorted(Comparator.comparing(SecurityIncident::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .collect(Collectors.toList());
    }

    /** Returns active incidents filtered by status. */
    public List<SecurityIncident> getIncidentsByStatus(String status) {
        String normalized = normalizeStatus(status);
        return activeIncidents.values().stream()
                .filter(i -> normalized.equals(i.getStatus()))
                .sorted(Comparator.comparing(SecurityIncident::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .collect(Collectors.toList());
    }

    // ══════════════════════════════════════════════════════════════════
    //  Reporting
    // ══════════════════════════════════════════════════════════════════

    /**
     * Generates a comprehensive incident report.
     *
     * @param incidentId the incident ID
     * @return report map with all incident details, timeline, IOC summary, MITRE mapping,
     *         playbook actions, affected assets, and recommendations
     */
    public Map<String, Object> generateReport(String incidentId) {
        SecurityIncident incident = getIncident(incidentId);
        if (incident == null) {
            return Map.of("error", "Incident not found: " + incidentId);
        }

        Map<String, Object> report = new LinkedHashMap<>();

        // Header
        report.put("reportId", "RPT-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        report.put("generatedAt", Instant.now().toString());
        report.put("incidentId", incident.getIncidentId());
        report.put("title", incident.getTitle());
        report.put("severity", incident.getSeverity());
        report.put("status", incident.getStatus());

        // Timing
        Map<String, Object> timing = new LinkedHashMap<>();
        timing.put("createdAt", formatInstant(incident.getCreatedAt()));
        timing.put("updatedAt", formatInstant(incident.getUpdatedAt()));
        timing.put("resolvedAt", formatInstant(incident.getResolvedAt()));
        if (incident.getCreatedAt() != null && incident.getResolvedAt() != null) {
            Duration mttr = Duration.between(incident.getCreatedAt(), incident.getResolvedAt());
            timing.put("meanTimeToResolve", formatDuration(mttr));
        } else if (incident.getCreatedAt() != null) {
            Duration openDuration = Duration.between(incident.getCreatedAt(), Instant.now());
            timing.put("openDuration", formatDuration(openDuration));
        }
        report.put("timing", timing);

        // Description and details
        report.put("description", incident.getDescription());
        report.put("assignedTo", incident.getAssignedTo() != null ? incident.getAssignedTo() : "Unassigned");
        report.put("rootCause", incident.getRootCause() != null ? incident.getRootCause() : "Under investigation");
        report.put("resolution", incident.getResolution() != null ? incident.getResolution() : "Pending");

        // Affected assets
        Map<String, Object> assets = new LinkedHashMap<>();
        assets.put("affectedIps", incident.getAffectedIps());
        assets.put("totalAffectedIps", incident.getAffectedIps().size());
        report.put("affectedAssets", assets);

        // IOC summary
        Map<String, Object> iocSummary = new LinkedHashMap<>();
        iocSummary.put("totalIocs", incident.getIocValues().size());
        iocSummary.put("values", incident.getIocValues());
        report.put("indicatorsOfCompromise", iocSummary);

        // MITRE ATT&CK mapping
        Map<String, Object> mitre = new LinkedHashMap<>();
        mitre.put("techniquesObserved", incident.getMitreTechniques());
        mitre.put("totalTechniques", incident.getMitreTechniques().size());
        report.put("mitreAttack", mitre);

        // Related alerts
        report.put("relatedAlerts", Map.of(
                "alertIds", incident.getAlertIds(),
                "totalAlerts", incident.getAlertIds().size()
        ));

        // Playbook actions taken
        report.put("playbookActions", Map.of(
                "executionIds", incident.getPlaybookExecutionIds(),
                "totalExecutions", incident.getPlaybookExecutionIds().size()
        ));

        // Timeline
        List<Map<String, String>> timelineEntries = incident.getTimeline().stream()
                .sorted(Comparator.comparing(TimelineEntry::getTimestamp, Comparator.nullsLast(Comparator.naturalOrder())))
                .map(entry -> {
                    Map<String, String> e = new LinkedHashMap<>();
                    e.put("timestamp", formatInstant(entry.getTimestamp()));
                    e.put("action", entry.getAction());
                    e.put("description", entry.getDescription());
                    e.put("actor", entry.getActor());
                    return e;
                })
                .collect(Collectors.toList());
        report.put("timeline", timelineEntries);

        // Recommendations
        report.put("recommendations", generateRecommendations(incident));

        // Metadata
        if (incident.getMetadata() != null && !incident.getMetadata().isEmpty()) {
            report.put("metadata", incident.getMetadata());
        }

        log.info("Generated report for incident {}", incidentId);
        return report;
    }

    /**
     * Returns dashboard data with aggregate incident metrics.
     *
     * @return dashboard map with open incidents by severity, MTTR, incidents per day, and top attack types
     */
    public Map<String, Object> getIncidentDashboard() {
        Map<String, Object> dashboard = new LinkedHashMap<>();
        Instant now = Instant.now();

        // Open incidents by severity
        Map<String, Long> bySeverity = activeIncidents.values().stream()
                .collect(Collectors.groupingBy(SecurityIncident::getSeverity, Collectors.counting()));
        dashboard.put("openIncidentsBySeverity", bySeverity);

        // Open incidents by status
        Map<String, Long> byStatus = activeIncidents.values().stream()
                .collect(Collectors.groupingBy(SecurityIncident::getStatus, Collectors.counting()));
        dashboard.put("openIncidentsByStatus", byStatus);

        // Total counts
        dashboard.put("totalActiveIncidents", activeIncidents.size());
        dashboard.put("totalClosedIncidents", closedIncidents.size());

        // Mean Time to Resolve (for closed incidents with resolved timestamps)
        OptionalDouble avgMttrMinutes = closedIncidents.stream()
                .filter(i -> i.getCreatedAt() != null && i.getResolvedAt() != null)
                .mapToLong(i -> Duration.between(i.getCreatedAt(), i.getResolvedAt()).toMinutes())
                .average();
        if (avgMttrMinutes.isPresent()) {
            dashboard.put("meanTimeToResolveMinutes", Math.round(avgMttrMinutes.getAsDouble()));
            dashboard.put("meanTimeToResolve", formatDuration(Duration.ofMinutes(Math.round(avgMttrMinutes.getAsDouble()))));
        } else {
            dashboard.put("meanTimeToResolve", "N/A");
        }

        // Incidents created in last 24 hours
        Instant dayAgo = now.minus(24, ChronoUnit.HOURS);
        long incidentsLast24h = activeIncidents.values().stream()
                .filter(i -> i.getCreatedAt() != null && i.getCreatedAt().isAfter(dayAgo))
                .count();
        incidentsLast24h += closedIncidents.stream()
                .filter(i -> i.getCreatedAt() != null && i.getCreatedAt().isAfter(dayAgo))
                .count();
        dashboard.put("incidentsLast24Hours", incidentsLast24h);

        // Incidents created in last 7 days
        Instant weekAgo = now.minus(7, ChronoUnit.DAYS);
        long incidentsLast7d = activeIncidents.values().stream()
                .filter(i -> i.getCreatedAt() != null && i.getCreatedAt().isAfter(weekAgo))
                .count();
        incidentsLast7d += closedIncidents.stream()
                .filter(i -> i.getCreatedAt() != null && i.getCreatedAt().isAfter(weekAgo))
                .count();
        dashboard.put("incidentsLast7Days", incidentsLast7d);

        // Top MITRE techniques across all active incidents
        Map<String, Long> topTechniques = activeIncidents.values().stream()
                .flatMap(i -> i.getMitreTechniques().stream())
                .collect(Collectors.groupingBy(t -> t, Collectors.counting()));
        List<Map.Entry<String, Long>> sortedTechniques = topTechniques.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(10)
                .toList();
        Map<String, Long> topTechniquesMap = new LinkedHashMap<>();
        sortedTechniques.forEach(e -> topTechniquesMap.put(e.getKey(), e.getValue()));
        dashboard.put("topMitreTechniques", topTechniquesMap);

        // Critical and unassigned incidents (requiring immediate attention)
        long criticalUnassigned = activeIncidents.values().stream()
                .filter(i -> "CRITICAL".equals(i.getSeverity()))
                .filter(i -> i.getAssignedTo() == null || i.getAssignedTo().isBlank())
                .count();
        dashboard.put("criticalUnassignedIncidents", criticalUnassigned);

        // Oldest open incident age
        activeIncidents.values().stream()
                .filter(i -> i.getCreatedAt() != null)
                .min(Comparator.comparing(SecurityIncident::getCreatedAt))
                .ifPresent(oldest -> {
                    Duration age = Duration.between(oldest.getCreatedAt(), now);
                    dashboard.put("oldestOpenIncident", Map.of(
                            "incidentId", oldest.getIncidentId(),
                            "title", oldest.getTitle(),
                            "severity", oldest.getSeverity(),
                            "age", formatDuration(age)
                    ));
                });

        return dashboard;
    }

    // ══════════════════════════════════════════════════════════════════
    //  Private helpers
    // ══════════════════════════════════════════════════════════════════

    private SecurityIncident findIncidentOrThrow(String incidentId) {
        SecurityIncident incident = activeIncidents.get(incidentId);
        if (incident == null) {
            throw new IllegalArgumentException("Active incident not found: " + incidentId);
        }
        return incident;
    }

    private void addTimelineEntry(SecurityIncident incident, String action, String description, String actor) {
        TimelineEntry entry = TimelineEntry.builder()
                .timestamp(Instant.now())
                .action(action)
                .description(description)
                .actor(actor)
                .build();
        incident.getTimeline().add(entry);
    }

    private void validateStatusTransition(String currentStatus, String newStatus) {
        if (currentStatus.equals(newStatus)) return;

        Set<String> allowedTransitions = switch (currentStatus) {
            case "OPEN" -> Set.of("INVESTIGATING", "CONTAINED", "RESOLVED", "CLOSED");
            case "INVESTIGATING" -> Set.of("CONTAINED", "RESOLVED", "CLOSED", "OPEN");
            case "CONTAINED" -> Set.of("RESOLVED", "CLOSED", "INVESTIGATING");
            case "RESOLVED" -> Set.of("CLOSED", "INVESTIGATING"); // allow reopen
            case "CLOSED" -> Set.of(); // closed is terminal
            default -> Set.of("OPEN", "INVESTIGATING", "CONTAINED", "RESOLVED", "CLOSED");
        };

        if (!allowedTransitions.contains(newStatus)) {
            throw new IllegalArgumentException(
                    "Invalid status transition: " + currentStatus + " -> " + newStatus
                            + ". Allowed: " + allowedTransitions);
        }
    }

    private String normalizeSeverity(String severity) {
        if (severity == null || severity.isBlank()) return "MEDIUM";
        return switch (severity.trim().toUpperCase()) {
            case "CRITICAL", "P1" -> "CRITICAL";
            case "HIGH", "P2" -> "HIGH";
            case "MEDIUM", "P3" -> "MEDIUM";
            case "LOW", "P4" -> "LOW";
            default -> "MEDIUM";
        };
    }

    private String normalizeStatus(String status) {
        if (status == null || status.isBlank()) return "OPEN";
        return switch (status.trim().toUpperCase()) {
            case "OPEN" -> "OPEN";
            case "INVESTIGATING" -> "INVESTIGATING";
            case "CONTAINED" -> "CONTAINED";
            case "RESOLVED" -> "RESOLVED";
            case "CLOSED" -> "CLOSED";
            default -> throw new IllegalArgumentException("Unknown status: " + status);
        };
    }

    private int severityOrdinal(String severity) {
        return switch (severity) {
            case "CRITICAL" -> 4;
            case "HIGH" -> 3;
            case "MEDIUM" -> 2;
            case "LOW" -> 1;
            default -> 0;
        };
    }

    private List<String> generateRecommendations(SecurityIncident incident) {
        List<String> recommendations = new ArrayList<>();

        if ("OPEN".equals(incident.getStatus()) || "INVESTIGATING".equals(incident.getStatus())) {
            if (incident.getAssignedTo() == null || incident.getAssignedTo().isBlank()) {
                recommendations.add("Assign this incident to an analyst for investigation.");
            }
            if ("CRITICAL".equals(incident.getSeverity())) {
                recommendations.add("CRITICAL severity — consider immediate containment actions.");
            }
        }

        if (!incident.getAffectedIps().isEmpty()) {
            recommendations.add("Verify all affected IPs (" + incident.getAffectedIps().size()
                    + ") are blocked or monitored.");
        }

        if (!incident.getMitreTechniques().isEmpty()) {
            recommendations.add("Review MITRE ATT&CK techniques ("
                    + String.join(", ", incident.getMitreTechniques())
                    + ") for additional detection opportunities.");
        }

        if (incident.getPlaybookExecutionIds().isEmpty()) {
            recommendations.add("No automated playbook actions have been taken — consider manual response steps.");
        }

        if (incident.getRootCause() == null || incident.getRootCause().isBlank()) {
            recommendations.add("Root cause analysis has not been completed.");
        }

        if (incident.getIocValues().isEmpty()) {
            recommendations.add("Extract and document IOCs for threat intelligence sharing.");
        } else {
            recommendations.add("Share " + incident.getIocValues().size()
                    + " IOC(s) with threat intelligence feeds for community defense.");
        }

        return recommendations;
    }

    private void trimClosedIncidents() {
        while (closedIncidents.size() > MAX_CLOSED_INCIDENTS) {
            closedIncidents.remove(0);
        }
    }

    private String formatInstant(Instant instant) {
        return instant != null ? instant.toString() : "N/A";
    }

    private String formatDuration(Duration duration) {
        if (duration == null) return "N/A";
        long totalMinutes = duration.toMinutes();
        if (totalMinutes < 60) {
            return totalMinutes + " minutes";
        }
        long hours = totalMinutes / 60;
        long minutes = totalMinutes % 60;
        if (hours < 24) {
            return hours + "h " + minutes + "m";
        }
        long days = hours / 24;
        hours = hours % 24;
        return days + "d " + hours + "h " + minutes + "m";
    }
}
