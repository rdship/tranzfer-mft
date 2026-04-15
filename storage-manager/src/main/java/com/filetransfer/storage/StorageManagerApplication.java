package com.filetransfer.storage;

import org.springframework.boot.SpringApplication;
import com.filetransfer.shared.config.PlatformBanner;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = {"com.filetransfer.storage", "com.filetransfer.shared"})
@EntityScan(basePackages = {"com.filetransfer.shared.entity", "com.filetransfer.shared.entity.core", "com.filetransfer.shared.entity.vfs"})
@EnableJpaRepositories(basePackages = {"com.filetransfer.storage.repository", "com.filetransfer.shared.repository"})
@EnableScheduling
public class StorageManagerApplication {
    public static void main(String[] args) { SpringApplication app = new SpringApplication(StorageManagerApplication.class); app.setBanner(new PlatformBanner()); app.run(args); }
}
