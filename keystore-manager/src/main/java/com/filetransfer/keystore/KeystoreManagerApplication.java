package com.filetransfer.keystore;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class KeystoreManagerApplication {
    public static void main(String[] args) { SpringApplication.run(KeystoreManagerApplication.class, args); }
}
