package com.filetransfer.cli;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class CliApplication {
    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(CliApplication.class); app.setBanner((env, src, out) -> { out.println("TranzFer MFT CLI v" + env.getProperty("platform.version", "UNKNOWN")); }); app.run(args);
    }
}
