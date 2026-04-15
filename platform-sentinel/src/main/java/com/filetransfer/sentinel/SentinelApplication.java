package com.filetransfer.sentinel;

import org.springframework.boot.SpringApplication;
import com.filetransfer.shared.config.PlatformBanner;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = {"com.filetransfer.sentinel", "com.filetransfer.shared"})
@EntityScan(basePackages = {"com.filetransfer.shared.entity", "com.filetransfer.shared.entity.core", "com.filetransfer.shared.entity.transfer", "com.filetransfer.shared.entity.security"})
@EnableJpaRepositories(basePackages = {"com.filetransfer.sentinel.repository", "com.filetransfer.shared.repository"})
@EnableCaching
@EnableScheduling
public class SentinelApplication {
    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(SentinelApplication.class); app.setBanner(new PlatformBanner()); app.run(args);
    }
}
