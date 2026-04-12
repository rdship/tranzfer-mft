package com.filetransfer.storage;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = {"com.filetransfer.storage", "com.filetransfer.shared"})
@EnableJpaRepositories(basePackages = {"com.filetransfer.storage.repository", "com.filetransfer.shared.repository"})
@EnableScheduling
public class StorageManagerApplication {
    public static void main(String[] args) { SpringApplication.run(StorageManagerApplication.class, args); }
}
