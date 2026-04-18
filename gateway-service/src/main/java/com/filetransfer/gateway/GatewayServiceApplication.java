package com.filetransfer.gateway;

import org.springframework.boot.SpringApplication;
import com.filetransfer.shared.config.PlatformBanner;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ComponentScan(
    basePackages = {"com.filetransfer.gateway", "com.filetransfer.shared"},
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
// R102: see SharedConfig @EnableAsync comment — forces CGLIB proxies so
// AOT proxy generation doesn't fall back to JDK dynamic proxy on beans
// without declared interfaces.
@EnableAsync(proxyTargetClass = true)
@EnableJpaRepositories(basePackages = {
    "com.filetransfer.shared.repository.core",
    "com.filetransfer.shared.repository.transfer",
    "com.filetransfer.shared.repository.security",
    "com.filetransfer.shared.repository.integration",
    "com.filetransfer.shared.repository.vfs"
})
@EnableScheduling
public class GatewayServiceApplication {
    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(GatewayServiceApplication.class); app.setBanner(new PlatformBanner()); app.run(args);
    }
}
