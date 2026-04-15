package com.filetransfer.onboarding.controller;

import com.filetransfer.shared.cluster.ClusterContext;
import com.filetransfer.shared.cluster.ClusterService;
import com.filetransfer.shared.entity.core.User;
import com.filetransfer.shared.repository.core.*;
import com.filetransfer.shared.repository.transfer.*;
import com.filetransfer.shared.repository.integration.*;
import com.filetransfer.shared.repository.security.*;
import com.filetransfer.shared.util.TrackIdGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;

import java.lang.reflect.Field;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for AdminCliController: command validation, input sanitization,
 * email/password validation, search caps, and help/version commands.
 *
 * Uses real instances for TrackIdGenerator and ClusterService (concrete classes
 * that JDK 25 Mockito cannot mock).
 */
@ExtendWith(MockitoExtension.class)
class AdminCliControllerTest {

    @Mock private UserRepository userRepository;
    @Mock private TransferAccountRepository accountRepository;
    @Mock private FolderMappingRepository folderMappingRepository;
    @Mock private FileTransferRecordRepository transferRecordRepository;
    @Mock private ServiceRegistrationRepository serviceRegistrationRepository;
    @Mock private AuditLogRepository auditLogRepository;
    @Mock private FileFlowRepository flowRepository;
    @Mock private FlowExecutionRepository flowExecutionRepository;
    @Mock private ClusterNodeRepository clusterNodeRepository;

    // Real instances — JDK 25 Mockito cannot mock concrete classes
    private TrackIdGenerator trackIdGenerator;
    private ClusterService clusterService;

    private AdminCliController controller;

    @BeforeEach
    void setUp() throws Exception {
        trackIdGenerator = new TrackIdGenerator();
        Field prefixField = TrackIdGenerator.class.getDeclaredField("prefix");
        prefixField.setAccessible(true);
        prefixField.set(trackIdGenerator, "TRZ");

        clusterService = new ClusterService(new ClusterContext(), serviceRegistrationRepository);

        controller = new AdminCliController(
                userRepository, accountRepository, folderMappingRepository,
                transferRecordRepository, serviceRegistrationRepository,
                auditLogRepository, flowRepository, flowExecutionRepository,
                trackIdGenerator, clusterService, clusterNodeRepository);
    }

    // --- Command length validation: >500 chars rejected ---

    @Test
    void execute_rejectsCommandLongerThan500Chars() {
        String longCommand = "a".repeat(501);

        ResponseEntity<Map<String, Object>> response = controller.execute(Map.of("command", longCommand));

        String output = (String) response.getBody().get("output");
        assertTrue(output.contains("too long"));
        assertTrue(output.contains("500"));
    }

    @Test
    void execute_acceptsCommandExactly500Chars() {
        String command = "help" + " ".repeat(496);

        ResponseEntity<Map<String, Object>> response = controller.execute(Map.of("command", command));

        String output = (String) response.getBody().get("output");
        assertFalse(output.contains("too long"));
    }

    // --- Control character stripping: input with \t, \r, \n gets sanitized ---

    @Test
    void execute_stripsControlCharactersFromInput() {
        String maliciousInput = "version\r\nINJECTED_LOG_LINE\tanother";

        ResponseEntity<Map<String, Object>> response = controller.execute(Map.of("command", maliciousInput));

        String output = (String) response.getBody().get("output");
        assertNotNull(output);
        assertFalse(output.contains("\r"));
        assertFalse(output.contains("\n"));
        assertFalse(output.contains("\t"));
    }

    // --- Email validation on onboard command: valid emails pass, "not-an-email" rejected ---

