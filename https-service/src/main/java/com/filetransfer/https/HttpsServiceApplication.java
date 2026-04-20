package com.filetransfer.https;

import com.filetransfer.shared.config.PlatformBanner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * HTTPS listener service (port 8099 management, plus per-listener HTTPS
 * connectors on the ports configured in the ServerInstance rows).
 *
 * <p>Pattern mirrors sftp-service / ftp-service: a dynamic listener
 * registry consumes {@code ServerInstanceChangeEvent} via RabbitMQ and
 * binds / unbinds Tomcat {@code Connector}s at runtime. Each connector
 * pulls its TLS cert from Keystore Manager at bind time and applies
 * {@code SecurityProfile} allowlists via {@link
 * com.filetransfer.shared.security.SecurityProfileEnforcer}.
 *
 * <p>Upload endpoint at {@code POST /api/upload/{account}/{filename}}
 * streams bytes to storage-manager (MinIO CAS), writes a VFS entry, and
 * invokes {@code RoutingEngine.onFileUploaded} so the flow engine picks
 * up the file — same path SFTP and FTP uploads use.
 *
 * <p>R134l cosmetic: suppress UserDetailsServiceAutoConfiguration —
 * {@code InternalServiceSecurityConfig} provides our SecurityFilterChain;
 * the autoconfig's in-memory user + "generated security password"
 * warning is unused noise.
 */
@SpringBootApplication(exclude = {
    UserDetailsServiceAutoConfiguration.class
})
@ComponentScan(
    basePackages = {"com.filetransfer.https", "com.filetransfer.shared"},
    excludeFilters = @ComponentScan.Filter(
        type = FilterType.REGEX,
        pattern = "com\\.filetransfer\\.shared\\.(compliance|scheduler)\\..*"
    )
)
@EntityScan(basePackages = {
    "com.filetransfer.shared.entity.core",
    "com.filetransfer.shared.entity.transfer",
    "com.filetransfer.shared.entity.security",
    "com.filetransfer.shared.entity.integration",
    "com.filetransfer.shared.entity.vfs"
})
@EnableJpaRepositories(basePackages = {
    "com.filetransfer.shared.repository.core",
    "com.filetransfer.shared.repository.transfer",
    "com.filetransfer.shared.repository.security",
    "com.filetransfer.shared.repository.integration",
    "com.filetransfer.shared.repository.vfs"
})
@EnableScheduling
public class HttpsServiceApplication {
    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(HttpsServiceApplication.class);
        app.setBanner(new PlatformBanner());
        app.run(args);
    }
}
