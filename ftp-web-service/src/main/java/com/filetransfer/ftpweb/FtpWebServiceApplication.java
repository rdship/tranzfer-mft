package com.filetransfer.ftpweb;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication(scanBasePackages = {"com.filetransfer.ftpweb", "com.filetransfer.shared"})
@EnableJpaRepositories(basePackages = {"com.filetransfer.shared.repository"})
public class FtpWebServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(FtpWebServiceApplication.class, args);
    }
}
