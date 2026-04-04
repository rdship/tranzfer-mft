package com.filetransfer.encryption;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import java.security.Security;

@SpringBootApplication(scanBasePackages = {"com.filetransfer.encryption", "com.filetransfer.shared"})
@EntityScan(basePackages = "com.filetransfer.shared.entity")
@EnableJpaRepositories(basePackages = "com.filetransfer.shared.repository")
public class EncryptionServiceApplication {
    public static void main(String[] args) {
        Security.addProvider(new BouncyCastleProvider());
        SpringApplication.run(EncryptionServiceApplication.class, args);
    }
}
