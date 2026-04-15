package com.filetransfer.config;

import com.filetransfer.config.controller.FileFlowController;
import com.filetransfer.config.controller.ListenerSecurityPolicyController;
import com.filetransfer.config.controller.PlatformSettingsController;
import com.filetransfer.config.messaging.FlowRuleEventPublisher;
import com.filetransfer.config.service.MatchCriteriaService;
import com.filetransfer.config.service.PlatformSettingsService;
import com.filetransfer.shared.entity.transfer.FileFlow;
import com.filetransfer.shared.entity.security.ListenerSecurityPolicy;
import com.filetransfer.shared.entity.core.PlatformSetting;
import com.filetransfer.shared.entity.core.ServerInstance;
import com.filetransfer.shared.enums.Environment;
import com.filetransfer.shared.enums.SecurityTier;
import com.filetransfer.shared.flow.FlowFunction;
import com.filetransfer.shared.flow.FlowFunctionRegistry;
import com.filetransfer.shared.flow.FunctionImportExportService;
import com.filetransfer.shared.flow.IOMode;
import com.filetransfer.shared.repository.transfer.FileFlowRepository;
import com.filetransfer.shared.repository.transfer.FlowExecutionRepository;
import com.filetransfer.shared.repository.security.ListenerSecurityPolicyRepository;
import com.filetransfer.shared.repository.core.PlatformSettingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.lang.reflect.Field;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Regression, usability, and performance tests for config-service.
 * Pure JUnit 5 + Mockito — no @SpringBootTest.
 */
@ExtendWith(MockitoExtension.class)
class ConfigServiceRegressionTest {

    // ── PlatformSettings dependencies ──
    @Mock private PlatformSettingRepository settingRepo;
    private PlatformSettingsService settingsService;
    private PlatformSettingsController settingsController;

    // ── FileFlow dependencies ──
    @Mock private FileFlowRepository flowRepo;
    @Mock private FlowExecutionRepository execRepo;
    @Mock private MatchCriteriaService matchCriteriaService;
    @Mock private FlowRuleEventPublisher flowRuleEventPublisher;
    @Mock private FunctionImportExportService functionImportExportService;
    private FlowFunctionRegistry flowFunctionRegistry;
    private FileFlowController fileFlowController;

    // ── ListenerSecurityPolicy dependencies ──
    @Mock private ListenerSecurityPolicyRepository policyRepo;
    private ListenerSecurityPolicyController policyController;

    // Real RabbitTemplate (no connection factory) — publishEvent catches exceptions
    private final RabbitTemplate rabbitTemplate = new RabbitTemplate();

