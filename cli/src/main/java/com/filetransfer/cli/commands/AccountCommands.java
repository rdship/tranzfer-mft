package com.filetransfer.cli.commands;

import com.filetransfer.cli.client.ApiClient;
import lombok.RequiredArgsConstructor;
import org.springframework.shell.standard.ShellComponent;
import org.springframework.shell.standard.ShellMethod;
import org.springframework.shell.standard.ShellOption;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.HashMap;
import java.util.Map;

@ShellComponent
@RequiredArgsConstructor
public class AccountCommands {

    private final ApiClient apiClient;
    private final WebClient onboardingClient;

    @ShellMethod(value = "Create a transfer account (SFTP / FTP / FTP_WEB)", key = "create-account")
    public String createAccount(
            @ShellOption(help = "Protocol: SFTP, FTP, FTP_WEB") String protocol,
            @ShellOption(help = "Username for transfer") String username,
            @ShellOption(help = "Password") String password,
            @ShellOption(help = "SSH public key (optional, SFTP only)", defaultValue = "") String publicKey) {

        Map<String, Object> body = new HashMap<>();
        body.put("protocol", protocol.toUpperCase());
        body.put("username", username);
        body.put("password", password);
        if (!publicKey.isBlank()) body.put("publicKey", publicKey);
        return apiClient.post(onboardingClient, "/api/accounts", body);
    }

    @ShellMethod(value = "Get a transfer account by ID", key = "get-account")
    public String getAccount(@ShellOption(help = "Account UUID") String id) {
        return apiClient.get(onboardingClient, "/api/accounts/" + id);
    }

    @ShellMethod(value = "Enable or disable a transfer account", key = "toggle-account")
    public String toggleAccount(
            @ShellOption(help = "Account UUID") String id,
            @ShellOption(help = "true/false") boolean active) {
        return apiClient.post(onboardingClient, "/api/accounts/" + id, Map.of("active", active));
    }
}
