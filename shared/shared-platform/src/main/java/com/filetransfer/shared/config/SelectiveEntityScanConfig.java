package com.filetransfer.shared.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;

/**
 * Selective JPA entity scanning — dormant until platform.entity-scan.mode=selective.
 *
 * <p>Currently unused. All services use @EntityScan on their Application class
 * with full com.filetransfer.shared.entity package. Boot speed achieved via
 * hibernate.boot.allow_jdbc_metadata_access=false in docker-compose.
 *
 * <p>Future: enable per-service entity sub-packages for even faster boot.
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "platform.entity-scan.mode", havingValue = "selective")
public class SelectiveEntityScanConfig {
    // Dormant — activate by setting platform.entity-scan.mode=selective in application.yml
}
