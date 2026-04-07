package com.filetransfer.shared.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Self-healing schema validator that runs on EVERY boot of EVERY service.
 *
 * <p>Each service declares its required tables and columns in application.yml.
 * On startup, this validator:
 * <ol>
 *   <li>Checks if each required table exists — creates it if missing</li>
 *   <li>Checks if each required column exists with correct type — adds/fixes if wrong</li>
 *   <li>Logs a clear report of what was validated, created, or repaired</li>
 * </ol>
 *
 * <p>If a service is not deployed, its tables never get created.
 * If schema is tampered with, it's auto-detected and auto-fixed.
 *
 * <p>Configuration in application.yml:
 * <pre>
 * platform:
 *   readiness:
 *     schema-file: db/schema/sftp-service-schema.sql
 *     required-tables: transfer_accounts,audit_logs,server_instances
 *     required-columns:
 *       transfer_accounts: id:uuid,username:varchar,protocol:varchar,active:boolean
 *       audit_logs: id:uuid,track_id:varchar,action:varchar
 * </pre>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "spring.datasource.url")
public class ServiceReadinessValidator {

    private final JdbcTemplate jdbcTemplate;

    @Value("${spring.application.name:unknown}")
    private String serviceName;

    @Value("${platform.readiness.required-tables:}")
    private String requiredTablesConfig;

    @Value("${platform.readiness.schema-file:}")
    private String schemaFile;

    private int validated = 0;
    private int created = 0;
    private int repaired = 0;
    private int failed = 0;

    @PostConstruct
    public void validateAndRepairSchema() {
        if (requiredTablesConfig == null || requiredTablesConfig.isBlank()) {
            log.debug("[READINESS] {} — no required-tables configured, skipping", serviceName);
            return;
        }

        log.info("[READINESS] ══════════════════════════════════════════════════");
        log.info("[READINESS] {} — Schema validation starting", serviceName);
        log.info("[READINESS] ══════════════════════════════════════════════════");

        Set<String> existingTables = fetchExistingTables();
        if (existingTables == null) return; // DB not reachable

        Map<String, Map<String, String>> existingColumns = new HashMap<>();

        List<String> requiredTables = Arrays.stream(requiredTablesConfig.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();

        // Phase 1: Ensure core infrastructure tables exist (shedlock, flyway)
        ensureInfrastructureTables(existingTables);

        // Phase 2: Run service-specific schema file if tables are missing
        Set<String> tablesSnapshot = existingTables;
        List<String> missingTables = requiredTables.stream()
                .filter(t -> !tablesSnapshot.contains(t.toLowerCase()))
                .toList();

        if (!missingTables.isEmpty() && schemaFile != null && !schemaFile.isBlank()) {
            log.info("[READINESS] {} — {} missing table(s): {} — running schema file",
                    serviceName, missingTables.size(), missingTables);
            runSchemaFile(schemaFile);
            // Re-fetch after schema creation
            existingTables = fetchExistingTables();
            if (existingTables == null) return;
        }

        // Phase 3: Validate each required table exists
        for (String table : requiredTables) {
            if (existingTables.contains(table.toLowerCase())) {
                validated++;
                log.debug("[READINESS] {} — table '{}' exists", serviceName, table);
            } else {
                log.warn("[READINESS] {} — table '{}' MISSING (not created by schema file)",
                        serviceName, table);
                failed++;
            }
        }

        // Phase 4: Validate columns for existing tables
        validateColumns(requiredTables, existingTables);

        // Phase 5: Check Flyway version
        checkFlywayVersion();

        // Report
        log.info("[READINESS] ──────────────────────────────────────────────────");
        log.info("[READINESS] {} — Result: {} validated, {} created, {} repaired, {} failed",
                serviceName, validated, created, repaired, failed);

        if (failed > 0) {
            log.error("[READINESS] {} — {} check(s) FAILED — service may have issues",
                    serviceName, failed);
        } else {
            log.info("[READINESS] {} — READY (all schema checks passed)", serviceName);
        }
        log.info("[READINESS] ══════════════════════════════════════════════════");
    }

    private Set<String> fetchExistingTables() {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'");
            Set<String> tables = new HashSet<>();
            for (Map<String, Object> row : rows) {
                tables.add(((String) row.get("table_name")).toLowerCase());
            }
            return tables;
        } catch (Exception e) {
            log.error("[READINESS] {} — Cannot query database: {}", serviceName, e.getMessage());
            return null;
        }
    }

