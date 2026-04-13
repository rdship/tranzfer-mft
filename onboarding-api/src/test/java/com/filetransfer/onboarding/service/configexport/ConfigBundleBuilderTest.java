package com.filetransfer.onboarding.service.configexport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.filetransfer.onboarding.dto.configexport.ConfigBundle;
import com.filetransfer.shared.entity.transfer.FileFlow;
import com.filetransfer.shared.entity.transfer.FolderMapping;
import com.filetransfer.shared.entity.core.Partner;
import com.filetransfer.shared.entity.core.ServerInstance;
import com.filetransfer.shared.entity.core.TransferAccount;
import com.filetransfer.shared.enums.Protocol;
import com.filetransfer.shared.repository.FileFlowRepository;
import com.filetransfer.shared.repository.FolderMappingRepository;
import com.filetransfer.shared.repository.PartnerRepository;
import com.filetransfer.shared.repository.ServerInstanceRepository;
import com.filetransfer.shared.repository.TransferAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link ConfigBundleBuilder}.
 *
 * <p>Mocks all five repositories, stubs {@code findAll()} with a couple of
 * hardcoded entities per type, and verifies that:
 * <ul>
 *   <li>the bundle is built with the right schema version, entity counts,
 *       and a valid SHA-256 checksum;</li>
 *   <li>account password hashes never appear in the serialized JSON; and</li>
 *   <li>a {@link ConfigBundle.Redaction} row is emitted for each account's
 *       {@code passwordHash}.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class ConfigBundleBuilderTest {

    @Mock private PartnerRepository partnerRepo;
    @Mock private TransferAccountRepository accountRepo;
    @Mock private FileFlowRepository flowRepo;
    @Mock private FolderMappingRepository folderMappingRepo;
    @Mock private ServerInstanceRepository serverInstanceRepo;

    private ConfigBundleBuilder builder;
    private ObjectMapper objectMapper;

    private static final String SECRET_HASH_1 = "SECRET_HASH_SHOULD_NEVER_LEAK_AAA111";
    private static final String SECRET_HASH_2 = "SECRET_HASH_SHOULD_NEVER_LEAK_BBB222";

    @BeforeEach
    void setUp() {
        builder = new ConfigBundleBuilder(
                partnerRepo, accountRepo, flowRepo, folderMappingRepo, serverInstanceRepo);
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    @Test
    void buildsBundleWithAllFiveEntityTypes() throws Exception {
        // ── Partners ──
        Partner p1 = Partner.builder()
                .id(UUID.randomUUID())
                .companyName("Acme Corp")
                .slug("acme")
                .partnerType("EXTERNAL")
                .status("ACTIVE")
                .build();
        Partner p2 = Partner.builder()
                .id(UUID.randomUUID())
                .companyName("Globex Inc")
                .slug("globex")
                .partnerType("INTERNAL")
                .status("ACTIVE")
                .build();
        when(partnerRepo.findAll()).thenReturn(List.of(p1, p2));

        // ── Accounts (with password hashes we expect redacted) ──
        TransferAccount a1 = TransferAccount.builder()
                .id(UUID.randomUUID())
                .protocol(Protocol.SFTP)
                .username("alice")
                .passwordHash(SECRET_HASH_1)
                .homeDir("/home/alice")
                .active(true)
                .build();
        TransferAccount a2 = TransferAccount.builder()
                .id(UUID.randomUUID())
                .protocol(Protocol.SFTP)
                .username("bob")
                .passwordHash(SECRET_HASH_2)
                .homeDir("/home/bob")
                .active(true)
                .build();
        when(accountRepo.findAll()).thenReturn(List.of(a1, a2));

        // ── Flows ──
        FileFlow f1 = FileFlow.builder()
                .id(UUID.randomUUID())
                .name("inbound-pgp")
                .description("Decrypt PGP on inbound")
                .steps(List.of(FileFlow.FlowStep.builder().type("DECRYPT_PGP").order(0).build()))
                .active(true)
                .build();
        FileFlow f2 = FileFlow.builder()
                .id(UUID.randomUUID())
                .name("outbound-zip")
                .description("Zip on outbound")
                .steps(List.of(FileFlow.FlowStep.builder().type("COMPRESS_ZIP").order(0).build()))
                .active(true)
                .build();
        when(flowRepo.findAll()).thenReturn(List.of(f1, f2));

        // ── Folder mappings ──
        FolderMapping m1 = FolderMapping.builder()
                .id(UUID.randomUUID())
                .sourcePath("/in")
                .destinationPath("/out")
                .active(true)
                .build();
        when(folderMappingRepo.findAll()).thenReturn(List.of(m1));

        // ── Server instances ──
        ServerInstance s1 = ServerInstance.builder()
                .id(UUID.randomUUID())
                .instanceId("sftp-01")
                .protocol(Protocol.SFTP)
                .name("SFTP Primary")
                .internalHost("sftp-service")
                .internalPort(2222)
                .maxConnections(500)
                .active(true)
                .build();
        ServerInstance s2 = ServerInstance.builder()
                .id(UUID.randomUUID())
                .instanceId("ftp-01")
                .protocol(Protocol.FTP)
                .name("FTP Primary")
                .internalHost("ftp-service")
                .internalPort(21)
                .maxConnections(200)
                .active(true)
                .build();
        when(serverInstanceRepo.findAll()).thenReturn(List.of(s1, s2));

        // ── Act ──
        ConfigBundle bundle = builder.build(Set.of(
                ConfigBundleBuilder.KEY_PARTNERS,
                ConfigBundleBuilder.KEY_ACCOUNTS,
                ConfigBundleBuilder.KEY_FLOWS,
                ConfigBundleBuilder.KEY_FOLDER_MAPPINGS,
                ConfigBundleBuilder.KEY_SERVER_INSTANCES));

        // ── Assert: schema version and timestamps ──
        assertThat(bundle.getSchemaVersion()).isEqualTo("1.0.0");
        assertThat(bundle.getExportedAt()).isNotNull().isBefore(Instant.now().plusSeconds(1));

        // ── Assert: entity counts ──
        Map<String, List<?>> entities = bundle.getEntities();
        assertThat(entities.get(ConfigBundleBuilder.KEY_PARTNERS)).hasSize(2);
        assertThat(entities.get(ConfigBundleBuilder.KEY_ACCOUNTS)).hasSize(2);
        assertThat(entities.get(ConfigBundleBuilder.KEY_FLOWS)).hasSize(2);
        assertThat(entities.get(ConfigBundleBuilder.KEY_FOLDER_MAPPINGS)).hasSize(1);
        assertThat(entities.get(ConfigBundleBuilder.KEY_SERVER_INSTANCES)).hasSize(2);

        // ── Assert: scope is a superset of what we asked for ──
        assertThat(bundle.getScope())
                .containsExactlyInAnyOrder(
                        ConfigBundleBuilder.KEY_PARTNERS,
                        ConfigBundleBuilder.KEY_ACCOUNTS,
                        ConfigBundleBuilder.KEY_FLOWS,
                        ConfigBundleBuilder.KEY_FOLDER_MAPPINGS,
                        ConfigBundleBuilder.KEY_SERVER_INSTANCES);

        // ── Assert: checksum is a 64-char lowercase hex string ──
        assertThat(bundle.getChecksum()).isNotNull();
        assertThat(bundle.getChecksum()).hasSize(64);
        assertThat(bundle.getChecksum()).matches("[0-9a-f]{64}");

        // ── Assert: password hashes never appear in the serialized bundle ──
        String serialized = objectMapper.writeValueAsString(bundle);
        assertThat(serialized).doesNotContain(SECRET_HASH_1);
        assertThat(serialized).doesNotContain(SECRET_HASH_2);
        assertThat(serialized).doesNotContain("passwordHash\":\""); // no such property at all

        // ── Assert: redaction rows were added for both account password hashes ──
        List<ConfigBundle.Redaction> redactions = bundle.getRedactions();
        assertThat(redactions).hasSize(2);
        assertThat(redactions).allSatisfy(r -> {
            assertThat(r.getEntityType()).isEqualTo(ConfigBundleBuilder.KEY_ACCOUNTS);
            assertThat(r.getField()).isEqualTo("passwordHash");
            assertThat(r.getReason()).contains("environment-specific");
        });
        assertThat(redactions).extracting(ConfigBundle.Redaction::getEntityId)
                .containsExactlyInAnyOrder(a1.getId().toString(), a2.getId().toString());
    }
}
