package com.filetransfer.keystore;

import org.springframework.boot.SpringApplication;
import com.filetransfer.shared.config.PlatformBanner;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ComponentScan(
    basePackages = {"com.filetransfer.keystore", "com.filetransfer.shared"},
    excludeFilters = @ComponentScan.Filter(
        type = FilterType.REGEX,
        pattern = "com\\.filetransfer\\.shared\\.(routing|vfs|compliance|scheduler|event)\\..*"
    )
)
// R99: added shared.entity.security + shared.repository.security so
// PermissionService / PermissionAspect (scanned from shared-platform) can
// satisfy their RolePermissionRepository + UserPermissionRepository deps.
// Without this, AOT's eager bean pre-registration fails at startup.
@EntityScan(basePackages = {
    "com.filetransfer.keystore.entity",
    "com.filetransfer.shared.entity.core",
    "com.filetransfer.shared.entity.security"
})
@EnableJpaRepositories(basePackages = {
    "com.filetransfer.shared.repository.core",
    "com.filetransfer.shared.repository.security",
    "com.filetransfer.keystore.repository"
})
@EnableScheduling
public class KeystoreManagerApplication {
    public static void main(String[] args) { SpringApplication app = new SpringApplication(KeystoreManagerApplication.class); app.setBanner(new PlatformBanner()); app.run(args); }
}
