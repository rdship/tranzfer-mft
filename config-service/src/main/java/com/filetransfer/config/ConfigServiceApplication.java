package com.filetransfer.config;

import org.springframework.boot.SpringApplication;
import com.filetransfer.shared.config.PlatformBanner;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = {"com.filetransfer.config", "com.filetransfer.shared"})
@EnableJpaRepositories(basePackages = "com.filetransfer.shared.repository")
@EntityScan(basePackages = "com.filetransfer.shared.entity")
@EnableCaching
@EnableScheduling
public class ConfigServiceApplication {
    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(ConfigServiceApplication.class); app.setBanner(new PlatformBanner()); app.run(args);
    }
}
