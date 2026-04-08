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
public class DmzCommands {

    private final ApiClient apiClient;
    private final WebClient dmzClient;

    @ShellMethod(value = "List DMZ proxy port mappings and live stats", key = "dmz-list")
    public String listMappings() {
        return apiClient.get(dmzClient, "/api/proxy/mappings");
    }

    @ShellMethod(value = "Add a DMZ port mapping (hot-add, no restart)", key = "dmz-add")
    public String addMapping(
            @ShellOption(help = "Mapping name") String name,
            @ShellOption(help = "External listen port") int listenPort,
            @ShellOption(help = "Internal target host") String targetHost,
            @ShellOption(help = "Internal target port") int targetPort) {
        return apiClient.post(dmzClient, "/api/proxy/mappings",
                Map.of("name", name, "listenPort", listenPort,
                        "targetHost", targetHost, "targetPort", targetPort));
    }

    @ShellMethod(value = "Remove a DMZ port mapping (hot-remove)", key = "dmz-remove")
    public String removeMapping(@ShellOption(help = "Mapping name") String name) {
        apiClient.delete(dmzClient, "/api/proxy/mappings/" + name);
        return "Removed DMZ mapping: " + name;
    }

    @ShellMethod(value = "Show DMZ proxy health", key = "dmz-health")
    public String health() {
        return dmzClient.get().uri("/api/proxy/health")
                .retrieve().bodyToMono(String.class).block();
    }
}
