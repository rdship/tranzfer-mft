package com.filetransfer.shared.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * Enables JPA auditing for @CreatedDate, @LastModifiedDate, @CreatedBy, @LastModifiedBy.
 * Works with the Auditable base entity and PlatformAuditorAware.
 */
@Configuration
@EnableJpaAuditing(auditorAwareRef = "auditorAware")
public class JpaAuditingConfig {
}
