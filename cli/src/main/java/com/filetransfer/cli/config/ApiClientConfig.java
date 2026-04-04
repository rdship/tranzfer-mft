package com.filetransfer.cli.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "api")
public class ApiClientConfig {

    private String baseUrl = "http://localhost:8080";
    private String configUrl = "http://localhost:8084";
    private String gatewayUrl = "http://localhost:8085";
    private String encryptionUrl = "http://localhost:8086";
    private String forwarderUrl = "http://localhost:8087";
    private String dmzUrl = "http://localhost:8088";

    /** Stores the JWT token after login */
    private String authToken;

    @Bean public WebClient onboardingClient() { return WebClient.builder().baseUrl(baseUrl).build(); }
    @Bean public WebClient configClient()     { return WebClient.builder().baseUrl(configUrl).build(); }
    @Bean public WebClient gatewayClient()    { return WebClient.builder().baseUrl(gatewayUrl).build(); }
    @Bean public WebClient encryptionClient() { return WebClient.builder().baseUrl(encryptionUrl).build(); }
    @Bean public WebClient forwarderClient()  { return WebClient.builder().baseUrl(forwarderUrl).build(); }
    @Bean public WebClient dmzClient()        { return WebClient.builder().baseUrl(dmzUrl).build(); }
}
