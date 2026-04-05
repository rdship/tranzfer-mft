package com.filetransfer.license.catalog;

import lombok.*;
import java.util.*;

/**
 * Master catalog of all licensable platform components.
 * Each component maps to a Helm values key for enable/disable.
 */
public final class ComponentCatalog {

    private ComponentCatalog() {}

    @Data @Builder @AllArgsConstructor
    public static class Component {
        private final String id;
        private final String name;
        private final String description;
        private final Category category;
        private final String helmKey;         // values.yaml key, e.g. "sftpService"
        private final boolean coreRequired;   // always on, not toggleable
        private final String defaultTier;     // minimum tier that includes this (STANDARD/PROFESSIONAL/ENTERPRISE)
    }

    public enum Category {
        CORE("Core Platform", "Essential services — always included"),
        SERVER("Protocol Servers", "Inbound file reception over SFTP, FTP, HTTPS, AS2/AS4"),
        CLIENT("Outbound Clients", "Deliver files to external partners via SFTP, FTP, HTTP, Kafka, AS2/AS4"),
        ENGINE("Processing Engines", "AI classification, analytics, routing, and flow execution"),
        CONNECTOR("Connectors", "Gateway, DMZ proxy, webhook, and external forwarding"),
        CONVERTER("Data Converters", "EDI X12/EDIFACT, format transformation"),
        SECURITY("Security & Compliance", "Encryption, sanctions screening, key management"),
        STORAGE("Storage Management", "Tiered storage lifecycle (hot/warm/cold)"),
        UI("User Interfaces", "Admin dashboard, partner portal, web file manager");

        @Getter private final String displayName;
        @Getter private final String description;

        Category(String displayName, String description) {
            this.displayName = displayName;
            this.description = description;
        }
    }

