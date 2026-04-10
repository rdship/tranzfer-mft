package com.filetransfer.onboarding.bootstrap;

import com.filetransfer.shared.dto.FolderDefinition;
import com.filetransfer.shared.entity.*;
import com.filetransfer.shared.enums.*;
import com.filetransfer.shared.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    private final PasswordEncoder passwordEncoder;

    @EventListener(ApplicationReadyEvent.class)
    @Order(Ordered.LOWEST_PRECEDENCE)
    @Transactional
    public void onApplicationReady() {
        try {
            if (userRepository.count() > 0) {
                log.info("[Bootstrap] Users table is not empty — skipping seed data");
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

            log.info("[Bootstrap] ===== Platform bootstrap complete =====");
            log.info("[Bootstrap]   1 super-admin user");
            log.info("[Bootstrap]   {} server instances", servers.size());
            log.info("[Bootstrap]   {} partners", partners.size());
            log.info("[Bootstrap]   {} transfer accounts", accounts.size());
            log.info("[Bootstrap]   {} delivery endpoints", endpoints.size());
            log.info("[Bootstrap]   1 folder template");
            log.info("[Bootstrap]   5 file flows");
            log.info("[Bootstrap]   Platform settings seeded");
            log.info("[Bootstrap] ==========================================");

        } catch (Exception e) {
            log.error("[Bootstrap] Failed to seed platform data — app will continue without demo data", e);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 1. Super Admin
    // ──────────────────────────────────────────────────────────────────────────

    private User seedSuperAdmin() {
        User admin = User.builder()
                .email("superadmin@tranzfer.io")
                .passwordHash(passwordEncoder.encode("superadmin"))
                .role(UserRole.ADMIN)
                .build();
        admin = userRepository.save(admin);
        log.info("[Bootstrap] Created super-admin user: superadmin@tranzfer.io (role=ADMIN)");
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

        List<ServerInstance> saved = serverInstanceRepository.saveAll(servers);
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
                .defaultStorageMode("PHYSICAL")
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
                .priority(100)
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
}
