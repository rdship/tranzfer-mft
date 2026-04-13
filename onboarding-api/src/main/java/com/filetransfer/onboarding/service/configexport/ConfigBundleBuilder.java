package com.filetransfer.onboarding.service.configexport;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.filetransfer.onboarding.dto.configexport.ConfigBundle;
import com.filetransfer.shared.entity.transfer.FileFlow;
import com.filetransfer.shared.entity.transfer.FolderMapping;
import com.filetransfer.shared.entity.core.Partner;
import com.filetransfer.shared.entity.core.ServerInstance;
import com.filetransfer.shared.entity.core.TransferAccount;
import com.filetransfer.shared.repository.FileFlowRepository;
import com.filetransfer.shared.repository.FolderMappingRepository;
import com.filetransfer.shared.repository.PartnerRepository;
import com.filetransfer.shared.repository.ServerInstanceRepository;
import com.filetransfer.shared.repository.TransferAccountRepository;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Builds a {@link ConfigBundle} by walking the Spring Data repositories for the
 * five entity types that Phase 1 of the config-export feature supports:
 * partners, transfer accounts, file flows, folder mappings and server instances.
 *
 * <p>All secret material (password hashes, private keys, etc.) is stripped from
 * the serialized DTOs. When something load-bearing is stripped — e.g. an
 * account's password hash — a {@link ConfigBundle.Redaction} row is added so
 * the import side can prompt the operator to re-supply it.
 *
 * <p>A deterministic SHA-256 checksum is computed over a canonically ordered
 * JSON serialization of the {@code entities} + {@code redactions} sections so
 * the import side can verify the bundle hasn't been tampered with.
 */
@Service
@Slf4j
public class ConfigBundleBuilder {

    public static final String KEY_PARTNERS = "partners";
    public static final String KEY_ACCOUNTS = "accounts";
    public static final String KEY_FLOWS = "flows";
    public static final String KEY_FOLDER_MAPPINGS = "folder-mappings";
    public static final String KEY_SERVER_INSTANCES = "server-instances";

    public static final Set<String> SUPPORTED_SCOPES = Set.of(
            KEY_PARTNERS, KEY_ACCOUNTS, KEY_FLOWS, KEY_FOLDER_MAPPINGS, KEY_SERVER_INSTANCES);

    private final PartnerRepository partnerRepo;
    private final TransferAccountRepository accountRepo;
    private final FileFlowRepository flowRepo;
    private final FolderMappingRepository folderMappingRepo;
    private final ServerInstanceRepository serverInstanceRepo;

    /** Canonical mapper used for the checksum — keys sorted, no pretty print. */
    private final ObjectMapper canonicalMapper;

    @Value("${platform.environment:${PLATFORM_ENVIRONMENT:UNKNOWN}}")
    private String sourceEnvironment;

    @Value("${cluster.id:${CLUSTER_ID:UNKNOWN}}")
    private String sourceCluster;

    @Value("${platform.version:UNKNOWN}")
    private String sourcePlatformVersion;

