package com.filetransfer.onboarding;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication(scanBasePackages = {"com.filetransfer.onboarding", "com.filetransfer.shared"})
@EntityScan(basePackages = "com.filetransfer.shared.entity")
@EnableJpaRepositories(basePackages = "com.filetransfer.shared.repository")
public class OnboardingApiApplication {
    public static void main(String[] args) {
        SpringApplication.run(OnboardingApiApplication.class, args);
    }
}
