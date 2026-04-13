package com.filetransfer.shared.config;

import jakarta.persistence.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypesScanner;

import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Selective JPA entity scanning with transitive association resolution.
 *
 * <p>Filters entities per service AND walks {@code @ManyToOne}, {@code @OneToMany},
 * {@code @OneToOne}, {@code @ManyToMany} associations to include all required
 * entities transitively. Prevents "targets unknown entity" Hibernate crashes.
 *
 * <p>Example: if sftp-service declares it needs {@code TransferAccount},
 * and {@code TransferAccount.user} is {@code @ManyToOne User}, then {@code User}
 * is automatically included — and so are User's associations, recursively.
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "platform.entity-scan.mode", havingValue = "selective")
public class SelectiveEntityScanConfig {

    @Bean
    @Primary
    PersistenceManagedTypes selectiveManagedTypes(Environment env, ResourceLoader resourceLoader) {
        String raw = env.getProperty("platform.entity-scan.packages", "");
        if (raw.isBlank()) {
            log.warn("platform.entity-scan.mode=selective but no packages — full scan");
            return PersistenceManagedTypes.of(List.of(), List.of());
        }

        String[] entries = raw.split(",");
        Set<String> allowedClasses = new LinkedHashSet<>();
        Set<String> scanPackages = new LinkedHashSet<>();

        for (String entry : entries) {
            String trimmed = entry.trim();
            int lastDot = trimmed.lastIndexOf('.');
            if (lastDot > 0 && Character.isUpperCase(trimmed.charAt(lastDot + 1))) {
                allowedClasses.add(trimmed);
                scanPackages.add(trimmed.substring(0, lastDot));
            } else {
                scanPackages.add(trimmed);
            }
        }

        // Always include com.filetransfer.shared.entity for dependency resolution
        scanPackages.add("com.filetransfer.shared.entity");

        // Scan all relevant packages to build the full entity index
        PersistenceManagedTypesScanner scanner = new PersistenceManagedTypesScanner(resourceLoader);
        PersistenceManagedTypes scanned = scanner.scan(scanPackages.toArray(String[]::new));
        Map<String, Class<?>> entityIndex = buildEntityIndex(scanned.getManagedClassNames());

        // If only package names (no specific classes), return all scanned entities
        if (allowedClasses.isEmpty()) {
            log.info("Selective entity scan: {} package(s), {} managed types",
                    scanPackages.size(), scanned.getManagedClassNames().size());
            return scanned;
        }

        // Build initial set: explicitly listed classes + all from service-own packages
        Set<String> seeds = new LinkedHashSet<>();
        for (String cls : scanned.getManagedClassNames()) {
            if (allowedClasses.contains(cls)) {
                seeds.add(cls);
            } else {
                // Include all entities from non-shared packages (service's own entities)
                String pkg = cls.contains(".") ? cls.substring(0, cls.lastIndexOf('.')) : "";
                if (!pkg.equals("com.filetransfer.shared.entity")) {
                    seeds.add(cls);
                }
            }
        }

        // Resolve transitive associations (graph closure)
        Set<String> resolved = resolveAssociations(seeds, entityIndex);

        log.info("Selective entity scan: {} seeds → {} entities (resolved {} associations from {} scanned)",
                seeds.size(), resolved.size(), resolved.size() - seeds.size(),
                scanned.getManagedClassNames().size());

        return PersistenceManagedTypes.of(new ArrayList<>(resolved), List.of());
    }

    /**
     * Walks @ManyToOne, @OneToMany, @OneToOne, @ManyToMany associations
     * transitively until no new entities are discovered (graph closure).
     */
    private Set<String> resolveAssociations(Set<String> seeds, Map<String, Class<?>> entityIndex) {
        Set<String> resolved = new LinkedHashSet<>(seeds);
        Queue<String> queue = new LinkedList<>(seeds);

        while (!queue.isEmpty()) {
            String className = queue.poll();
            Class<?> clazz = entityIndex.get(className);
            if (clazz == null) continue;

            // Walk the class hierarchy (entity inheritance)
            for (Class<?> current = clazz; current != null && current != Object.class; current = current.getSuperclass()) {
                for (Field field : current.getDeclaredFields()) {
                    Class<?> target = getAssociationTarget(field);
                    if (target != null) {
                        String targetName = target.getName();
                        if (entityIndex.containsKey(targetName) && resolved.add(targetName)) {
                            queue.add(targetName); // New entity found — resolve its associations too
                        }
                    }
                }
            }
        }
        return resolved;
    }

    /**
     * If a field has a JPA relationship annotation, returns the target entity class.
     * Handles both direct references and Collection&lt;Entity&gt; generics.
     */
    private Class<?> getAssociationTarget(Field field) {
        if (field.isAnnotationPresent(ManyToOne.class) || field.isAnnotationPresent(OneToOne.class)) {
            Class<?> type = field.getType();
            return type.isAnnotationPresent(Entity.class) ? type : null;
        }
        if (field.isAnnotationPresent(OneToMany.class) || field.isAnnotationPresent(ManyToMany.class)) {
            // Extract generic type: Collection<TargetEntity>
            Type generic = field.getGenericType();
            if (generic instanceof ParameterizedType pt) {
                Type[] args = pt.getActualTypeArguments();
                if (args.length > 0 && args[0] instanceof Class<?> targetClass) {
                    return targetClass.isAnnotationPresent(Entity.class) ? targetClass : null;
                }
            }
        }
        return null;
    }

    private Map<String, Class<?>> buildEntityIndex(List<String> classNames) {
        Map<String, Class<?>> index = new LinkedHashMap<>();
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        for (String name : classNames) {
            try {
                index.put(name, cl.loadClass(name));
            } catch (ClassNotFoundException e) {
                log.debug("Could not load entity class for index: {}", name);
            }
        }
        return index;
    }
}
