package com.filetransfer.ftp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication(scanBasePackages = {"com.filetransfer.ftp", "com.filetransfer.shared"})
@EnableJpaRepositories(basePackages = "com.filetransfer.shared.repository")
public class FtpServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(FtpServiceApplication.class, args);
    }
}
