package com.filetransfer.encryption;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.springframework.boot.SpringApplication;
import com.filetransfer.shared.config.PlatformBanner;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import java.security.Security;

/**
 * Encryption-service: selective shared bean loading.
 *
 * <p>Excludes shared-platform packages that encryption-service doesn't need:
 * routing, vfs, compliance, scheduler, event.
 * Entity/repository scans restricted to core subpackage only.
 */
@SpringBootApplication
@ComponentScan(
    basePackages = {"com.filetransfer.encryption", "com.filetransfer.shared"},
    excludeFilters = @ComponentScan.Filter(
        type = FilterType.REGEX,
        pattern = "com\\.filetransfer\\.shared\\.(routing|vfs|compliance|scheduler|event)\\..*"
    )
)
@EntityScan(basePackages = "com.filetransfer.shared.entity.core")
@EnableJpaRepositories(basePackages = "com.filetransfer.shared.repository.core")
public class EncryptionServiceApplication {
    public static void main(String[] args) {
        Security.addProvider(new BouncyCastleProvider());
        SpringApplication app = new SpringApplication(EncryptionServiceApplication.class); app.setBanner(new PlatformBanner()); app.run(args);
    }
}
