package com.filetransfer.cli.commands;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.filetransfer.cli.config.ApiClientConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.shell.standard.ShellComponent;
import org.springframework.shell.standard.ShellMethod;
import org.springframework.shell.standard.ShellOption;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.http.MediaType;

import java.util.*;

@ShellComponent
@RequiredArgsConstructor
public class LicenseCommands {

    private final ApiClientConfig config;
    private final WebClient licenseClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // ── License Activation & Validation ─────────────────────────────────

    @ShellMethod(key = "license activate", value = "Activate a license key")
    public String activate(@ShellOption("--key") String licenseKey) {
        try {
            Map<String, String> body = Map.of(
                "licenseKey", licenseKey,
                "serviceType", "PLATFORM",
                "hostId", getHostname()
            );

            String response = licenseClient.post().uri("/api/v1/licenses/validate")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve().bodyToMono(String.class).block();

            JsonNode json = objectMapper.readTree(response);
            boolean valid = json.path("valid").asBoolean();

            if (valid) {
                StringBuilder sb = new StringBuilder();
                sb.append("\n  LICENSE ACTIVATED SUCCESSFULLY\n\n");
                sb.append("  Edition       : ").append(json.path("edition").asText()).append("\n");
                sb.append("  Mode          : ").append(json.path("mode").asText()).append("\n");
                sb.append("  Max Instances : ").append(json.path("maxInstances").asInt()).append("\n");
                sb.append("  Max Connections: ").append(json.path("maxConcurrentConnections").asInt()).append("\n");
                sb.append("  Expires       : ").append(json.path("expiresAt").asText()).append("\n");

                JsonNode features = json.path("features");
                if (features.isArray() && !features.isEmpty()) {
                    sb.append("  Components    : ").append(features.size()).append(" licensed\n");
                }
                sb.append("\n  ").append(json.path("message").asText());
                return sb.toString();
            } else {
                return "\n  LICENSE INVALID: " + json.path("message").asText();
            }
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    @ShellMethod(key = "license trial", value = "Activate a 30-day trial")
    public String trial(
            @ShellOption(value = "--customer-id", defaultValue = "") String customerId,
            @ShellOption(value = "--customer-name", defaultValue = "") String customerName) {
        try {
            Map<String, String> body = new HashMap<>();
            body.put("fingerprint", generateFingerprint());
            body.put("serviceType", "PLATFORM");
            body.put("hostId", getHostname());
            if (!customerId.isEmpty()) body.put("customerId", customerId);
            if (!customerName.isEmpty()) body.put("customerName", customerName);

            String response = licenseClient.post().uri("/api/v1/licenses/trial")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve().bodyToMono(String.class).block();

            JsonNode json = objectMapper.readTree(response);
            boolean valid = json.path("valid").asBoolean();

            if (valid) {
                int days = json.path("trialDaysRemaining").asInt();
                return "\n  TRIAL ACTIVATED\n\n"
                    + "  Days Remaining: " + days + "\n"
                    + "  Features      : BASIC_SFTP, BASIC_FTP, ADMIN_UI\n"
                    + "  Max Instances : 1\n"
                    + "  Max Connections: 10\n\n"
                    + "  " + json.path("message").asText();
            } else {
                return "\n  TRIAL EXPIRED: " + json.path("message").asText();
            }
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    @ShellMethod(key = "license status", value = "Show current license status")
    public String status(@ShellOption(value = "--key", defaultValue = "") String licenseKey) {
        try {
            Map<String, String> body = new HashMap<>();
            if (!licenseKey.isEmpty()) {
                body.put("licenseKey", licenseKey);
            }
            body.put("installationFingerprint", generateFingerprint());
            body.put("serviceType", "PLATFORM");
            body.put("hostId", getHostname());

            String response = licenseClient.post().uri("/api/v1/licenses/validate")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve().bodyToMono(String.class).block();

            JsonNode json = objectMapper.readTree(response);
            StringBuilder sb = new StringBuilder();
            sb.append("\n  LICENSE STATUS\n");
            sb.append("  ─────────────────────────────────────\n");
            sb.append("  Valid           : ").append(json.path("valid").asBoolean() ? "YES" : "NO").append("\n");
            sb.append("  Mode            : ").append(json.path("mode").asText("N/A")).append("\n");
            sb.append("  Edition         : ").append(json.path("edition").asText("N/A")).append("\n");
            if (json.has("trialDaysRemaining")) {
                sb.append("  Trial Days Left : ").append(json.path("trialDaysRemaining").asInt()).append("\n");
            }
            sb.append("  Max Instances   : ").append(json.path("maxInstances").asInt()).append("\n");
            sb.append("  Max Connections : ").append(json.path("maxConcurrentConnections").asInt()).append("\n");
            sb.append("  Expires         : ").append(json.path("expiresAt").asText("N/A")).append("\n");
            sb.append("  Message         : ").append(json.path("message").asText("")).append("\n");

            JsonNode features = json.path("features");
            if (features.isArray() && !features.isEmpty()) {
                sb.append("\n  Licensed Components:\n");
                for (JsonNode f : features) {
                    sb.append("    - ").append(f.asText()).append("\n");
                }
            }
            return sb.toString();
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    // ── Admin: Issue & Manage Licenses ──────────────────────────────────

    @ShellMethod(key = "license issue", value = "Issue a new license key (admin)")
    public String issue(
            @ShellOption("--customer-id") String customerId,
            @ShellOption("--customer-name") String customerName,
            @ShellOption("--edition") String edition,
            @ShellOption(value = "--days", defaultValue = "365") int days,
            @ShellOption(value = "--components", defaultValue = "") String components) {
        try {
            String adminKey = config.getLicenseAdminKey();
            if (adminKey == null || adminKey.isEmpty()) {
                return "Error: License admin key not configured. Set LICENSE_ADMIN_KEY env variable.";
            }

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("customerId", customerId);
            body.put("customerName", customerName);
            body.put("edition", edition.toUpperCase());
            body.put("validDays", days);

            // Build services list from component IDs
            List<Map<String, Object>> services = new ArrayList<>();
            Set<String> componentIds = new LinkedHashSet<>();

            if (!components.isEmpty()) {
                // Custom component selection
                for (String c : components.split(",")) {
                    componentIds.add(c.trim().toUpperCase());
                }
            } else {
                // Default to tier components
                // We'll just pass the edition and let the license carry all tier components as features
            }

            // Create a single PLATFORM service entry with all component IDs as features
            Map<String, Object> platformService = new LinkedHashMap<>();
            platformService.put("serviceType", "PLATFORM");
            platformService.put("maxInstances", edition.equalsIgnoreCase("ENTERPRISE") ? 100 :
                                                 edition.equalsIgnoreCase("PROFESSIONAL") ? 10 : 3);
            platformService.put("maxConcurrentConnections", edition.equalsIgnoreCase("ENTERPRISE") ? 10000 :
                                                             edition.equalsIgnoreCase("PROFESSIONAL") ? 2000 : 500);
            platformService.put("features", componentIds.isEmpty()
                ? List.of() // tier defaults will be inferred from edition
                : new ArrayList<>(componentIds));
            services.add(platformService);

            body.put("services", services);

            String response = licenseClient.post().uri("/api/v1/licenses/issue")
                .header("X-Admin-Key", adminKey)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve().bodyToMono(String.class).block();

            JsonNode json = objectMapper.readTree(response);
            String key = json.path("licenseKey").asText();
            return "\n  LICENSE ISSUED\n\n"
                + "  Customer : " + customerName + "\n"
                + "  Edition  : " + edition.toUpperCase() + "\n"
                + "  Valid    : " + days + " days\n\n"
                + "  License Key:\n  " + key + "\n\n"
                + "  Save this key — it cannot be retrieved later.";
        } catch (WebClientResponseException e) {
            return "Error " + e.getStatusCode() + ": " + e.getResponseBodyAsString();
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    @ShellMethod(key = "license list", value = "List all issued licenses (admin)")
    public String list() {
        try {
            String adminKey = config.getLicenseAdminKey();
            String response = licenseClient.get().uri("/api/v1/licenses")
                .header("X-Admin-Key", adminKey)
                .retrieve().bodyToMono(String.class).block();

            JsonNode json = objectMapper.readTree(response);
            if (!json.isArray() || json.isEmpty()) return "No licenses found.";

            StringBuilder sb = new StringBuilder();
            sb.append("\n  ISSUED LICENSES\n");
            sb.append("  ─────────────────────────────────────────────────────\n");
            sb.append(String.format("  %-14s %-20s %-14s %-8s%n", "License ID", "Customer", "Edition", "Active"));
            sb.append("  ─────────────────────────────────────────────────────\n");

            for (JsonNode lic : json) {
                sb.append(String.format("  %-14s %-20s %-14s %-8s%n",
                    lic.path("licenseId").asText(),
                    truncate(lic.path("customerName").asText(), 18),
                    lic.path("edition").asText(),
                    lic.path("active").asBoolean() ? "YES" : "REVOKED"));
            }
            return sb.toString();
        } catch (WebClientResponseException e) {
            return "Error " + e.getStatusCode() + ": " + e.getResponseBodyAsString();
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    @ShellMethod(key = "license revoke", value = "Revoke a license (admin)")
    public String revoke(@ShellOption("--license-id") String licenseId) {
        try {
            String adminKey = config.getLicenseAdminKey();
            licenseClient.delete().uri("/api/v1/licenses/" + licenseId + "/revoke")
                .header("X-Admin-Key", adminKey)
                .retrieve().bodyToMono(Void.class).block();
            return "License " + licenseId + " revoked.";
        } catch (WebClientResponseException e) {
            return "Error " + e.getStatusCode() + ": " + e.getResponseBodyAsString();
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    private String getHostname() {
        try { return java.net.InetAddress.getLocalHost().getHostName(); }
        catch (Exception e) { return "unknown"; }
    }

    private String generateFingerprint() {
        try {
            String hostname = java.net.InetAddress.getLocalHost().getHostName();
            String os = System.getProperty("os.name");
            return Base64.getEncoder().encodeToString((hostname + "|" + os + "|PLATFORM").getBytes());
        } catch (Exception e) {
            return "cli-" + System.currentTimeMillis();
        }
    }

    private String truncate(String s, int max) {
        return s.length() > max ? s.substring(0, max - 2) + ".." : s;
    }
}
