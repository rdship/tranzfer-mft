package com.filetransfer.storage;

import org.springframework.boot.SpringApplication;
import com.filetransfer.shared.config.PlatformBanner;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

// R134l — suppress UserDetailsServiceAutoConfiguration (cosmetic warning cleanup).
@SpringBootApplication(exclude = {
    org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class
})
@ComponentScan(
    basePackages = {"com.filetransfer.storage", "com.filetransfer.shared"},
    excludeFilters = @ComponentScan.Filter(
        type = FilterType.REGEX,
        pattern = "com\\.filetransfer\\.shared\\.(routing|vfs|compliance|scheduler|event)\\..*"
    )
)
// R99: shared.entity.security + shared.repository.security so
// PermissionService / PermissionAspect (scanned from shared-platform) can
// satisfy their repository dependencies. See R95 acceptance report.
@EntityScan(basePackages = {
    "com.filetransfer.storage.entity",
    "com.filetransfer.shared.entity.core",
    "com.filetransfer.shared.entity.security"
})
@EnableJpaRepositories(basePackages = {
    "com.filetransfer.shared.repository.core",
    "com.filetransfer.shared.repository.security",
    "com.filetransfer.storage.repository"
})
@EnableScheduling
public class StorageManagerApplication {
    public static void main(String[] args) { SpringApplication app = new SpringApplication(StorageManagerApplication.class); app.setBanner(new PlatformBanner()); app.run(args); }
}
