package com.filetransfer.shared.health;

import jakarta.persistence.EntityManager;
import jakarta.persistence.metamodel.EntityType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/**
 * Lightweight background schema health sensor.
 *
 * <p>Non-blocking: service boots and serves immediately. This sensor checks
 * entity-to-table mapping on a configurable schedule (default: every 5 min).
 * First check runs 30s after boot.
 *
 * <p>Configuration (application.yml or env var):
 * <pre>
 * platform:
 *   schema-check:
 *     enabled: true           # default true
 *     initial-delay-seconds: 30   # first check after boot
 *     interval-seconds: 300       # repeat every 5 min (configurable via Scheduler UI)
 * </pre>
 *
 * <p>Results visible at {@code /actuator/health/schema} and via REST API
 * {@code GET /api/internal/schema-health}. Logs SCHEMA_DRIFT as ERROR
 * for Platform Sentinel to detect.
 */
@Slf4j
@Component
public class SchemaHealthIndicator implements HealthIndicator {

    private final EntityManager entityManager;
    private final DataSource dataSource;

    @Value("${platform.schema-check.enabled:true}")
    private boolean enabled;

    @Value("${platform.schema-check.initial-delay-seconds:30}")
    private int initialDelaySeconds;

    @Value("${platform.schema-check.interval-seconds:300}")
    private int intervalSeconds;

    @Value("${cluster.service-type:UNKNOWN}")
    private String serviceType;

    private volatile boolean checked = false;
    private volatile boolean healthy = true;
    private volatile Instant lastChecked;
    private final Map<String, Object> findings = new ConcurrentHashMap<>();
    private ScheduledExecutorService scheduler;

    public SchemaHealthIndicator(EntityManager entityManager, DataSource dataSource) {
        this.entityManager = entityManager;
        this.dataSource = dataSource;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void startScheduledValidation() {
        if (!enabled) {
            log.info("Schema health check disabled (platform.schema-check.enabled=false)");
            checked = true;
            findings.put("status", "DISABLED");
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "schema-health");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::runValidation,
                initialDelaySeconds, intervalSeconds, TimeUnit.SECONDS);
        log.info("Schema health check scheduled: first in {}s, then every {}s",
                initialDelaySeconds, intervalSeconds);
    }

    /** Run on-demand (called by scheduler or API trigger). */
    public Map<String, Object> runValidation() {
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            Set<EntityType<?>> entities = entityManager.getMetamodel().getEntities();

            int validated = 0;
            List<String> missing = new ArrayList<>();

            for (EntityType<?> entity : entities) {
                String tableName = resolveTableName(entity);
                if (tableName == null) continue;

                if (tableExists(meta, tableName) || tableExists(meta, tableName.toLowerCase())) {
                    validated++;
                } else {
                    missing.add(entity.getName() + " → " + tableName);
                }
            }

            checked = true;
            lastChecked = Instant.now();

            if (missing.isEmpty()) {
                healthy = true;
                findings.put("status", "ALL_TABLES_PRESENT");
                findings.put("validated", validated);
                findings.put("total", entities.size());
                findings.put("lastChecked", lastChecked.toString());
                findings.put("service", serviceType);
                findings.remove("missing");
                if (log.isDebugEnabled()) {
                    log.debug("Schema check passed: {}/{} tables", validated, entities.size());
                }
            } else {
                healthy = false;
                findings.put("status", "SCHEMA_DRIFT_DETECTED");
                findings.put("validated", validated);
                findings.put("total", entities.size());
                findings.put("missingCount", missing.size());
                findings.put("missing", missing);
                findings.put("lastChecked", lastChecked.toString());
                findings.put("service", serviceType);
                log.error("SCHEMA_DRIFT [{}]: {} missing table(s): {}",
                        serviceType, missing.size(), String.join("; ", missing));
            }
        } catch (Exception e) {
            checked = true;
            lastChecked = Instant.now();
            healthy = false;
            findings.put("status", "VALIDATION_ERROR");
            findings.put("error", e.getMessage());
            findings.put("lastChecked", lastChecked.toString());
            log.error("Schema health check failed: {}", e.getMessage());
        }
        return Collections.unmodifiableMap(findings);
    }

    private boolean tableExists(DatabaseMetaData meta, String name) throws Exception {
        try (ResultSet rs = meta.getTables(null, null, name, new String[]{"TABLE"})) {
            return rs.next();
        }
    }

    private String resolveTableName(EntityType<?> entity) {
        try {
            Class<?> javaType = entity.getJavaType();
            jakarta.persistence.Table table = javaType.getAnnotation(jakarta.persistence.Table.class);
            if (table != null && !table.name().isEmpty()) {
                return table.name();
            }
            return camelToSnake(entity.getName());
        } catch (Exception e) {
            return null;
        }
    }

    private String camelToSnake(String name) {
        return name.replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase();
    }

    /** Actuator health endpoint: /actuator/health/schema */
    @Override
    public Health health() {
        if (!enabled) {
            return Health.up().withDetail("schema", "DISABLED").build();
        }
        if (!checked) {
            return Health.up()
                    .withDetail("schema", "PENDING — first check in " + initialDelaySeconds + "s")
                    .build();
        }
        Health.Builder builder = healthy ? Health.up() : Health.up(); // never DOWN — advisory only
        findings.forEach((k, v) -> builder.withDetail(k, v));
        return builder.build();
    }

    /** For REST API access and configuration updates. */
    public int getIntervalSeconds() { return intervalSeconds; }

    public void setIntervalSeconds(int seconds) {
        this.intervalSeconds = Math.max(10, seconds); // minimum 10s
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "schema-health");
                t.setDaemon(true);
                return t;
            });
            scheduler.scheduleAtFixedRate(this::runValidation, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
            log.info("Schema check interval updated to {}s", intervalSeconds);
        }
    }
}
