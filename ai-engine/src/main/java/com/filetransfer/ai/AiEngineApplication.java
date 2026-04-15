package com.filetransfer.ai;

import org.springframework.boot.SpringApplication;
import com.filetransfer.shared.config.PlatformBanner;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = {"com.filetransfer.ai", "com.filetransfer.shared"})
@EntityScan(basePackages = {
    "com.filetransfer.ai.entity",
    "com.filetransfer.shared.entity.core",
    "com.filetransfer.shared.entity.transfer",
    "com.filetransfer.shared.entity.integration",
    "com.filetransfer.shared.entity.security",
    "com.filetransfer.shared.entity.vfs"
})
@EnableJpaRepositories(basePackages = {
    "com.filetransfer.ai.repository",
    "com.filetransfer.shared.repository.core",
    "com.filetransfer.shared.repository.transfer",
    "com.filetransfer.shared.repository.integration",
    "com.filetransfer.shared.repository.security",
    "com.filetransfer.shared.repository.vfs"
})
@EnableScheduling
@EnableAsync
public class AiEngineApplication {
    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(AiEngineApplication.class); app.setBanner(new PlatformBanner()); app.run(args);
    }
}
