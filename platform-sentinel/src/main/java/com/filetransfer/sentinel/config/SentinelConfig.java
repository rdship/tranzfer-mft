package com.filetransfer.sentinel.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

@Configuration
@ConfigurationProperties(prefix = "sentinel")
@Getter @Setter
public class SentinelConfig {

    private GitHub github = new GitHub();
    private Map<String, String> services = Map.of();

    @Getter @Setter
    public static class GitHub {
        private boolean enabled = false;
        private String token;
        private String owner;
        private String repo;
    }
}
