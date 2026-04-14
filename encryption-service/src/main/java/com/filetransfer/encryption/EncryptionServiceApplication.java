package com.filetransfer.encryption;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import java.security.Security;

/**
 * Encryption-service: selective shared bean loading.
 *
 * <p>Excludes heavy shared-platform packages that encryption-service doesn't need:
 * routing (RoutingEngine), vfs (VirtualFileSystem), fabric (Kafka consumers),
 * cache (PartnerCache, batch writers), matching initializer, connector, compliance,
 * scheduler. These packages inject 29+ repositories and pull in entities from all
 * subpackages — none of which encryption-service uses.
 *
 * <p>Entity scan kept broad for now — restricting to core/ requires also restricting
 * @EnableJpaRepositories (repo proxy creation validates entity types). That's phase 2.
 */
@SpringBootApplication
@ComponentScan(
    basePackages = {"com.filetransfer.encryption", "com.filetransfer.shared"},
    excludeFilters = @ComponentScan.Filter(
        type = FilterType.REGEX,
        pattern = "com\\.filetransfer\\.shared\\.(routing|vfs|fabric|cache|connector|compliance|scheduler|event)\\..*"
    )
)
@EntityScan(basePackages = "com.filetransfer.shared.entity")
@EnableJpaRepositories(basePackages = "com.filetransfer.shared.repository")
public class EncryptionServiceApplication {
    public static void main(String[] args) {
        Security.addProvider(new BouncyCastleProvider());
        SpringApplication.run(EncryptionServiceApplication.class, args);
    }
}
