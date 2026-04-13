package com.filetransfer.license;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = {"com.filetransfer.license", "com.filetransfer.shared"})
@EntityScan(basePackages = {"com.filetransfer.shared.entity", "com.filetransfer.shared.entity.core"})
@EnableJpaRepositories(basePackages = {"com.filetransfer.license", "com.filetransfer.shared.repository"})
@EnableCaching
@EnableScheduling
public class LicenseServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(LicenseServiceApplication.class, args);
    }
}