    /** All licensable components */
    private static final List<Component> ALL_COMPONENTS = List.of(
        // ── CORE (always included) ──────────────────────────────────────
        Component.builder().id("ONBOARDING_API").name("Onboarding API")
            .description("Core user/account management and authentication")
            .category(Category.CORE).helmKey("onboardingApi").coreRequired(true).defaultTier("STANDARD").build(),
        Component.builder().id("CONFIG_SERVICE").name("Configuration Service")
            .description("Flow, encryption key, and security profile management")
            .category(Category.CORE).helmKey("configService").coreRequired(true).defaultTier("STANDARD").build(),
        Component.builder().id("LICENSE_SERVICE").name("License Service")
            .description("License validation and activation management")
            .category(Category.CORE).helmKey("licenseService").coreRequired(true).defaultTier("STANDARD").build(),

        // ── SERVERS ─────────────────────────────────────────────────────
        Component.builder().id("SFTP_SERVER").name("SFTP Server")
            .description("SSH File Transfer Protocol server (port 2222) with public key + password auth")
            .category(Category.SERVER).helmKey("sftpService").coreRequired(false).defaultTier("STANDARD").build(),
        Component.builder().id("FTP_SERVER").name("FTP Server")
            .description("Classic FTP server (port 21) with passive mode support")
            .category(Category.SERVER).helmKey("ftpService").coreRequired(false).defaultTier("STANDARD").build(),
        Component.builder().id("FTP_WEB_SERVER").name("Web File Portal")
            .description("Browser-based file upload/download portal for partners without SFTP/FTP clients")
            .category(Category.SERVER).helmKey("ftpWebService").coreRequired(false).defaultTier("STANDARD").build(),
        Component.builder().id("AS2_SERVER").name("AS2/AS4 Inbound Server")
            .description("Receive EDI files from trading partners via AS2 (RFC 4130) or AS4 (OASIS ebMS3)")
            .category(Category.SERVER).helmKey("as2Service").coreRequired(false).defaultTier("PROFESSIONAL").build(),

        // ── CLIENTS (outbound) ──────────────────────────────────────────
        Component.builder().id("SFTP_CLIENT").name("SFTP Outbound Client")
            .description("Forward files to external SFTP servers")
            .category(Category.CLIENT).helmKey("externalForwarderService").coreRequired(false).defaultTier("STANDARD").build(),
        Component.builder().id("FTP_CLIENT").name("FTP Outbound Client")
            .description("Forward files to external FTP servers")
            .category(Category.CLIENT).helmKey("externalForwarderService").coreRequired(false).defaultTier("STANDARD").build(),
        Component.builder().id("FTPS_CLIENT").name("FTPS Outbound Client")
            .description("Forward files to external FTPS (FTP over TLS) servers")
            .category(Category.CLIENT).helmKey("externalForwarderService").coreRequired(false).defaultTier("STANDARD").build(),
        Component.builder().id("HTTP_CLIENT").name("HTTP/HTTPS Outbound Client")
            .description("Deliver files via HTTP POST/PUT with OAuth2, API key, or basic auth")
            .category(Category.CLIENT).helmKey("externalForwarderService").coreRequired(false).defaultTier("STANDARD").build(),
        Component.builder().id("AS2_CLIENT").name("AS2 Outbound Client")
            .description("Send files to trading partners via AS2 with MDN receipt and MIC verification")
            .category(Category.CLIENT).helmKey("externalForwarderService").coreRequired(false).defaultTier("PROFESSIONAL").build(),
        Component.builder().id("AS4_CLIENT").name("AS4 Outbound Client")
            .description("Send files via AS4/ebMS3 SOAP envelopes (Peppol, eDelivery compatible)")
            .category(Category.CLIENT).helmKey("externalForwarderService").coreRequired(false).defaultTier("PROFESSIONAL").build(),
        Component.builder().id("KAFKA_CLIENT").name("Kafka Producer Client")
            .description("Forward files to Apache Kafka topics for event streaming")
            .category(Category.CLIENT).helmKey("externalForwarderService").coreRequired(false).defaultTier("PROFESSIONAL").build(),

        // ── ENGINES ─────────────────────────────────────────────────────
        Component.builder().id("AI_ENGINE").name("AI Classification Engine")
            .description("PCI/PII/PHI detection via Claude AI + regex. Anomaly detection on transfer patterns")
            .category(Category.ENGINE).helmKey("aiEngine").coreRequired(false).defaultTier("ENTERPRISE").build(),
        Component.builder().id("ANALYTICS_ENGINE").name("Analytics Engine")
            .description("Transfer metrics aggregation, predictive analytics, Prometheus export")
            .category(Category.ENGINE).helmKey("analyticsService").coreRequired(false).defaultTier("PROFESSIONAL").build(),

        // ── CONNECTORS ──────────────────────────────────────────────────
        Component.builder().id("EXTERNAL_FORWARDER").name("External Forwarder Service")
            .description("Central hub for outbound file delivery to all external destinations")
            .category(Category.CONNECTOR).helmKey("externalForwarderService").coreRequired(false).defaultTier("STANDARD").build(),
        Component.builder().id("GATEWAY").name("Protocol Gateway")
            .description("Smart SFTP/FTP proxy — routes connections to backend server instances")
            .category(Category.CONNECTOR).helmKey("gatewayService").coreRequired(false).defaultTier("STANDARD").build(),
        Component.builder().id("DMZ_PROXY").name("DMZ Proxy")
            .description("TCP port-forwarding proxy for secure DMZ deployments")
            .category(Category.CONNECTOR).helmKey("dmzProxy").coreRequired(false).defaultTier("PROFESSIONAL").build(),

        // ── CONVERTERS ──────────────────────────────────────────────────
        Component.builder().id("EDI_CONVERTER").name("EDI Converter")
            .description("X12 and EDIFACT ↔ JSON conversion for EDI document processing")
            .category(Category.CONVERTER).helmKey("ediConverter").coreRequired(false).defaultTier("PROFESSIONAL").build(),

        // ── SECURITY ────────────────────────────────────────────────────
        Component.builder().id("ENCRYPTION_SERVICE").name("Encryption Service")
            .description("PGP and AES-256-GCM encryption/decryption with master key management")
            .category(Category.SECURITY).helmKey("encryptionService").coreRequired(false).defaultTier("STANDARD").build(),
        Component.builder().id("SCREENING_SERVICE").name("Sanctions Screening")
            .description("OFAC, EU, and UN sanctions list screening with configurable threshold")
            .category(Category.SECURITY).helmKey("screeningService").coreRequired(false).defaultTier("ENTERPRISE").build(),
        Component.builder().id("KEYSTORE_MANAGER").name("Keystore Manager")
            .description("Encrypted PGP key storage with master password protection")
            .category(Category.SECURITY).helmKey("keystoreManager").coreRequired(false).defaultTier("STANDARD").build(),

        // ── STORAGE ─────────────────────────────────────────────────────
        Component.builder().id("STORAGE_MANAGER").name("Storage Manager")
            .description("Tiered storage lifecycle: HOT → WARM → COLD with retention policies")
            .category(Category.STORAGE).helmKey("storageManager").coreRequired(false).defaultTier("ENTERPRISE").build(),

        // ── UI ──────────────────────────────────────────────────────────
        Component.builder().id("ADMIN_UI").name("Admin Dashboard")
            .description("Web-based admin console for platform management and monitoring")
            .category(Category.UI).helmKey("adminUi").coreRequired(false).defaultTier("STANDARD").build(),
        Component.builder().id("PARTNER_PORTAL").name("Partner Portal")
            .description("Self-service web portal for trading partners")
            .category(Category.UI).helmKey("partnerPortal").coreRequired(false).defaultTier("STANDARD").build(),
        Component.builder().id("FTP_WEB_UI").name("Web File Manager UI")
            .description("Browser-based file manager for the web file portal")
            .category(Category.UI).helmKey("ftpWebUi").coreRequired(false).defaultTier("STANDARD").build(),
        Component.builder().id("API_GATEWAY").name("API Gateway")
            .description("Nginx reverse proxy with rate limiting, TLS, and security headers")
            .category(Category.UI).helmKey("apiGateway").coreRequired(false).defaultTier("STANDARD").build()
    );