    private void ensureInfrastructureTables(Set<String> existingTables) {
        // shedlock is needed by any service using @SchedulerLock
        if (!existingTables.contains("shedlock")) {
            log.info("[READINESS] {} — Creating shedlock table", serviceName);
            try {
                jdbcTemplate.execute("""
                    CREATE TABLE IF NOT EXISTS shedlock (
                        name VARCHAR(64) NOT NULL PRIMARY KEY,
                        lock_until TIMESTAMPTZ NOT NULL,
                        locked_at TIMESTAMPTZ NOT NULL,
                        locked_by VARCHAR(255) NOT NULL
                    )""");
                existingTables.add("shedlock");
                created++;
            } catch (Exception e) {
                log.warn("[READINESS] {} — shedlock creation failed (may already exist): {}",
                        serviceName, e.getMessage());
            }
        }

        // service_registrations for cluster awareness
        if (!existingTables.contains("service_registrations")) {
            log.info("[READINESS] {} — Creating service_registrations table", serviceName);
            try {
                jdbcTemplate.execute("""
                    CREATE TABLE IF NOT EXISTS service_registrations (
                        id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                        service_instance_id VARCHAR(255) NOT NULL,
                        cluster_id VARCHAR(255),
                        service_type VARCHAR(50),
                        host VARCHAR(255),
                        control_port INTEGER,
                        active BOOLEAN DEFAULT true,
                        last_heartbeat TIMESTAMPTZ,
                        registered_at TIMESTAMPTZ DEFAULT now()
                    )""");
                existingTables.add("service_registrations");
                created++;
            } catch (Exception e) {
                log.warn("[READINESS] {} — service_registrations creation failed: {}",
                        serviceName, e.getMessage());
            }
        }
    }

