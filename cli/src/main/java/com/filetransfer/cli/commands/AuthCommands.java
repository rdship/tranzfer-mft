package com.filetransfer.cli.commands;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.filetransfer.cli.config.ApiClientConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.shell.standard.ShellComponent;
import org.springframework.shell.standard.ShellMethod;
import org.springframework.shell.standard.ShellOption;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;

@ShellComponent
@RequiredArgsConstructor
public class AuthCommands {

    private final ApiClientConfig config;
    private final WebClient onboardingClient;
    private final ObjectMapper objectMapper;

    @ShellMethod(value = "Register a new user account", key = "register")
    public String register(
            @ShellOption(help = "Email address") String email,
            @ShellOption(help = "Password (min 8 chars)") String password) {
        try {
            String response = onboardingClient.post().uri("/api/auth/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("email", email, "password", password))
                    .retrieve().bodyToMono(String.class).block();
            JsonNode node = objectMapper.readTree(response);
            config.setAuthToken(node.get("accessToken").asText());
            return "Registered and logged in as " + email + "\nToken: " + config.getAuthToken();
        } catch (Exception e) {
            return "Registration failed: " + e.getMessage();
        }
    }

    @ShellMethod(value = "Login to the platform", key = "login")
    public String login(
            @ShellOption(help = "Email address") String email,
            @ShellOption(help = "Password") String password) {
        try {
            String response = onboardingClient.post().uri("/api/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("email", email, "password", password))
                    .retrieve().bodyToMono(String.class).block();
            JsonNode node = objectMapper.readTree(response);
            config.setAuthToken(node.get("accessToken").asText());
            return "Logged in as " + email;
        } catch (Exception e) {
            return "Login failed: " + e.getMessage();
        }
    }

    @ShellMethod(value = "Show current auth token", key = "whoami")
    public String whoami() {
        String token = config.getAuthToken();
        return token == null ? "Not logged in" : "Authenticated. Token: " + token.substring(0, Math.min(20, token.length())) + "...";
    }
}
