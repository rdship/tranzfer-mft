package com.filetransfer.sftp;

import org.springframework.boot.SpringApplication;
import com.filetransfer.shared.config.PlatformBanner;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = {"com.filetransfer.sftp", "com.filetransfer.shared"})
@EntityScan(basePackages = "com.filetransfer.shared.entity")
@EnableJpaRepositories(basePackages = "com.filetransfer.shared.repository")
@EnableScheduling
public class SftpServiceApplication {
    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(SftpServiceApplication.class); app.setBanner(new PlatformBanner()); app.run(args);
    }
}
