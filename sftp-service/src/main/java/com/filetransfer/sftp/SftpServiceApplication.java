package com.filetransfer.sftp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication(scanBasePackages = {"com.filetransfer.sftp", "com.filetransfer.shared"})
@EnableJpaRepositories(basePackages = "com.filetransfer.shared.repository")
public class SftpServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(SftpServiceApplication.class, args);
    }
}