    @Test
    void onboard_validEmailPasses() {
        when(userRepository.findByEmail("partner@example.com")).thenReturn(Optional.empty());
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(accountRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ResponseEntity<Map<String, Object>> response =
                controller.execute(Map.of("command", "onboard partner@example.com SecureP@ss1"));

        String output = (String) response.getBody().get("output");
        assertTrue(output.contains("Created user"), "Valid email should create user");
        assertTrue(output.contains("partner@example.com"));
    }

    @Test
    void onboard_invalidEmailRejected() {
        ResponseEntity<Map<String, Object>> response =
                controller.execute(Map.of("command", "onboard not-an-email SecureP@ss1"));

        String output = (String) response.getBody().get("output");
        assertTrue(output.contains("Invalid email"), "Invalid email should be rejected");
    }

    @Test
    void onboard_emailWithSpecialCharsRejected() {
        ResponseEntity<Map<String, Object>> response =
                controller.execute(Map.of("command", "onboard user<script>@evil.com password123"));

        String output = (String) response.getBody().get("output");
        assertTrue(output.contains("Invalid email"));
    }

    // --- Password minimum length: <8 chars rejected ---

    @Test
    void onboard_passwordShorterThan8CharsRejected() {
        ResponseEntity<Map<String, Object>> response =
                controller.execute(Map.of("command", "onboard user@test.com short"));

        String output = (String) response.getBody().get("output");
        assertTrue(output.contains("at least 8 characters"));
    }

    @Test
    void onboard_passwordExactly8CharsAccepted() {
        when(userRepository.findByEmail("user@test.com")).thenReturn(Optional.empty());
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(accountRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ResponseEntity<Map<String, Object>> response =
                controller.execute(Map.of("command", "onboard user@test.com 12345678"));

        String output = (String) response.getBody().get("output");
        assertTrue(output.contains("Created user"));
    }

    // --- Search result cap: results limited to MAX_SEARCH_RESULTS (100) ---

    @Test
    void searchRecent_capsResultsAt100() {
        when(transferRecordRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        controller.execute(Map.of("command", "search recent 999"));

        verify(transferRecordRepository).findAll(argThat((Pageable p) -> p.getPageSize() <= 100));
    }

    @Test
    void searchRecent_minimumIsOne() {
        when(transferRecordRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        controller.execute(Map.of("command", "search recent 0"));

        verify(transferRecordRepository).findAll(argThat((Pageable p) -> p.getPageSize() >= 1));
    }

    // --- Help command: returns help text ---

    @Test
    void helpCommand_returnsHelpText() {
        ResponseEntity<Map<String, Object>> response =
                controller.execute(Map.of("command", "help"));

        String output = (String) response.getBody().get("output");
        assertTrue(output.contains("TranzFer MFT Admin CLI"));
        assertTrue(output.contains("COMMANDS"));
        assertTrue(output.contains("help"));
        assertTrue(output.contains("status"));
        assertTrue(output.contains("onboard"));
    }

    // --- Empty/null command: handled gracefully ---

    @Test
    void emptyCommand_returnsHelpPrompt() {
        ResponseEntity<Map<String, Object>> response =
                controller.execute(Map.of("command", ""));

        String output = (String) response.getBody().get("output");
        assertTrue(output.contains("help"));
    }

    @Test
    void whitespaceOnlyCommand_returnsHelpPrompt() {
        ResponseEntity<Map<String, Object>> response =
                controller.execute(Map.of("command", "   "));

        String output = (String) response.getBody().get("output");
        assertTrue(output.contains("help"));
    }

    @Test
    void missingCommandKey_returnsHelpPrompt() {
        ResponseEntity<Map<String, Object>> response =
                controller.execute(Map.of());

        String output = (String) response.getBody().get("output");
        assertTrue(output.contains("help"));
    }

    // --- Unknown command: returns error message ---

    @Test
    void unknownCommand_returnsErrorMessage() {
        ResponseEntity<Map<String, Object>> response =
                controller.execute(Map.of("command", "foobar"));

        String output = (String) response.getBody().get("output");
        assertTrue(output.contains("Unknown command"));
        assertTrue(output.contains("foobar"));
        assertTrue(output.contains("help"));
    }

    @Test
    void unknownCommand_withArguments() {
        ResponseEntity<Map<String, Object>> response =
                controller.execute(Map.of("command", "badcmd arg1 arg2"));

        String output = (String) response.getBody().get("output");
        assertTrue(output.contains("Unknown command"));
        assertTrue(output.contains("badcmd"));
    }

    // --- Version command (basic sanity) ---

    @Test
    void versionCommand_returnsVersion() {
        ResponseEntity<Map<String, Object>> response =
                controller.execute(Map.of("command", "version"));

        String output = (String) response.getBody().get("output");
        assertTrue(output.contains("TranzFer MFT"));
    }

    // --- Onboard: duplicate user is rejected ---

    @Test
    void onboard_duplicateUserRejected() {
        when(userRepository.findByEmail("existing@test.com"))
                .thenReturn(Optional.of(User.builder().email("existing@test.com").build()));

        ResponseEntity<Map<String, Object>> response =
                controller.execute(Map.of("command", "onboard existing@test.com password123"));

        String output = (String) response.getBody().get("output");
        assertTrue(output.contains("already exists"));
    }

    // --- Response always includes timestamp ---

    @Test
    void execute_responseAlwaysIncludesTimestamp() {
        ResponseEntity<Map<String, Object>> response =
                controller.execute(Map.of("command", "version"));

        assertNotNull(response.getBody().get("timestamp"));
    }
}
