package com.filetransfer.cli.commands;

import com.filetransfer.cli.client.ApiClient;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
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

    @Value("${control-api.key:internal_control_secret}")
    private String controlKey;

    @ShellMethod(value = "List DMZ proxy port mappings and live stats", key = "dmz-list")
    public String listMappings() {
        return dmzClient.get().uri("/api/proxy/mappings")
                .header("X-Internal-Key", controlKey)
                .retrieve().bodyToMono(String.class).block();
    }

    @ShellMethod(value = "Add a DMZ port mapping (hot-add, no restart)", key = "dmz-add")
    public String addMapping(
            @ShellOption(help = "Mapping name") String name,
            @ShellOption(help = "External listen port") int listenPort,
            @ShellOption(help = "Internal target host") String targetHost,
            @ShellOption(help = "Internal target port") int targetPort) {
        return dmzClient.post().uri("/api/proxy/mappings")
                .header("X-Internal-Key", controlKey)
                .bodyValue(Map.of("name", name, "listenPort", listenPort,
                        "targetHost", targetHost, "targetPort", targetPort))
                .retrieve().bodyToMono(String.class).block();
    }

    @ShellMethod(value = "Remove a DMZ port mapping (hot-remove)", key = "dmz-remove")
    public String removeMapping(@ShellOption(help = "Mapping name") String name) {
        dmzClient.delete().uri("/api/proxy/mappings/" + name)
                .header("X-Internal-Key", controlKey)
                .retrieve().bodyToMono(Void.class).block();
        return "Removed DMZ mapping: " + name;
    }

    @ShellMethod(value = "Show DMZ proxy health", key = "dmz-health")
    public String health() {
        return dmzClient.get().uri("/api/proxy/health")
                .retrieve().bodyToMono(String.class).block();
    }
}
