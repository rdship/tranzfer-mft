package com.filetransfer.keystore;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = {"com.filetransfer.keystore", "com.filetransfer.shared"})
@EnableJpaRepositories(basePackages = {"com.filetransfer.keystore", "com.filetransfer.shared.repository"})
@EnableScheduling
public class KeystoreManagerApplication {
    public static void main(String[] args) { SpringApplication.run(KeystoreManagerApplication.class, args); }
}
