package com.filetransfer.cli.client;

import com.filetransfer.cli.config.ApiClientConfig;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.Map;

/**
 * Thin HTTP client that all CLI commands use.
 * Automatically attaches the Bearer token after login.
 */
@Component
@RequiredArgsConstructor
public class ApiClient {

    private final ApiClientConfig config;
    private final WebClient onboardingClient;
    private final WebClient configClient;
    private final WebClient forwarderClient;
    private final WebClient dmzClient;

    public String post(WebClient client, String path, Object body) {
        try {
            return client.post().uri(path)
                    .header("Authorization", "Bearer " + config.getAuthToken())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
        } catch (WebClientResponseException e) {
            return "ERROR " + e.getStatusCode() + ": " + e.getResponseBodyAsString();
        }
    }

    public String get(WebClient client, String path) {
        try {
            return client.get().uri(path)
                    .header("Authorization", "Bearer " + config.getAuthToken())
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
        } catch (WebClientResponseException e) {
            return "ERROR " + e.getStatusCode() + ": " + e.getResponseBodyAsString();
        }
    }

    public String delete(WebClient client, String path) {
        try {
            client.delete().uri(path)
                    .header("Authorization", "Bearer " + config.getAuthToken())
                    .retrieve()
                    .bodyToMono(Void.class)
                    .block();
            return "Deleted successfully";
        } catch (WebClientResponseException e) {
            return "ERROR " + e.getStatusCode() + ": " + e.getResponseBodyAsString();
        }
    }
}
