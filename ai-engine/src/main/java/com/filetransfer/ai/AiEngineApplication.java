package com.filetransfer.ai;

import org.springframework.boot.SpringApplication;
import com.filetransfer.shared.config.PlatformBanner;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = {"com.filetransfer.ai", "com.filetransfer.shared"})
@EntityScan(basePackages = {"com.filetransfer.shared.entity", "com.filetransfer.shared.entity.core", "com.filetransfer.shared.entity.transfer"})
@EnableJpaRepositories(basePackages = {"com.filetransfer.ai", "com.filetransfer.shared.repository"})
@EnableScheduling
@EnableAsync
public class AiEngineApplication {
    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(AiEngineApplication.class); app.setBanner(new PlatformBanner()); app.run(args);
    }
}
