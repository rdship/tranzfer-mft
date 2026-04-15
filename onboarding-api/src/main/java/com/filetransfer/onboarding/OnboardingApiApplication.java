package com.filetransfer.onboarding;

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
    basePackages = {"com.filetransfer.onboarding", "com.filetransfer.shared"},
    excludeFilters = @ComponentScan.Filter(
        type = FilterType.REGEX,
        pattern = "com\\.filetransfer\\.shared\\.(connector|compliance|event)\\..*"
    )
)
@EnableJpaRepositories(basePackages = {
    "com.filetransfer.shared.repository.core",
    "com.filetransfer.shared.repository.transfer",
    "com.filetransfer.shared.repository.integration",
    "com.filetransfer.shared.repository.security",
    "com.filetransfer.shared.repository.vfs"
})
@EntityScan(basePackages = {
    "com.filetransfer.shared.entity.core",
    "com.filetransfer.shared.entity.transfer",
    "com.filetransfer.shared.entity.integration",
    "com.filetransfer.shared.entity.security",
    "com.filetransfer.shared.entity.vfs"
})
@EnableScheduling
public class OnboardingApiApplication {
    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(OnboardingApiApplication.class); app.setBanner(new PlatformBanner()); app.run(args);
    }
}
