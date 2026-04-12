package com.filetransfer.ai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = {"com.filetransfer.ai", "com.filetransfer.shared"})
@EnableJpaRepositories(basePackages = {"com.filetransfer.ai", "com.filetransfer.shared.repository"})
@EnableScheduling
@EnableAsync
public class AiEngineApplication {
    public static void main(String[] args) {
        SpringApplication.run(AiEngineApplication.class, args);
    }
}
