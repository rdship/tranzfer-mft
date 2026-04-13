package com.filetransfer.shared.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.domain.EntityScanPackages;
import org.springframework.boot.orm.jpa.EntityManagerFactoryBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypesScanner;

import org.springframework.context.ResourceLoaderAware;
import org.springframework.core.io.ResourceLoader;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Enables selective JPA entity scanning per service.
 *
 * <p>When {@code platform.entity-scan.packages} is set, this config ensures
 * Hibernate only manages and validates those specific entities — not all 61
 * from shared-platform. This keeps schema validation (catches real bugs)
 * while cutting boot time from ~5min to ~10-30s.
 *
 * <p>Supports both package names AND fully-qualified class names:
 * <pre>
 * platform:
 *   entity-scan:
 *     mode: selective
 *     packages: com.filetransfer.storage.entity,com.filetransfer.shared.entity.FileTransferRecord
 * </pre>
 *
 * <p>Package names → all @Entity classes in that package are included.
 * Class names → only that specific entity (its package is scanned, others filtered out).
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "platform.entity-scan.mode", havingValue = "selective")
public class SelectiveEntityScanConfig {

    /**
     * Provides a filtered PersistenceManagedTypes that only includes entities
     * listed in the config. Hibernate validates these against the DB schema
     * on boot — still catches missing columns/tables, but only for entities
     * this service actually uses.
     */
    @Bean
    @Primary
    PersistenceManagedTypes selectiveManagedTypes(Environment env, ResourceLoader resourceLoader) {
        String raw = env.getProperty("platform.entity-scan.packages", "");
        if (raw.isBlank()) {
            log.warn("platform.entity-scan.mode=selective but no packages configured — full scan");
            return PersistenceManagedTypes.of(List.of(), List.of());
        }

        String[] entries = raw.split(",");
        Set<String> allowedClasses = new LinkedHashSet<>();
        Set<String> scanPackages = new LinkedHashSet<>();

        for (String entry : entries) {
            String trimmed = entry.trim();
            int lastDot = trimmed.lastIndexOf('.');
            if (lastDot > 0 && Character.isUpperCase(trimmed.charAt(lastDot + 1))) {
                // Fully-qualified class name → allow only this class, scan its package
                allowedClasses.add(trimmed);
                scanPackages.add(trimmed.substring(0, lastDot));
            } else {
                // Package name → allow ALL classes in this package
                scanPackages.add(trimmed);
            }
        }

        // Scan the resolved packages to discover @Entity classes
        PersistenceManagedTypesScanner scanner = new PersistenceManagedTypesScanner(resourceLoader);
        PersistenceManagedTypes scanned = scanner.scan(scanPackages.toArray(String[]::new));

        // If only packages were listed (no specific classes), return everything found
        if (allowedClasses.isEmpty()) {
            log.info("Selective entity scan: {} package(s), {} managed types",
                    scanPackages.size(), scanned.getManagedClassNames().size());
            return scanned;
        }

        // Filter: keep classes that are either explicitly listed OR from a non-shared package
        // (service-own packages like com.filetransfer.storage.entity keep all their entities)
        Set<String> classPackages = allowedClasses.stream()
                .map(c -> c.substring(0, c.lastIndexOf('.')))
                .collect(Collectors.toSet());

        List<String> filtered = scanned.getManagedClassNames().stream()
                .filter(cls -> {
                    if (allowedClasses.contains(cls)) return true;
                    // Keep all entities from service-own packages (not shared.entity)
                    String pkg = cls.substring(0, cls.lastIndexOf('.'));
                    return !pkg.equals("com.filetransfer.shared.entity")
                            || classPackages.stream().noneMatch(p -> p.equals("com.filetransfer.shared.entity"));
                })
                .toList();

        log.info("Selective entity scan: {} entities from {} entries (scanned {} packages, filtered from {})",
                filtered.size(), entries.length, scanPackages.size(), scanned.getManagedClassNames().size());

        return PersistenceManagedTypes.of(filtered, List.of());
    }
}
