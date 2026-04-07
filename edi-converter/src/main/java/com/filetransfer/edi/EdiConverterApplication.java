package com.filetransfer.edi;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = {"com.filetransfer.edi", "com.filetransfer.shared"})
public class EdiConverterApplication {
    public static void main(String[] args) { SpringApplication.run(EdiConverterApplication.class, args); }
}
