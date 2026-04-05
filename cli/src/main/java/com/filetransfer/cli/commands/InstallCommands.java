package com.filetransfer.cli.commands;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.filetransfer.cli.config.ApiClientConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.shell.standard.ShellComponent;
import org.springframework.shell.standard.ShellMethod;
import org.springframework.shell.standard.ShellOption;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.http.MediaType;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;

/**
 * Installation wizard CLI commands.
 * Shows all platform components, validates against license, and generates
 * Helm values override for deployment.
 */
@ShellComponent
@RequiredArgsConstructor
public class InstallCommands {

    private final ApiClientConfig config;
    private final WebClient licenseClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // ── Interactive Install Wizard ──────────────────────────────────────

    @ShellMethod(key = "install", value = "Interactive installation wizard — select components and generate Helm config")
    public String install(
            @ShellOption(value = "--license-key", defaultValue = "") String licenseKey,
            @ShellOption(value = "--tier", defaultValue = "") String tier,
            @ShellOption(value = "--output", defaultValue = "values-licensed.yaml") String outputFile) {

        StringBuilder out = new StringBuilder();
        out.append("\n");
        out.append("  ╔══════════════════════════════════════════════════════════════╗\n");
        out.append("  ║          TranzFer MFT — Installation Wizard                 ║\n");
        out.append("  ╚══════════════════════════════════════════════════════════════╝\n\n");

        // Step 1: Fetch product catalog
        JsonNode catalog;
        JsonNode tiers;
        try {
            String catalogResp = licenseClient.get().uri("/api/v1/licenses/catalog/components")
                .retrieve().bodyToMono(String.class).block();
            catalog = objectMapper.readTree(catalogResp);

            String tiersResp = licenseClient.get().uri("/api/v1/licenses/catalog/tiers")
                .retrieve().bodyToMono(String.class).block();
            tiers = objectMapper.readTree(tiersResp);
        } catch (Exception e) {
            return out + "  ERROR: Cannot reach license service at " + config.getLicenseUrl()
                + "\n  Set LICENSE_API_URL env variable or --api.license-url flag.\n"
                + "  Detail: " + e.getMessage();
        }

        // Step 2: Show available tiers
        out.append("  PRODUCT TIERS\n");
        out.append("  ─────────────────────────────────────────────────────────────\n");
        for (JsonNode t : tiers) {
            out.append(String.format("  [%s] %-15s — %s%n",
                t.path("id").asText().charAt(0),
                t.path("name").asText(),
                t.path("description").asText()));
            out.append(String.format("       %d components | %d instances | %,d connections%n",
                t.path("componentCount").asInt(),
                t.path("maxInstances").asInt(),
                t.path("maxConcurrentConnections").asInt()));
        }
        out.append("\n");

        // Step 3: Determine entitled components
        Set<String> entitledIds = new LinkedHashSet<>();
        String edition = "TRIAL";

        if (!licenseKey.isEmpty()) {
            // Validate license and get entitlements
            try {
                Map<String, String> body = Map.of(
                    "licenseKey", licenseKey,
                    "serviceType", "PLATFORM",
                    "hostId", getHostname());

                String resp = licenseClient.post().uri("/api/v1/licenses/catalog/entitled")
                    .contentType(MediaType.APPLICATION_JSON).bodyValue(body)
                    .retrieve().bodyToMono(String.class).block();

                JsonNode entitled = objectMapper.readTree(resp);
                if (!entitled.path("valid").asBoolean()) {
                    return out + "  LICENSE INVALID: " + entitled.path("message").asText();
                }
                edition = entitled.path("edition").asText("STANDARD");
                JsonNode components = entitled.path("entitledComponents");
                if (components.isArray()) {
                    for (JsonNode c : components) {
                        entitledIds.add(c.path("id").asText());
                    }
                }
                out.append("  LICENSE: ").append(edition).append(" edition — ")
                    .append(entitledIds.size()).append(" components entitled\n\n");
            } catch (Exception e) {
                return out + "  ERROR validating license: " + e.getMessage();
            }
        } else if (!tier.isEmpty()) {
            // Use tier selection without license
            edition = tier.toUpperCase();
            for (JsonNode t : tiers) {
                if (t.path("id").asText().equalsIgnoreCase(tier)) {
                    ArrayNode ids = (ArrayNode) t.path("componentIds");
                    for (JsonNode id : ids) entitledIds.add(id.asText());
                    break;
                }
            }
            out.append("  TIER: ").append(edition).append(" — ").append(entitledIds.size()).append(" components\n\n");
        } else {
            out.append("  MODE: No license key or tier specified — showing full catalog.\n");
            out.append("  Use --license-key <key> or --tier <STANDARD|PROFESSIONAL|ENTERPRISE>\n\n");
        }

        // Step 4: Show full component catalog with entitlement status
        out.append("  COMPONENT CATALOG\n");
        out.append("  ═══════════════════════════════════════════════════════════════\n");

        Set<String> enabledHelmKeys = new LinkedHashSet<>();
        Iterator<String> categories = catalog.fieldNames();

        while (categories.hasNext()) {
            String catKey = categories.next();
            JsonNode catNode = catalog.get(catKey);
            String catName = catNode.path("displayName").asText();
            String catDesc = catNode.path("description").asText();
            ArrayNode components = (ArrayNode) catNode.path("components");

            out.append("\n  ").append(catName.toUpperCase()).append("\n");
            out.append("  ").append(catDesc).append("\n");
            out.append("  ─────────────────────────────────────────────────────────────\n");

            for (JsonNode comp : components) {
                String id = comp.path("id").asText();
                String name = comp.path("name").asText();
                String desc = comp.path("description").asText();
                boolean core = comp.path("coreRequired").asBoolean();
                String minTier = comp.path("minimumTier").asText();
                String helmKey = comp.path("helmKey").asText();

                boolean entitled = core || entitledIds.contains(id) || entitledIds.isEmpty();
                String marker;
                if (core) {
                    marker = "[CORE]    ";
                    enabledHelmKeys.add(helmKey);
                } else if (entitledIds.isEmpty()) {
                    marker = "[--]      ";  // no license, show all
                } else if (entitled) {
                    marker = "[ENABLED] ";
                    enabledHelmKeys.add(helmKey);
                } else {
                    marker = "[LOCKED]  ";
                }

                out.append(String.format("  %s %-28s (min: %-13s helm: %s)%n", marker, name, minTier, helmKey));
                out.append(String.format("             %s%n", desc));
            }
        }

        // Step 5: Generate Helm values override
        if (!entitledIds.isEmpty()) {
            out.append("\n\n  HELM VALUES OVERRIDE\n");
            out.append("  ═══════════════════════════════════════════════════════════════\n\n");

            String yaml = generateHelmValues(enabledHelmKeys, edition, catalog);
            out.append(yaml);

            // Write to file
            try {
                Path path = Paths.get(outputFile);
                Files.writeString(path, yaml);
                out.append("\n\n  Helm values written to: ").append(path.toAbsolutePath());
                out.append("\n\n  Deploy with:");
                out.append("\n    helm upgrade --install mft ./helm/mft-platform -f ").append(outputFile);
            } catch (Exception e) {
                out.append("\n\n  Warning: Could not write to ").append(outputFile).append(": ").append(e.getMessage());
                out.append("\n  Copy the YAML above manually.");
            }
        }

        out.append("\n");
        return out.toString();
    }

