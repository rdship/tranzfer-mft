package com.filetransfer.ftpweb;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = {"com.filetransfer.ftpweb", "com.filetransfer.shared"})
@EntityScan(basePackages = "com.filetransfer.shared.entity")
@EnableJpaRepositories(basePackages = {"com.filetransfer.shared.repository"})
@EnableScheduling
public class FtpWebServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(FtpWebServiceApplication.class, args);
    }
}
