package com.filetransfer.screening;

import org.springframework.boot.SpringApplication;
import com.filetransfer.shared.config.PlatformBanner;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = {"com.filetransfer.screening", "com.filetransfer.shared"})
@EntityScan(basePackages = {"com.filetransfer.shared.entity", "com.filetransfer.shared.entity.core", "com.filetransfer.shared.entity.security"})
@EnableJpaRepositories(basePackages = {"com.filetransfer.screening.repository", "com.filetransfer.shared.repository"})
@EnableScheduling
public class ScreeningServiceApplication {
    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(ScreeningServiceApplication.class); app.setBanner(new PlatformBanner()); app.run(args);
    }
}
