package com.filetransfer.shared.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.domain.EntityScanPackages;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Enables selective JPA entity scanning per service.
 *
 * <p>When {@code platform.entity-scan.packages} is set in a service's application.yml,
 * Hibernate only validates those specific entity packages instead of all 61 entities.
 * This reduces startup time from ~90s to ~25s for services that use few entities.
 *
 * <p>Example:
 * <pre>
 * # In analytics-service/application.yml:
 * platform:
 *   entity-scan:
 *     packages: com.filetransfer.analytics.entity,com.filetransfer.shared.entity.FileTransferRecord,com.filetransfer.shared.entity.TransferAccount
 * </pre>
 *
 * <p>If the property is NOT set, the default full scan applies (backward compatible).
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "platform.entity-scan.mode", havingValue = "selective")
public class SelectiveEntityScanConfig {

    @Bean
    static BeanFactoryPostProcessor selectiveEntityScanPostProcessor(Environment env) {
        return (ConfigurableListableBeanFactory beanFactory) -> {
            String packages = env.getProperty("platform.entity-scan.packages", "");
            if (!packages.isBlank()) {
                String[] entries = packages.split(",");
                // EntityScanPackages only accepts package names, not class names.
                // If an entry looks like a class (last segment starts uppercase), extract its package.
                java.util.Set<String> resolvedPackages = new java.util.LinkedHashSet<>();
                for (String entry : entries) {
                    String trimmed = entry.trim();
                    int lastDot = trimmed.lastIndexOf('.');
                    if (lastDot > 0 && Character.isUpperCase(trimmed.charAt(lastDot + 1))) {
                        // Class name → extract package
                        resolvedPackages.add(trimmed.substring(0, lastDot));
                    } else {
                        resolvedPackages.add(trimmed);
                    }
                }
                String[] pkgs = resolvedPackages.toArray(String[]::new);
                log.info("Selective entity scan: {} package(s) from {} entries: {}",
                        pkgs.length, entries.length, String.join(", ", pkgs));
                EntityScanPackages.register(
                        (org.springframework.beans.factory.support.BeanDefinitionRegistry) beanFactory,
                        pkgs);
            }
        };
    }
}
