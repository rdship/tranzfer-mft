package com.filetransfer.notification;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = {"com.filetransfer.notification", "com.filetransfer.shared"})
@EntityScan(basePackages = {"com.filetransfer.notification.entity", "com.filetransfer.shared.entity"})
@EnableJpaRepositories(basePackages = {"com.filetransfer.notification.repository", "com.filetransfer.shared.repository"})
@EnableScheduling
public class NotificationApplication {
    public static void main(String[] args) {
        SpringApplication.run(NotificationApplication.class, args);
    }
}
