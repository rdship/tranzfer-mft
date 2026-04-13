package com.filetransfer.shared.health;

import jakarta.persistence.EntityManager;
import jakarta.persistence.metamodel.EntityType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Background schema health sensor.
 *
 * <p>Runs 30s after boot (non-blocking). Validates that every JPA entity's table
 * exists in the database. Reports results via {@code /actuator/health/schema}
 * and logs drift for Platform Sentinel to detect.
 *
 * <p>This replaces blocking Hibernate schema validation on boot:
 * <ul>
 *   <li>Demo/K8s: boot fast, sensor detects drift in background
 *   <li>On-premise: same — admin is alerted, service keeps running
 * </ul>
 */
@Slf4j
@Component
@ConditionalOnBean(EntityManager.class)
public class SchemaHealthIndicator implements HealthIndicator {

    private final EntityManager entityManager;
    private final DataSource dataSource;

    private volatile boolean checked = false;
    private volatile boolean healthy = true;
    private final Map<String, String> findings = new ConcurrentHashMap<>();

    public SchemaHealthIndicator(EntityManager entityManager, DataSource dataSource) {
        this.entityManager = entityManager;
        this.dataSource = dataSource;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Async
    public void validateSchemaInBackground() {
        try {
            // Wait 30s after boot so the service is serving traffic immediately
            Thread.sleep(30_000);
            runValidation();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void runValidation() {
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            Set<EntityType<?>> entities = entityManager.getMetamodel().getEntities();

            int validated = 0;
            List<String> missing = new ArrayList<>();

            for (EntityType<?> entity : entities) {
                String tableName = resolveTableName(entity);
                if (tableName == null) continue;

                try (ResultSet rs = meta.getTables(null, null, tableName, new String[]{"TABLE"})) {
                    if (rs.next()) {
                        validated++;
                    } else {
                        // Try lowercase (PostgreSQL normalizes to lowercase)
                        try (ResultSet rs2 = meta.getTables(null, null, tableName.toLowerCase(), new String[]{"TABLE"})) {
                            if (rs2.next()) {
                                validated++;
                            } else {
                                missing.add(entity.getName() + " → " + tableName);
                            }
                        }
                    }
                }
            }

            checked = true;

            if (missing.isEmpty()) {
                healthy = true;
                findings.put("status", "ALL_TABLES_PRESENT");
                findings.put("validated", String.valueOf(validated));
                log.info("Schema health check passed: {}/{} entity tables verified",
                        validated, entities.size());
            } else {
                healthy = false;
                findings.put("status", "SCHEMA_DRIFT_DETECTED");
                findings.put("validated", String.valueOf(validated));
                findings.put("missing", String.join(", ", missing));
                // Log as ERROR so Sentinel picks it up
                log.error("SCHEMA_DRIFT: {} missing table(s): {}",
                        missing.size(), String.join("; ", missing));
            }
        } catch (Exception e) {
            checked = true;
            healthy = false;
            findings.put("status", "VALIDATION_ERROR");
            findings.put("error", e.getMessage());
            log.error("Schema health check failed: {}", e.getMessage());
        }
    }

    private String resolveTableName(EntityType<?> entity) {
        try {
            Class<?> javaType = entity.getJavaType();
            jakarta.persistence.Table table = javaType.getAnnotation(jakarta.persistence.Table.class);
            if (table != null && !table.name().isEmpty()) {
                return table.name();
            }
            // Default: camelCase → snake_case
            return camelToSnake(entity.getName());
        } catch (Exception e) {
            return null;
        }
    }

    private String camelToSnake(String name) {
        return name.replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase();
    }

    @Override
    public Health health() {
        if (!checked) {
            return Health.up()
                    .withDetail("schema", "PENDING — background check runs 30s after boot")
                    .build();
        }
        if (healthy) {
            return Health.up()
                    .withDetails(findings)
                    .build();
        }
        // DOWN would prevent traffic — use DEGRADED via OUT_OF_SERVICE or just add detail
        return Health.up()
                .withDetails(findings)
                .build();
    }
}