    // ── Show Catalog (non-interactive) ──────────────────────────────────

    @ShellMethod(key = "catalog", value = "Show all available platform components and tiers")
    public String showCatalog() {
        return install("", "", "");
    }

    @ShellMethod(key = "catalog tiers", value = "Show product tier comparison")
    public String showTiers() {
        try {
            String tiersResp = licenseClient.get().uri("/api/v1/licenses/catalog/tiers")
                .retrieve().bodyToMono(String.class).block();
            JsonNode tiers = objectMapper.readTree(tiersResp);

            String catalogResp = licenseClient.get().uri("/api/v1/licenses/catalog/components")
                .retrieve().bodyToMono(String.class).block();
            JsonNode catalog = objectMapper.readTree(catalogResp);

            // Build full component list
            Map<String, String> componentNames = new LinkedHashMap<>();
            Iterator<String> cats = catalog.fieldNames();
            while (cats.hasNext()) {
                JsonNode catNode = catalog.get(cats.next());
                for (JsonNode comp : catNode.path("components")) {
                    componentNames.put(comp.path("id").asText(), comp.path("name").asText());
                }
            }

            // Build tier sets
            Map<String, Set<String>> tierSets = new LinkedHashMap<>();
            for (JsonNode t : tiers) {
                Set<String> ids = new LinkedHashSet<>();
                for (JsonNode id : t.path("componentIds")) ids.add(id.asText());
                tierSets.put(t.path("id").asText(), ids);
            }

            StringBuilder sb = new StringBuilder();
            sb.append("\n  TIER COMPARISON\n");
            sb.append("  ═══════════════════════════════════════════════════════════════════\n");
            sb.append(String.format("  %-30s %-10s %-14s %-12s%n", "Component", "Standard", "Professional", "Enterprise"));
            sb.append("  ─────────────────────────────────────────────────────────────────\n");

            for (Map.Entry<String, String> e : componentNames.entrySet()) {
                String id = e.getKey();
                String name = e.getValue();
                String std = tierSets.getOrDefault("STANDARD", Set.of()).contains(id) ? "  YES" : "  ---";
                String pro = tierSets.getOrDefault("PROFESSIONAL", Set.of()).contains(id) ? "    YES" : "    ---";
                String ent = tierSets.getOrDefault("ENTERPRISE", Set.of()).contains(id) ? "    YES" : "    ---";
                sb.append(String.format("  %-30s %-10s %-14s %-12s%n", truncate(name, 28), std, pro, ent));
            }

            sb.append("  ─────────────────────────────────────────────────────────────────\n");
            for (JsonNode t : tiers) {
                sb.append(String.format("  %-30s %-10s%n", t.path("name").asText() + " Max Instances",
                    t.path("maxInstances").asInt()));
                sb.append(String.format("  %-30s %-10s%n", t.path("name").asText() + " Max Connections",
                    String.format("%,d", t.path("maxConcurrentConnections").asInt())));
            }

            return sb.toString();
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    // ── Helm Config Generation ──────────────────────────────────────────

    @ShellMethod(key = "install generate-helm", value = "Generate Helm values file from license key")
    public String generateHelm(
            @ShellOption("--license-key") String licenseKey,
            @ShellOption(value = "--output", defaultValue = "values-licensed.yaml") String outputFile) {
        return install(licenseKey, "", outputFile);
    }

    // ── Private Helpers ─────────────────────────────────────────────────

    private String generateHelmValues(Set<String> enabledHelmKeys, String edition, JsonNode catalog) {
        // Collect ALL helm keys from catalog
        Set<String> allHelmKeys = new LinkedHashSet<>();
        Iterator<String> cats = catalog.fieldNames();
        while (cats.hasNext()) {
            JsonNode catNode = catalog.get(cats.next());
            for (JsonNode comp : catNode.path("components")) {
                allHelmKeys.add(comp.path("helmKey").asText());
            }
        }

        StringBuilder yaml = new StringBuilder();
        yaml.append("# ═══════════════════════════════════════════════════════════════\n");
        yaml.append("# TranzFer MFT — Licensed Component Configuration\n");
        yaml.append("# Edition: ").append(edition).append("\n");
        yaml.append("# Generated: ").append(java.time.Instant.now()).append("\n");
        yaml.append("# ═══════════════════════════════════════════════════════════════\n\n");

        // Infrastructure always on
        yaml.append("# Infrastructure (always enabled)\n");
        yaml.append("postgresql:\n  enabled: true\n\n");
        yaml.append("rabbitmq:\n  enabled: true\n\n");

        yaml.append("# ── Licensed Components ──────────────────────────────────────\n\n");

        // Sort: enabled first, then disabled
        List<String> sorted = new ArrayList<>(allHelmKeys);
        sorted.sort((a, b) -> {
            boolean aOn = enabledHelmKeys.contains(a);
            boolean bOn = enabledHelmKeys.contains(b);
            if (aOn == bOn) return a.compareTo(b);
            return aOn ? -1 : 1;
        });

        for (String key : sorted) {
            boolean enabled = enabledHelmKeys.contains(key);
            yaml.append(key).append(":\n");
            yaml.append("  enabled: ").append(enabled).append("\n\n");
        }

        return yaml.toString();
    }

    private String getHostname() {
        try { return java.net.InetAddress.getLocalHost().getHostName(); }
        catch (Exception e) { return "unknown"; }
    }

    private String truncate(String s, int max) {
        return s.length() > max ? s.substring(0, max - 2) + ".." : s;
    }
}
