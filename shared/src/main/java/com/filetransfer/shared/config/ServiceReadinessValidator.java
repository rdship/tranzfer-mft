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
 * Fast schema validator that ensures required tables exist on boot.
 *
 * <p>Optimized for speed: uses a single batched query to check all tables,
 * only runs schema files when tables are actually missing, and skips
 * per-column validation (Flyway guarantees column correctness).
 *
 * <p>Configuration in application.yml:
 * <pre>
 * platform:
 *   readiness:
 *     schema-file: db/schema/sftp-service-schema.sql
 *     required-tables: transfer_accounts,audit_logs,server_instances
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

    @PostConstruct
    public void validateAndRepairSchema() {
        long startMs = System.currentTimeMillis();

        if (requiredTablesConfig == null || requiredTablesConfig.isBlank()) {
            log.debug("[READINESS] {} — no required-tables configured, skipping", serviceName);
            return;
        }

        List<String> requiredTables = Arrays.stream(requiredTablesConfig.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();

        // Single query: check which required tables + infrastructure tables exist
        Set<String> existingTables = fetchRequiredTables(requiredTables);
        if (existingTables == null) {
            log.error("[READINESS] {} — Cannot reach database, skipping validation", serviceName);
            return;
        }

        int created = 0;
        int validated = 0;

        // Ensure infrastructure tables (shedlock, service_registrations) — one DDL each
        created += ensureInfrastructureTables(existingTables);

        // Check which required tables are missing
        Set<String> snapshot = existingTables;
        List<String> missingTables = requiredTables.stream()
                .filter(t -> !snapshot.contains(t.toLowerCase()))
                .toList();

        if (!missingTables.isEmpty() && schemaFile != null && !schemaFile.isBlank()) {
            log.info("[READINESS] {} — {} missing table(s): {} — running schema file",
                    serviceName, missingTables.size(), missingTables);
            created += runSchemaFile(schemaFile);
            // Re-check only the missing tables
            Set<String> refreshed = fetchRequiredTables(requiredTables);
            if (refreshed == null) return;
            missingTables = requiredTables.stream()
                    .filter(t -> !refreshed.contains(t.toLowerCase()))
                    .toList();
        }

        validated = requiredTables.size() - missingTables.size();
        long elapsed = System.currentTimeMillis() - startMs;

        if (missingTables.isEmpty()) {
            log.info("[READINESS] {} — READY ({} tables verified, {} created) [{}ms]",
                    serviceName, validated, created, elapsed);
        } else {
            log.error("[READINESS] {} — {} table(s) still MISSING after repair: {} [{}ms]",
                    serviceName, missingTables.size(), missingTables, elapsed);
        }
    }

    /**
     * Single batched query — only checks the tables we care about instead of
     * scanning the entire information_schema.tables catalog.
     */
    private Set<String> fetchRequiredTables(List<String> requiredTables) {
        try {
            // Build list of all tables to check (required + infrastructure)
            List<String> allToCheck = new ArrayList<>(requiredTables.size() + 3);
            for (String t : requiredTables) allToCheck.add(t.toLowerCase());
            allToCheck.add("shedlock");
            allToCheck.add("service_registrations");
            allToCheck.add("flyway_schema_history");

            String placeholders = allToCheck.stream().map(t -> "?").collect(Collectors.joining(","));
            String sql = "SELECT table_name FROM information_schema.tables " +
                    "WHERE table_schema = 'public' AND table_name IN (" + placeholders + ")";

            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, allToCheck.toArray());
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

    private int ensureInfrastructureTables(Set<String> existingTables) {
        int created = 0;
        if (!existingTables.contains("shedlock")) {
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
                log.debug("[READINESS] {} — shedlock creation race (harmless): {}", serviceName, e.getMessage());
            }
        }

        if (!existingTables.contains("service_registrations")) {
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
                log.debug("[READINESS] {} — service_registrations creation race (harmless): {}", serviceName, e.getMessage());
            }
        }
        return created;
    }

    private int runSchemaFile(String resourcePath) {
        try {
            ClassPathResource resource = new ClassPathResource(resourcePath);
            if (!resource.exists()) {
                log.warn("[READINESS] {} — Schema file not found: {}", serviceName, resourcePath);
                return 0;
            }
            String sql;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
                sql = reader.lines().collect(Collectors.joining("\n"));
            }

            String[] statements = sql.split(";");
            int executed = 0;
            for (String stmt : statements) {
                String trimmed = stmt.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("--")) continue;
                try {
                    jdbcTemplate.execute(trimmed);
                    executed++;
                } catch (Exception e) {
                    log.debug("[READINESS] {} — DDL race (harmless): {}", serviceName, e.getMessage());
                }
            }
            log.info("[READINESS] {} — Executed {} statements from {}", serviceName, executed, resourcePath);
            return executed;
        } catch (Exception e) {
            log.error("[READINESS] {} — Failed to run schema file {}: {}",
                    serviceName, resourcePath, e.getMessage());
            return 0;
        }
    }
}
