package com.filetransfer.onboarding.bootstrap;

import com.filetransfer.shared.dto.FolderDefinition;
import com.filetransfer.shared.entity.core.*;
import com.filetransfer.shared.entity.transfer.*;
import com.filetransfer.shared.entity.vfs.*;
import com.filetransfer.shared.entity.security.*;
import com.filetransfer.shared.entity.integration.*;
import com.filetransfer.shared.enums.*;
import com.filetransfer.shared.repository.core.*;
import com.filetransfer.shared.repository.transfer.*;
import com.filetransfer.shared.repository.integration.*;
import com.filetransfer.shared.repository.security.*;
import com.filetransfer.shared.repository.vfs.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Seeds the database with realistic demo data on every fresh install.
 * <p>
 * Detection: runs only when the users table is empty (no users = fresh install).
 * Idempotent: subsequent starts with existing users skip seeding entirely.
 * Safe: wrapped in try-catch so bootstrap failure never prevents app startup.
 */
@Service
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(name = "platform.bootstrap.enabled", havingValue = "true", matchIfMissing = true)
public class PlatformBootstrapService {

    private final UserRepository userRepository;
    private final ServerInstanceRepository serverInstanceRepository;
    private final PartnerRepository partnerRepository;
    private final PartnerContactRepository partnerContactRepository;
    private final TransferAccountRepository transferAccountRepository;
    private final DeliveryEndpointRepository deliveryEndpointRepository;
    private final FolderTemplateRepository folderTemplateRepository;
    private final FolderMappingRepository folderMappingRepository;
    private final FileFlowRepository fileFlowRepository;
    private final PlatformSettingRepository platformSettingRepository;
    private final WebhookConnectorRepository webhookConnectorRepository;
    private final PartnerWebhookRepository partnerWebhookRepository;
    private final SecurityProfileRepository securityProfileRepository;
    private final ListenerSecurityPolicyRepository listenerSecurityPolicyRepository;
    private final As2PartnershipRepository as2PartnershipRepository;
    private final ProxyGroupRepository proxyGroupRepository;
    private final ScheduledTaskRepository scheduledTaskRepository;
    private final TenantRepository tenantRepository;
    private final ComplianceProfileRepository complianceProfileRepository;
    private final MigrationEventRepository migrationEventRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${platform.bootstrap.seed-demo-data:true}")
    private boolean seedDemoData;

    /**
     * Initial super-admin password. Env var overrides the dev-insecure default.
     * When the default ("superadmin") is used, {@link #seedSuperAdmin} logs a
     * loud WARN at startup telling operators to rotate it via the API. This
     * avoids forcing every dev/CI run to generate a strong password (the
     * PasswordPolicy validator would reject "superadmin") while still making
     * production misuse visible at every container start.
     */
    @Value("${platform.bootstrap.admin-password:superadmin}")
    private String adminPassword;

    @Value("${platform.environment:DEV}")
    private String platformEnvironment;

    @EventListener(ApplicationReadyEvent.class)
    @Order(Ordered.LOWEST_PRECEDENCE)
    @Transactional
    public void onApplicationReady() {
        try {
            // Always emit the security posture line before any gating so
            // ops can `docker logs mft-onboarding-api | grep BOOTSTRAP-SECURITY`
            // and get a deterministic answer regardless of whether the
            // seed path actually runs this boot.
            emitBootstrapSecurityStatus();

            if (userRepository.count() > 0) {
                log.info("[Bootstrap] Users table is not empty — skipping seed data");
                return;
            }

            if (!seedDemoData) {
                log.info("[Bootstrap] Fresh install detected but platform.bootstrap.seed-demo-data=false — skipping demo data");
                // Still create the super-admin user so the platform is usable
                seedSuperAdmin();
                return;
            }

            log.info("[Bootstrap] Fresh install detected — seeding platform with demo data...");

            User admin = seedSuperAdmin();
            List<ServerInstance> servers = seedServerInstances();
            List<Partner> partners = seedPartners();
            Map<String, TransferAccount> accounts = seedTransferAccounts(admin, partners);
            List<DeliveryEndpoint> endpoints = seedDeliveryEndpoints(partners);
            FolderTemplate template = seedFolderTemplate();
            seedFolderMappings(accounts);
            seedFileFlows(accounts, endpoints, partners);
            seedPlatformSettings();

            // ── Additional seed data — fills every UI page on first login ──
            seedWebhookConnectors();
            seedPartnerWebhooks(partners);
            seedSecurityProfiles();
            seedListenerSecurityPolicies(servers);
            seedAs2Partnerships();
            seedProxyGroups();
            seedScheduledTasks();
            seedTenants();
            seedComplianceProfiles(servers);

            log.warn("[Bootstrap] Demo data seeded with placeholder credentials — NOT for production use");
            log.info("[Bootstrap] ===== Platform bootstrap complete =====");
            log.info("[Bootstrap]   1 super-admin user");
            log.info("[Bootstrap]   {} server instances", servers.size());
            log.info("[Bootstrap]   {} partners", partners.size());
            log.info("[Bootstrap]   {} transfer accounts", accounts.size());
            log.info("[Bootstrap]   {} delivery endpoints", endpoints.size());
            log.info("[Bootstrap]   1 folder template");
            log.info("[Bootstrap]   6 file flows");
            log.info("[Bootstrap]   Platform settings, connectors, security profiles,");
            log.info("[Bootstrap]   AS2 partnerships, proxy groups, schedules, tenants seeded");
            log.info("[Bootstrap] ==========================================");

        } catch (Exception e) {
            log.error("[Bootstrap] Failed to seed platform data — app will continue without demo data", e);
        }
    }