    @BeforeEach
    void setUp() throws Exception {
        // PlatformSettings
        settingsService = new PlatformSettingsService(settingRepo, rabbitTemplate);
        Field exchangeField = PlatformSettingsService.class.getDeclaredField("exchange");
        exchangeField.setAccessible(true);
        exchangeField.set(settingsService, "test-exchange");
        settingsController = new PlatformSettingsController(settingsService);

        // FlowFunctionRegistry — register 17 functions to match DRP upgrade catalog
        flowFunctionRegistry = new FlowFunctionRegistry();
        String[] functionTypes = {
                "ENCRYPT_PGP", "DECRYPT_PGP", "ENCRYPT_AES", "DECRYPT_AES",
                "COMPRESS_GZIP", "DECOMPRESS_GZIP", "COMPRESS_ZIP", "DECOMPRESS_ZIP",
                "RENAME", "SCREEN", "EXECUTE_SCRIPT", "MAILBOX",
                "FILE_DELIVERY", "ROUTE", "CONVERT_EDI", "APPROVE", "NOTIFY"
        };
        for (String type : functionTypes) {
            final String t = type;
            flowFunctionRegistry.register(new FlowFunction() {
                @Override public String executePhysical(com.filetransfer.shared.flow.FlowFunctionContext ctx) { return null; }
                @Override public String type() { return t; }
                @Override public IOMode ioMode() { return IOMode.MATERIALIZING; }
                @Override public String description() { return t + " function"; }
            });
        }

        fileFlowController = new FileFlowController(
                flowRepo, execRepo, matchCriteriaService,
                flowRuleEventPublisher, flowFunctionRegistry, functionImportExportService);

        // ListenerSecurityPolicy — DmzProxyClient null (best-effort try-catch, useProxy=false)
        policyController = new ListenerSecurityPolicyController(policyRepo, null);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    // ── 1. platformSettings_getAndSet_shouldPersist ──

    @Test
    void platformSettings_getAndSet_shouldPersist() {
        PlatformSetting input = PlatformSetting.builder()
                .settingKey("max.file.size")
                .settingValue("100MB")
                .environment(Environment.PROD)
                .serviceName("GLOBAL")
                .dataType("STRING")
                .active(true)
                .build();

        when(settingRepo.existsBySettingKeyAndEnvironmentAndServiceName(
                "max.file.size", Environment.PROD, "GLOBAL")).thenReturn(false);
        when(settingRepo.save(any(PlatformSetting.class))).thenAnswer(inv -> {
            PlatformSetting s = inv.getArgument(0);
            s.setId(UUID.randomUUID());
            return s;
        });

        // Create
        PlatformSetting created = settingsService.create(input);
        assertNotNull(created.getId());

        // Now mock get
        when(settingRepo.findById(created.getId())).thenReturn(Optional.of(created));
        PlatformSetting retrieved = settingsService.get(created.getId());

        assertEquals("max.file.size", retrieved.getSettingKey());
        assertEquals("100MB", retrieved.getSettingValue());
        assertEquals(Environment.PROD, retrieved.getEnvironment());
    }

    // ── 2. platformSettings_nullKey_shouldHandleGracefully ──

    @Test
    void platformSettings_nullKey_shouldHandleGracefully() {
        PlatformSetting input = PlatformSetting.builder()
                .settingKey(null)
                .settingValue("value")
                .environment(Environment.PROD)
                .serviceName("GLOBAL")
                .dataType("STRING")
                .active(true)
                .build();

        // The service calls existsBySettingKeyAndEnvironmentAndServiceName with null key;
        // no NPE should propagate — either the repo handles it or the service guards it
        when(settingRepo.existsBySettingKeyAndEnvironmentAndServiceName(
                null, Environment.PROD, "GLOBAL")).thenReturn(false);
        when(settingRepo.save(any(PlatformSetting.class))).thenAnswer(inv -> {
            PlatformSetting s = inv.getArgument(0);
            s.setId(UUID.randomUUID());
            return s;
        });

        // Should not throw NPE
        assertDoesNotThrow(() -> settingsService.create(input));
    }

    // ── 3. fileFlowController_flowCrud_shouldValidate ──

    @Test
    void fileFlowController_flowCrud_shouldValidate() {
        // Duplicate name check on create
        when(flowRepo.existsByName("existing-flow")).thenReturn(true);

        FileFlow flow = new FileFlow();
        flow.setName("existing-flow");
        flow.setSteps(List.of());

        assertThrows(IllegalArgumentException.class, () -> fileFlowController.createFlow(flow));
    }

    // ── 4. fileFlowController_functionCatalog_shouldReturn17Functions ──

    @Test
    void fileFlowController_functionCatalog_shouldReturn17Functions() {
        List<Map<String, Object>> catalog = fileFlowController.functionCatalog();

        assertEquals(17, catalog.size(), "Function catalog should have 17 functions after DRP upgrade");

        // Verify catalog entries are sorted by type
        List<String> types = catalog.stream()
                .map(m -> (String) m.get("type"))
                .toList();
        List<String> sorted = new ArrayList<>(types);
        Collections.sort(sorted);
        assertEquals(sorted, types, "Catalog should be sorted alphabetically by type");

        // Verify expected types present
        assertTrue(types.contains("ENCRYPT_PGP"));
        assertTrue(types.contains("CONVERT_EDI"));
        assertTrue(types.contains("APPROVE"));
        assertTrue(types.contains("NOTIFY"));
    }

    // ── 5. listenerSecurityPolicy_validation_shouldRejectInvalid ──

    @Test
    void listenerSecurityPolicy_validation_shouldRejectInvalid() {
        // Both FK null — should reject
        ListenerSecurityPolicy policy = new ListenerSecurityPolicy();
        policy.setName("test-policy");
        policy.setSecurityTier(SecurityTier.AI);
        policy.setServerInstance(null);
        policy.setExternalDestination(null);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> policyController.create(policy));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());

        // Blank name with valid FK — should reject
        ListenerSecurityPolicy blankName = new ListenerSecurityPolicy();
        blankName.setName("   ");
        blankName.setServerInstance(new ServerInstance());

        ResponseStatusException ex2 = assertThrows(ResponseStatusException.class,
                () -> policyController.create(blankName));
        assertEquals(HttpStatus.BAD_REQUEST, ex2.getStatusCode());

        // Null name with valid FK — should reject
        ListenerSecurityPolicy nullName = new ListenerSecurityPolicy();
        nullName.setName(null);
        nullName.setServerInstance(new ServerInstance());

        ResponseStatusException ex3 = assertThrows(ResponseStatusException.class,
                () -> policyController.create(nullName));
        assertEquals(HttpStatus.BAD_REQUEST, ex3.getStatusCode());
    }

    // ── 6. configService_performance_1000SettingLookups_shouldBeUnder100ms ──

    @Test
    void configService_performance_1000SettingLookups_shouldBeUnder100ms() {
        UUID id = UUID.randomUUID();
        PlatformSetting setting = PlatformSetting.builder()
                .settingKey("perf.key")
                .settingValue("perf-value")
                .environment(Environment.PROD)
                .serviceName("GLOBAL")
                .dataType("STRING")
                .active(true)
                .build();
        setting.setId(id);
        when(settingRepo.findById(id)).thenReturn(Optional.of(setting));

        long start = System.nanoTime();
        for (int i = 0; i < 1000; i++) {
            settingsService.get(id);
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertTrue(elapsedMs < 100, "1000 setting lookups took " + elapsedMs + "ms, expected <100ms");
    }
}
