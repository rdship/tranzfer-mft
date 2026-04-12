package com.filetransfer.screening;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = {"com.filetransfer.screening", "com.filetransfer.shared"})
@EnableJpaRepositories(basePackages = {"com.filetransfer.screening.repository", "com.filetransfer.shared.repository"})
@EnableScheduling
public class ScreeningServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(ScreeningServiceApplication.class, args);
    }
}