    public ConfigBundleBuilder(PartnerRepository partnerRepo,
                               TransferAccountRepository accountRepo,
                               FileFlowRepository flowRepo,
                               FolderMappingRepository folderMappingRepo,
                               ServerInstanceRepository serverInstanceRepo) {
        this.partnerRepo = partnerRepo;
        this.accountRepo = accountRepo;
        this.flowRepo = flowRepo;
        this.folderMappingRepo = folderMappingRepo;
        this.serverInstanceRepo = serverInstanceRepo;
        this.canonicalMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
                .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true);
    }

    /**
     * Build a bundle containing the requested entity-type keys.
     *
     * @param scope subset of {@link #SUPPORTED_SCOPES}. Unknown keys are ignored
     *              with a warning; empty/null scope produces an empty bundle.
     */
    public ConfigBundle build(Set<String> scope) {
        Instant start = Instant.now();
        log.info("ConfigBundleBuilder: starting export for scope={}", scope);

        Map<String, List<?>> entities = new LinkedHashMap<>();
        List<ConfigBundle.Redaction> redactions = new ArrayList<>();
        List<String> actualScope = new ArrayList<>();

        if (scope == null) {
            scope = Set.of();
        }

        if (scope.contains(KEY_PARTNERS)) {
            try {
                List<PartnerDto> rows = partnerRepo.findAll().stream().map(this::toPartnerDto).toList();
                entities.put(KEY_PARTNERS, rows);
                actualScope.add(KEY_PARTNERS);
            } catch (Exception e) {
                log.warn("ConfigBundleBuilder: failed to export partners: {}", e.getMessage(), e);
            }
        }

        if (scope.contains(KEY_ACCOUNTS)) {
            try {
                List<TransferAccount> all = accountRepo.findAll();
                List<AccountDto> rows = new ArrayList<>(all.size());
                for (TransferAccount a : all) {
                    rows.add(toAccountDto(a));
                    // Every account carries a password hash — always redact.
                    redactions.add(ConfigBundle.Redaction.builder()
                            .entityType(KEY_ACCOUNTS)
                            .entityId(a.getId() == null ? null : a.getId().toString())
                            .field("passwordHash")
                            .reason("Password hash is environment-specific")
                            .build());
                }
                entities.put(KEY_ACCOUNTS, rows);
                actualScope.add(KEY_ACCOUNTS);
            } catch (Exception e) {
                log.warn("ConfigBundleBuilder: failed to export accounts: {}", e.getMessage(), e);
            }
        }

        if (scope.contains(KEY_FLOWS)) {
            try {
                List<FlowDto> rows = flowRepo.findAll().stream().map(this::toFlowDto).toList();
                entities.put(KEY_FLOWS, rows);
                actualScope.add(KEY_FLOWS);
            } catch (Exception e) {
                log.warn("ConfigBundleBuilder: failed to export flows: {}", e.getMessage(), e);
            }
        }

        if (scope.contains(KEY_FOLDER_MAPPINGS)) {
            try {
                List<FolderMappingDto> rows = folderMappingRepo.findAll().stream()
                        .map(this::toFolderMappingDto).toList();
                entities.put(KEY_FOLDER_MAPPINGS, rows);
                actualScope.add(KEY_FOLDER_MAPPINGS);
            } catch (Exception e) {
                log.warn("ConfigBundleBuilder: failed to export folder-mappings: {}", e.getMessage(), e);
            }
        }

        if (scope.contains(KEY_SERVER_INSTANCES)) {
            try {
                List<ServerInstanceDto> rows = serverInstanceRepo.findAll().stream()
                        .map(this::toServerInstanceDto).toList();
                entities.put(KEY_SERVER_INSTANCES, rows);
                actualScope.add(KEY_SERVER_INSTANCES);
            } catch (Exception e) {
                log.warn("ConfigBundleBuilder: failed to export server-instances: {}", e.getMessage(), e);
            }
        }

        // Warn on any scope keys we don't recognize.
        for (String key : scope) {
            if (!SUPPORTED_SCOPES.contains(key)) {
                log.warn("ConfigBundleBuilder: unsupported scope key ignored: {}", key);
            }
        }

        String checksum = computeChecksum(entities, redactions);

        ConfigBundle bundle = ConfigBundle.builder()
                .schemaVersion("1.0.0")
                .exportedAt(Instant.now())
                .sourceEnvironment(sourceEnvironment)
                .sourcePlatformVersion(sourcePlatformVersion)
                .sourceCluster(sourceCluster)
                .scope(actualScope)
                .entities(entities)
                .redactions(redactions)
                .checksum(checksum)
                .build();

        log.info("ConfigBundleBuilder: export complete scope={} entityCount={} redactions={} durationMs={}",
                actualScope,
                entities.values().stream().mapToInt(List::size).sum(),
                redactions.size(),
                java.time.Duration.between(start, Instant.now()).toMillis());

        return bundle;
    }

    // ─── Canonical checksum ─────────────────────────────────────────────────

    private String computeChecksum(Map<String, List<?>> entities, List<ConfigBundle.Redaction> redactions) {
        try {
            // Canonical payload: TreeMap so keys are sorted; canonicalMapper
            // also sorts map entries + properties alphabetically.
            Map<String, Object> canonical = new TreeMap<>();
            canonical.put("entities", new TreeMap<>(entities));
            canonical.put("redactions", redactions);
            byte[] bytes = canonicalMapper.writeValueAsBytes(canonical);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(bytes);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is part of every JRE; if missing the JRE is broken.
            throw new IllegalStateException("SHA-256 not available", e);
        } catch (Exception e) {
            log.warn("ConfigBundleBuilder: checksum computation failed: {}", e.getMessage(), e);
            return "";
        }
    }

    // ─── Entity → DTO mappers (explicit field lists, no reflection) ─────────

    private PartnerDto toPartnerDto(Partner p) {
        return PartnerDto.builder()
                .id(p.getId() == null ? null : p.getId().toString())
                .companyName(p.getCompanyName())
                .displayName(p.getDisplayName())
                .slug(p.getSlug())
                .industry(p.getIndustry())
                .partnerType(p.getPartnerType())
                .status(p.getStatus())
                .onboardingPhase(p.getOnboardingPhase())
                .protocolsEnabled(p.getProtocolsEnabled())
                .slaTier(p.getSlaTier())
                .maxFileSizeBytes(p.getMaxFileSizeBytes())
                .maxTransfersPerDay(p.getMaxTransfersPerDay())
                .retentionDays(p.getRetentionDays())
                .notes(p.getNotes())
                .createdAt(p.getCreatedAt())
                .updatedAt(p.getUpdatedAt())
                .build();
    }

    private AccountDto toAccountDto(TransferAccount a) {
        UUID userId = a.getUser() == null ? null : a.getUser().getId();
        return AccountDto.builder()
                .id(a.getId() == null ? null : a.getId().toString())
                .userId(userId == null ? null : userId.toString())
                .protocol(a.getProtocol() == null ? null : a.getProtocol().name())
                .username(a.getUsername())
                .publicKey(a.getPublicKey())
                .homeDir(a.getHomeDir())
                .active(a.isActive())
                .serverInstance(a.getServerInstance())
                .partnerId(a.getPartnerId() == null ? null : a.getPartnerId().toString())
                .storageMode(a.getStorageMode())
                .inlineMaxBytes(a.getInlineMaxBytes())
                .chunkThresholdBytes(a.getChunkThresholdBytes())
                .permissions(a.getPermissions())
                .build();
    }

    private FlowDto toFlowDto(FileFlow f) {
        return FlowDto.builder()
                .id(f.getId() == null ? null : f.getId().toString())
                .name(f.getName())
                .description(f.getDescription())
                .matchCriteria(f.getMatchCriteria())
                .steps(f.getSteps())
                .active(f.isActive())
                .createdAt(f.getCreatedAt())
                .updatedAt(f.getUpdatedAt())
                .build();
    }

    private FolderMappingDto toFolderMappingDto(FolderMapping m) {
        UUID srcId = m.getSourceAccount() == null ? null : m.getSourceAccount().getId();
        UUID dstId = m.getDestinationAccount() == null ? null : m.getDestinationAccount().getId();
        UUID extId = m.getExternalDestination() == null ? null : m.getExternalDestination().getId();
        return FolderMappingDto.builder()
                .id(m.getId() == null ? null : m.getId().toString())
                .sourceAccountId(srcId == null ? null : srcId.toString())
                .destinationAccountId(dstId == null ? null : dstId.toString())
                .externalDestinationId(extId == null ? null : extId.toString())
                .sourceDirectory(m.getSourcePath())
                .destinationDirectory(m.getDestinationPath())
                .encryptionOption(m.getEncryptionOption() == null ? null : m.getEncryptionOption().name())
                .active(m.isActive())
                .createdAt(m.getCreatedAt())
                .updatedAt(m.getUpdatedAt())
                .build();
    }

    private ServerInstanceDto toServerInstanceDto(ServerInstance s) {
        return ServerInstanceDto.builder()
                .id(s.getId() == null ? null : s.getId().toString())
                .instanceId(s.getInstanceId())
                .protocol(s.getProtocol() == null ? null : s.getProtocol().name())
                .name(s.getName())
                .internalHost(s.getInternalHost())
                .internalPort(s.getInternalPort())
                .externalHost(s.getExternalHost())
                .externalPort(s.getExternalPort())
                .maxConnections(s.getMaxConnections())
                .active(s.isActive())
                .build();
    }

    // ─── Serializable DTO records ───────────────────────────────────────────

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonPropertyOrder(alphabetic = true)
    public static class PartnerDto {
        private String id;
        private String companyName;
        private String displayName;
        private String slug;
        private String industry;
        private String partnerType;
        private String status;
        private String onboardingPhase;
        private String protocolsEnabled;
        private String slaTier;
        private Long maxFileSizeBytes;
        private Integer maxTransfersPerDay;
        private Integer retentionDays;
        private String notes;
        private Instant createdAt;
        private Instant updatedAt;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonPropertyOrder(alphabetic = true)
    public static class AccountDto {
        private String id;
        private String userId;
        private String protocol;
        private String username;
        private String publicKey;
        private String homeDir;
        private boolean active;
        private String serverInstance;
        private String partnerId;
        private String storageMode;
        private Long inlineMaxBytes;
        private Long chunkThresholdBytes;
        private Map<String, Boolean> permissions;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonPropertyOrder(alphabetic = true)
    public static class FlowDto {
        private String id;
        private String name;
        private String description;
        private Object matchCriteria;
        private List<FileFlow.FlowStep> steps;
        private boolean active;
        private Instant createdAt;
        private Instant updatedAt;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonPropertyOrder(alphabetic = true)
    public static class FolderMappingDto {
        private String id;
        private String sourceAccountId;
        private String destinationAccountId;
        private String externalDestinationId;
        private String sourceDirectory;
        private String destinationDirectory;
        private String encryptionOption;
        private boolean active;
        private Instant createdAt;
        private Instant updatedAt;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonPropertyOrder(alphabetic = true)
    public static class ServerInstanceDto {
        private String id;
        private String instanceId;
        private String protocol;
        private String name;
        private String internalHost;
        private int internalPort;
        private String externalHost;
        private Integer externalPort;
        private int maxConnections;
        private boolean active;
    }
}