    /**
     * Always-emitted bootstrap-security status line. Ops needs a
     * deterministic, greppable posture signal on every boot — not just
     * the first-install seeder run. ERROR level in PROD-ish environments
     * so log-aggregator filters don't drop it; WARN in DEV to avoid noise.
     */
    private void emitBootstrapSecurityStatus() {
        boolean usingDefault = "superadmin".equals(adminPassword);
        String env = platformEnvironment != null ? platformEnvironment.toUpperCase() : "DEV";
        boolean isProdish = "PROD".equals(env) || "STAGING".equals(env) || "CERT".equals(env);
        if (usingDefault) {
            String msg = "[BOOTSTRAP-SECURITY] status=DEFAULT_PASSWORD_IN_USE env=" + env
                    + " — set PLATFORM_BOOTSTRAP_ADMIN_PASSWORD or rotate via POST /api/users/me/password";
            if (isProdish) log.error(msg); else log.warn(msg);
        } else {
            log.info("[BOOTSTRAP-SECURITY] status=CUSTOM_PASSWORD_SET env={}", env);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 1. Super Admin
    // ──────────────────────────────────────────────────────────────────────────

    private User seedSuperAdmin() {
        String effectivePassword = adminPassword;
        boolean isDefault = "superadmin".equals(effectivePassword);
        User admin = User.builder()
                .email("superadmin@tranzfer.io")
                .passwordHash(passwordEncoder.encode(effectivePassword))
                .role(UserRole.ADMIN)
                .build();
        admin = userRepository.save(admin);
        log.info("[Bootstrap] Created super-admin user: superadmin@tranzfer.io (role=ADMIN)");
        // Make the break-glass password visible at every cold boot. Every line is
        // tagged [BOOTSTRAP-SECURITY] so ops can grep it out of otherwise noisy
        // startup logs: `docker logs mft-onboarding-api | grep BOOTSTRAP-SECURITY`.
        if (isDefault) {
            String env = platformEnvironment != null ? platformEnvironment.toUpperCase() : "DEV";
            boolean isProdish = "PROD".equals(env) || "STAGING".equals(env) || "CERT".equals(env);
            String severity = isProdish ? "!! CRITICAL !!" : "(dev default)";
            // ERROR level in prod-ish environments so it cannot be filtered out by
            // WARN-and-above log aggregators; WARN level in DEV to avoid spooking
            // local devs. Either way every line carries the [BOOTSTRAP-SECURITY] tag.
            java.util.function.Consumer<String> emit = isProdish ? log::error : log::warn;
            emit.accept("═══════════════════════════════════════════════════════════════");
            emit.accept(String.format("[BOOTSTRAP-SECURITY] %s SUPER-ADMIN USING DEFAULT PASSWORD 'superadmin'", severity));
            emit.accept("[BOOTSTRAP-SECURITY] Bootstrap seed bypasses the password policy validator.");
            emit.accept("[BOOTSTRAP-SECURITY] Set PLATFORM_BOOTSTRAP_ADMIN_PASSWORD at first boot, OR rotate");
            emit.accept("[BOOTSTRAP-SECURITY] immediately via POST /api/users/me/password after login.");
            emit.accept("═══════════════════════════════════════════════════════════════");
        } else {
            log.info("[BOOTSTRAP-SECURITY] Super-admin password set via PLATFORM_BOOTSTRAP_ADMIN_PASSWORD — OK.");
        }
        return admin;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 2. Server Instances (6 total: 2 SFTP, 2 FTP, 2 FTP_WEB)
    // ──────────────────────────────────────────────────────────────────────────

    private List<ServerInstance> seedServerInstances() {
        List<ServerInstance> servers = new ArrayList<>();

        // SFTP servers
        servers.add(buildServer("sftp-server-1", "SFTP Server 1 — Primary", Protocol.SFTP,
                "sftp-service", 2222, "localhost", 2222,
                "Primary SFTP server for partner file exchange"));
        servers.add(buildServer("sftp-server-2", "SFTP Server 2 — Secondary", Protocol.SFTP,
                "sftp-service-2", 2223, "localhost", 2223,
                "Secondary SFTP server for high-availability"));

        // FTP/FTPS servers
        servers.add(buildServer("ftps-server-1", "FTPS Server 1 — Primary", Protocol.FTP,
                "ftp-service", 990, "localhost", 990,
                "Primary FTPS server with implicit TLS"));
        servers.add(buildServer("ftps-server-2", "FTPS Server 2 — Secondary", Protocol.FTP,
                "ftp-service-2", 991, "localhost", 991,
                "Secondary FTPS server for failover"));

        // FTP-Web servers
        servers.add(buildServer("ftp-web-server-1", "FTP-Web Server 1 — Primary", Protocol.FTP_WEB,
                "ftp-web-service", 8083, "localhost", 8083,
                "Primary browser-based file transfer portal"));
        servers.add(buildServer("ftp-web-server-2", "FTP-Web Server 2 — Secondary", Protocol.FTP_WEB,
                "ftp-web-service-2", 8183, "localhost", 8183,
                "Secondary web transfer portal for load balancing"));

        // Idempotent: skip any ServerInstance whose instanceId OR (host,port)
        // tuple already exists — protects against re-seed on container restart
        // and against the dynamic-listener registry having already written the
        // row via bindState updates.
        List<ServerInstance> toInsert = new ArrayList<>();
        for (ServerInstance s : servers) {
            if (serverInstanceRepository.existsByInstanceId(s.getInstanceId())) {
                log.debug("[Bootstrap] Skipping existing server instance '{}'", s.getInstanceId());
                continue;
            }
            if (serverInstanceRepository.findByInternalHostAndInternalPortAndActiveTrue(
                    s.getInternalHost(), s.getInternalPort()).isPresent()) {
                log.debug("[Bootstrap] Skipping '{}' — port {}:{} already claimed",
                        s.getInstanceId(), s.getInternalHost(), s.getInternalPort());
                continue;
            }
            toInsert.add(s);
        }
        if (toInsert.isEmpty()) {
            log.info("[Bootstrap] All server instances already seeded — skipping");
            return serverInstanceRepository.findAll();
        }
        List<ServerInstance> saved = serverInstanceRepository.saveAll(toInsert);
        saved.forEach(s -> log.info("[Bootstrap] Created server instance: {} ({}:{}, protocol={})",
                s.getName(), s.getInternalHost(), s.getInternalPort(), s.getProtocol()));
        return saved;
    }

    private ServerInstance buildServer(String instanceId, String name, Protocol protocol,
                                       String internalHost, int internalPort,
                                       String externalHost, int externalPort,
                                       String description) {
        return ServerInstance.builder()
                .instanceId(instanceId)
                .name(name)
                .protocol(protocol)
                .internalHost(internalHost)
                .internalPort(internalPort)
                .externalHost(externalHost)
                .externalPort(externalPort)
                .description(description)
                .active(true)
                .maxConnections(500)
                .securityTier("RULES")
                .defaultStorageMode("VIRTUAL")
                .build();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 3. Partners (5 test partners)
    // ──────────────────────────────────────────────────────────────────────────

    private List<Partner> seedPartners() {
        List<Partner> partners = new ArrayList<>();

        partners.add(buildPartner("Acme Corp", "acme-corp",
                "Global EDI trading partner specializing in supply chain document exchange",
                "Manufacturing", "[\"SFTP\"]"));

        partners.add(buildPartner("GlobalBank", "globalbank",
                "Tier-1 financial institution exchanging SWIFT payment files and compliance reports",
                "Financial Services", "[\"SFTP\",\"FTP\"]"));

        partners.add(buildPartner("MedTech Solutions", "medtech-solutions",
                "Healthcare technology provider exchanging HL7/FHIR clinical data",
                "Healthcare", "[\"AS2\"]"));

        partners.add(buildPartner("RetailMax", "retailmax",
                "Major retail chain with browser-based order management and invoice exchange",
                "Retail", "[\"FTP_WEB\"]"));

        partners.add(buildPartner("LogiFlow", "logiflow",
                "Logistics and freight forwarding company exchanging shipping manifests and BOL documents",
                "Logistics", "[\"SFTP\"]"));

        List<Partner> saved = partnerRepository.saveAll(partners);

        // Create primary contacts for each partner
        seedPartnerContacts(saved);

        // ── Migration seed data — makes Acme Corp + GlobalBank look mid-migration ──
        seedMigrationData(saved);

        saved.forEach(p -> log.info("[Bootstrap] Created partner: {} (slug={}, status={}, industry={})",
                p.getCompanyName(), p.getSlug(), p.getStatus(), p.getIndustry()));
        return saved;
    }

    private Partner buildPartner(String companyName, String slug, String description,
                                  String industry, String protocols) {
        return Partner.builder()
                .companyName(companyName)
                .displayName(companyName)
                .slug(slug)
                .industry(industry)
                .notes(description)
                .partnerType("EXTERNAL")
                .status("ACTIVE")
                .onboardingPhase("LIVE")
                .protocolsEnabled(protocols)
                .slaTier("STANDARD")
                .maxFileSizeBytes(536870912L)
                .maxTransfersPerDay(1000)
                .retentionDays(90)
                .build();
    }

    private void seedPartnerContacts(List<Partner> partners) {
        List<PartnerContact> contacts = new ArrayList<>();

        contacts.add(buildContact(partners.get(0), "John Mitchell", "john.mitchell@acme-corp.com", "+1-555-0101", "Technical Lead"));
        contacts.add(buildContact(partners.get(1), "Sarah Chen", "sarah.chen@globalbank.com", "+1-555-0202", "VP Integration"));
        contacts.add(buildContact(partners.get(2), "Dr. James Wilson", "j.wilson@medtech-solutions.com", "+1-555-0303", "CTO"));
        contacts.add(buildContact(partners.get(3), "Maria Rodriguez", "m.rodriguez@retailmax.com", "+1-555-0404", "IT Director"));
        contacts.add(buildContact(partners.get(4), "Alex Thompson", "a.thompson@logiflow.com", "+1-555-0505", "Systems Architect"));

        partnerContactRepository.saveAll(contacts);
        contacts.forEach(c -> log.info("[Bootstrap] Created partner contact: {} ({}) for {}",
                c.getName(), c.getEmail(), c.getPartner().getCompanyName()));
    }

    private PartnerContact buildContact(Partner partner, String name, String email, String phone, String role) {
        return PartnerContact.builder()
                .partner(partner)
                .name(name)
                .email(email)
                .phone(phone)
                .role(role)
                .isPrimary(true)
                .build();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 3b. Migration seed data — realistic mid-migration state for demo
    // ──────────────────────────────────────────────────────────────────────────

    private void seedMigrationData(List<Partner> partners) {
        // Acme Corp — completed migration from Axway
        Partner acme = partners.get(0);
        acme.setMigrationStatus("COMPLETED");
        acme.setMigrationSource("axway-prod-01");
        acme.setMigrationStartedAt(Instant.now().minus(30, ChronoUnit.DAYS));
        acme.setMigrationCompletedAt(Instant.now().minus(5, ChronoUnit.DAYS));
        acme.setVerificationTransferCount(247);
        partnerRepository.save(acme);

        // GlobalBank — in-progress migration with shadow mode from Sterling
        Partner globalBank = partners.get(1);
        globalBank.setMigrationStatus("SHADOW_MODE");
        globalBank.setMigrationSource("sterling-legacy");
        globalBank.setMigrationStartedAt(Instant.now().minus(10, ChronoUnit.DAYS));
        globalBank.setShadowModeEnabled(true);
        globalBank.setLegacyHost("legacy-sftp.globalbank.com");
        globalBank.setLegacyPort(22);
        globalBank.setLegacyUsername("globalbank-sftp");
        partnerRepository.save(globalBank);

        // Migration events — audit trail
        migrationEventRepository.save(MigrationEvent.builder()
                .partnerId(acme.getId())
                .partnerName(acme.getCompanyName())
                .eventType("CUTOVER_COMPLETED")
                .details("Migration completed \u2014 partner fully on TranzFer")
                .actor("admin@company.com")
                .build());

        migrationEventRepository.save(MigrationEvent.builder()
                .partnerId(acme.getId())
                .partnerName(acme.getCompanyName())
                .eventType("VERIFICATION_PASSED")
                .details("247 transfers verified against Axway source \u2014 zero discrepancies")
                .actor("admin@company.com")
                .build());

        migrationEventRepository.save(MigrationEvent.builder()
                .partnerId(globalBank.getId())
                .partnerName(globalBank.getCompanyName())
                .eventType("SHADOW_ENABLED")
                .details("Shadow mode activated \u2014 mirroring traffic from sterling-legacy")
                .actor("admin@company.com")
                .build());

        log.info("[Bootstrap] Seeded migration data: Acme Corp=COMPLETED, GlobalBank=SHADOW_MODE");
        log.info("[Bootstrap] Seeded 3 migration events");
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 4. Transfer Accounts (1 per partner per relevant protocol)
    // ──────────────────────────────────────────────────────────────────────────

    private Map<String, TransferAccount> seedTransferAccounts(User admin, List<Partner> partners) {
        String hashedPassword = passwordEncoder.encode("partner123");
        Map<String, TransferAccount> accounts = new LinkedHashMap<>();

        // Acme Corp — SFTP
        accounts.put("acme-sftp", buildAccount(admin, "acme-sftp", Protocol.SFTP,
                "/data/partners/acme", hashedPassword, partners.get(0).getId(),
                Map.of("read", true, "write", true, "delete", false)));

        // GlobalBank — SFTP + FTP
        accounts.put("globalbank-sftp", buildAccount(admin, "globalbank-sftp", Protocol.SFTP,
                "/data/partners/globalbank", hashedPassword, partners.get(1).getId(),
                Map.of("read", true, "write", true, "delete", false)));
        accounts.put("globalbank-ftps", buildAccount(admin, "globalbank-ftps", Protocol.FTP,
                "/data/partners/globalbank", hashedPassword, partners.get(1).getId(),
                Map.of("read", true, "write", true, "delete", false)));

        // MedTech Solutions — AS2
        accounts.put("medtech-as2", buildAccount(admin, "medtech-as2", Protocol.AS2,
                "/data/partners/medtech", hashedPassword, partners.get(2).getId(),
                Map.of("read", true, "write", true, "delete", false)));

        // RetailMax — FTP_WEB
        accounts.put("retailmax-ftp-web", buildAccount(admin, "retailmax-ftp-web", Protocol.FTP_WEB,
                "/data/partners/retailmax", hashedPassword, partners.get(3).getId(),
                Map.of("read", true, "write", true, "delete", true)));

        // LogiFlow — SFTP
        accounts.put("logiflow-sftp", buildAccount(admin, "logiflow-sftp", Protocol.SFTP,
                "/data/partners/logiflow", hashedPassword, partners.get(4).getId(),
                Map.of("read", true, "write", true, "delete", false)));

        // Save all accounts
        List<TransferAccount> saved = transferAccountRepository.saveAll(accounts.values());
        // Re-map with saved entities (IDs populated)
        Map<String, TransferAccount> result = new LinkedHashMap<>();
        for (TransferAccount acct : saved) {
            result.put(acct.getUsername(), acct);
            log.info("[Bootstrap] Created transfer account: {} (protocol={}, homeDir={}, partner={})",
                    acct.getUsername(), acct.getProtocol(), acct.getHomeDir(), acct.getPartnerId());
        }
        return result;
    }

    private TransferAccount buildAccount(User owner, String username, Protocol protocol,
                                          String homeDir, String hashedPassword, UUID partnerId,
                                          Map<String, Boolean> permissions) {
        return TransferAccount.builder()
                .user(owner)
                .username(username)
                .protocol(protocol)
                .homeDir(homeDir)
                .passwordHash(hashedPassword)
                .partnerId(partnerId)
                .permissions(permissions)
                .storageMode("PHYSICAL")
                .active(true)
                .build();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 5. Delivery Endpoints (6 external endpoints, various protocols)
    // ──────────────────────────────────────────────────────────────────────────

    private List<DeliveryEndpoint> seedDeliveryEndpoints(List<Partner> partners) {
        List<DeliveryEndpoint> endpoints = new ArrayList<>();

        endpoints.add(DeliveryEndpoint.builder()
                .name("partner-sftp-endpoint")
                .description("SFTP delivery to Partner A — primary EDI exchange")
                .protocol(DeliveryProtocol.SFTP)
                .host("sftp.partner-a.com")
                .port(22)
                .basePath("/inbound")
                .authType(AuthType.BASIC)
                .username("partner-a-user")
                .encryptedPassword("demo-encrypted-placeholder")
                .tlsEnabled(false)
                .partnerId(partners.get(0).getId())
                .tags("edi,sftp,production")
                .active(true)
                .build());

        endpoints.add(DeliveryEndpoint.builder()
                .name("partner-ftps-endpoint")
                .description("FTPS delivery to Partner B — secure financial data")
                .protocol(DeliveryProtocol.FTPS)
                .host("ftps.partner-b.com")
                .port(990)
                .basePath("/payments/inbound")
                .authType(AuthType.BASIC)
                .username("partner-b-user")
                .encryptedPassword("demo-encrypted-placeholder")
                .tlsEnabled(true)
                .partnerId(partners.get(1).getId())
                .tags("financial,ftps,swift")
                .active(true)
                .build());

        endpoints.add(DeliveryEndpoint.builder()
                .name("partner-ftp-endpoint")
                .description("FTP delivery to Partner C — legacy integration")
                .protocol(DeliveryProtocol.FTP)
                .host("ftp.partner-c.com")
                .port(21)
                .basePath("/upload")
                .authType(AuthType.BASIC)
                .username("partner-c-user")
                .encryptedPassword("demo-encrypted-placeholder")
                .tlsEnabled(false)
                .partnerId(partners.get(2).getId())
                .tags("legacy,ftp")
                .active(true)
                .build());

        endpoints.add(DeliveryEndpoint.builder()
                .name("partner-as2-endpoint")
                .description("AS2 delivery to Partner D — healthcare HL7 messages")
                .protocol(DeliveryProtocol.AS2)
                .host("as2.partner-d.com")
                .port(443)
                .basePath("/as2/receive")
                .authType(AuthType.NONE)
                .tlsEnabled(true)
                .partnerId(partners.get(2).getId())
                .tags("healthcare,as2,hl7")
                .active(true)
                .build());

        endpoints.add(DeliveryEndpoint.builder()
                .name("partner-https-endpoint")
                .description("HTTPS API delivery to Partner E — REST webhook upload")
                .protocol(DeliveryProtocol.HTTPS)
                .host("api.partner-e.com")
                .port(443)
                .basePath("/api/v1/upload")
                .authType(AuthType.BEARER_TOKEN)
                .bearerToken("demo-bearer-token-placeholder")
                .httpMethod("POST")
                .contentType("application/octet-stream")
                .tlsEnabled(true)
                .partnerId(partners.get(3).getId())
                .tags("api,https,webhook")
                .active(true)
                .build());

        endpoints.add(DeliveryEndpoint.builder()
                .name("partner-sftp-endpoint-2")
                .description("SFTP delivery to Partner F — logistics manifest exchange")
                .protocol(DeliveryProtocol.SFTP)
                .host("sftp.partner-f.com")
                .port(2222)
                .basePath("/shipping/inbound")
                .authType(AuthType.SSH_KEY)
                .username("partner-f-user")
                .sshPrivateKey("demo-ssh-key-placeholder")
                .tlsEnabled(false)
                .partnerId(partners.get(4).getId())
                .tags("logistics,sftp,shipping")
                .active(true)
                .build());

        List<DeliveryEndpoint> saved = deliveryEndpointRepository.saveAll(endpoints);
        saved.forEach(ep -> log.info("[Bootstrap] Created delivery endpoint: {} (protocol={}, host={}:{})",
                ep.getName(), ep.getProtocol(), ep.getHost(), ep.getPort()));
        return saved;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 6. Folder Template & Folder Mappings
    // ──────────────────────────────────────────────────────────────────────────

    private FolderTemplate seedFolderTemplate() {
        List<FolderDefinition> folders = List.of(
                FolderDefinition.builder()
                        .path("/inbox")
                        .description("Incoming files land here — monitored for new arrivals")
                        .metadata(Map.of("type", "inbound", "monitored", "true"))
                        .build(),
                FolderDefinition.builder()
                        .path("/outbox")
                        .description("Files staged for partner pickup")
                        .metadata(Map.of("type", "outbound", "monitored", "true"))
                        .build(),
                FolderDefinition.builder()
                        .path("/sent")
                        .description("Delivered files archived here for audit trail")
                        .metadata(Map.of("type", "archive", "monitored", "false"))
                        .build(),
                FolderDefinition.builder()
                        .path("/error")
                        .description("Files that failed processing — requires manual review")
                        .metadata(Map.of("type", "error", "monitored", "true"))
                        .build()
        );

        FolderTemplate template = FolderTemplate.builder()
                .name("Standard Partner Layout")
                .description("Default folder structure for all partner accounts: inbox, outbox, sent, error")
                .builtIn(true)
                .folders(folders)
                .active(true)
                .build();

        template = folderTemplateRepository.save(template);
        log.info("[Bootstrap] Created folder template: '{}' with {} folder definitions",
                template.getName(), folders.size());
        return template;
    }

    private void seedFolderMappings(Map<String, TransferAccount> accounts) {
        List<FolderMapping> mappings = new ArrayList<>();

        // For each SFTP/FTP/FTP_WEB account, create inbox→outbox self-routing mapping
        for (Map.Entry<String, TransferAccount> entry : accounts.entrySet()) {
            TransferAccount acct = entry.getValue();
            Protocol p = acct.getProtocol();
            if (p == Protocol.SFTP || p == Protocol.FTP || p == Protocol.FTP_WEB) {
                mappings.add(FolderMapping.builder()
                        .sourceAccount(acct)
                        .sourcePath("/inbox")
                        .destinationAccount(acct)
                        .destinationPath("/outbox")
                        .filenamePattern(null) // match all
                        .active(true)
                        .build());
            }
        }

        // Cross-account mappings: Acme inbox → GlobalBank outbox (EDI exchange)
        TransferAccount acmeSftp = accounts.get("acme-sftp");
        TransferAccount globalbankSftp = accounts.get("globalbank-sftp");
        if (acmeSftp != null && globalbankSftp != null) {
            mappings.add(FolderMapping.builder()
                    .sourceAccount(acmeSftp)
                    .sourcePath("/inbox")
                    .destinationAccount(globalbankSftp)
                    .destinationPath("/outbox")
                    .filenamePattern(".*\\.edi")
                    .active(true)
                    .build());
        }

        // LogiFlow inbox → Acme outbox (shipping docs)
        TransferAccount logiflowSftp = accounts.get("logiflow-sftp");
        if (logiflowSftp != null && acmeSftp != null) {
            mappings.add(FolderMapping.builder()
                    .sourceAccount(logiflowSftp)
                    .sourcePath("/inbox")
                    .destinationAccount(acmeSftp)
                    .destinationPath("/outbox")
                    .filenamePattern(".*\\.(csv|xml)")
                    .active(true)
                    .build());
        }

        List<FolderMapping> saved = folderMappingRepository.saveAll(mappings);
        log.info("[Bootstrap] Created {} folder mappings (self-routing + cross-account)", saved.size());
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 7. File Flows (5 diverse processing pipelines)
    // ──────────────────────────────────────────────────────────────────────────

    private void seedFileFlows(Map<String, TransferAccount> accounts,
                               List<DeliveryEndpoint> endpoints,
                               List<Partner> partners) {

        TransferAccount acmeSftp = accounts.get("acme-sftp");
        TransferAccount globalbankSftp = accounts.get("globalbank-sftp");
        TransferAccount medtechAs2 = accounts.get("medtech-as2");
        TransferAccount retailmaxWeb = accounts.get("retailmax-ftp-web");
        TransferAccount logiflowSftp = accounts.get("logiflow-sftp");

        // Find endpoints by name for linking
        DeliveryEndpoint sftpEndpoint = endpoints.stream()
                .filter(e -> "partner-sftp-endpoint".equals(e.getName())).findFirst().orElse(null);
        DeliveryEndpoint as2Endpoint = endpoints.stream()
                .filter(e -> "partner-as2-endpoint".equals(e.getName())).findFirst().orElse(null);

        List<FileFlow> flows = new ArrayList<>();

        // Flow 1: EDI Processing Pipeline
        flows.add(FileFlow.builder()
                .name("EDI Processing Pipeline")
                .description("Receives EDI files from Acme Corp, screens for compliance, converts to JSON, " +
                        "verifies checksum, and routes to GlobalBank's mailbox for SWIFT processing")
                .filenamePattern(".*\\.edi")
                .sourceAccount(acmeSftp)
                .sourcePath("/inbox")
                .destinationAccount(globalbankSftp)
                .destinationPath("/inbox")
                .partnerId(partners.get(0).getId())
                .direction("INBOUND")
                .priority(10)
                .steps(List.of(
                        FileFlow.FlowStep.builder()
                                .type("SCREEN")
                                .config(Map.of())
                                .order(0)
                                .build(),
                        FileFlow.FlowStep.builder()
                                .type("CONVERT_EDI")
                                .config(Map.of("targetFormat", "JSON",
                                        "partnerId", partners.get(0).getId().toString()))
                                .order(1)
                                .build(),
                        FileFlow.FlowStep.builder()
                                .type("CHECKSUM_VERIFY")
                                .config(Map.of())
                                .order(2)
                                .build(),
                        FileFlow.FlowStep.builder()
                                .type("MAILBOX")
                                .config(Map.of("destinationUsername", "globalbank-sftp"))
                                .order(3)
                                .build()
                ))
                .active(true)
                .build());

        // Flow 2: Encrypted Delivery
        flows.add(FileFlow.builder()
                .name("Encrypted Delivery")
                .description("Outbound XML files are checksummed, PGP-encrypted, GZIP-compressed, " +
                        "and delivered to Partner A's SFTP endpoint for secure document exchange")
                .filenamePattern(".*\\.xml")
                .sourceAccount(globalbankSftp)
                .sourcePath("/outbox")
                .externalDestination(sftpEndpoint != null ? null : null)
                .partnerId(partners.get(1).getId())
                .direction("OUTBOUND")
                .priority(20)
                .steps(List.of(
                        FileFlow.FlowStep.builder()
                                .type("CHECKSUM_VERIFY")
                                .config(Map.of())
                                .order(0)
                                .build(),
                        FileFlow.FlowStep.builder()
                                .type("ENCRYPT_PGP")
                                .config(Map.of("keyId", "demo-pgp-key"))
                                .order(1)
                                .build(),
                        FileFlow.FlowStep.builder()
                                .type("COMPRESS_GZIP")
                                .config(Map.of())
                                .order(2)
                                .build(),
                        FileFlow.FlowStep.builder()
                                .type("FILE_DELIVERY")
                                .config(sftpEndpoint != null
                                        ? Map.of("deliveryEndpointIds", sftpEndpoint.getId().toString())
                                        : Map.of())
                                .order(3)
                                .build()
                ))
                .active(true)
                .build());

        // Flow 3: Healthcare Compliance
        flows.add(FileFlow.builder()
                .name("Healthcare Compliance")
                .description("HL7 healthcare messages screened for PHI/PII, checksummed, AES-encrypted, " +
                        "require admin approval before delivery — HIPAA compliant pipeline")
                .filenamePattern(".*\\.hl7")
                .sourceAccount(medtechAs2)
                .sourcePath("/inbox")
                .partnerId(partners.get(2).getId())
                .direction("INBOUND")
                .priority(15)
                .steps(List.of(
                        FileFlow.FlowStep.builder()
                                .type("SCREEN")
                                .config(Map.of())
                                .order(0)
                                .build(),
                        FileFlow.FlowStep.builder()
                                .type("CHECKSUM_VERIFY")
                                .config(Map.of())
                                .order(1)
                                .build(),
                        FileFlow.FlowStep.builder()
                                .type("ENCRYPT_AES")
                                .config(Map.of("keyId", "demo-aes-key"))
                                .order(2)
                                .build(),
                        FileFlow.FlowStep.builder()
                                .type("APPROVE")
                                .config(Map.of("requiredApprovers", "admin"))
                                .order(3)
                                .build(),
                        FileFlow.FlowStep.builder()
                                .type("FILE_DELIVERY")
                                .config(as2Endpoint != null
                                        ? Map.of("deliveryEndpointIds", as2Endpoint.getId().toString())
                                        : Map.of())
                                .order(4)
                                .build()
                ))
                .active(true)
                .build());

        // Flow 4: Mailbox Distribution
        // Priority 1 (lowest) — this is a catch-all with pattern ".*" that matches
        // every inbound filename. At the original priority 100 it preempted every
        // fixture/user-defined flow in the test rig (and would do the same in a
        // real multi-partner install). Lowering it to 1 keeps the default
        // fan-everything-into-RetailMax-mailbox behavior for the demo scenario
        // while letting any more-specific flow (regtest-f7 at 10, user custom at
        // 50, etc.) win on their pattern match.
        flows.add(FileFlow.builder()
                .name("Mailbox Distribution")
                .description("All files arriving at RetailMax are screened, renamed with timestamp, " +
                        "and routed to the RetailMax FTP-Web mailbox for browser-based pickup")
                .filenamePattern(".*")
                .sourceAccount(retailmaxWeb)
                .sourcePath("/inbox")
                .destinationAccount(retailmaxWeb)
                .destinationPath("/outbox")
                .partnerId(partners.get(3).getId())
                .direction("INBOUND")
                .priority(1)
                .steps(List.of(
                        FileFlow.FlowStep.builder()
                                .type("SCREEN")
                                .config(Map.of())
                                .order(0)
                                .build(),
                        FileFlow.FlowStep.builder()
                                .type("RENAME")
                                .config(Map.of("pattern", "${basename}_${timestamp}.${ext}"))
                                .order(1)
                                .build(),
                        FileFlow.FlowStep.builder()
                                .type("MAILBOX")
                                .config(Map.of("destinationUsername", "retailmax-ftp-web"))
                                .order(2)
                                .build()
                ))
                .active(true)
                .build());

        // Flow 5: Archive & Compress
        flows.add(FileFlow.builder()
                .name("Archive & Compress")
                .description("CSV data files from LogiFlow are integrity-checked, GZIP-compressed, " +
                        "and renamed for archival in the logistics data warehouse")
                .filenamePattern(".*\\.csv")
                .sourceAccount(logiflowSftp)
                .sourcePath("/inbox")
                .destinationAccount(logiflowSftp)
                .destinationPath("/sent")
                .partnerId(partners.get(4).getId())
                .direction("INBOUND")
                .priority(50)
                .steps(List.of(
                        FileFlow.FlowStep.builder()
                                .type("CHECKSUM_VERIFY")
                                .config(Map.of())
                                .order(0)
                                .build(),
                        FileFlow.FlowStep.builder()
                                .type("COMPRESS_GZIP")
                                .config(Map.of())
                                .order(1)
                                .build(),
                        FileFlow.FlowStep.builder()
                                .type("RENAME")
                                .config(Map.of("pattern", "${basename}_archived.gz"))
                                .order(2)
                                .build()
                ))
                .active(true)
                .build());

        // Flow 6: EDI-to-XML Conversion (showcases trained map with partner profile)
        flows.add(FileFlow.builder()
                .name("EDI X12 to XML Conversion")
                .description("Converts X12 EDI 850/810/856 documents to XML format using partner-specific " +
                        "trained maps from MedTech Solutions. Screens, converts, encrypts, and delivers.")
                .filenamePattern(".*\\.(x12|850|810|856)")
                .sourceAccount(accounts.get("medtech-as2"))
                .sourcePath("/inbox")
                .partnerId(partners.get(2).getId())
                .direction("INBOUND")
                .priority(12)
                .steps(List.of(
                        FileFlow.FlowStep.builder()
                                .type("SCREEN")
                                .config(Map.of())
                                .order(0)
                                .build(),
                        FileFlow.FlowStep.builder()
                                .type("CONVERT_EDI")
                                .config(Map.of("targetFormat", "XML",
                                        "partnerId", partners.get(2).getId().toString()))
                                .order(1)
                                .build(),
                        FileFlow.FlowStep.builder()
                                .type("CHECKSUM_VERIFY")
                                .config(Map.of())
                                .order(2)
                                .build(),
                        FileFlow.FlowStep.builder()
                                .type("ENCRYPT_AES")
                                .config(Map.of("keyId", "demo-aes-key"))
                                .order(3)
                                .build(),
                        FileFlow.FlowStep.builder()
                                .type("FILE_DELIVERY")
                                .config(Map.of("deliveryEndpointIds",
                                        endpoints.size() > 3 ? endpoints.get(3).getId().toString() : ""))
                                .order(4)
                                .build()
                ))
                .active(true)
                .build());

        List<FileFlow> saved = fileFlowRepository.saveAll(flows);
        saved.forEach(f -> log.info("[Bootstrap] Created file flow: '{}' (direction={}, priority={}, steps={})",
                f.getName(), f.getDirection(), f.getPriority(), f.getSteps().size()));
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 8. Platform Settings (keystore keys + HTTPS listener config)
    // ──────────────────────────────────────────────────────────────────────────

    private void seedPlatformSettings() {
        List<PlatformSetting> settings = new ArrayList<>();

        // Keystore key references
        settings.add(PlatformSetting.builder()
                .settingKey("keystore.demo-pgp-key")
                .settingValue("demo-pgp-key")
                .environment(Environment.DEV)
                .serviceName("GLOBAL")
                .dataType("STRING")
                .description("Demo PGP keypair for encrypt/decrypt flow steps — auto-generated on bootstrap")
                .category("Security")
                .sensitive(false)
                .active(true)
                .build());

        settings.add(PlatformSetting.builder()
                .settingKey("keystore.demo-aes-key")
                .settingValue("demo-aes-key")
                .environment(Environment.DEV)
                .serviceName("GLOBAL")
                .dataType("STRING")
                .description("Demo AES-256 key for symmetric encryption flow steps — auto-generated on bootstrap")
                .category("Security")
                .sensitive(false)
                .active(true)
                .build());

        settings.add(PlatformSetting.builder()
                .settingKey("keystore.demo-tls-cert")
                .settingValue("demo-tls-cert")
                .environment(Environment.DEV)
                .serviceName("GLOBAL")
                .dataType("STRING")
                .description("Demo self-signed TLS certificate for HTTPS listener and endpoint testing")
                .category("Security")
                .sensitive(false)
                .active(true)
                .build());

        settings.add(PlatformSetting.builder()
                .settingKey("keystore.platform-ca")
                .settingValue("platform-ca")
                .environment(Environment.DEV)
                .serviceName("GLOBAL")
                .dataType("STRING")
                .description("Platform CA certificate for internal mTLS between services")
                .category("Security")
                .sensitive(false)
                .active(true)
                .build());

        // HTTPS Listener configuration
        settings.add(PlatformSetting.builder()
                .settingKey("https.listener.port")
                .settingValue("8443")
                .environment(Environment.DEV)
                .serviceName("GLOBAL")
                .dataType("INTEGER")
                .description("HTTPS listener port for UI/API access with TLS termination")
                .category("Network")
                .sensitive(false)
                .active(true)
                .build());

        settings.add(PlatformSetting.builder()
                .settingKey("https.listener.tls-cert")
                .settingValue("demo-tls-cert")
                .environment(Environment.DEV)
                .serviceName("GLOBAL")
                .dataType("STRING")
                .description("TLS certificate alias used by HTTPS listener (references keystore entry)")
                .category("Network")
                .sensitive(false)
                .active(true)
                .build());

        settings.add(PlatformSetting.builder()
                .settingKey("https.listener.enabled")
                .settingValue("true")
                .environment(Environment.DEV)
                .serviceName("GLOBAL")
                .dataType("BOOLEAN")
                .description("Enable HTTPS listener for secure UI access")
                .category("Network")
                .sensitive(false)
                .active(true)
                .build());

        // Platform defaults
        settings.add(PlatformSetting.builder()
                .settingKey("platform.default-retention-days")
                .settingValue("90")
                .environment(Environment.DEV)
                .serviceName("GLOBAL")
                .dataType("INTEGER")
                .description("Default file retention period in days before automatic cleanup")
                .category("Storage")
                .sensitive(false)
                .active(true)
                .build());

        settings.add(PlatformSetting.builder()
                .settingKey("platform.max-file-size-bytes")
                .settingValue("536870912")
                .environment(Environment.DEV)
                .serviceName("GLOBAL")
                .dataType("LONG")
                .description("Maximum allowed file size in bytes (default 512MB)")
                .category("Storage")
                .sensitive(false)
                .active(true)
                .build());

        settings.add(PlatformSetting.builder()
                .settingKey("platform.screening.enabled")
                .settingValue("true")
                .environment(Environment.DEV)
                .serviceName("GLOBAL")
                .dataType("BOOLEAN")
                .description("Enable file content screening for malware and compliance")
                .category("Security")
                .sensitive(false)
                .active(true)
                .build());

        settings.add(PlatformSetting.builder()
                .settingKey("platform.bootstrap.completed")
                .settingValue("true")
                .environment(Environment.DEV)
                .serviceName("onboarding-api")
                .dataType("BOOLEAN")
                .description("Flag indicating platform bootstrap has been completed")
                .category("System")
                .sensitive(false)
                .active(true)
                .build());

        List<PlatformSetting> saved = platformSettingRepository.saveAll(settings);
        saved.forEach(s -> log.info("[Bootstrap] Created platform setting: {} = {} (category={}, env={})",
                s.getSettingKey(), s.isSensitive() ? "****" : s.getSettingValue(),
                s.getCategory(), s.getEnvironment()));
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 9. Webhook Connectors (Ops Slack + Admin Email)
    // ──────────────────────────────────────────────────────────────────────────

    private void seedWebhookConnectors() {
        try {
            List<WebhookConnector> connectors = List.of(
                    WebhookConnector.builder()
                            .name("Ops Slack")
                            .type("SLACK")
                            .url("https://example.com/slack-webhook-placeholder")
                            .triggerEvents(List.of("FLOW_FAIL", "TRANSFER_FAILED", "ANOMALY_DETECTED"))
                            .minSeverity("MEDIUM")
                            .active(true)
                            .build(),
                    WebhookConnector.builder()
                            .name("Admin Email")
                            .type("EMAIL")
                            .url("mailto:admin@tranzfer.io")
                            .triggerEvents(List.of("FLOW_FAIL", "LICENSE_EXPIRED", "QUARANTINE"))
                            .minSeverity("HIGH")
                            .active(true)
                            .build(),
                    WebhookConnector.builder()
                            .name("PagerDuty On-Call")
                            .type("PAGERDUTY")
                            .url("https://events.pagerduty.com/v2/enqueue")
                            .authToken("demo-pagerduty-routing-key")
                            .triggerEvents(List.of("TRANSFER_FAILED", "INTEGRITY_FAIL", "AI_BLOCKED"))
                            .minSeverity("CRITICAL")
                            .active(true)
                            .build()
            );
            List<WebhookConnector> saved = webhookConnectorRepository.saveAll(connectors);
            saved.forEach(c -> log.info("[Bootstrap] Created webhook connector: {} (type={}, events={})",
                    c.getName(), c.getType(), c.getTriggerEvents()));
        } catch (Exception e) {
            log.warn("[Bootstrap] Failed to seed webhook connectors: {}", e.getMessage());
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 10. Partner Webhooks (per-partner notification endpoints)
    // ──────────────────────────────────────────────────────────────────────────

    private void seedPartnerWebhooks(List<Partner> partners) {
        try {
            List<PartnerWebhook> webhooks = List.of(
                    PartnerWebhook.builder()
                            .partnerName(partners.get(0).getCompanyName())
                            .url("https://api.acme-corp.com/webhooks/file-transfer")
                            .secret("acme-hmac-secret-demo")
                            .events(List.of("FLOW_COMPLETED", "FLOW_FAILED"))
                            .description("Acme Corp — notifies their ERP system on flow completion/failure")
                            .active(true)
                            .build(),
                    PartnerWebhook.builder()
                            .partnerName(partners.get(1).getCompanyName())
                            .url("https://integration.globalbank.com/callbacks/mft")
                            .secret("globalbank-hmac-secret-demo")
                            .events(List.of("FLOW_COMPLETED"))
                            .description("GlobalBank — triggers downstream SWIFT processing on successful delivery")
                            .active(true)
                            .build()
            );
            List<PartnerWebhook> saved = partnerWebhookRepository.saveAll(webhooks);
            saved.forEach(w -> log.info("[Bootstrap] Created partner webhook: {} → {} (events={})",
                    w.getPartnerName(), w.getUrl(), w.getEvents()));
        } catch (Exception e) {
            log.warn("[Bootstrap] Failed to seed partner webhooks: {}", e.getMessage());
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 11. Security Profiles (SSH + TLS cryptographic baselines)
    // ──────────────────────────────────────────────────────────────────────────

    private void seedSecurityProfiles() {
        try {
            List<SecurityProfile> profiles = List.of(
                    SecurityProfile.builder()
                            .name("Standard SSH")
                            .description("Balanced SSH profile — modern ciphers, broad client compatibility")
                            .type("SSH")
                            .sshCiphers(List.of("aes256-gcm@openssh.com", "aes128-gcm@openssh.com",
                                    "chacha20-poly1305@openssh.com", "aes256-ctr", "aes128-ctr"))
                            .sshMacs(List.of("hmac-sha2-512-etm@openssh.com", "hmac-sha2-256-etm@openssh.com",
                                    "hmac-sha2-512", "hmac-sha2-256"))
                            .kexAlgorithms(List.of("curve25519-sha256", "ecdh-sha2-nistp256",
                                    "diffie-hellman-group16-sha512"))
                            .hostKeyAlgorithms(List.of("ssh-ed25519", "rsa-sha2-512", "ecdsa-sha2-nistp256"))
                            .active(true)
                            .build(),
                    SecurityProfile.builder()
                            .name("High Security SSH")
                            .description("Hardened SSH profile — AEAD-only ciphers, curve25519 KEX, no legacy algorithms")
                            .type("SSH")
                            .sshCiphers(List.of("aes256-gcm@openssh.com", "chacha20-poly1305@openssh.com"))
                            .sshMacs(List.of("hmac-sha2-512-etm@openssh.com"))
                            .kexAlgorithms(List.of("curve25519-sha256", "mlkem768x25519-sha256"))
                            .hostKeyAlgorithms(List.of("ssh-ed25519"))
                            .active(true)
                            .build(),
                    SecurityProfile.builder()
                            .name("Standard TLS")
                            .description("TLS 1.2+ profile for FTPS and HTTPS — ECDHE with AEAD cipher suites")
                            .type("TLS")
                            .tlsMinVersion("TLSv1.2")
                            .tlsCiphers(List.of("TLS_AES_256_GCM_SHA384", "TLS_AES_128_GCM_SHA256",
                                    "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
                                    "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256"))
                            .clientAuthRequired(false)
                            .active(true)
                            .build(),
                    SecurityProfile.builder()
                            .name("High Security TLS")
                            .description("TLS 1.3 only — mandatory client certificate authentication for mTLS")
                            .type("TLS")
                            .tlsMinVersion("TLSv1.3")
                            .tlsCiphers(List.of("TLS_AES_256_GCM_SHA384", "TLS_CHACHA20_POLY1305_SHA256"))
                            .clientAuthRequired(true)
                            .active(true)
                            .build()
            );
            List<SecurityProfile> saved = securityProfileRepository.saveAll(profiles);
            saved.forEach(p -> log.info("[Bootstrap] Created security profile: {} (type={}, active={})",
                    p.getName(), p.getType(), p.isActive()));
        } catch (Exception e) {
            log.warn("[Bootstrap] Failed to seed security profiles: {}", e.getMessage());
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 12. Listener Security Policies (per-server firewall + rate-limit rules)
    // ──────────────────────────────────────────────────────────────────────────

    private void seedListenerSecurityPolicies(List<ServerInstance> servers) {
        try {
            // Map servers by instanceId for easy lookup
            ServerInstance sftp1 = servers.stream()
                    .filter(s -> "sftp-server-1".equals(s.getInstanceId())).findFirst().orElse(null);
            ServerInstance ftps1 = servers.stream()
                    .filter(s -> "ftps-server-1".equals(s.getInstanceId())).findFirst().orElse(null);

            List<ListenerSecurityPolicy> policies = new ArrayList<>();

            if (sftp1 != null) {
                policies.add(ListenerSecurityPolicy.builder()
                        .name("SFTP Primary — Standard Policy")
                        .description("Standard rate-limiting and encryption policy for the primary SFTP listener")
                        .securityTier(SecurityTier.AI)
                        .serverInstance(sftp1)
                        .rateLimitPerMinute(120)
                        .maxConcurrent(50)
                        .maxBytesPerMinute(1_000_000_000L)
                        .maxAuthAttempts(5)
                        .idleTimeoutSeconds(300)
                        .requireEncryption(true)
                        .connectionLogging(true)
                        .blockedFileExtensions(List.of(".exe", ".bat", ".cmd", ".ps1", ".sh"))
                        .maxFileSizeBytes(536_870_912L)
                        .ipWhitelist(List.of("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16"))
                        .active(true)
                        .build());
            }

            if (ftps1 != null) {
                policies.add(ListenerSecurityPolicy.builder()
                        .name("FTPS Primary — Financial Policy")
                        .description("Strict policy for financial data exchange — lower rate limits, IP whitelist, encryption required")
                        .securityTier(SecurityTier.AI_LLM)
                        .serverInstance(ftps1)
                        .rateLimitPerMinute(60)
                        .maxConcurrent(20)
                        .maxBytesPerMinute(500_000_000L)
                        .maxAuthAttempts(3)
                        .idleTimeoutSeconds(180)
                        .requireEncryption(true)
                        .connectionLogging(true)
                        .allowedFileExtensions(List.of(".xml", ".json", ".csv", ".edi", ".txt"))
                        .maxFileSizeBytes(268_435_456L)
                        .geoAllowedCountries(List.of("US", "GB", "DE", "JP", "SG"))
                        .active(true)
                        .build());
            }

            if (!policies.isEmpty()) {
                List<ListenerSecurityPolicy> saved = listenerSecurityPolicyRepository.saveAll(policies);
                saved.forEach(p -> log.info("[Bootstrap] Created listener security policy: {} (tier={}, rateLimit={}/min)",
                        p.getName(), p.getSecurityTier(), p.getRateLimitPerMinute()));
            }
        } catch (Exception e) {
            log.warn("[Bootstrap] Failed to seed listener security policies: {}", e.getMessage());
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 13. AS2 Partnerships (B2B trading partner configs)
    // ──────────────────────────────────────────────────────────────────────────

    private void seedAs2Partnerships() {
        try {
            List<As2Partnership> partnerships = List.of(
                    As2Partnership.builder()
                            .partnerName("MedTech Solutions")
                            .partnerAs2Id("MEDTECH-AS2")
                            .ourAs2Id("TRANZFER-AS2")
                            .endpointUrl("https://as2.medtech-solutions.com/as2/receive")
                            .signingAlgorithm("SHA256")
                            .encryptionAlgorithm("AES256")
                            .mdnRequired(true)
                            .mdnAsync(false)
                            .compressionEnabled(true)
                            .protocol("AS2")
                            .active(true)
                            .build(),
                    As2Partnership.builder()
                            .partnerName("GlobalBank")
                            .partnerAs2Id("GLOBALBANK-AS2")
                            .ourAs2Id("TRANZFER-AS2-FIN")
                            .endpointUrl("https://as2.globalbank.com/b2b/receive")
                            .signingAlgorithm("SHA384")
                            .encryptionAlgorithm("AES256")
                            .mdnRequired(true)
                            .mdnAsync(true)
                            .mdnUrl("https://as2.tranzfer.io/mdn/receive")
                            .compressionEnabled(false)
                            .protocol("AS2")
                            .active(true)
                            .build()
            );
            List<As2Partnership> saved = as2PartnershipRepository.saveAll(partnerships);
            saved.forEach(p -> log.info("[Bootstrap] Created AS2 partnership: {} (as2Id={}, signing={}, encryption={})",
                    p.getPartnerName(), p.getPartnerAs2Id(), p.getSigningAlgorithm(), p.getEncryptionAlgorithm()));
        } catch (Exception e) {
            log.warn("[Bootstrap] Failed to seed AS2 partnerships: {}", e.getMessage());
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 14. Proxy Groups (DMZ network zones)
    // ──────────────────────────────────────────────────────────────────────────

    private void seedProxyGroups() {
        try {
            List<ProxyGroup> groups = List.of(
                    ProxyGroup.builder()
                            .name("Internal Network")
                            .type("INTERNAL")
                            .description("Corporate/private network zone — all protocols, TLS optional, " +
                                    "trusted CIDR ranges for internal service-to-service traffic")
                            .allowedProtocols(List.of("SFTP", "FTP", "FTPS", "AS2", "HTTPS"))
                            .tlsRequired(false)
                            .trustedCidrs("10.0.0.0/8,172.16.0.0/12,192.168.0.0/16")
                            .maxConnectionsPerInstance(1000)
                            .routingPriority(10)
                            .active(true)
                            .build(),
                    ProxyGroup.builder()
                            .name("External / Partner DMZ")
                            .type("EXTERNAL")
                            .description("Internet-facing DMZ zone — SFTP and AS2 only, TLS mandatory, " +
                                    "rate-limited for partner connections through DMZ proxy")
                            .allowedProtocols(List.of("SFTP", "AS2", "HTTPS"))
                            .tlsRequired(true)
                            .maxConnectionsPerInstance(500)
                            .routingPriority(20)
                            .active(true)
                            .build(),
                    ProxyGroup.builder()
                            .name("Cloud Bridge")
                            .type("CLOUD")
                            .description("Cloud-to-cloud connectivity zone for AWS/Azure/GCP storage " +
                                    "integrations — HTTPS only, mTLS required")
                            .allowedProtocols(List.of("HTTPS"))
                            .tlsRequired(true)
                            .maxConnectionsPerInstance(200)
                            .routingPriority(30)
                            .active(true)
                            .build()
            );
            List<ProxyGroup> saved = proxyGroupRepository.saveAll(groups);
            saved.forEach(g -> log.info("[Bootstrap] Created proxy group: {} (type={}, protocols={}, tlsRequired={})",
                    g.getName(), g.getType(), g.getAllowedProtocols(), g.isTlsRequired()));
        } catch (Exception e) {
            log.warn("[Bootstrap] Failed to seed proxy groups: {}", e.getMessage());
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 15. Scheduled Tasks (automated jobs)
    // ──────────────────────────────────────────────────────────────────────────

    private void seedScheduledTasks() {
        try {
            List<ScheduledTask> tasks = List.of(
                    ScheduledTask.builder()
                            .name("Daily Archive Cleanup")
                            .description("Purges archived files older than the retention period (default 90 days) " +
                                    "every night at 2:00 AM UTC")
                            .cronExpression("0 0 2 * * *")
                            .timezone("UTC")
                            .taskType("CLEANUP")
                            .config(Map.of("retentionDays", "90", "targetPaths", "/sent,/archive"))
                            .enabled(true)
                            .build(),
                    ScheduledTask.builder()
                            .name("Hourly Partner Pull — Acme Corp")
                            .description("Polls Acme Corp's external SFTP server every hour for new inbound files")
                            .cronExpression("0 0 * * * *")
                            .timezone("UTC")
                            .taskType("PULL_FILES")
                            .config(Map.of("accountUsername", "acme-sftp", "remotePath", "/outbound"))
                            .enabled(true)
                            .build(),
                    ScheduledTask.builder()
                            .name("Weekly Integrity Report")
                            .description("Generates a weekly checksum integrity report for all partner transfers — " +
                                    "runs every Sunday at 6:00 AM UTC")
                            .cronExpression("0 0 6 * * SUN")
                            .timezone("UTC")
                            .taskType("EXECUTE_SCRIPT")
                            .config(Map.of("script", "integrity-report", "emailTo", "admin@tranzfer.io"))
                            .enabled(true)
                            .build()
            );
            List<ScheduledTask> saved = scheduledTaskRepository.saveAll(tasks);
            saved.forEach(t -> log.info("[Bootstrap] Created scheduled task: {} (cron={}, type={}, enabled={})",
                    t.getName(), t.getCronExpression(), t.getTaskType(), t.isEnabled()));
        } catch (Exception e) {
            log.warn("[Bootstrap] Failed to seed scheduled tasks: {}", e.getMessage());
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 16. Tenants (multi-tenant isolation)
    // ──────────────────────────────────────────────────────────────────────────

    private void seedTenants() {
        try {
            List<Tenant> tenants = List.of(
                    Tenant.builder()
                            .slug("default")
                            .companyName("TranzFer (Platform Owner)")
                            .contactEmail("admin@tranzfer.io")
                            .plan("ENTERPRISE")
                            .transferLimit(1_000_000L)
                            .customDomain("app.tranzfer.io")
                            .branding(Map.of("primaryColor", "#1a73e8", "logoUrl", "/assets/logo.svg"))
                            .active(true)
                            .build(),
                    Tenant.builder()
                            .slug("acme-corp")
                            .companyName("Acme Corp")
                            .contactEmail("john.mitchell@acme-corp.com")
                            .plan("PROFESSIONAL")
                            .transferLimit(100_000L)
                            .active(true)
                            .build(),
                    Tenant.builder()
                            .slug("globalbank")
                            .companyName("GlobalBank")
                            .contactEmail("sarah.chen@globalbank.com")
                            .plan("ENTERPRISE")
                            .transferLimit(500_000L)
                            .active(true)
                            .build()
            );
            List<Tenant> saved = tenantRepository.saveAll(tenants);
            saved.forEach(t -> log.info("[Bootstrap] Created tenant: {} (slug={}, plan={}, limit={})",
                    t.getCompanyName(), t.getSlug(), t.getPlan(), t.getTransferLimit()));
        } catch (Exception e) {
            log.warn("[Bootstrap] Failed to seed tenants: {}", e.getMessage());
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 17. Compliance Profiles
    // ──────────────────────────────────────────────────────────────────────────

    private void seedComplianceProfiles(List<ServerInstance> servers) {
        try {
            ComplianceProfile pciStrict = ComplianceProfile.builder()
                    .name("PCI-DSS Strict")
                    .description("Payment Card Industry compliance — blocks PCI data, requires encryption and TLS")
                    .severity("CRITICAL")
                    .allowPciData(false)
                    .allowPhiData(false)
                    .allowPiiData(true)
                    .allowClassifiedData(false)
                    .maxAllowedRiskLevel("MEDIUM")
                    .maxAllowedRiskScore(60)
                    .requireEncryption(true)
                    .requireScreening(true)
                    .requireChecksum(false)
                    .blockedFileExtensions("exe,bat,cmd,ps1,sh,vbs,js")
                    .requireTls(true)
                    .allowAnonymousAccess(false)
                    .requireMfa(false)
                    .auditAllTransfers(true)
                    .notifyOnViolation(true)
                    .violationAction("BLOCK")
                    .active(true)
                    .build();

            ComplianceProfile hipaa = ComplianceProfile.builder()
                    .name("HIPAA Healthcare")
                    .description("Healthcare compliance — blocks PHI data, requires encryption, screening, and checksum")
                    .severity("CRITICAL")
                    .allowPciData(false)
                    .allowPhiData(false)
                    .allowPiiData(false)
                    .allowClassifiedData(false)
                    .maxAllowedRiskLevel("LOW")
                    .maxAllowedRiskScore(50)
                    .requireEncryption(true)
                    .requireScreening(true)
                    .requireChecksum(true)
                    .blockedFileExtensions("exe,bat,cmd,ps1,sh,vbs,js,msi")
                    .requireTls(true)
                    .allowAnonymousAccess(false)
                    .requireMfa(false)
                    .auditAllTransfers(true)
                    .notifyOnViolation(true)
                    .violationAction("BLOCK")
                    .active(true)
                    .build();

            ComplianceProfile internal = ComplianceProfile.builder()
                    .name("Internal Standard")
                    .description("Standard internal policy — allows all data types, warns on violations")
                    .severity("MEDIUM")
                    .allowPciData(true)
                    .allowPhiData(true)
                    .allowPiiData(true)
                    .allowClassifiedData(false)
                    .maxAllowedRiskLevel("HIGH")
                    .maxAllowedRiskScore(85)
                    .requireEncryption(false)
                    .requireScreening(true)
                    .requireChecksum(false)
                    .blockedFileExtensions("exe,bat,cmd")
                    .requireTls(false)
                    .allowAnonymousAccess(false)
                    .requireMfa(false)
                    .auditAllTransfers(true)
                    .notifyOnViolation(true)
                    .violationAction("WARN")
                    .active(true)
                    .build();

            ComplianceProfile government = ComplianceProfile.builder()
                    .name("Government Classified")
                    .description("Government-grade compliance — no classified data, requires encryption, MFA, and TLS")
                    .severity("CRITICAL")
                    .allowPciData(false)
                    .allowPhiData(false)
                    .allowPiiData(false)
                    .allowClassifiedData(false)
                    .maxAllowedRiskLevel("LOW")
                    .maxAllowedRiskScore(40)
                    .requireEncryption(true)
                    .requireScreening(true)
                    .requireChecksum(true)
                    .allowedFileExtensions("edi,xml,json,csv,txt,pdf")
                    .blockedFileExtensions("exe,bat,cmd,ps1,sh,vbs,js,msi,dll,so")
                    .requireTls(true)
                    .allowAnonymousAccess(false)
                    .requireMfa(true)
                    .auditAllTransfers(true)
                    .notifyOnViolation(true)
                    .violationAction("BLOCK")
                    .active(true)
                    .build();

            List<ComplianceProfile> saved = complianceProfileRepository.saveAll(
                    List.of(pciStrict, hipaa, internal, government));
            saved.forEach(p -> log.info("[Bootstrap] Created compliance profile: {} (severity={}, action={})",
                    p.getName(), p.getSeverity(), p.getViolationAction()));

            // Assign PCI-DSS Strict to FTPS servers, Internal Standard to SFTP servers
            for (ServerInstance server : servers) {
                if (server.getProtocol() == Protocol.FTP) {
                    server.setComplianceProfileId(pciStrict.getId());
                    serverInstanceRepository.save(server);
                    log.info("[Bootstrap] Assigned '{}' to server '{}'", pciStrict.getName(), server.getName());
                } else if (server.getProtocol() == Protocol.SFTP) {
                    server.setComplianceProfileId(internal.getId());
                    serverInstanceRepository.save(server);
                    log.info("[Bootstrap] Assigned '{}' to server '{}'", internal.getName(), server.getName());
                }
            }
        } catch (Exception e) {
            log.warn("[Bootstrap] Failed to seed compliance profiles: {}", e.getMessage());
        }
    }
}
