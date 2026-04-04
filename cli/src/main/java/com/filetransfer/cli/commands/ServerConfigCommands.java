package com.filetransfer.cli.commands;

import com.filetransfer.cli.client.ApiClient;
import lombok.RequiredArgsConstructor;
import org.springframework.shell.standard.ShellComponent;
import org.springframework.shell.standard.ShellMethod;
import org.springframework.shell.standard.ShellOption;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;

@ShellComponent
@RequiredArgsConstructor
public class ServerConfigCommands {

    private final ApiClient apiClient;
    private final WebClient configClient;

    @ShellMethod(value = "List all configured server instances", key = "list-servers")
    public String listServers(@ShellOption(help = "Filter by type: SFTP, FTP, FTP_WEB, GATEWAY", defaultValue = "") String type) {
        String url = type.isBlank() ? "/api/servers" : "/api/servers?type=" + type.toUpperCase();
        return apiClient.get(configClient, url);
    }

    @ShellMethod(value = "Add a new server configuration", key = "add-server")
    public String addServer(
            @ShellOption(help = "Display name") String name,
            @ShellOption(help = "Service type: SFTP, FTP, FTP_WEB") String type,
            @ShellOption(help = "Host") String host,
            @ShellOption(help = "Port") int port) {
        return apiClient.post(configClient, "/api/servers",
                Map.of("name", name, "serviceType", type.toUpperCase(), "host", host, "port", port));
    }

    @ShellMethod(value = "Enable or disable a server config", key = "toggle-server")
    public String toggleServer(
            @ShellOption(help = "Server config UUID") String id,
            @ShellOption(help = "true/false") boolean active) {
        return apiClient.post(configClient, "/api/servers/" + id + "/active?value=" + active, Map.of());
    }

    @ShellMethod(value = "Add a legacy server for unknown user routing", key = "add-legacy-server")
    public String addLegacyServer(
            @ShellOption(help = "Display name") String name,
            @ShellOption(help = "Protocol: SFTP or FTP") String protocol,
            @ShellOption(help = "Host") String host,
            @ShellOption(help = "Port") int port) {
        return apiClient.post(configClient, "/api/legacy-servers",
                Map.of("name", name, "protocol", protocol.toUpperCase(), "host", host, "port", port, "active", true));
    }

    @ShellMethod(value = "List legacy servers", key = "list-legacy-servers")
    public String listLegacyServers() {
        return apiClient.get(configClient, "/api/legacy-servers");
    }

    @ShellMethod(value = "Add an external forwarding destination", key = "add-destination")
    public String addDestination(
            @ShellOption(help = "Name") String name,
            @ShellOption(help = "Type: SFTP, FTP, KAFKA") String type,
            @ShellOption(help = "Host (SFTP/FTP) or bootstrap servers (Kafka)") String host,
            @ShellOption(help = "Port (SFTP/FTP)", defaultValue = "22") int port,
            @ShellOption(help = "Username", defaultValue = "") String username,
            @ShellOption(help = "Password", defaultValue = "") String password,
            @ShellOption(help = "Remote path or Kafka topic", defaultValue = "/") String pathOrTopic) {
        var body = new java.util.HashMap<String, Object>();
        body.put("name", name);
        body.put("type", type.toUpperCase());
        if (type.equalsIgnoreCase("KAFKA")) {
            body.put("kafkaBootstrapServers", host);
            body.put("kafkaTopic", pathOrTopic);
        } else {
            body.put("host", host);
            body.put("port", port);
            body.put("username", username);
            body.put("encryptedPassword", password);
            body.put("remotePath", pathOrTopic);
        }
        body.put("active", true);
        return apiClient.post(configClient, "/api/external-destinations", body);
    }

    @ShellMethod(value = "List service registry (all running instances)", key = "service-registry")
    public String serviceRegistry() {
        return apiClient.get(configClient, "/api/service-registry");
    }
}
