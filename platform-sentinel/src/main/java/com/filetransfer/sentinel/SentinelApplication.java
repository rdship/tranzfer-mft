package com.filetransfer.sentinel;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = {"com.filetransfer.sentinel", "com.filetransfer.shared"})
@EnableJpaRepositories(basePackages = {"com.filetransfer.sentinel.repository", "com.filetransfer.shared.repository"})
@EnableCaching
@EnableScheduling
public class SentinelApplication {
    public static void main(String[] args) {
        SpringApplication.run(SentinelApplication.class, args);
    }
}
