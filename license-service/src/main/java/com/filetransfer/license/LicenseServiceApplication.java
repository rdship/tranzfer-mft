package com.filetransfer.license;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = {"com.filetransfer.license", "com.filetransfer.shared"})
@EnableCaching
@EnableScheduling
public class LicenseServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(LicenseServiceApplication.class, args);
    }
}
