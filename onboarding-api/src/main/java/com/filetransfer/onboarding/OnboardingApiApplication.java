package com.filetransfer.onboarding;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = {"com.filetransfer.onboarding", "com.filetransfer.shared"})
@EnableJpaRepositories(basePackages = "com.filetransfer.shared.repository")
@EntityScan(basePackages = "com.filetransfer.shared.entity")
@EnableScheduling
public class OnboardingApiApplication {
    public static void main(String[] args) {
        SpringApplication.run(OnboardingApiApplication.class, args);
    }
}
