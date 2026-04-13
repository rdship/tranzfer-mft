package com.filetransfer.shared.entity.core;

import com.filetransfer.shared.entity.Auditable;

import com.filetransfer.shared.enums.Environment;
import jakarta.persistence.*;
import lombok.*;

/**
 * Database-backed platform configuration.
 * Every setting is scoped by (key, environment, service) so the same key
 * can have different values in TEST vs PROD, and per-service overrides
 * take precedence over GLOBAL defaults.
 *
 * Gartner CRUD pattern: all configuration survives crashes because it
 * lives in PostgreSQL, not YAML files or environment variables.
 */
@Entity
@Table(name = "platform_settings", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"setting_key", "environment", "service_name"})
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PlatformSetting extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private java.util.UUID id;

    /** Dot-notation key, e.g. "sftp.port", "analytics.alert-error-rate-threshold" */
    @Column(name = "setting_key", nullable = false, length = 128)
    private String settingKey;

    /** The value stored as text; parsed by consumers based on dataType */
    @Column(name = "setting_value", columnDefinition = "TEXT")
    private String settingValue;

    /** Which environment this value applies to */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    @Builder.Default
    private Environment environment = Environment.PROD;

    /**
     * Which microservice this applies to.
     * "GLOBAL" means it applies to all services unless overridden.
     */
    @Column(name = "service_name", nullable = false, length = 32)
    @Builder.Default
    private String serviceName = "GLOBAL";

    /** Data type hint for UI rendering and parsing */
    @Column(name = "data_type", nullable = false, length = 16)
    @Builder.Default
    private String dataType = "STRING";

    /** Human-readable description shown in admin UI */
    @Column(columnDefinition = "TEXT")
    private String description;

    /** Grouping category for UI (e.g. "Security", "Network", "Storage") */
    @Column(length = 64)
    private String category;

    /** If true, value is masked in UI and API responses */
    @Builder.Default
    private boolean sensitive = false;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

}
