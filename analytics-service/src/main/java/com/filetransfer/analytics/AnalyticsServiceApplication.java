package com.filetransfer.analytics;

import org.springframework.boot.SpringApplication;
import com.filetransfer.shared.config.PlatformBanner;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ComponentScan(
    basePackages = {"com.filetransfer.analytics", "com.filetransfer.shared"},
    excludeFilters = @ComponentScan.Filter(
        type = FilterType.REGEX,
        pattern = "com\\.filetransfer\\.shared\\.(routing|vfs|fabric|cache|connector|compliance|scheduler|event|matching|flow)\\..*"
    )
)
@EntityScan(basePackages = {
    "com.filetransfer.shared.entity.core",
    "com.filetransfer.shared.entity.transfer"
})
@EnableJpaRepositories(basePackages = {
    "com.filetransfer.analytics.repository",
    "com.filetransfer.shared.repository.core",
    "com.filetransfer.shared.repository.transfer"
})
@EnableCaching
@EnableScheduling
public class AnalyticsServiceApplication {
    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(AnalyticsServiceApplication.class); app.setBanner(new PlatformBanner()); app.run(args);
    }
}
