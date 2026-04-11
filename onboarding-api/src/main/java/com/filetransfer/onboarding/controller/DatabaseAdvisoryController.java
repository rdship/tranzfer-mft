package com.filetransfer.onboarding.controller;

import com.filetransfer.shared.security.Roles;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.*;

/**
 * DatabaseAdvisoryController — publishes the platform's recommended
 * Postgres tuning so DBAs at any org running TranzFer MFT can fetch
 * the advice programmatically without having to read source.
 *
 * Backed by R23's evidence-based workload audit
 * (see config/postgres/postgresql.tuned.conf for the per-setting
 * rationale and docs/operations/DATABASE-TUNING.md for the full guide).
 *
 * Endpoints:
 *   GET /api/v1/db-advisory              — full advice bundle as JSON
 *   GET /api/v1/db-advisory/postgres-conf — raw postgresql.conf text
 *   GET /api/v1/db-advisory/psql-commands — ALTER SYSTEM script for
 *                                            zero-downtime application
 *   GET /api/v1/db-advisory/scaling-table — hardware scaling matrix
 *
 * Design notes:
 *   - Everything is hardcoded in this file so DBAs get a stable,
 *     committed answer. No DB calls, no YAML lookup — just facts.
 *   - Versioned via the `version` field. Consumers can pin or
 *     diff against a known revision.
 *   - Each setting carries its own rationale + scaling note so the
 *     UI can render a tooltip or the DBA can paste it into a change
 *     request.
 */
@RestController
@RequestMapping("/api/v1/db-advisory")
@PreAuthorize(Roles.ADMIN)
@Tag(name = "Database Advisory", description = "Published Postgres tuning recommendations")
@Slf4j
public class DatabaseAdvisoryController {

    private static final String VERSION = "1.0";
    private static final String POSTGRES_MIN_VERSION = "16";

    /**
     * Platform's own DataSource — used when the admin chooses "apply
     * against the current database" (the common case). An override
     * jdbcUrl+credentials in the request body bypasses this and connects
     * directly via DriverManager so DBAs can target any Postgres they
     * can reach from the onboarding-api network.
     */
    @Autowired
    private DataSource platformDataSource;