    public static List<Component> getAll() {
        return ALL_COMPONENTS;
    }

    public static Optional<Component> findById(String id) {
        return ALL_COMPONENTS.stream().filter(c -> c.getId().equals(id)).findFirst();
    }

    public static List<Component> getByCategory(Category category) {
        return ALL_COMPONENTS.stream().filter(c -> c.getCategory() == category).toList();
    }

    public static List<Component> getCoreComponents() {
        return ALL_COMPONENTS.stream().filter(Component::isCoreRequired).toList();
    }

    public static List<Component> getLicensableComponents() {
        return ALL_COMPONENTS.stream().filter(c -> !c.isCoreRequired()).toList();
    }

    /** Get components included in a tier (cumulative: ENTERPRISE includes everything) */
    public static List<Component> getComponentsForTier(String tier) {
        List<String> tierOrder = List.of("STANDARD", "PROFESSIONAL", "ENTERPRISE");
        int tierLevel = tierOrder.indexOf(tier);
        if (tierLevel < 0) tierLevel = 0;

        int finalTierLevel = tierLevel;
        return ALL_COMPONENTS.stream()
            .filter(c -> c.isCoreRequired() || tierOrder.indexOf(c.getDefaultTier()) <= finalTierLevel)
            .toList();
    }

    /** Get all unique Helm keys that need to be enabled for a set of component IDs */
    public static Set<String> getHelmKeysForComponents(Collection<String> componentIds) {
        Set<String> keys = new LinkedHashSet<>();
        // Always include core
        getCoreComponents().forEach(c -> keys.add(c.getHelmKey()));
        for (String id : componentIds) {
            findById(id).ifPresent(c -> keys.add(c.getHelmKey()));
        }
        return keys;
    }

    /** Get all unique Helm keys in the catalog */
    public static Set<String> getAllHelmKeys() {
        Set<String> keys = new LinkedHashSet<>();
        ALL_COMPONENTS.forEach(c -> keys.add(c.getHelmKey()));
        return keys;
    }
}