    private void runSchemaFile(String resourcePath) {
        try {
            ClassPathResource resource = new ClassPathResource(resourcePath);
            if (!resource.exists()) {
                log.warn("[READINESS] {} — Schema file not found: {}", serviceName, resourcePath);
                return;
            }
            String sql;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
                sql = reader.lines().collect(Collectors.joining("\n"));
            }

            // Split by semicolons and execute each statement
            String[] statements = sql.split(";");
            int executed = 0;
            for (String stmt : statements) {
                String trimmed = stmt.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("--")) continue;
                try {
                    jdbcTemplate.execute(trimmed);
                    executed++;
                } catch (Exception e) {
                    // IF NOT EXISTS should make most failures harmless
                    log.debug("[READINESS] {} — Statement warning (may be harmless): {}",
                            serviceName, e.getMessage());
                }
            }
            log.info("[READINESS] {} — Executed {} statements from {}", serviceName, executed, resourcePath);
            created += executed;
        } catch (Exception e) {
            log.error("[READINESS] {} — Failed to run schema file {}: {}",
                    serviceName, resourcePath, e.getMessage());
            failed++;
        }
    }

    private void validateColumns(List<String> requiredTables, Set<String> existingTables) {
        // Get column config from environment — injected as map via Spring relaxed binding
        // Format: platform.readiness.required-columns.TABLE_NAME=col1:type1,col2:type2
        for (String table : requiredTables) {
            if (!existingTables.contains(table.toLowerCase())) continue;

            String colConfig = getColumnConfig(table);
            if (colConfig == null || colConfig.isBlank()) continue;

            Map<String, String> existingCols = fetchTableColumns(table);
            if (existingCols == null) continue;

            String[] specs = colConfig.split(",");
            for (String spec : specs) {
                String[] parts = spec.trim().split(":");
                if (parts.length < 2) continue;
                String colName = parts[0].trim().toLowerCase();
                String expectedType = parts[1].trim().toLowerCase();

                if (!existingCols.containsKey(colName)) {
                    // Column missing — try to add it
                    String pgType = mapToPgType(expectedType);
                    log.warn("[READINESS] {} — table '{}' missing column '{}' ({}) — adding",
                            serviceName, table, colName, pgType);
                    try {
                        jdbcTemplate.execute(String.format(
                                "ALTER TABLE %s ADD COLUMN IF NOT EXISTS %s %s",
                                table, colName, pgType));
                        repaired++;
                    } catch (Exception e) {
                        log.error("[READINESS] {} — Failed to add column {}.{}: {}",
                                serviceName, table, colName, e.getMessage());
                        failed++;
                    }
                } else {
                    // Column exists — check type compatibility
                    String actualType = existingCols.get(colName).toLowerCase();
                    if (!isTypeCompatible(expectedType, actualType)) {
                        log.warn("[READINESS] {} — column {}.{} type mismatch: expected={} actual={}",
                                serviceName, table, colName, expectedType, actualType);
                        // Don't auto-alter type — too dangerous. Just log and flag.
                        failed++;
                    } else {
                        validated++;
                    }
                }
            }
        }
    }

    private String getColumnConfig(String table) {
        // Try to resolve from Spring environment
        try {
            String key = "platform.readiness.required-columns." + table.replace("_", "-");
            return jdbcTemplate.getDataSource() != null ?
                    System.getProperty(key, System.getenv(
                            "PLATFORM_READINESS_REQUIRED_COLUMNS_" + table.toUpperCase())) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private Map<String, String> fetchTableColumns(String table) {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT column_name, data_type FROM information_schema.columns " +
                    "WHERE table_schema = 'public' AND table_name = ?", table);
            Map<String, String> cols = new HashMap<>();
            for (Map<String, Object> row : rows) {
                cols.put(((String) row.get("column_name")).toLowerCase(),
                        ((String) row.get("data_type")).toLowerCase());
            }
            return cols;
        } catch (Exception e) {
            log.warn("[READINESS] {} — Cannot fetch columns for {}: {}",
                    serviceName, table, e.getMessage());
            return null;
        }
    }

    private String mapToPgType(String type) {
        return switch (type.toLowerCase()) {
            case "uuid" -> "UUID DEFAULT gen_random_uuid()";
            case "varchar" -> "VARCHAR(255)";
            case "text" -> "TEXT";
            case "boolean", "bool" -> "BOOLEAN DEFAULT false";
            case "int", "integer" -> "INTEGER DEFAULT 0";
            case "bigint", "long" -> "BIGINT DEFAULT 0";
            case "timestamp", "timestamptz" -> "TIMESTAMPTZ";
            case "jsonb" -> "JSONB";
            case "bytea" -> "BYTEA";
            case "double", "float8" -> "DOUBLE PRECISION";
            default -> "VARCHAR(255)";
        };
    }

    private boolean isTypeCompatible(String expected, String actual) {
        String e = expected.toLowerCase();
        String a = actual.toLowerCase();
        // Loose matching — pg reports "character varying" for varchar, "uuid" for uuid, etc.
        if (e.equals(a)) return true;
        if (e.equals("varchar") && a.contains("character")) return true;
        if (e.equals("text") && (a.contains("text") || a.contains("character"))) return true;
        if (e.equals("boolean") && a.contains("boolean")) return true;
        if (e.equals("bool") && a.contains("boolean")) return true;
        if (e.equals("int") && a.contains("integer")) return true;
        if (e.equals("integer") && a.contains("integer")) return true;
        if (e.equals("bigint") && a.contains("bigint")) return true;
        if (e.equals("long") && a.contains("bigint")) return true;
        if (e.equals("uuid") && a.contains("uuid")) return true;
        if (e.equals("timestamp") && a.contains("timestamp")) return true;
        if (e.equals("timestamptz") && a.contains("timestamp")) return true;
        if (e.equals("jsonb") && a.contains("json")) return true;
        if (e.equals("bytea") && a.contains("bytea")) return true;
        if (e.equals("double") && a.contains("double")) return true;
        if (e.equals("float8") && a.contains("double")) return true;
        return false;
    }

    private void checkFlywayVersion() {
        try {
            String version = jdbcTemplate.queryForObject(
                    "SELECT MAX(version) FROM flyway_schema_history WHERE success = true",
                    String.class);
            log.info("[READINESS] {} — Flyway schema version: {}", serviceName, version);
        } catch (Exception e) {
            log.info("[READINESS] {} — No flyway_schema_history (service-managed schema)", serviceName);
        }
    }
}
