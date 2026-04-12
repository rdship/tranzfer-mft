package com.filetransfer.as2;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication(scanBasePackages = {"com.filetransfer.as2", "com.filetransfer.shared"})
@EnableJpaRepositories(basePackages = "com.filetransfer.shared.repository")
public class As2ServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(As2ServiceApplication.class, args);
    }
}
