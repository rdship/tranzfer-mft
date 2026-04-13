package com.filetransfer.onboarding.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.filetransfer.onboarding.dto.request.UnifiedOnboardRequest;
import com.filetransfer.onboarding.dto.request.UnifiedOnboardRequest.*;
import com.filetransfer.onboarding.dto.response.UnifiedOnboardResponse;
import com.filetransfer.onboarding.dto.response.UnifiedOnboardResponse.*;
import com.filetransfer.onboarding.messaging.AccountEventPublisher;
import com.filetransfer.onboarding.security.PasswordPolicy;
import com.filetransfer.shared.audit.AuditService;
import com.filetransfer.shared.dto.AccountCreatedEvent;
import com.filetransfer.shared.entity.*;
import com.filetransfer.shared.entity.core.*;
import com.filetransfer.shared.entity.transfer.*;
import com.filetransfer.shared.entity.vfs.*;
import com.filetransfer.shared.entity.security.*;
import com.filetransfer.shared.entity.integration.*;
import com.filetransfer.shared.enums.ExternalDestinationType;
import com.filetransfer.shared.enums.Protocol;
import com.filetransfer.shared.enums.UserRole;
import com.filetransfer.shared.repository.*;
import com.filetransfer.shared.util.JwtUtil;
import com.filetransfer.shared.vfs.VirtualFileSystem;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Unified onboarding service that creates a complete user setup in a single transaction:
 * User + TransferAccounts + (optional) Partner + (optional) FileFlows
 * + (optional) FolderMappings + (optional) ExternalDestinations.
 *
 * <p>Local DB operations are transactional — any failure rolls back all local changes.
 * Remote service calls (config-service) are best-effort: failures are logged as warnings
 * but do not roll back the local transaction.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UnifiedOnboardService {

    private final UserRepository userRepository;
    private final TransferAccountRepository accountRepository;
    private final PartnerRepository partnerRepository;
    private final PartnerContactRepository contactRepository;
    private final FileFlowRepository fileFlowRepository;
    private final FolderMappingRepository folderMappingRepository;
    private final ExternalDestinationRepository externalDestinationRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final PasswordPolicy passwordPolicy;
    private final AuditService auditService;
    private final AccountEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;
    private final ServerInstanceRepository serverInstanceRepository;
    private final FolderTemplateRepository folderTemplateRepository;
    private final VirtualFileSystem virtualFileSystem;

    @Value("${file-transfer.sftp-home-base:/data/sftp}")
    private String sftpHomeBase;

    @Value("${file-transfer.ftp-home-base:/data/ftp}")
    private String ftpHomeBase;

    @Value("${file-transfer.ftpweb-home-base:/data/ftpweb}")
    private String ftpWebHomeBase;

    @Transactional
    public UnifiedOnboardResponse onboard(UnifiedOnboardRequest request) {
        List<String> warnings = new ArrayList<>();

        // ── 1. Validate & create user ──────────────────────────────────
        UserSetup userSetup = request.getUser();

        if (userRepository.existsByEmail(userSetup.getEmail())) {
            throw new IllegalArgumentException("Email already registered: " + userSetup.getEmail());
        }

        passwordPolicy.validate(userSetup.getPassword(), userSetup.getEmail());

        UserRole role = resolveRole(userSetup.getRole());
        User user = User.builder()
                .email(userSetup.getEmail())
                .passwordHash(passwordEncoder.encode(userSetup.getPassword()))
                .role(role)
                .build();
        userRepository.save(user);

        auditService.logLogin(userSetup.getEmail(), null, true, "unified-onboarding-registration");
        log.info("Unified onboard: created user email={} role={}", user.getEmail(), role);

        // ── 2. Resolve server instance (once for all accounts) ─────────
        String serverInstanceId = request.getServerInstanceId();
        ServerInstance resolvedServer = null;
        if (serverInstanceId != null) {
            resolvedServer = serverInstanceRepository.findByInstanceId(serverInstanceId)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Server instance not found: " + serverInstanceId));
        }

        String storageMode = resolvedServer != null ? resolvedServer.getDefaultStorageMode() : "PHYSICAL";
        List<String> folderPaths = resolvedServer != null && resolvedServer.getFolderTemplate() != null
                ? resolvedServer.getFolderTemplate().getFolders().stream()
                    .map(com.filetransfer.shared.dto.FolderDefinition::getPath).toList()
                : List.of();

        // ── 3. Create transfer accounts ────────────────────────────────
        Map<String, TransferAccount> accountsByUsername = new LinkedHashMap<>();
        List<AccountResult> accountResults = new ArrayList<>();

        for (AccountSetup acctSetup : request.getAccounts()) {
            if (accountRepository.existsByUsername(acctSetup.getUsername())) {
                throw new IllegalArgumentException("Username already taken: " + acctSetup.getUsername());
            }

            Protocol protocol = Protocol.valueOf(acctSetup.getProtocol());
            String homeDir = acctSetup.getHomeDir() != null
                    ? acctSetup.getHomeDir()
                    : resolveHomeDir(protocol, acctSetup.getUsername());
            provisionHomeDir(homeDir);

            Map<String, Boolean> permissions = acctSetup.getPermissions() != null
                    ? acctSetup.getPermissions()
                    : Map.of("read", true, "write", true, "delete", false);

            TransferAccount account = TransferAccount.builder()
                    .user(user)
                    .protocol(protocol)
                    .username(acctSetup.getUsername())
                    .passwordHash(passwordEncoder.encode(acctSetup.getPassword()))
                    .publicKey(acctSetup.getPublicKey())
                    .homeDir(homeDir)
                    .permissions(permissions)
                    .serverInstance(serverInstanceId)
                    .storageMode(storageMode)
                    .build();
            accountRepository.save(account);

            accountsByUsername.put(acctSetup.getUsername(), account);

            // Virtual mode: provision phantom folders (zero disk I/O)
            if ("VIRTUAL".equalsIgnoreCase(account.getStorageMode())) {
                try {
                    virtualFileSystem.provisionFolders(account.getId(), folderPaths);
                } catch (Exception e) {
                    log.warn("Failed to provision virtual folders for {}: {}", account.getUsername(), e.getMessage());
                    warnings.add("Virtual folder provisioning failed for " + account.getUsername());
                }
            }

            // Publish account created event
            try {
                eventPublisher.publishAccountCreated(AccountCreatedEvent.builder()
                        .accountId(account.getId())
                        .protocol(account.getProtocol())
                        .username(account.getUsername())
                        .homeDir(account.getHomeDir())
                        .serverInstance(account.getServerInstance())
                        .folderPaths(folderPaths)
                        .build());
            } catch (Exception e) {
                log.warn("Failed to publish account.created event for {}: {}", account.getUsername(), e.getMessage());
                warnings.add("Account event publication failed for " + account.getUsername());
            }

            accountResults.add(AccountResult.builder()
                    .id(account.getId())
                    .protocol(protocol.name())
                    .username(account.getUsername())
                    .homeDir(homeDir)
                    .build());

            log.info("Unified onboard: created account username={} protocol={}", account.getUsername(), protocol);
        }

        // ── 4. Create partner (optional) ───────────────────────────────
        PartnerResult partnerResult = null;
        Partner partner = null;

        if (request.getPartner() != null) {
            partner = createPartner(request.getPartner(), user.getEmail(), accountsByUsername, warnings);
            partnerResult = PartnerResult.builder()
                    .id(partner.getId())
                    .companyName(partner.getCompanyName())
                    .status(partner.getStatus())
                    .build();
        }

        // ── 5. Create external destinations (optional) ─────────────────
        List<ExternalDestinationResult> extDestResults = null;
        Map<String, ExternalDestination> extDestsByName = new LinkedHashMap<>();

        if (request.getExternalDestinations() != null && !request.getExternalDestinations().isEmpty()) {
            extDestResults = new ArrayList<>();
            for (ExternalDestinationSetup edSetup : request.getExternalDestinations()) {
                try {
                    ExternalDestination extDest = createExternalDestination(edSetup);
                    extDestsByName.put(edSetup.getName(), extDest);
                    extDestResults.add(ExternalDestinationResult.builder()
                            .id(extDest.getId())
                            .name(extDest.getName())
                            .type(extDest.getType().name())
                            .build());
                    log.info("Unified onboard: created external destination name={}", edSetup.getName());
                } catch (Exception e) {
                    log.warn("Failed to create external destination '{}': {}", edSetup.getName(), e.getMessage());
                    warnings.add("External destination '" + edSetup.getName() + "' failed: " + e.getMessage());
                }
            }
        }

        // ── 6. Create file flows (optional) ────────────────────────────
        List<FlowResult> flowResults = null;

        if (request.getFlows() != null && !request.getFlows().isEmpty()) {
            flowResults = new ArrayList<>();
            for (FlowSetup flowSetup : request.getFlows()) {
                try {
                    FileFlow flow = createFlow(flowSetup, accountsByUsername, partner);
                    flowResults.add(FlowResult.builder()
                            .id(flow.getId())
                            .name(flow.getName())
                            .stepCount(flow.getSteps() != null ? flow.getSteps().size() : 0)
                            .build());
                    log.info("Unified onboard: created flow name={}", flow.getName());
                } catch (Exception e) {
                    log.warn("Failed to create flow '{}': {}", flowSetup.getName(), e.getMessage());
                    warnings.add("Flow '" + flowSetup.getName() + "' failed: " + e.getMessage());
                }
            }
        }

        // ── 7. Create folder mappings (optional) ───────────────────────
        List<FolderMappingResult> mappingResults = null;

        if (request.getFolderMappings() != null && !request.getFolderMappings().isEmpty()) {
            mappingResults = new ArrayList<>();
            for (FolderMappingSetup fmSetup : request.getFolderMappings()) {
                try {
                    FolderMapping mapping = createFolderMapping(fmSetup, accountsByUsername);
                    mappingResults.add(FolderMappingResult.builder()
                            .id(mapping.getId())
                            .sourceAccount(mapping.getSourceAccount().getUsername())
                            .destinationPath(mapping.getDestinationPath())
                            .build());
                    log.info("Unified onboard: created folder mapping source={} dest={}",
                            fmSetup.getSourceAccountUsername(), fmSetup.getDestinationPath());
                } catch (Exception e) {
                    log.warn("Failed to create folder mapping for '{}': {}",
                            fmSetup.getSourceAccountUsername(), e.getMessage());
                    warnings.add("Folder mapping for '" + fmSetup.getSourceAccountUsername() + "' failed: " + e.getMessage());
                }
            }
        }

        // ── 8. Generate JWT ────────────────────────────────────────────
        String token = jwtUtil.generateToken(user.getEmail(), user.getRole().name());

        log.info("Unified onboard complete: user={} accounts={} partner={} flows={} mappings={} extDests={}",
                user.getEmail(),
                accountResults.size(),
                partnerResult != null ? partnerResult.getCompanyName() : "none",
                flowResults != null ? flowResults.size() : 0,
                mappingResults != null ? mappingResults.size() : 0,
                extDestResults != null ? extDestResults.size() : 0);

        return UnifiedOnboardResponse.builder()
                .userId(user.getId())
                .userEmail(user.getEmail())
                .accessToken(token)
                .accounts(accountResults)
                .partner(partnerResult)
                .flows(flowResults)
                .folderMappings(mappingResults)
                .externalDestinations(extDestResults)
                .warnings(warnings.isEmpty() ? null : warnings)
                .message("Onboarding complete" + (warnings.isEmpty() ? "" : " with " + warnings.size() + " warning(s)"))
                .build();
    }

    // ── Private helpers ─────────────────────────────────────────────────

    private Partner createPartner(PartnerSetup setup, String createdBy,
                                  Map<String, TransferAccount> accountsByUsername,
                                  List<String> warnings) {
        String slug = generateSlug(setup.getCompanyName());

        if (partnerRepository.existsBySlug(slug)) {
            throw new IllegalArgumentException("Partner slug already exists: " + slug);
        }

        Partner partner = Partner.builder()
                .companyName(setup.getCompanyName())
                .displayName(setup.getDisplayName())
                .slug(slug)
                .industry(setup.getIndustry())
                .partnerType(setup.getPartnerType() != null ? setup.getPartnerType() : "EXTERNAL")
                .status("PENDING")
                .onboardingPhase("SETUP")
                .protocolsEnabled(toJson(setup.getProtocolsEnabled()))
                .slaTier(setup.getSlaTier() != null ? setup.getSlaTier() : "STANDARD")
                .build();
        partner.setCreatedBy(createdBy);
        partnerRepository.save(partner);

        log.info("Unified onboard: created partner id={} slug={}", partner.getId(), slug);

        // Create contacts
        if (setup.getContacts() != null) {
            for (ContactSetup cs : setup.getContacts()) {
                PartnerContact contact = PartnerContact.builder()
                        .partner(partner)
                        .name(cs.getName())
                        .email(cs.getEmail())
                        .phone(cs.getPhone())
                        .role(cs.getRole() != null ? cs.getRole() : "Technical")
                        .isPrimary(cs.isPrimary())
                        .build();
                contactRepository.save(contact);
            }
        }

        // Link all accounts to partner
        for (TransferAccount account : accountsByUsername.values()) {
            account.setPartnerId(partner.getId());
            accountRepository.save(account);
        }

        return partner;
    }

    private ExternalDestination createExternalDestination(ExternalDestinationSetup setup) {
        ExternalDestinationType type = ExternalDestinationType.valueOf(setup.getType());

        ExternalDestination dest = ExternalDestination.builder()
                .name(setup.getName())
                .type(type)
                .host(setup.getHost())
                .port(setup.getPort() > 0 ? setup.getPort() : null)
                .username(setup.getUsername())
                .encryptedPassword(setup.getPassword()) // should be encrypted by caller or encryption-service
                .remotePath(setup.getRemotePath())
                .active(true)
                .build();
        externalDestinationRepository.save(dest);
        return dest;
    }

    private FileFlow createFlow(FlowSetup setup, Map<String, TransferAccount> accountsByUsername,
                                Partner partner) {
        if (fileFlowRepository.existsByName(setup.getName())) {
            throw new IllegalArgumentException("Flow name already exists: " + setup.getName());
        }

        List<FileFlow.FlowStep> steps = setup.getSteps().stream()
                .map(s -> FileFlow.FlowStep.builder()
                        .type(s.getType())
                        .config(s.getConfig())
                        .order(s.getOrder())
                        .build())
                .collect(Collectors.toList());

        // Use the first account as the source account for the flow if not empty
        TransferAccount sourceAccount = accountsByUsername.values().iterator().next();

        FileFlow flow = FileFlow.builder()
                .name(setup.getName())
                .description(setup.getDescription())
                .filenamePattern(setup.getFilenamePattern())
                .sourceAccount(sourceAccount)
                .sourcePath(setup.getSourcePath())
                .steps(steps)
                .priority(setup.getPriority() > 0 ? setup.getPriority() : 100)
                .partnerId(partner != null ? partner.getId() : null)
                .active(true)
                .build();
        fileFlowRepository.save(flow);
        return flow;
    }

    private FolderMapping createFolderMapping(FolderMappingSetup setup,
                                              Map<String, TransferAccount> accountsByUsername) {
        TransferAccount sourceAccount = accountsByUsername.get(setup.getSourceAccountUsername());
        if (sourceAccount == null) {
            throw new IllegalArgumentException(
                    "Source account username '" + setup.getSourceAccountUsername()
                            + "' not found in this onboarding request");
        }

        TransferAccount destAccount = null;
        if (setup.getDestinationAccountUsername() != null) {
            destAccount = accountsByUsername.get(setup.getDestinationAccountUsername());
            if (destAccount == null) {
                throw new IllegalArgumentException(
                        "Destination account username '" + setup.getDestinationAccountUsername()
                                + "' not found in this onboarding request");
            }
        }

        FolderMapping mapping = FolderMapping.builder()
                .sourceAccount(sourceAccount)
                .sourcePath(normalizePath(setup.getSourcePath()))
                .destinationAccount(destAccount)
                .destinationPath(normalizePath(setup.getDestinationPath()))
                .filenamePattern(setup.getPattern())
                .active(true)
                .build();
        folderMappingRepository.save(mapping);
        return mapping;
    }

    private UserRole resolveRole(String role) {
        if (role == null || role.isBlank()) {
            return UserRole.USER;
        }
        try {
            return UserRole.valueOf(role.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid role: " + role
                    + ". Valid values: " + Arrays.toString(UserRole.values()));
        }
    }

    private String resolveHomeDir(Protocol protocol, String username) {
        String base = switch (protocol) {
            case SFTP -> sftpHomeBase;
            case FTP -> ftpHomeBase;
            case FTP_WEB, HTTPS -> ftpWebHomeBase;
            case AS2, AS4 -> sftpHomeBase;
        };
        return base + "/" + username;
    }

    private List<String> resolveFolderPaths(String serverInstanceId) {
        if (serverInstanceId == null) return List.of();
        return serverInstanceRepository.findByInstanceId(serverInstanceId)
                .filter(si -> si.getFolderTemplate() != null)
                .map(si -> si.getFolderTemplate().getFolders().stream()
                        .map(com.filetransfer.shared.dto.FolderDefinition::getPath).toList())
                .orElse(List.of());
    }

    private void provisionHomeDir(String homeDir) {
        try {
            Files.createDirectories(Paths.get(homeDir));
        } catch (IOException e) {
            throw new RuntimeException("Failed to create home directory: " + homeDir, e);
        }
    }

    private String normalizePath(String path) {
        if (path == null) return "/";
        return path.startsWith("/") ? path : "/" + path;
    }

    private String generateSlug(String companyName) {
        return companyName.toLowerCase()
                .replaceAll("[^a-z0-9\\s-]", "")
                .replaceAll("\\s+", "-")
                .replaceAll("-+", "-")
                .replaceAll("^-|-$", "");
    }

    private String toJson(List<String> list) {
        if (list == null || list.isEmpty()) {
            return "[]";
        }
        try {
            return objectMapper.writeValueAsString(list);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize protocols list, defaulting to empty array", e);
            return "[]";
        }
    }
}