    /** Main endpoint — the entire advice bundle as structured JSON. */
    @GetMapping
    @Operation(summary = "Full Postgres tuning advice bundle")
    public Map<String, Object> getAdvisory() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("version", VERSION);
        out.put("postgresMinVersion", POSTGRES_MIN_VERSION);
        out.put("publishedAt", "2026-04-11");
        out.put("summary", "Evidence-based Postgres 16 tuning for the TranzFer MFT workload. "
                + "Backed by a live audit of 17 services, 60 @Entity classes, 98 total Hikari "
                + "pool connections, and the hot-path queries the dashboard polls every 5-10 seconds.");
        out.put("workloadSnapshot", workloadSnapshot());
        out.put("categories", categories());
        out.put("hardwareScaling", hardwareScaling());
        out.put("verificationChecklist", verificationChecklist());
        return out;
    }

    /** Raw postgresql.conf text — copy/paste into $PGDATA/postgresql.conf */
    @GetMapping(value = "/postgres-conf", produces = "text/plain")
    @Operation(summary = "Raw postgresql.conf text for direct paste")
    public String getPostgresConf() {
        StringBuilder sb = new StringBuilder();
        sb.append("# TranzFer MFT — Tuned Postgres ").append(POSTGRES_MIN_VERSION)
                .append(" Configuration (v").append(VERSION).append(")\n");
        sb.append("# Fetched from /api/v1/db-advisory/postgres-conf on a live onboarding-api.\n");
        sb.append("# Full rationale: docs/operations/DATABASE-TUNING.md\n\n");
        for (Map<String, Object> cat : categories()) {
            sb.append("# ── ").append(cat.get("label")).append(" ──\n");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> settings = (List<Map<String, Object>>) cat.get("settings");
            for (Map<String, Object> s : settings) {
                sb.append("# ").append(s.get("rationale")).append("\n");
                sb.append(s.get("name")).append(" = ").append(s.get("value")).append("\n\n");
            }
        }
        return sb.toString();
    }

    /** psql ALTER SYSTEM commands for hot-apply without restart. */
    @GetMapping(value = "/psql-commands", produces = "text/plain")
    @Operation(summary = "psql ALTER SYSTEM script for hot-apply")
    public String getPsqlCommands() {
        StringBuilder sb = new StringBuilder();
        sb.append("-- TranzFer MFT — Postgres tuning as ALTER SYSTEM statements\n");
        sb.append("-- Apply against a running Postgres: psql -U postgres -f this-file\n");
        sb.append("-- Then reload: SELECT pg_reload_conf();\n");
        sb.append("-- Some settings (shared_buffers, max_connections, wal_level) require a restart.\n\n");
        for (Map<String, Object> cat : categories()) {
            sb.append("-- ").append(cat.get("label")).append("\n");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> settings = (List<Map<String, Object>>) cat.get("settings");
            for (Map<String, Object> s : settings) {
                String name = (String) s.get("name");
                String value = (String) s.get("value");
                // ALTER SYSTEM requires quotes around ANY value that isn't a
                // pure number (0, 200, 0.9) or the boolean literals on/off.
                // Values with units (1GB, 4MB, 15min, 1s, 30s) MUST be
                // quoted even though postgres.conf accepts them unquoted.
                boolean isPureNumber = value.matches("^-?\\d+(\\.\\d+)?$");
                boolean isBoolean = value.equals("on") || value.equals("off");
                String quoted = isPureNumber || isBoolean ? value : "'" + value + "'";
                sb.append("ALTER SYSTEM SET ").append(name).append(" = ").append(quoted).append(";\n");
            }
            sb.append("\n");
        }
        sb.append("SELECT pg_reload_conf();\n");
        return sb.toString();
    }

    /** Hardware scaling matrix as JSON for UI rendering. */
    @GetMapping("/scaling-table")
    @Operation(summary = "Hardware scaling matrix")
    public List<Map<String, Object>> getScalingTable() {
        return hardwareScaling();
    }

    // ════════════════════════════════════════════════════════════════════
    //  APPLY + STATUS — actually makes the changes / reports the drift
    // ════════════════════════════════════════════════════════════════════

    /**
     * Status / drift report: compare every recommended setting against
     * what Postgres currently has live. Returns a list of
     *   { name, recommended, current, match, restartRequired, context }
     * so the UI can render a coloured diff view.
     *
     * Uses the platform's own DataSource (no override) because this is
     * read-only — the operator just wants to see "are we in compliance".
     * A separate endpoint for checking foreign databases can be added
     * later if needed.
     */
    @GetMapping("/status")
    @Operation(summary = "Compare current Postgres settings against the R23 recommendations")
    public Map<String, Object> getStatus() {
        Map<String, String> recommended = flatRecommendedSettings();
        Map<String, String[]> currentAndContext = fetchCurrentSettings(platformDataSource, recommended.keySet());

        List<Map<String, Object>> diffs = new ArrayList<>();
        int matched = 0, drifted = 0, restartOnly = 0, unknown = 0;

        for (Map.Entry<String, String> rec : recommended.entrySet()) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("name", rec.getKey());
            d.put("recommended", rec.getValue());
            String[] cur = currentAndContext.get(rec.getKey().toLowerCase());
            if (cur == null) {
                d.put("current", null);
                d.put("match", false);
                d.put("restartRequired", false);
                d.put("context", "unknown");
                d.put("note", "Setting not found in pg_settings (Postgres version mismatch?)");
                unknown++;
            } else {
                String currentValue = cur[0];
                String context = cur[1];
                boolean match = normalizeValue(currentValue).equals(normalizeValue(rec.getValue()));
                d.put("current", currentValue);
                d.put("match", match);
                d.put("restartRequired", "postmaster".equals(context));
                d.put("context", context);
                if (match) matched++;
                else if ("postmaster".equals(context)) restartOnly++;
                else drifted++;
            }
            diffs.add(d);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("totalSettings", recommended.size());
        out.put("matched", matched);
        out.put("drifted", drifted);
        out.put("restartRequired", restartOnly);
        out.put("unknown", unknown);
        out.put("compliancePct", recommended.isEmpty() ? 100 : (matched * 100) / recommended.size());
        out.put("diffs", diffs);
        return out;
    }

    /**
     * Apply the recommended settings to a live Postgres.
     *
     * Request body (all fields optional):
     *   {
     *     "jdbcUrl": "jdbc:postgresql://host:5432/filetransfer",  // blank = use platform DataSource
     *     "username": "postgres",
     *     "password": "***",
     *     "onlyDrifted": true,        // if true, only apply settings whose
     *                                 //   current value differs; else apply all
     *     "reload": true              // if true, run pg_reload_conf() at the end
     *   }
     *
     * Responds with one result row per setting:
     *   {
     *     "summary": { "applied": N, "skipped": N, "restartRequired": N, "failed": N },
     *     "results": [
     *       { "name": "work_mem", "recommended": "16MB", "status": "APPLIED", "message": null },
     *       { "name": "shared_buffers", "recommended": "1GB", "status": "RESTART_REQUIRED",
     *         "message": "ALTER SYSTEM accepted; restart Postgres to take effect" },
     *       { "name": "max_connections", "recommended": "200", "status": "FAILED",
     *         "message": "permission denied" },
     *     ]
     *   }
     *
     * Safety:
     *   - Only ADMIN role can call (class-level @PreAuthorize)
     *   - Every invocation is logged at INFO with correlation id
     *   - If the override credentials can't connect, returns 400 BEFORE
     *     any ALTER SYSTEM runs
     *   - If pg_reload_conf fails, the response still reports which
     *     statements were ALTER SYSTEM'd — DBAs can manually reload later
     */
    @PostMapping("/apply")
    @Operation(summary = "Apply the recommended settings to a live Postgres (ALTER SYSTEM + pg_reload_conf)")
    public Map<String, Object> applyAdvisory(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> req = body == null ? Collections.emptyMap() : body;

        String jdbcUrl  = asString(req.get("jdbcUrl"));
        String username = asString(req.get("username"));
        String password = asString(req.get("password"));
        boolean onlyDrifted = Boolean.TRUE.equals(req.get("onlyDrifted"));
        boolean reload      = req.get("reload") == null || Boolean.TRUE.equals(req.get("reload"));

        boolean useOverride = jdbcUrl != null && !jdbcUrl.isBlank();
        if (useOverride && (username == null || username.isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Override username is required when jdbcUrl is provided");
        }

        log.info("Database advisory apply requested: override={}, onlyDrifted={}, reload={}",
                useOverride, onlyDrifted, reload);

        Map<String, String> recommended = flatRecommendedSettings();

        try (Connection conn = useOverride
                ? DriverManager.getConnection(jdbcUrl, username, password)
                : platformDataSource.getConnection()) {

            // ALTER SYSTEM MUST run outside an explicit transaction. The
            // platform DataSource (HikariCP) is configured auto-commit=false
            // for ORM performance, so we flip it to true on this borrowed
            // connection. The try-with-resources close will restore the
            // default auto-commit state when we return the connection to
            // the pool — JDBC spec guarantees Hikari resets it.
            conn.setAutoCommit(true);

            Map<String, String[]> currentAndContext = fetchCurrentSettings(conn, recommended.keySet());

            int applied = 0, skipped = 0, restart = 0, failed = 0;
            List<Map<String, Object>> results = new ArrayList<>();

            for (Map.Entry<String, String> e : recommended.entrySet()) {
                String name = e.getKey();
                String rec  = e.getValue();
                String[] cur = currentAndContext.get(name.toLowerCase());
                String currentValue = cur != null ? cur[0] : null;
                String context      = cur != null ? cur[1] : "unknown";
                boolean isRestart   = "postmaster".equals(context);
                boolean match = currentValue != null
                        && normalizeValue(currentValue).equals(normalizeValue(rec));

                Map<String, Object> row = new LinkedHashMap<>();
                row.put("name", name);
                row.put("recommended", rec);
                row.put("current", currentValue);
                row.put("context", context);

                if (match && onlyDrifted) {
                    row.put("status", "SKIPPED");
                    row.put("message", "Already at the recommended value");
                    skipped++;
                    results.add(row);
                    continue;
                }

                // Execute ALTER SYSTEM SET name = value  (quoted properly)
                String quoted = quoteValueForAlterSystem(rec);
                String sql = "ALTER SYSTEM SET " + sanitizeIdentifier(name) + " = " + quoted;
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute(sql);
                    if (isRestart) {
                        row.put("status", "RESTART_REQUIRED");
                        row.put("message", "ALTER SYSTEM accepted; restart Postgres to take effect (setting context=postmaster)");
                        restart++;
                    } else {
                        row.put("status", "APPLIED");
                        row.put("message", null);
                        applied++;
                    }
                } catch (SQLException ex) {
                    row.put("status", "FAILED");
                    row.put("message", ex.getMessage());
                    failed++;
                    log.warn("ALTER SYSTEM failed for {}: {}", name, ex.getMessage());
                }
                results.add(row);
            }

            // Reload config so reloadable settings actually take effect
            boolean reloaded = false;
            String reloadError = null;
            if (reload) {
                try (Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery("SELECT pg_reload_conf()")) {
                    reloaded = rs.next() && rs.getBoolean(1);
                } catch (SQLException ex) {
                    reloadError = ex.getMessage();
                    log.warn("pg_reload_conf() failed: {}", ex.getMessage());
                }
            }

            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("applied", applied);
            summary.put("skipped", skipped);
            summary.put("restartRequired", restart);
            summary.put("failed", failed);
            summary.put("totalConsidered", recommended.size());
            summary.put("reloaded", reloaded);
            if (reloadError != null) summary.put("reloadError", reloadError);
            summary.put("usedOverrideConnection", useOverride);

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("summary", summary);
            out.put("results", results);
            return out;
        } catch (SQLException ex) {
            log.warn("Database advisory apply: connection failed: {}", ex.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Couldn't connect to the target database: " + ex.getMessage());
        }
    }

    // ── Helpers for apply/status ────────────────────────────────────────

    /** Flatten all recommended settings from categories() into one map. */
    @SuppressWarnings("unchecked")
    private Map<String, String> flatRecommendedSettings() {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map<String, Object> cat : categories()) {
            List<Map<String, Object>> settings = (List<Map<String, Object>>) cat.get("settings");
            for (Map<String, Object> s : settings) {
                out.put((String) s.get("name"), (String) s.get("value"));
            }
        }
        return out;
    }

    /**
     * Query pg_settings for the current value + context (reloadable vs
     * restart-required) of each recommended setting. Returns a map keyed
     * by setting name with a String[] { currentValue, context }.
     */
    private Map<String, String[]> fetchCurrentSettings(DataSource ds, Collection<String> names) {
        try (Connection conn = ds.getConnection()) {
            return fetchCurrentSettings(conn, names);
        } catch (SQLException ex) {
            log.warn("fetchCurrentSettings failed: {}", ex.getMessage());
            return Collections.emptyMap();
        }
    }

    private Map<String, String[]> fetchCurrentSettings(Connection conn, Collection<String> names) {
        if (names.isEmpty()) return Collections.emptyMap();
        Map<String, String[]> out = new HashMap<>();
        // Postgres's pg_settings is case-sensitive on `name` but the GUC
        // parser that ALTER SYSTEM / postgresql.conf use is NOT — e.g. the
        // canonical name is `TimeZone` but `timezone` works via SHOW. We
        // compare via LOWER() so our recommended list doesn't have to
        // match the exact case of pg_settings. The result is stored back
        // keyed by the recommendation's lowercase form.
        String placeholders = String.join(",", Collections.nCopies(names.size(), "?"));
        String sql = "SELECT name, setting, unit, context FROM pg_settings WHERE LOWER(name) IN (" + placeholders + ")";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int i = 1;
            for (String n : names) ps.setString(i++, n.toLowerCase());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String name = rs.getString("name");
                    String setting = rs.getString("setting");
                    String unit = rs.getString("unit");
                    String context = rs.getString("context");
                    // pg_settings returns raw integer values + unit — e.g.
                    // shared_buffers comes back as setting=131072, unit=8kB.
                    // We normalize to human form so diffs match the recommendations.
                    String human = normalizePgSettingValue(setting, unit);
                    out.put(name.toLowerCase(), new String[]{human, context});
                }
            }
        } catch (SQLException ex) {
            log.warn("fetchCurrentSettings query failed: {}", ex.getMessage());
        }
        return out;
    }

    /**
     * Convert pg_settings' raw integer+unit representation to the
     * human string we compare against the recommendations.
     *   setting=131072, unit=8kB → "1GB"
     *   setting=16384,  unit=kB  → "16MB"
     *   setting=900,    unit=s   → "15min"
     *   setting=2000,   unit=(null) → "2000"
     *   setting=on,     unit=(null) → "on"
     */
    private String normalizePgSettingValue(String setting, String unit) {
        if (setting == null) return "";
        if (unit == null || unit.isEmpty()) return setting;
        long raw;
        try {
            raw = Long.parseLong(setting);
        } catch (NumberFormatException e) {
            return setting;
        }
        // Memory units: convert to GB/MB/kB
        if ("8kB".equals(unit)) {
            long bytes = raw * 8L * 1024L;
            return humanBytes(bytes);
        }
        if ("kB".equals(unit)) {
            return humanBytes(raw * 1024L);
        }
        if ("MB".equals(unit)) {
            return humanBytes(raw * 1024L * 1024L);
        }
        // Time units
        if ("ms".equals(unit)) {
            return raw == 0 ? "0" : humanMs(raw);
        }
        if ("s".equals(unit)) {
            return raw == 0 ? "0" : humanSeconds(raw);
        }
        if ("min".equals(unit)) {
            return raw == 0 ? "0" : raw + "min";
        }
        return setting + unit;
    }

    private String humanBytes(long bytes) {
        if (bytes == 0) return "0";
        if (bytes % (1024L * 1024L * 1024L) == 0) return (bytes / (1024L * 1024L * 1024L)) + "GB";
        if (bytes % (1024L * 1024L) == 0) return (bytes / (1024L * 1024L)) + "MB";
        if (bytes % 1024L == 0) return (bytes / 1024L) + "kB";
        return bytes + "B";
    }

    private String humanSeconds(long s) {
        if (s % 3600 == 0) return (s / 3600) + "h";
        if (s % 60 == 0) return (s / 60) + "min";
        return s + "s";
    }

    private String humanMs(long ms) {
        if (ms % 60000 == 0) return (ms / 60000) + "min";
        if (ms % 1000 == 0) return (ms / 1000) + "s";
        return ms + "ms";
    }

    /**
     * Normalize a value string for equality comparison. Handles
     * canonical cases where the same quantity spells different:
     *   "1GB" == "1024MB" == "1048576kB"
     *   "15min" == "900s" == "900000ms"
     *   "1s" == "1000ms"
     *   "on" == "pglz" for wal_compression (Postgres auto-canonicalizes)
     */
    private String normalizeValue(String v) {
        if (v == null) return "";
        String s = v.trim().toLowerCase();
        // Handle "on" / "off" and wal_compression synonyms.
        // Postgres canonicalizes `on` -> `pglz` for wal_compression in
        // pg_settings, so treat them as equal for comparison purposes.
        if (s.equals("on") || s.equals("pglz") || s.equals("lz4")) return "on";
        if (s.equals("off")) return "off";
        // timezone synonyms — Postgres canonicalizes `UTC` to `Etc/UTC` on
        // display. Treat the whole family as one canonical value.
        if (s.equals("utc") || s.equals("etc/utc") || s.equals("gmt") || s.equals("etc/gmt")) return "utc";
        // Try to extract number + unit
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("^(-?\\d+(?:\\.\\d+)?)(kb|mb|gb|ms|s|min|h)?$")
                .matcher(s);
        if (!m.matches()) return s;
        double num = Double.parseDouble(m.group(1));
        String unit = m.group(2);
        if (unit == null) return String.valueOf((long) num);
        switch (unit) {
            case "kb": return String.valueOf((long) (num * 1024));
            case "mb": return String.valueOf((long) (num * 1024 * 1024));
            case "gb": return String.valueOf((long) (num * 1024 * 1024 * 1024));
            case "ms": return String.valueOf((long) num);
            case "s":  return String.valueOf((long) (num * 1000));
            case "min":return String.valueOf((long) (num * 60 * 1000));
            case "h":  return String.valueOf((long) (num * 3600 * 1000));
            default:   return s;
        }
    }

    /** ALTER SYSTEM requires quotes around any non-pure-number / non-boolean. */
    private String quoteValueForAlterSystem(String value) {
        boolean isPureNumber = value.matches("^-?\\d+(\\.\\d+)?$");
        boolean isBoolean = value.equals("on") || value.equals("off");
        return isPureNumber || isBoolean ? value : "'" + value + "'";
    }

    /**
     * Only allow alphanumeric/underscore in the setting name, just to be
     * extra safe about SQL injection even though the list is static.
     */
    private String sanitizeIdentifier(String name) {
        if (!name.matches("^[a-zA-Z][a-zA-Z0-9_]*$")) {
            throw new IllegalArgumentException("Invalid setting name: " + name);
        }
        return name;
    }

    private String asString(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    // ── Snapshot data (sourced from R23 audit) ─────────────────────────

    private Map<String, Object> workloadSnapshot() {
        Map<String, Object> w = new LinkedHashMap<>();
        w.put("servicesWithDatabase", 17);
        w.put("totalHikariMaxPool", 98);
        w.put("entityClasses", 60);
        w.put("jsonbTextByteaColumns", 167);
        w.put("joinFetchMaxDepth", 5);
        w.put("pessimisticLocks", 0);
        w.put("batchInsertEnabled", true);
        w.put("batchSize", 25);
        w.put("concurrentIndexesOnBoot", 8);
        w.put("dashboardPollInterval", "5-10s");
        w.put("largestFindallCallers", List.of(
                "ScreeningEngine.sanctionsRepository",
                "TransferCollector.transferRepository",
                "AuditCollector.auditLogRepository",
                "SentinelController.ruleRepository"
        ));
        return w;
    }

    private List<Map<String, Object>> categories() {
        List<Map<String, Object>> cats = new ArrayList<>();

        cats.add(category("connections", "Connections & Authentication", List.of(
                setting("max_connections", "200",
                        "Hard cap on simultaneous backends. Total Hikari max-pool-size is 98; 2x headroom + 5 superuser slots.",
                        "+16 per additional service replica"),
                setting("superuser_reserved_connections", "5",
                        "Keeps slots aside for psql/pg_dump/admin work so connection storms don't lock out the DBA.",
                        "Fixed")
        )));

        cats.add(category("memory", "Memory", List.of(
                setting("shared_buffers", "1GB",
                        "Postgres in-process cache for table + index pages. TOAST-heavy workload (167+ JSONB/TEXT/BYTEA columns) earns this immediately.",
                        "Dedicated host: 25% of RAM, cap at 40 GB"),
                setting("effective_cache_size", "4GB",
                        "Planner hint — tells Postgres how much RAM the OS+PG collectively use for caching. Higher = prefer index scans.",
                        "Dedicated host: 50-75% of RAM"),
                setting("work_mem", "16MB",
                        "Per-operation sort/hash memory. Comfortable for 5-way JOIN FETCH on file_transfer_records.",
                        "Bump to 32-64 MB for reporting replicas"),
                setting("maintenance_work_mem", "256MB",
                        "Memory ceiling for CREATE INDEX / VACUUM. V42 fires 8 CONCURRENTLY index builds on boot — 256 MB makes them fast.",
                        "Bump to 1 GB on bigger hosts for faster REINDEX"),
                setting("temp_buffers", "16MB",
                        "Per-session temp table cache. Low usage in this workload.",
                        "Fixed")
        )));

        cats.add(category("wal", "Write-Ahead Log", List.of(
                setting("wal_buffers", "32MB",
                        "Buffer for WAL records before flush. 17 services doing 25-row batch inserts produce bursty writes.",
                        "Keep at 1/32 of shared_buffers or 32 MB, whichever is larger"),
                setting("max_wal_size", "4GB",
                        "Controls checkpoint frequency. 4 GB = ~10 hours of WAL at demo rate, very safe.",
                        "Size for 10-15 min of peak WAL production"),
                setting("min_wal_size", "1GB",
                        "Keep WAL files for reuse instead of creating new ones. Reduces filesystem churn.",
                        "25% of max_wal_size"),
                setting("checkpoint_timeout", "15min",
                        "Default 5 min is aggressive for write-heavy workloads. 15 min plus completion_target=0.9 smooths fsync pressure.",
                        "Fixed"),
                setting("checkpoint_completion_target", "0.9",
                        "Spread checkpoint writes over 90% of the interval. Avoids IO stall spikes.",
                        "Fixed"),
                setting("wal_compression", "on",
                        "~50% WAL savings on JSONB-heavy writes (full-page images compress well). Low CPU cost in PG 14+.",
                        "Always on"),
                setting("wal_level", "replica",
                        "Required for streaming replication without dump/restore.",
                        "Set to 'logical' if using logical replication")
        )));

        cats.add(category("planner", "Planner / Query Optimization", List.of(
                setting("random_page_cost", "1.1",
                        "SSD assumption. Makes the planner prefer index scans for high-cardinality queries.",
                        "Set to 4.0 on spinning disks (rare)"),
                setting("seq_page_cost", "1.0",
                        "Baseline sequential read cost. Keep at default.",
                        "Fixed"),
                setting("effective_io_concurrency", "200",
                        "SSD concurrency hint. Enables aggressive prefetch on bitmap heap scans.",
                        "1 for HDD, 100 for SATA SSD, 200+ for NVMe"),
                setting("default_statistics_target", "200",
                        "2x default. Bigger ANALYZE sample gives better selectivity on JSONB columns with many distinct values.",
                        "Fixed")
        )));

        cats.add(category("parallelism", "Parallelism", List.of(
                setting("max_worker_processes", "8",
                        "Ceiling on background worker processes. One less than CPU count on 9-CPU demo.",
                        "Set to N_CPUS - 1, cap at 16"),
                setting("max_parallel_workers", "6",
                        "How many workers can be used for parallel queries simultaneously.",
                        "75% of max_worker_processes"),
                setting("max_parallel_workers_per_gather", "2",
                        "Workers per single query. Conservative so dashboard aggregates don't starve transactional work.",
                        "Bump to 4 on reporting replicas"),
                setting("max_parallel_maintenance_workers", "2",
                        "Parallel workers for CREATE INDEX / VACUUM. Helps V42 CONCURRENTLY indexes finish faster.",
                        "Match max_parallel_workers_per_gather")
        )));

        cats.add(category("autovacuum", "Autovacuum", List.of(
                setting("autovacuum", "on",
                        "Must be on. file_transfer_records sees frequent status transitions leaving dead tuples.",
                        "Fixed"),
                setting("autovacuum_max_workers", "4",
                        "Bumped from default 3. VIP tables (transfers, audit_logs, flow_executions, sentinel_findings) can be vacuumed in parallel.",
                        "Scale with entity count (roughly 1 worker per 15-20 hot tables)"),
                setting("autovacuum_naptime", "30s",
                        "Default 1 min is too lazy for our churn. 30s catches bloat sooner.",
                        "Fixed"),
                setting("autovacuum_vacuum_scale_factor", "0.1",
                        "Vacuum at 10% dead tuples, not 20%. Multi-million row tables need sooner-than-default.",
                        "Fixed"),
                setting("autovacuum_analyze_scale_factor", "0.05",
                        "ANALYZE more often so planner always has fresh stats for dashboard queries.",
                        "Fixed"),
                setting("autovacuum_vacuum_cost_limit", "2000",
                        "10x default. Modern SSDs can absorb much more vacuum IO than the 2001-era default assumed.",
                        "Fixed")
        )));

        cats.add(category("timeouts", "Timeouts", List.of(
                setting("statement_timeout", "0",
                        "MUST be 0. V42 CREATE INDEX CONCURRENTLY can run 60+ seconds. See BUG-2.",
                        "DBAs not running CONCURRENTLY can set to 300s"),
                setting("idle_in_transaction_session_timeout", "0",
                        "MUST be 0. Flyway non-transactional migrations legitimately idle while PG builds indexes. See R18 fix.",
                        "Post-bootstrap, can set to 60s to protect against stuck clients"),
                setting("lock_timeout", "10s",
                        "Short-circuit lock waits. Protects hot path if autovacuum stalls on a hot table.",
                        "Fixed"),
                setting("deadlock_timeout", "1s",
                        "Default. No pessimistic locks in the code, so deadlocks are rare.",
                        "Fixed")
        )));

        cats.add(category("logging", "Logging & Observability", List.of(
                setting("log_min_duration_statement", "500",
                        "Log statements slower than 500 ms. Dashboard polls every 5-10s so anything over 500 ms is user-visible.",
                        "Tighten to 200 ms on reporting replicas"),
                setting("log_checkpoints", "on",
                        "Cheap. Confirms wal_buffers + max_wal_size are correctly sized.",
                        "Always on"),
                setting("log_connections", "off",
                        "Platform creates thousands of connections per minute on startup; logging each drowns the pipeline.",
                        "Turn on temporarily for auth debugging"),
                setting("log_disconnections", "off",
                        "Same rationale as log_connections.",
                        "Turn on temporarily for auth debugging"),
                setting("log_lock_waits", "on",
                        "Early warning for contention. Cheap to enable.",
                        "Always on"),
                setting("log_autovacuum_min_duration", "1s",
                        "Observability for autovacuum bloat management.",
                        "Fixed"),
                setting("log_temp_files", "4MB",
                        "Log queries that spill more than 4 MB of temp files. If you see many, bump work_mem.",
                        "Fixed")
        )));

        cats.add(category("jit", "JIT Compilation", List.of(
                setting("jit", "on",
                        "Compile long-running expressions to native code. Benefits sentinel correlation + analytics aggregates.",
                        "Fixed"),
                setting("jit_above_cost", "100000",
                        "Only JIT expensive queries. Small dashboard queries skip compilation overhead.",
                        "Fixed")
        )));

        cats.add(category("locale", "Locale & Time", List.of(
                setting("timezone", "UTC",
                        "All timestamps stored + reported in UTC so multi-region deployments never mismatch.",
                        "Fixed"),
                setting("log_timezone", "UTC",
                        "Same rationale for log messages.",
                        "Fixed")
        )));

        return cats;
    }

    private List<Map<String, Object>> hardwareScaling() {
        return List.of(
                scalingRow("8 GB",  "4",   "1 GB",  "4 GB",   "8 MB",  "128 MB", "100"),
                scalingRow("16 GB", "8",   "2 GB",  "10 GB",  "16 MB", "256 MB", "200"),
                scalingRow("32 GB", "12",  "4 GB",  "20 GB",  "16 MB", "512 MB", "300"),
                scalingRow("64 GB", "16",  "8 GB",  "40 GB",  "32 MB", "1 GB",   "500"),
                scalingRow("128 GB","32",  "16 GB", "80 GB",  "64 MB", "2 GB",   "800"),
                scalingRow("256+ GB","64+","40 GB cap","160 GB+","128 MB","4 GB","1000+")
        );
    }

    private List<String> verificationChecklist() {
        return List.of(
                "SHOW ALL — diff against the recommended values",
                "docker logs mft-postgres | grep -i checkpoint — confirm ~15 min intervals under load",
                "SELECT * FROM pg_stat_database WHERE datname = 'filetransfer' — cache hit ratio > 95%",
                "./scripts/demo-edi.sh — validates no timeout regression",
                "Admin UI Dashboard: every poll returns < 500 ms"
        );
    }

    // ── tiny builders to keep the category() calls readable ───────────

    private Map<String, Object> category(String id, String label, List<Map<String, Object>> settings) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("id", id);
        c.put("label", label);
        c.put("settings", settings);
        return c;
    }

    private Map<String, Object> setting(String name, String value, String rationale, String scalingNote) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("name", name);
        s.put("value", value);
        s.put("rationale", rationale);
        s.put("scalingNote", scalingNote);
        return s;
    }

    private Map<String, Object> scalingRow(String ram, String cpus, String sharedBuffers, String effectiveCache,
                                            String workMem, String maintenanceWorkMem, String maxConnections) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ram", ram);
        r.put("cpus", cpus);
        r.put("sharedBuffers", sharedBuffers);
        r.put("effectiveCacheSize", effectiveCache);
        r.put("workMem", workMem);
        r.put("maintenanceWorkMem", maintenanceWorkMem);
        r.put("maxConnections", maxConnections);
        return r;
    }
}
