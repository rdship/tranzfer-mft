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
public class FolderMappingCommands {

    private final ApiClient apiClient;
    private final WebClient onboardingClient;

    @ShellMethod(value = "Create a folder routing mapping", key = "create-mapping")
    public String createMapping(
            @ShellOption(help = "Source account UUID") String sourceAccountId,
            @ShellOption(help = "Source path, e.g. /inbox") String sourcePath,
            @ShellOption(help = "Destination account UUID (or use --external-dest-id)") String destAccountId,
            @ShellOption(help = "Destination path, e.g. /outbox") String destPath,
            @ShellOption(help = "Filename regex pattern (optional)", defaultValue = "") String pattern,
            @ShellOption(help = "Encryption option: NONE, ENCRYPT_BEFORE_FORWARD, DECRYPT_THEN_FORWARD", defaultValue = "NONE") String encryption,
            @ShellOption(help = "External destination UUID (overrides destAccountId)", defaultValue = "") String externalDestId) {

        Map<String, Object> body = new HashMap<>();
        body.put("sourceAccountId", sourceAccountId);
        body.put("sourcePath", sourcePath);
        body.put("destinationPath", destPath);
        body.put("encryptionOption", encryption.toUpperCase());

        if (!externalDestId.isBlank()) {
            body.put("externalDestinationId", externalDestId);
        } else {
            body.put("destinationAccountId", destAccountId);
        }
        if (!pattern.isBlank()) body.put("filenamePattern", pattern);

        return apiClient.post(onboardingClient, "/api/folder-mappings", body);
    }

    @ShellMethod(value = "List folder mappings for an account", key = "list-mappings")
    public String listMappings(@ShellOption(help = "Account UUID") String accountId) {
        return apiClient.get(onboardingClient, "/api/folder-mappings?accountId=" + accountId);
    }

    @ShellMethod(value = "Disable a folder mapping", key = "disable-mapping")
    public String disableMapping(@ShellOption(help = "Mapping UUID") String id) {
        return apiClient.post(onboardingClient, "/api/folder-mappings/" + id + "/active?value=false", Map.of());
    }

    @ShellMethod(value = "Delete a folder mapping", key = "delete-mapping")
    public String deleteMapping(@ShellOption(help = "Mapping UUID") String id) {
        return apiClient.delete(onboardingClient, "/api/folder-mappings/" + id);
    }
}
